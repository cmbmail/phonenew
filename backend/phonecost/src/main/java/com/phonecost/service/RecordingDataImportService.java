package com.phonecost.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.phonecost.domain.RecordingDataBatch;
import com.phonecost.domain.RecordingDataEntry;
import com.phonecost.repository.RecordingDataBatchRepository;
import com.phonecost.repository.RecordingDataEntryRepository;
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
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingDataImportService {

    private final RecordingDataBatchRepository batchRepository;
    private final RecordingDataEntryRepository entryRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int BATCH_SIZE = 5000;
    private static final int MAX_CONCURRENT_IMPORTS = 2;

    private static final ExecutorService IMPORT_EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT_IMPORTS, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("recording-data-import-worker");
                return t;
            });

    private static final ScheduledExecutorService CLEANUP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "recording-data-import-cleanup");
                t.setDaemon(true);
                return t;
            });

    private final Map<Long, ImportProgress> progressMap = new ConcurrentHashMap<>();

    @PreDestroy
    public void cleanup() {
        IMPORT_EXECUTOR.shutdownNow();
        CLEANUP_SCHEDULER.shutdownNow();
    }

    public RecordingDataBatch importRecordingData(MultipartFile file, Long userId) throws IOException {
        String batchNo = "REC-" + LocalDateTime.now().format(DTF);
        String fileName = file.getOriginalFilename();

        long activeImports = progressMap.values().stream()
                .filter(p -> "PENDING".equals(p.getStatus()) || "READING".equals(p.getStatus()) || "WRITING".equals(p.getStatus()))
                .count();
        if (activeImports >= MAX_CONCURRENT_IMPORTS) {
            throw new IllegalStateException("当前有导入任务正在执行，请稍后再试");
        }

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        RecordingDataBatch batch = txTemplate.execute(status -> {
            RecordingDataBatch b = new RecordingDataBatch();
            b.setBatchNo(batchNo);
            b.setFileName(fileName != null ? fileName : "");
            b.setTotalCount(0);
            b.setImportStatus((byte) 0);
            b.setImportedBy(userId);
            return batchRepository.save(b);
        });

        final Long batchId = batch.getId();
        Path tempFile = Files.createTempFile("recording_import_", ".xlsx");
        try {
            file.transferTo(tempFile.toFile());
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        ImportProgress progress = new ImportProgress();
        progressMap.put(batchId, progress);
        progress.setSheetInfo("准备中...");

        IMPORT_EXECUTOR.submit(() -> {
            TransactionTemplate asyncTx = new TransactionTemplate(transactionManager);
            asyncTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                doImportAsync(tempFile, batchId, batchNo, progress, asyncTx);
            } catch (Exception e) {
                log.error("Recording data import failed: batch={}", batchNo, e);
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

    public ImportProgress getProgress(Long batchId) {
        return progressMap.get(batchId);
    }

    private void doImportAsync(Path tempFile, Long batchId, String batchNo,
                               ImportProgress progress, TransactionTemplate txTemplate) {
        long startTime = System.currentTimeMillis();
        progress.setStatus("READING");

        List<Object[]> allRows = new ArrayList<>(50000);
        int[] totalCountRef = {0};

        // Excel columns: 分机号(0), 外线号码(1), 所属部门(2), 备注(3)
        EasyExcel.read(tempFile.toFile(), new AnalysisEventListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> row, AnalysisContext context) {
                String extension = row.getOrDefault(0, "").trim();
                String phoneNumber = row.getOrDefault(1, "").trim();
                String deptName = row.getOrDefault(2, "").trim();
                String remark = row.getOrDefault(3, "").trim();

                if (extension.isEmpty() && phoneNumber.isEmpty()) return;

                allRows.add(new Object[]{batchId, extension, phoneNumber, deptName, remark});
                totalCountRef[0]++;
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Recording data Excel reading complete");
            }
        }).sheet().headRowNumber(1).doRead();

        int totalCount = totalCountRef[0];
        progress.setTotal(totalCount);
        progress.setStatus("WRITING");

        String insertSql = "INSERT INTO recording_data_entry " +
                "(batch_id, extension, phone_number, dept_name, remark, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW(), NOW())";

        for (int offset = 0; offset < totalCount; offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, totalCount);
            List<Object[]> chunk = allRows.subList(offset, end);
            txTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(insertSql, chunk));
            progress.setProcessed(end);
        }

        allRows.clear();

        // Update batch totals
        txTemplate.executeWithoutResult(status -> {
            batchRepository.findById(batchId).ifPresent(b -> {
                b.setImportStatus((byte) 1);
                b.setTotalCount(totalCount);
                batchRepository.save(b);
            });
        });

        long elapsed = System.currentTimeMillis() - startTime;
        progress.setProcessed(totalCount);
        progress.setStatus("COMPLETED");
        progress.setElapsedMs(elapsed);
        log.info("Recording data import completed: batch={}, total={}, time={}ms", batchNo, totalCount, elapsed);
    }

    public static class ImportProgress {
        private volatile int total;
        private volatile int processed;
        private volatile String status = "PENDING";
        private volatile String message;
        private volatile long elapsedMs;
        private volatile String sheetInfo;

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getProcessed() { return processed; }
        public void setProcessed(int processed) { this.processed = processed; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
        public String getSheetInfo() { return sheetInfo; }
        public void setSheetInfo(String sheetInfo) { this.sheetInfo = sheetInfo; }
    }
}
