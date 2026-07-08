package com.phonecost.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.phonecost.domain.PhoneOwnershipBatch;
import com.phonecost.domain.PhoneOwnershipEntry;
import com.phonecost.repository.PhoneOwnershipBatchRepository;
import com.phonecost.repository.PhoneOwnershipEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 号码归属表导入服务（性能优化版 v2）
 *
 * 优化点:
 *   1. EasyExcel 流式读取，内存占用低（不再用 XSSFWorkbook 全量加载）
 *   2. 异步导入：API 立即返回批次 ID，后台线程处理，前端轮询进度
 *   3. JdbcTemplate 批量 INSERT（绕过 JPA IDENTITY 策略对 batch 的禁用）
 *   4. 有界线程池：限制并发导入数量，防止资源耗尽
 *   5. 进度自动清理：完成后30分钟自动移除进度条目
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneOwnershipImportService {

    private final PhoneOwnershipBatchRepository batchRepository;
    private final PhoneOwnershipEntryRepository entryRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    private static final String EXCEPTION_PREFIX = "[例外]";

    /** JdbcTemplate 批量插入的批次大小 */
    private static final int BATCH_SIZE = 5000;
    /** 并发导入最大线程数 */
    private static final int MAX_CONCURRENT_IMPORTS = 2;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 有界线程池：限制并发导入数量 */
    private static final ExecutorService IMPORT_EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT_IMPORTS,
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("own-import-worker");
                t.setUncaughtExceptionHandler((th, ex) ->
                        log.error("Uncaught exception in ownership import thread {}", th.getName(), ex));
                return t;
            }
    );

    /** 进度延迟清理调度器 */
    private static final ScheduledExecutorService CLEANUP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "own-import-cleanup");
                t.setDaemon(true);
                return t;
            });

    /** 异步任务进度跟踪: batchId -> progress */
    private final Map<Long, ImportProgress> progressMap = new ConcurrentHashMap<>();

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down ownership import executors...");
        IMPORT_EXECUTOR.shutdownNow();
        CLEANUP_SCHEDULER.shutdownNow();
        try {
            if (!IMPORT_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Import executor did not terminate within 5 seconds");
            }
            if (!CLEANUP_SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Cleanup scheduler did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 导入号码归属（异步模式）
     * API 立即返回批次信息，后台线程执行实际导入
     */
    public PhoneOwnershipBatch importOwnership(MultipartFile file, Long userId) throws IOException {
        String batchNo = "OWN-" + LocalDateTime.now().format(DTF);
        String fileName = file.getOriginalFilename();

        // 并发导入数量限制
        long activeImports = progressMap.values().stream()
                .filter(p -> "PENDING".equals(p.getStatus()) || "READING".equals(p.getStatus()) || "WRITING".equals(p.getStatus()))
                .count();
        if (activeImports >= MAX_CONCURRENT_IMPORTS) {
            throw new IllegalStateException("当前有导入任务正在执行，请稍后再试");
        }

        // 1. 在独立短事务中创建批次记录
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        PhoneOwnershipBatch batch = txTemplate.execute(status -> {
            PhoneOwnershipBatch b = PhoneOwnershipBatch.builder()
                    .batchNo(batchNo)
                    .fileName(fileName != null ? fileName : "")
                    .totalCount(0)
                    .exceptionCount(0)
                    .importStatus((byte) 0)
                    .importedBy(userId)
                    .build();
            return batchRepository.save(b);
        });
        final Long batchId = batch.getId();

        // 2. 保存文件到临时位置
        Path tempFile = Files.createTempFile("own_import_", ".xlsx");
        try {
            file.transferTo(tempFile.toFile());
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        // 3. 初始化进度
        ImportProgress progress = new ImportProgress();
        progressMap.put(batchId, progress);

        // 4. 异步执行导入
        IMPORT_EXECUTOR.submit(() -> {
            TransactionTemplate asyncTx = new TransactionTemplate(transactionManager);
            asyncTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            try {
                doImportAsync(tempFile, batchId, batchNo, progress, asyncTx);
            } catch (Exception e) {
                log.error("Async ownership import failed: batch={}", batchNo, e);
                progress.setStatus("FAILED");
                progress.setMessage(e.getMessage());
                asyncTx.executeWithoutResult(status -> {
                    batchRepository.findById(batchId).ifPresent(b -> {
                        b.setImportStatus((byte) 2);
                        String msg = e.getMessage();
                        b.setErrorMessage(msg != null ? msg.substring(0, Math.min(msg.length(), 2000)) : "Unknown");
                        batchRepository.save(b);
                    });
                });
            } finally {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                CLEANUP_SCHEDULER.schedule(() -> progressMap.remove(batchId), 30, TimeUnit.MINUTES);
            }
        });

        return batch;
    }

    /**
     * 查询导入进度
     */
    public ImportProgress getProgress(Long batchId) {
        return progressMap.get(batchId);
    }

    /**
     * 后台导入主流程
     */
    private void doImportAsync(Path tempFile, Long batchId, String batchNo,
                               ImportProgress progress, TransactionTemplate txTemplate) {
        long startTime = System.currentTimeMillis();

        // === 阶段1: 流式读取 Excel ===
        progress.setStatus("READING");

        List<Object[]> allRows = new ArrayList<>(50000);
        int[] exceptionCounter = {0};

        EasyExcel.read(tempFile.toFile(), new AnalysisEventListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> row, AnalysisContext context) {
                String phoneNumber = row.getOrDefault(0, "");
                String description = row.getOrDefault(1, "");

                // Skip empty/AIGC rows
                if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.startsWith("AIGC:")) return;

                // Check for exception marker
                byte isException = (byte) 0;
                String matchLevel = "P2";
                if (description != null && description.startsWith(EXCEPTION_PREFIX)) {
                    isException = (byte) 1;
                    matchLevel = "P0";
                    exceptionCounter[0]++;
                }

                allRows.add(new Object[]{
                        batchId,
                        phoneNumber.trim(),
                        description != null ? description.trim() : "",
                        isException,
                        matchLevel
                });
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Ownership Excel reading done: {} entries parsed in {}ms",
                        allRows.size(), System.currentTimeMillis() - startTime);
            }
        }).sheet().headRowNumber(1).doRead();

        int totalCount = allRows.size();
        final int finalExceptionCount = exceptionCounter[0];
        progress.setTotal(totalCount);

        // === 阶段2: JdbcTemplate 批量 INSERT ===
        progress.setStatus("WRITING");
        long writeStart = System.currentTimeMillis();

        String insertSql = "INSERT INTO phone_ownership_entry " +
                "(batch_id, phone_number, description, is_exception, match_level, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW(), NOW())";

        for (int offset = 0; offset < totalCount; offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, totalCount);
            List<Object[]> chunk = allRows.subList(offset, end);

            final int chunkEnd = end;
            txTemplate.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate(insertSql, chunk);
            });

            progress.setProcessed(chunkEnd);
            log.info("Ownership import progress: {}/{} ({}%)", chunkEnd, totalCount, chunkEnd * 100 / totalCount);
        }

        // 释放内存
        allRows.clear();

        // 更新批次统计
        txTemplate.executeWithoutResult(status -> {
            batchRepository.findById(batchId).ifPresent(b -> {
                b.setImportStatus((byte) 1);
                b.setTotalCount(totalCount);
                b.setExceptionCount(finalExceptionCount);
                batchRepository.save(b);
            });
        });

        long elapsed = System.currentTimeMillis() - startTime;
        progress.setProcessed(totalCount);
        progress.setExceptionCount(finalExceptionCount);
        progress.setStatus("COMPLETED");
        progress.setElapsedMs(elapsed);

        log.info("Ownership import completed: batch={}, total={}, exceptions={}, time={}ms (read={}ms, write={}ms)",
                batchNo, totalCount, finalExceptionCount, elapsed,
                writeStart - startTime, System.currentTimeMillis() - writeStart);
    }

    /**
     * 导入进度（volatile 确保跨线程可见性）
     */
    public static class ImportProgress {
        private volatile int total;
        private volatile int processed;
        private volatile int exceptionCount;
        private volatile String status = "PENDING"; // PENDING, READING, WRITING, COMPLETED, FAILED
        private volatile String message;
        private volatile long elapsedMs;

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getProcessed() { return processed; }
        public void setProcessed(int processed) { this.processed = processed; }
        public int getExceptionCount() { return exceptionCount; }
        public void setExceptionCount(int exceptionCount) { this.exceptionCount = exceptionCount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    }
}
