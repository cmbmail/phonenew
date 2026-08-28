package com.phonecost.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.phonecost.domain.DirectoryBatch;
import com.phonecost.domain.DirectoryEntry;
import com.phonecost.repository.DirectoryBatchRepository;
import com.phonecost.repository.DirectoryEntryRepository;
import com.phonecost.repository.SysOrganizationRepository;
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
 * 通讯录导入服务（性能优化版 v2）
 *
 * 优化点:
 *   1. EasyExcel 流式读取，内存占用低（不再用 XSSFWorkbook 全量加载）
 *   2. 异步导入：API 立即返回批次 ID，后台线程处理，前端轮询进度
 *   3. JdbcTemplate 批量 INSERT（绕过 JPA IDENTITY 策略对 batch 的禁用）
 *   4. 组织编码预加载为 Map，消除 N+1 查询
 *   5. 分段事务：每 BATCH_SIZE 条一个事务，避免超长事务
 *   6. 有界线程池：限制并发导入数量，防止资源耗尽
 *   7. 进度自动清理：完成后30分钟自动移除进度条目
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectoryImportService {

    private final DirectoryBatchRepository batchRepository;
    private final DirectoryEntryRepository entryRepository;
    private final SysOrganizationRepository orgRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    /** Lazy-injected to avoid circular dependency */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private PhoneOwnershipGeneratorService ownershipGeneratorService;

    /** 借调关键词 */
    private static final List<String> SECONDED_KEYWORDS = List.of(
            "借调", "挂职", "交流", "轮岗", "代管", "派驻", "协助"
    );

    /** JdbcTemplate 批量插入的批次大小 */
    private static final int BATCH_SIZE = 5000;
    /** 并发导入最大线程数 */
    private static final int MAX_CONCURRENT_IMPORTS = 2;

    /** 有界线程池：限制并发导入数量 */
    private static final ExecutorService IMPORT_EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT_IMPORTS,
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("dir-import-worker");
                t.setUncaughtExceptionHandler((th, ex) ->
                        log.error("Uncaught exception in import thread {}", th.getName(), ex));
                return t;
            }
    );

    /** 进度延迟清理调度器 */
    private static final ScheduledExecutorService CLEANUP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dir-import-cleanup");
                t.setDaemon(true);
                return t;
            });

    /** 异步任务进度跟踪: batchId -> progress */
    private final Map<Long, ImportProgress> progressMap = new ConcurrentHashMap<>();

    /** M-33: Graceful shutdown of static thread pools on application stop */
    @PreDestroy
    public void cleanup() {
        log.info("Shutting down directory import executors...");
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
     * 导入通讯录（异步模式）
     * API 立即返回批次信息，后台线程执行实际导入
     *
     * 注意：不加 @Transactional，避免与异步线程的 REQUIRES_NEW 产生竞态
     */
    public DirectoryBatch importDirectory(MultipartFile file, Long userId, String billingMonth) throws IOException {
        String batchNo = "DIR-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = file.getOriginalFilename();

        // 并发导入数量限制
        long activeImports = progressMap.values().stream()
                .filter(p -> "PENDING".equals(p.getStatus()) || "READING".equals(p.getStatus()) || "WRITING".equals(p.getStatus()))
                .count();
        if (activeImports >= MAX_CONCURRENT_IMPORTS) {
            throw new IllegalStateException("当前有导入任务正在执行，请稍后再试");
        }

        // 1. 在独立短事务中创建批次记录（确保异步线程能读到已提交的数据）
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        DirectoryBatch batch = txTemplate.execute(status -> {
            DirectoryBatch b = DirectoryBatch.builder()
                    .batchNo(batchNo)
                    .fileName(fileName != null ? fileName : "")
                    .totalCount(0)
                    .secondedCount(0)
                    .billingMonth(billingMonth)
                    .importStatus((byte) 0)
                    .importedBy(userId)
                    .build();
            return batchRepository.save(b);
        });
        final Long batchId = batch.getId();

        // 2. 保存文件到临时位置（MultipartFile InputStream 在请求结束后不可用）
        Path tempFile = Files.createTempFile("dir_import_", ".xlsx");
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
                doImportAsync(tempFile, batchId, batchNo, progress, asyncTx, billingMonth, userId);
            } catch (Exception e) {
                log.error("Async directory import failed: batch={}", batchNo, e);
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
                // 30分钟后自动清理进度
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
     * 阶段1: EasyExcel 流式读取，解析为数据行
     * 阶段2: JdbcTemplate 批量 INSERT 入库（每 BATCH_SIZE 条一个事务）
     */
    private void doImportAsync(Path tempFile, Long batchId, String batchNo,
                               ImportProgress progress, TransactionTemplate txTemplate,
                               String billingMonth, Long importedBy) {
        long startTime = System.currentTimeMillis();

        // === 阶段1: 流式读取 Excel，解析为数据列表 ===
        progress.setStatus("READING");

        // 预加载组织编码映射
        Map<String, Long> orgCodeMap = txTemplate.execute(status ->
                orgRepository.findByDeletedAtIsNull().stream()
                        .filter(o -> o.getCode() != null && !o.getCode().isEmpty())
                        .collect(java.util.stream.Collectors.toMap(
                                o -> o.getCode().trim(),
                                o -> o.getId(),
                                (a, b) -> a
                        ))
        );
        log.info("Preloaded {} org codes", orgCodeMap.size());

        // 流式读取，解析所有行到内存（10万行约20MB，可接受）
        List<Object[]> allRows = new ArrayList<>(100000);
        // 使用 mutable counter for lambda
        int[] secondedCounter = {0};
        // Auto-detect format by reading header row
        // New format (v1.12.14+): 部门全路径(0), 用户名称(1), 分机号(2), 号码(3), 备注(4)
        // Cost center format: 一级分行(0), 部门路径(1), 分摊部门(2), 组织代码(3), 成本中心(4), 备注(5)
        // Old format: 号码(0), 部门路径(1), 分摊部门(2), 组织代码(3), 成本中心(4), 例外(5), 备注(6)
        final int[] formatType = {0}; // 0=old, 1=new, 2=cost-center

        EasyExcel.read(tempFile.toFile(), new AnalysisEventListener<Map<Integer, String>>() {
            @Override
            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                // Detect format by header columns
                String firstHeader = headMap != null ? headMap.getOrDefault(0, "") : "";
                String secondHeader = headMap != null ? headMap.getOrDefault(1, "") : "";
                if (firstHeader.contains("一级分行") || firstHeader.contains("L1 Branch")) {
                    formatType[0] = 2; // cost-center format
                } else if (firstHeader.contains("部门全路径") || firstHeader.contains("Dept Full Path")) {
                    formatType[0] = 1; // new format (directory)
                } else {
                    formatType[0] = 0; // old format
                }
                log.info("Directory import format detected: {} (firstHeader='{}', secondHeader='{}')",
                        formatType[0] == 2 ? "COST_CENTER" : (formatType[0] == 1 ? "NEW" : "OLD"),
                        firstHeader, secondHeader);
            }

            @Override
            public void invoke(Map<Integer, String> row, AnalysisContext context) {
                String deptPath, username, extension, phoneNumber, allocDept, orgCode, costCenter, remark;

                if (formatType[0] == 2) {
                    // Cost center format: 一级分行(0), 部门路径(1), 分摊部门(2), 组织代码(3), 成本中心(4), 备注(5)
                    // l1Branch is column 0, used for reference; dept_path is the authoritative field
                    deptPath = row.getOrDefault(1, "");
                    allocDept = row.getOrDefault(2, "");
                    orgCode = row.getOrDefault(3, "");
                    costCenter = row.getOrDefault(4, "");
                    remark = row.getOrDefault(5, "");
                    username = "";
                    extension = "";
                    phoneNumber = "";
                } else if (formatType[0] == 1) {
                    // New format: 部门全路径(0), 用户名称(1), 分机号(2), 号码(3), 备注(4)
                    deptPath = row.getOrDefault(0, "");
                    username = row.getOrDefault(1, "");
                    extension = row.getOrDefault(2, "");
                    phoneNumber = row.getOrDefault(3, "");
                    remark = row.getOrDefault(4, "");
                    allocDept = "";
                    orgCode = "";
                    costCenter = "";
                } else {
                    // Old format: 号码(0), 部门路径(1), 分摊部门(2), 组织代码(3), 成本中心(4), 例外(5), 备注(6)
                    phoneNumber = row.getOrDefault(0, "");
                    deptPath = row.getOrDefault(1, "");
                    allocDept = row.getOrDefault(2, "");
                    orgCode = row.getOrDefault(3, "");
                    costCenter = row.getOrDefault(4, "");
                    remark = row.getOrDefault(6, "");
                    username = "";
                    extension = "";
                }

                // Skip completely empty rows
                if ((phoneNumber == null || phoneNumber.isEmpty()) &&
                    (deptPath == null || deptPath.isEmpty())) return;

                // Skip AIGC watermark rows
                if (phoneNumber != null && phoneNumber.startsWith("AIGC:")) return;
                if (deptPath != null && deptPath.startsWith("AIGC:")) return;

                // Detect seconded from dept_path keywords
                byte isSeconded = 0;
                String secondedKeyword = "";
                if (deptPath != null) {
                    for (String kw : SECONDED_KEYWORDS) {
                        if (deptPath.contains(kw)) {
                            isSeconded = 1;
                            secondedKeyword = kw;
                            secondedCounter[0]++;
                            break;
                        }
                    }
                }

                Long orgId = matchOrgFromPathFast(deptPath, orgCodeMap);

                // 收集为 Object[] 供 JdbcTemplate 批量插入
                allRows.add(new Object[]{
                        batchId,
                        deptPath != null ? deptPath.trim() : "",
                        username != null ? username.trim() : "",
                        extension != null ? extension.trim() : "",
                        phoneNumber != null ? phoneNumber.trim() : "",
                        allocDept != null ? allocDept.trim() : "",
                        orgCode != null ? orgCode.trim() : "",
                        costCenter != null ? costCenter.trim() : "",
                        remark != null ? remark.trim() : "",
                        orgId,
                        isSeconded,
                        secondedKeyword
                });
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Excel reading done: {} entries parsed in {}ms",
                        allRows.size(), System.currentTimeMillis() - startTime);
            }
        }).sheet().headRowNumber(1).doRead();

        int totalCount = allRows.size();
        final int finalSecondedCount = secondedCounter[0];
        progress.setTotal(totalCount);

        // === 阶段2: JdbcTemplate 批量 INSERT（绕过 JPA IDENTITY 限制）===
        progress.setStatus("WRITING");
        long writeStart = System.currentTimeMillis();

        String insertSql = "INSERT INTO directory_entry " +
                "(batch_id, dept_path, username, extension, phone_number, alloc_dept, org_code, cost_center, remark, org_id, is_seconded, seconded_keyword, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        for (int offset = 0; offset < totalCount; offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, totalCount);
            List<Object[]> chunk = allRows.subList(offset, end);

            // 在独立事务中批量插入
            final int chunkEnd = end;
            txTemplate.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate(insertSql, chunk);
            });

            progress.setProcessed(chunkEnd);
            log.info("Import progress: {}/{} ({}%)", chunkEnd, totalCount, chunkEnd * 100 / totalCount);
        }

        // 释放解析数据内存
        allRows.clear();

        // 更新批次统计
        txTemplate.executeWithoutResult(status -> {
            batchRepository.findById(batchId).ifPresent(b -> {
                b.setImportStatus((byte) 1);
                b.setTotalCount(totalCount);
                b.setSecondedCount(finalSecondedCount);
                batchRepository.save(b);
            });
        });

        long elapsed = System.currentTimeMillis() - startTime;
        progress.setProcessed(totalCount);
        progress.setSecondedCount(finalSecondedCount);
        progress.setStatus("COMPLETED");
        progress.setElapsedMs(elapsed);

        log.info("Directory import completed: batch={}, total={}, seconded={}, time={}ms (read={}ms, write={}ms)",
                batchNo, totalCount, finalSecondedCount, elapsed,
                writeStart - startTime, System.currentTimeMillis() - writeStart);

        // Auto-trigger ownership generation after directory import
        if (billingMonth != null && !billingMonth.isEmpty()) {
            try {
                log.info("Auto-triggering ownership generation for month={} after directory import", billingMonth);
                Map<String, Object> genResult = ownershipGeneratorService.generate(billingMonth, importedBy);
                log.info("Auto-generated ownership: {}", genResult);
            } catch (Exception e) {
                log.warn("Auto ownership generation failed (non-blocking): {}", e.getMessage());
                // Don't fail the directory import if ownership generation fails
            }
        }
    }

    /**
     * 从预加载的 Map 中匹配组织（O(1)，无 N+1 问题）
     * 从 dept_path 末尾向前查找，优先匹配最深层级的组织编码
     */
    private Long matchOrgFromPathFast(String deptPath, Map<String, Long> orgCodeMap) {
        if (deptPath == null || deptPath.isEmpty()) return null;
        String[] segments = deptPath.split("-");
        // 从后往前查找，优先匹配最深层级
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i].trim();
            // 直接在map中查找，避免正则不匹配遗漏
            Long orgId = orgCodeMap.get(segment);
            if (orgId != null) return orgId;
        }
        return null;
    }

    /**
     * 导入进度（volatile 确保跨线程可见性）
     */
    public static class ImportProgress {
        private volatile int total;
        private volatile int processed;
        private volatile int secondedCount;
        private volatile String status = "PENDING"; // PENDING, READING, WRITING, COMPLETED, FAILED
        private volatile String message;
        private volatile long elapsedMs;

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getProcessed() { return processed; }
        public void setProcessed(int processed) { this.processed = processed; }
        public int getSecondedCount() { return secondedCount; }
        public void setSecondedCount(int secondedCount) { this.secondedCount = secondedCount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    }
}
