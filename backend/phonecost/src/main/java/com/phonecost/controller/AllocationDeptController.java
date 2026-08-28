package com.phonecost.controller;

import com.phonecost.domain.AllocationDeptBatch;
import com.phonecost.domain.AllocationDeptEntry;
import com.phonecost.dto.AddAllocationDeptEntryRequest;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.AllocationDeptBatchRepository;
import com.phonecost.repository.AllocationDeptEntryRepository;
import com.phonecost.service.AllocationDeptImportService;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分摊部门导入Controller
 * 提供分摊部门的导入、查询、导出API
 */
@RestController
@RequestMapping("/import/allocation-dept")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AllocationDeptController {

    private final AllocationDeptImportService allocationDeptImportService;
    private final AllocationDeptBatchRepository allocationDeptBatchRepository;
    private final AllocationDeptEntryRepository allocationDeptEntryRepository;
    private final AuditLogService auditLogService;
    private final DataScopeService dataScopeService;

    // ==================== 导入 ====================

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importAllocationDept(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        try {
            AllocationDeptBatch batch = allocationDeptImportService.importAllocationDept(file, userId, billingMonth);
            auditLogService.log(userId, "IMPORT_ALLOCATION_DEPT", "allocation_dept_batch", batch.getId(),
                    Map.of("batch_no", batch.getBatchNo(), "import_status", batch.getImportStatus()));
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "import_status", batch.getImportStatus(),
                    "message", "导入已启动，请轮询进度"
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("分摊部门导入失败: " + e.getMessage());
        }
    }

    // ==================== 进度轮询 ====================

    @GetMapping("/progress/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getImportProgress(
            @PathVariable Long batchId) {
        var progress = allocationDeptImportService.getProgress(batchId);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UNKNOWN", "message", "未找到导入任务")));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status", progress.getStatus(),
                "total", progress.getTotal(),
                "processed", progress.getProcessed(),
                "elapsed_ms", progress.getElapsedMs(),
                "message", progress.getMessage() != null ? progress.getMessage() : ""
        )));
    }

    // ==================== 模板下载 ====================

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("分摊部门");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "分行", "分摊部门", "机构代码", "成本中心"};
            String[] examples = {"100014-广州分行-100282-代管零售银行部", "广州分行", "代管零售银行部", "100282", "CC-100282"};
            Row headerRow = sheet.createRow(0);
            Row exampleRow = sheet.createRow(1);

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);

                Cell exampleCell = exampleRow.createCell(i);
                exampleCell.setCellValue(examples[i]);
            }

            CellStyle italicStyle = wb.createCellStyle();
            Font italicFont = wb.createFont();
            italicFont.setItalic(true);
            italicFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            italicStyle.setFont(italicFont);
            for (int i = 0; i < examples.length; i++) {
                exampleRow.getCell(i).setCellStyle(italicStyle);
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("分摊部门导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成分摊部门模板失败: " + e.getMessage());
        }
    }

    // ==================== 批次列表 ====================

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<AllocationDeptBatch>>> listBatches(
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        List<AllocationDeptBatch> batches;
        if (billingMonth != null && !billingMonth.isBlank()) {
            batches = allocationDeptBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
        } else {
            batches = allocationDeptBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        }
        return ResponseEntity.ok(ApiResponse.ok(batches));
    }

    // ==================== 月份列表 ====================

    @GetMapping("/months")
    public ResponseEntity<ApiResponse<List<String>>> listMonths() {
        List<String> months = allocationDeptBatchRepository.findDistinctBillingMonths();
        return ResponseEntity.ok(ApiResponse.ok(months));
    }

    // ==================== 按月份查询明细（分页+搜索） ====================

    @GetMapping("/entries-by-month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listEntriesByMonth(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        org.springframework.data.domain.Page<AllocationDeptEntry> pageResult;
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim() : "";

        // Allocation dept entries are global (no org-level data scope filtering needed)
        pageResult = hasSearch
                ? allocationDeptEntryRepository.searchByBillingMonthAndKeyword(billingMonth, keyword, pageable)
                : allocationDeptEntryRepository.findByBillingMonth(billingMonth, pageable);

        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 全量查询明细（分页+搜索，跨批次） ====================

    @GetMapping("/all-entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAllEntries(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim() : "";

        org.springframework.data.domain.Page<AllocationDeptEntry> pageResult = hasSearch
                ? allocationDeptEntryRepository.searchAllActiveEntries(keyword, pageable)
                : allocationDeptEntryRepository.findAllActiveEntriesPaged(pageable);

        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 删除批次（软删除+级联） ====================

    @DeleteMapping("/batches/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        AllocationDeptBatch batch = allocationDeptBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("分摊部门批次不存在或已被删除: " + id));

        allocationDeptEntryRepository.softDeleteByBatchId(id, java.time.LocalDateTime.now());

        batch.setDeletedAt(java.time.LocalDateTime.now());
        allocationDeptBatchRepository.save(batch);

        auditLogService.log(userId, "DELETE_ALLOCATION_DEPT_BATCH", "allocation_dept_batch", id,
                Map.of("batch_no", batch.getBatchNo()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", id,
                "batch_no", batch.getBatchNo(),
                "deleted", true
        )));
    }

    // ==================== 添加单条分摊部门 ====================

    @PostMapping("/entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> addEntry(
            @Valid @RequestBody AddAllocationDeptEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        String billingMonth = req.getBillingMonth();
        String phoneNumber = req.getPhoneNumber() != null ? req.getPhoneNumber() : "";
        String branch = req.getBranch() != null ? req.getBranch() : "";
        String deptName = req.getDeptName() != null ? req.getDeptName() : "";
        String fullPath = req.getFullPath() != null ? req.getFullPath() : "";
        String orgCode = req.getOrgCode() != null ? req.getOrgCode() : "";
        String costCenter = req.getCostCenter() != null ? req.getCostCenter() : "";

        if (billingMonth.isBlank()) {
            throw new IllegalArgumentException("月份不能为空");
        }

        // Find or create a "manual" batch for this billing month
        String batchNo = "AD-" + billingMonth.replace("-", "") + "-MANUAL";
        AllocationDeptBatch batch = allocationDeptBatchRepository.findByBatchNoAndDeletedAtIsNull(batchNo)
                .orElseGet(() -> {
                    AllocationDeptBatch b = new AllocationDeptBatch();
                    b.setBatchNo(batchNo);
                    b.setFileName("manual_entry");
                    b.setTotalCount(0);
                    b.setBillingMonth(billingMonth);
                    b.setImportStatus((byte) 1);
                    b.setImportedBy(userId);
                    return allocationDeptBatchRepository.save(b);
                });

        AllocationDeptEntry entry = new AllocationDeptEntry();
        entry.setBatchId(batch.getId());
        entry.setPhoneNumber(phoneNumber);
        entry.setBranch(branch);
        entry.setDeptName(deptName);
        entry.setFullPath(fullPath);
        entry.setOrgCode(orgCode);
        entry.setCostCenter(costCenter);
        entry = allocationDeptEntryRepository.save(entry);

        // Update batch total count
        long count = allocationDeptEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
        batch.setTotalCount((int) count);
        allocationDeptBatchRepository.save(batch);

        auditLogService.log(userId, "ADD_ALLOCATION_DEPT_ENTRY", "allocation_dept_entry", entry.getId(),
                Map.of("branch", branch, "dept_name", deptName, "billing_month", billingMonth));

        Map<String, Object> result = new HashMap<>();
        result.put("id", entry.getId());
        result.put("batch_id", entry.getBatchId());
        result.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
        result.put("branch", entry.getBranch());
        result.put("dept_name", entry.getDeptName());
        result.put("full_path", entry.getFullPath());
        result.put("org_code", entry.getOrgCode());
        result.put("cost_center", entry.getCostCenter());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 删除单条分摊部门 ====================

    @DeleteMapping("/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteEntry(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        AllocationDeptEntry entry = allocationDeptEntryRepository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("分摊部门记录不存在或已被删除: " + id));

        entry.setDeletedAt(java.time.LocalDateTime.now());
        allocationDeptEntryRepository.save(entry);

        // Update batch total count
        AllocationDeptBatch batch = allocationDeptBatchRepository.findByIdAndDeletedAtIsNull(entry.getBatchId())
                .orElse(null);
        if (batch != null) {
            long count = allocationDeptEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
            batch.setTotalCount((int) count);
            allocationDeptBatchRepository.save(batch);
        }

        auditLogService.log(userId, "DELETE_ALLOCATION_DEPT_ENTRY", "allocation_dept_entry", id,
                Map.of("branch", entry.getBranch(), "dept_name", entry.getDeptName()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", id, "deleted", true)));
    }

    // ==================== 导出 Excel（按月份） ====================

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEntries(
            @RequestParam("billing_month") String billingMonth) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("分摊部门");

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "分行", "分摊部门", "机构代码", "成本中心"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            // Fetch all entries for the billing month
            List<AllocationDeptEntry> entries = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth);

            int rowIdx = 1;
            for (AllocationDeptEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getFullPath() != null ? entry.getFullPath() : "");
                row.createCell(1).setCellValue(entry.getBranch() != null ? entry.getBranch() : "");
                row.createCell(2).setCellValue(entry.getDeptName() != null ? entry.getDeptName() : "");
                row.createCell(3).setCellValue(entry.getOrgCode() != null ? entry.getOrgCode() : "");
                row.createCell(4).setCellValue(entry.getCostCenter() != null ? entry.getCostCenter() : "");
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("分摊部门_" + billingMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出分摊部门失败: " + e.getMessage());
        }
    }

    // ==================== 导出 Excel（全量） ====================

    @GetMapping("/export-all")
    public ResponseEntity<byte[]> exportAllEntries() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("成本中心");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "分行", "分摊部门", "机构代码", "成本中心"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            List<AllocationDeptEntry> entries = allocationDeptEntryRepository.findAllActiveEntries();

            int rowIdx = 1;
            for (AllocationDeptEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getFullPath() != null ? entry.getFullPath() : "");
                row.createCell(1).setCellValue(entry.getBranch() != null ? entry.getBranch() : "");
                row.createCell(2).setCellValue(entry.getDeptName() != null ? entry.getDeptName() : "");
                row.createCell(3).setCellValue(entry.getOrgCode() != null ? entry.getOrgCode() : "");
                row.createCell(4).setCellValue(entry.getCostCenter() != null ? entry.getCostCenter() : "");
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("成本中心.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出成本中心失败: " + e.getMessage());
        }
    }
}
