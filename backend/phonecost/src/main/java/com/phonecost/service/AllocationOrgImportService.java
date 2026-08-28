package com.phonecost.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.phonecost.domain.AllocationOrgBatch;
import com.phonecost.domain.AllocationOrgEntry;
import com.phonecost.domain.SysOrganization;
import com.phonecost.repository.AllocationOrgBatchRepository;
import com.phonecost.repository.AllocationOrgEntryRepository;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class AllocationOrgImportService {

    private static final int MAX_CONCURRENT_IMPORTS = 2;
    private static final int BATCH_SIZE = 5000;

    private final AllocationOrgBatchRepository batchRepo;
    private final AllocationOrgEntryRepository entryRepo;
    private final SysOrganizationRepository sysOrgRepo;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;
    private final DataScopeService dataScopeService;

    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_IMPORTS);
    private final ConcurrentHashMap<Long, ImportProgress> progressMap = new ConcurrentHashMap<>();

    public AllocationOrgImportService(AllocationOrgBatchRepository batchRepo,
                                      AllocationOrgEntryRepository entryRepo,
                                      SysOrganizationRepository sysOrgRepo,
                                      JdbcTemplate jdbcTemplate,
                                      TransactionTemplate txTemplate,
                                      DataScopeService dataScopeService) {
        this.batchRepo = batchRepo;
        this.entryRepo = entryRepo;
        this.sysOrgRepo = sysOrgRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
        this.dataScopeService = dataScopeService;
    }

    @PreDestroy
    public void cleanup() {
        executor.shutdown();
    }

    public AllocationOrgBatch importAllocationOrg(MultipartFile file, Long userId, String billingMonth) {
        String batchNo = "ALLOC-ORG-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Resolve the L1 branch org of the importing user for data isolation
        Long branchOrgId = resolveBranchOrgId(userId);

        AllocationOrgBatch batch = AllocationOrgBatch.builder()
                .batchNo(batchNo)
                .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "")
                .totalCount(0)
                .billingMonth(billingMonth)
                .importStatus((byte) 0)
                .importedBy(userId)
                .branchOrgId(branchOrgId)
                .build();
        batch = batchRepo.save(batch);

        ImportProgress progress = new ImportProgress();
        progressMap.put(batch.getId(), progress);

        Path tempFile;
        try {
            tempFile = Files.createTempFile("alloc-org-", ".xlsx");
            file.transferTo(tempFile);
        } catch (Exception e) {
            batch.setImportStatus((byte) 2);
            batch.setErrorMessage("保存临时文件失败: " + e.getMessage());
            batchRepo.save(batch);
            throw new RuntimeException("保存临时文件失败", e);
        }

        final Long batchId = batch.getId();
        // 构建分行名称→orgId 映射，用于条目级数据隔离
        final java.util.Map<String, Long> branchNameToOrgId = buildBranchNameMap();
        executor.submit(() -> doImportAsync(tempFile, batchId, batchNo, progress, branchNameToOrgId));

        return batch;
    }

    public ImportProgress getProgress(Long batchId) {
        return progressMap.get(batchId);
    }

    private void doImportAsync(Path tempFile, Long batchId, String batchNo, ImportProgress progress, java.util.Map<String, Long> branchNameToOrgId) {
        long startMs = System.currentTimeMillis();
        progress.setStatus("READING");

        try {
            List<String> columnNames = List.of("phone_number", "l1_branch", "alloc_dept", "org_code", "cost_center", "remark");

            try (InputStream is = Files.newInputStream(tempFile)) {
                EasyExcel.read(is, new ReadListener() {
                    int count = 0;

                    @Override
                    public void invoke(Object data, AnalysisContext context) {
                        // data is a map from EasyExcel default read
                        count++;
                        progress.setProcessed(count);
                        progress.setStatus("READING");
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                        progress.setTotal(count);
                    }
                }).sheet().doRead();
            }

            // Re-read and write to DB
            progress.setStatus("WRITING");
            try (InputStream is = Files.newInputStream(tempFile)) {
                EasyExcel.read(is, new ReadListener() {
                    int rowIdx = 0;
                    List<java.util.Map<Integer, String>> batchRows = new java.util.ArrayList<>();

                    @Override
                    public void invoke(Object data, AnalysisContext context) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<Integer, String> row = (java.util.Map<Integer, String>) data;
                        batchRows.add(row);
                        rowIdx++;

                        if (batchRows.size() >= BATCH_SIZE) {
                            flushBatch(batchId, batchRows, columnNames, branchNameToOrgId);
                            batchRows.clear();
                            progress.setProcessed(rowIdx);
                        }
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                        if (!batchRows.isEmpty()) {
                            flushBatch(batchId, batchRows, columnNames, branchNameToOrgId);
                            batchRows.clear();
                        }
                        progress.setProcessed(rowIdx);
                        progress.setTotal(rowIdx);
                    }
                }).sheet().headRowNumber(1).doRead();
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            progress.setElapsedMs(elapsedMs);
            progress.setStatus("COMPLETED");

            AllocationOrgBatch batch = batchRepo.findById(batchId).orElse(null);
            if (batch != null) {
                batch.setImportStatus((byte) 1);
                batch.setTotalCount(progress.getTotal());
                batchRepo.save(batch);
            }

        } catch (Exception e) {
            log.error("Import failed for batch {}", batchId, e);
            progress.setStatus("FAILED");
            progress.setMessage(e.getMessage());

            AllocationOrgBatch batch = batchRepo.findById(batchId).orElse(null);
            if (batch != null) {
                batch.setImportStatus((byte) 2);
                batch.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 2000)) : "");
                batchRepo.save(batch);
            }
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }

    private void flushBatch(Long batchId, List<java.util.Map<Integer, String>> rows, List<String> columnNames, java.util.Map<String, Long> branchNameToOrgId) {
        String sql = "INSERT INTO allocation_org_entry (batch_id, phone_number, l1_branch, branch_org_id, alloc_dept, org_code, cost_center, remark, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        jdbcTemplate.batchUpdate(sql, rows, rows.size(), (ps, row) -> {
            ps.setLong(1, batchId);
            String l1Branch = safeGet(row, 1);
            ps.setString(2, safeGet(row, 0)); // phone_number
            ps.setString(3, l1Branch);        // l1_branch
            // 按 l1_branch 名称匹配分行 orgId，未匹配时设为 NULL
            Long branchOrgId = branchNameToOrgId.get(l1Branch != null ? l1Branch.trim() : "");
            if (branchOrgId != null) {
                ps.setLong(4, branchOrgId);
            } else {
                ps.setNull(4, java.sql.Types.BIGINT);
            }
            ps.setString(5, safeGet(row, 2)); // alloc_dept
            ps.setString(6, safeGet(row, 3)); // org_code
            ps.setString(7, safeGet(row, 4)); // cost_center
            ps.setString(8, safeGet(row, 5)); // remark
        });
    }

    /**
     * 构建分行名称→orgId 映射（type=2 的所有一级分行）
     */
    private java.util.Map<String, Long> buildBranchNameMap() {
        List<SysOrganization> branches = sysOrgRepo.findByTypeAndDeletedAtIsNull((byte) 2);
        java.util.Map<String, Long> map = new java.util.HashMap<>();
        for (SysOrganization org : branches) {
            if (org.getName() != null) {
                map.put(org.getName().trim(), org.getId());
            }
        }
        return map;
    }

    private String safeGet(java.util.Map<Integer, String> row, int idx) {
        String val = row.get(idx);
        return val != null ? val.trim() : "";
    }

    /**
     * 解析用户所属一级分行（type=2）组织ID，复用 DataScopeService 统一逻辑。
     */
    private Long resolveBranchOrgId(Long userId) {
        return dataScopeService.resolveBranchOrgId(userId);
    }

    public static class ImportProgress {
        private volatile int total;
        private volatile int processed;
        private volatile String status = "PENDING";
        private volatile String message;
        private volatile long elapsedMs;

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
    }
}
