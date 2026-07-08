package com.phonecost.controller;

import com.phonecost.domain.*;
import com.phonecost.dto.*;
import com.phonecost.repository.*;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.BillImportService;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import com.phonecost.service.DirectoryImportService;
import com.phonecost.service.OwnershipMatchService;
import com.phonecost.service.PhoneOwnershipImportService;
import com.phonecost.service.RecordingDataImportService;
import jakarta.validation.Valid;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 数据导入Controller
 * 提供号码归属、通讯录、电信账单的导入API
 */
@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DataImportController {

    private final PhoneOwnershipImportService ownershipImportService;
    private final DirectoryImportService directoryImportService;
    private final BillImportService billImportService;
    private final OwnershipMatchService ownershipMatchService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;
    private final RecordingDataImportService recordingDataImportService;

    private final PhoneOwnershipBatchRepository ownershipBatchRepository;
    private final PhoneOwnershipEntryRepository ownershipEntryRepository;
    private final DirectoryBatchRepository directoryBatchRepository;
    private final DirectoryEntryRepository directoryEntryRepository;
    private final BillBatchRepository billBatchRepository;
    private final BillDetailRepository billDetailRepository;
    private final DataSnapshotRepository dataSnapshotRepository;
    private final SysOrganizationRepository organizationRepository;
    private final AllocationResultRepository allocationResultRepository;
    private final AllocationAdjustmentRepository allocationAdjustmentRepository;
    private final RecordingDataBatchRepository recordingDataBatchRepository;
    private final RecordingDataEntryRepository recordingDataEntryRepository;

    // ==================== 号码归属导入 ====================

    @PostMapping("/ownership")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importOwnership(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            PhoneOwnershipBatch batch = ownershipImportService.importOwnership(file, userId);
            auditLogService.log(userId, "IMPORT_OWNERSHIP", "ownership_batch", batch.getId(),
                    Map.of("batch_no", batch.getBatchNo(), "import_status", batch.getImportStatus()));
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "import_status", batch.getImportStatus(),
                    "message", "导入已启动，请轮询进度"
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("号码归属导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/ownership/progress/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOwnershipImportProgress(
            @PathVariable Long batchId) {
        var progress = ownershipImportService.getProgress(batchId);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UNKNOWN", "message", "未找到导入任务")));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status", progress.getStatus(),
                "total", progress.getTotal(),
                "processed", progress.getProcessed(),
                "exception_count", progress.getExceptionCount(),
                "elapsed_ms", progress.getElapsedMs(),
                "message", progress.getMessage() != null ? progress.getMessage() : ""
        )));
    }

    @GetMapping("/ownership/template")
    public ResponseEntity<byte[]> downloadOwnershipTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("号码归属");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "描述"};
            String[] examples = {"13800138000", "[例外]总经理特批号码"};
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

            String fileName = java.net.URLEncoder.encode("号码归属导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成号码归属模板失败: " + e.getMessage());
        }
    }

    @GetMapping("/ownership/batches")
    public ResponseEntity<ApiResponse<List<PhoneOwnershipBatch>>> listOwnershipBatches(
            @RequestAttribute("userId") Long userId) {
        // 归属批次是全局的，所有用户可见（不按组织过滤）
        return ResponseEntity.ok(ApiResponse.ok(ownershipBatchRepository.findByDeletedAtIsNull()));
    }

    @GetMapping("/ownership/entries/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOwnershipEntries(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        // M-08: Cap page size to prevent OOM
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        // DB-level pagination (was in-memory pagination before)
        org.springframework.data.domain.Page<PhoneOwnershipEntry> paged;
        long total;
        if (scope.isAllScope()) {
            paged = ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
            total = paged.getTotalElements();
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null && !visibleIds.isEmpty()) {
                paged = ownershipEntryRepository.findByBatchIdAndOrgIdInAndDeletedAtIsNull(batchId, visibleIds, pageable);
                total = paged.getTotalElements();
            } else {
                paged = org.springframework.data.domain.Page.empty(pageable);
                total = 0;
            }
        }

        // Build directory phone→orgId map + phone→{username,extension}
        // Optimized: one query for all batches instead of N+1
        Map<String, Long> directoryOrgMap = new HashMap<>();
        Map<String, Map<String, String>> directoryInfoMap = new HashMap<>();
        List<Long> dirBatchIds = directoryBatchRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
                .stream().map(DirectoryBatch::getId).toList();
        if (!dirBatchIds.isEmpty()) {
            List<DirectoryEntry> allDirEntries = directoryEntryRepository.findByBatchIdInAndDeletedAtIsNull(dirBatchIds);
            for (DirectoryEntry de : allDirEntries) {
                String phone = de.getPhoneNumber();
                if (phone != null) {
                    if (de.getOrgId() != null && !directoryOrgMap.containsKey(phone)) {
                        directoryOrgMap.put(phone, de.getOrgId());
                    }
                    if (de.getIsSeconded() == 0 && !directoryInfoMap.containsKey(phone)) {
                        Map<String, String> info = new HashMap<>();
                        info.put("username", de.getUsername() != null ? de.getUsername() : "");
                        info.put("extension", de.getExtension() != null ? de.getExtension() : "");
                        directoryInfoMap.put(phone, info);
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", paged.getContent());
        result.put("total", total);
        result.put("page", paged.getNumber());
        result.put("size", paged.getSize());
        result.put("directoryOrgMap", directoryOrgMap);
        result.put("directoryInfoMap", directoryInfoMap);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 通讯录导入 ====================

    @PostMapping("/directory")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importDirectory(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            DirectoryBatch batch = directoryImportService.importDirectory(file, userId);
            auditLogService.log(userId, "IMPORT_DIRECTORY", "directory_batch", batch.getId(),
                    Map.of("batch_no", batch.getBatchNo(), "import_status", batch.getImportStatus()));
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "import_status", batch.getImportStatus(),
                    "message", "导入已启动，请轮询进度"
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("通讯录导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/directory/progress/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDirectoryImportProgress(
            @PathVariable Long batchId) {
        var progress = directoryImportService.getProgress(batchId);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UNKNOWN", "message", "未找到导入任务")));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status", progress.getStatus(),
                "total", progress.getTotal(),
                "processed", progress.getProcessed(),
                "seconded_count", progress.getSecondedCount(),
                "elapsed_ms", progress.getElapsedMs(),
                "message", progress.getMessage() != null ? progress.getMessage() : ""
        )));
    }

    @GetMapping("/directory/batches")
    public ResponseEntity<ApiResponse<List<DirectoryBatch>>> listDirectoryBatches(
            @RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(directoryBatchRepository.findByDeletedAtIsNull()));
    }

    @GetMapping("/directory/template")
    public ResponseEntity<byte[]> downloadDirectoryTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("部门归属");

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Headers: 号码, 部门路径, 分摊部门, 组织代码, 成本中心, 例外, 备注
            String[] headers = {"号码", "部门路径", "分摊部门", "组织代码", "成本中心", "例外", "备注"};
            String[] examples = {"1056070686", "100001-北京分行-100315", "936", "100315", "111", "是", "总行借调"};
            Row headerRow = sheet.createRow(0);
            Row exampleRow = sheet.createRow(1);

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 5000);

                Cell exampleCell = exampleRow.createCell(i);
                exampleCell.setCellValue(examples[i]);
                exampleCell.setCellStyle(sheet.getWorkbook().createCellStyle());
            }

            // Italic style for example row
            CellStyle italicStyle = wb.createCellStyle();
            Font italicFont = wb.createFont();
            italicFont.setItalic(true);
            italicFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            italicStyle.setFont(italicFont);
            for (int i = 0; i < examples.length; i++) {
                exampleRow.getCell(i).setCellStyle(italicStyle);
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("部门归属导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成部门归属模板失败: " + e.getMessage());
        }
    }

    @GetMapping("/directory/entries/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listDirectoryEntries(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        // M-08: Cap page size to prevent OOM with large datasets
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        org.springframework.data.domain.Page<DirectoryEntry> paged;
        if (scope.isAllScope()) {
            paged = directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null && !visibleIds.isEmpty()) {
                paged = directoryEntryRepository.findByBatchIdAndOrgIdInAndDeletedAtIsNull(batchId, visibleIds, pageable);
            } else {
                paged = org.springframework.data.domain.Page.empty(pageable);
            }
        }

        // M-08: Build code→name map using projection query instead of loading all orgs
        Map<String, String> codeToNameMap = new HashMap<>();
        List<Object[]> codeNamePairs = organizationRepository.findCodeNamePairs();
        for (Object[] pair : codeNamePairs) {
            String code = (String) pair[0];
            String name = (String) pair[1];
            if (code != null && !code.isEmpty()) {
                codeToNameMap.put(code, name);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", paged.getContent());
        result.put("total", paged.getTotalElements());
        result.put("page", paged.getNumber());
        result.put("size", paged.getSize());
        result.put("codeToNameMap", codeToNameMap);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 电信账单导入 ====================

    @GetMapping("/bill/template")
    public ResponseEntity<byte[]> downloadBillTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // --- Resolve sheet definitions from active BillTemplate or fallback ---
            List<SheetDef> sheetDefs = resolveSheetDefs();

            // --- Styles ---
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle exampleStyle = wb.createCellStyle();
            Font exampleFont = wb.createFont();
            exampleFont.setItalic(true);
            exampleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            exampleStyle.setFont(exampleFont);

            // --- Generate sheets ---
            for (SheetDef def : sheetDefs) {
                Sheet sheet = wb.createSheet(def.sheetName);
                Row headerRow = sheet.createRow(0);
                Row exampleRow = sheet.createRow(1);

                for (int i = 0; i < def.headers.length; i++) {
                    // Header cell
                    Cell hCell = headerRow.createCell(i);
                    hCell.setCellValue(def.headers[i]);
                    hCell.setCellStyle(headerStyle);
                    sheet.setColumnWidth(i, 5000);

                    // Example cell
                    Cell eCell = exampleRow.createCell(i);
                    if (def.examples != null && i < def.examples.length && def.examples[i] != null) {
                        eCell.setCellValue(def.examples[i]);
                    }
                    eCell.setCellStyle(exampleStyle);
                }
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("账单导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成账单模板失败: " + e.getMessage());
        }
    }

    /**
     * Provide the 4-sheet China Telecom bill template for download.
     * The headers match the actual bill format exactly, including all columns
     * (even those not directly mapped in the import template's sheet_configs).
     */
    private List<SheetDef> resolveSheetDefs() {
        List<SheetDef> defs = new ArrayList<>();

        // Sheet 1: 按号码费用 (10 columns matching actual China Telecom format)
        defs.add(new SheetDef("按号码费用",
                new String[]{"号码", "平台使用费", "码号月租费", "国内外呼时长（分钟）", "转接外呼时长（分钟）",
                        "国内费用", "国际时长（分钟）", "国际费用", "费用小计(单位：元)", "备注"},
                new String[]{"13800138000", "10.00", "25.00", "120", "30", "5.50", "0", "0.00", "40.50", ""}));

        // Sheet 2: 录音费用 (4 columns)
        defs.add(new SheetDef("录音费用",
                new String[]{"分机号", "号码", "关闭时间", "费用小计"},
                new String[]{"8001", "13800138000", "2026-01-15", "3.00"}));

        // Sheet 3: 彩铃费用 (3 columns)
        defs.add(new SheetDef("彩铃费用",
                new String[]{"分机号", "号码", "费用"},
                new String[]{"8001", "13800138000", "2.00"}));

        // Sheet 4: 闪信费用 (4 columns)
        defs.add(new SheetDef("闪信费用",
                new String[]{"号码", "月份", "下发量", "金额"},
                new String[]{"13800138000", "202606", "1000", "5.00"}));

        return defs;
    }

    /** Internal sheet definition */
    private static class SheetDef {
        final String sheetName;
        final String[] headers;
        final String[] examples;
        SheetDef(String sheetName, String[] headers, String[] examples) {
            this.sheetName = sheetName;
            this.headers = headers;
            this.examples = examples;
        }
    }

    @DeleteMapping("/bill/batches/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBillBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        BillBatch batch = billBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在或已被删除: " + id));

        // P1-1: Prevent deletion if allocation results have been confirmed
        long confirmedCount = allocationResultRepository.countByBatchIdAndConfirmStatusAndDeletedAtIsNull(id, (byte) 1);
        if (confirmedCount > 0) {
            throw new IllegalArgumentException("该账单已有 " + confirmedCount + " 条已确认的分摊结果，请先撤回后再删除");
        }

        // Soft-delete cascade: details → allocation adjustments → allocation results → snapshot → batch
        billDetailRepository.softDeleteByBatchId(id);
        allocationAdjustmentRepository.softDeleteByBatchId(id);
        allocationResultRepository.softDeleteByBatchId(id);
        dataSnapshotRepository.softDeleteByBillBatchId(id);

        batch.setDeletedAt(java.time.LocalDateTime.now());
        billBatchRepository.save(batch);

        auditLogService.log(userId, "DELETE_BILL_BATCH", "bill_batch", id,
                Map.of("batch_no", batch.getBatchNo(), "billing_month", batch.getBillingMonth()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", id,
                "batch_no", batch.getBatchNo(),
                "deleted", true
        )));
    }

    @PostMapping("/bill")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importBill(
            @RequestParam("file") MultipartFile file,
            @RequestParam("billing_month") @jakarta.validation.constraints.Pattern(regexp = "^\\d{4}-\\d{2}$", message = "月份格式必须为 yyyy-MM") String billingMonth,
            @RequestAttribute("userId") Long userId) {
        try {
            BillBatch batch = billImportService.importBill(file, userId, billingMonth);
            auditLogService.log(userId, "IMPORT_BILL", "bill_batch", batch.getId(),
                    Map.of("batch_no", batch.getBatchNo(), "import_status", batch.getImportStatus()));
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "batch_id", batch.getId(),
                    "batch_no", batch.getBatchNo(),
                    "import_status", batch.getImportStatus(),
                    "message", "导入已启动，请轮询进度"
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("账单导入失败: " + e.getMessage());
        }
    }

    @PutMapping("/bill/batches/{id}/month")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<BillBatch>> updateBillBatchMonth(
            @PathVariable Long id,
            @RequestBody @Valid SetDirectoryMonthRequest request,
            @RequestAttribute("userId") Long userId) {
        BillBatch batch = billBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在或已被删除: " + id));
        // Only allow month change when batch has no confirmed allocation results
        long confirmedCount = allocationResultRepository.countByBatchIdAndConfirmStatusAndDeletedAtIsNull(id, (byte) 1);
        if (confirmedCount > 0) {
            throw new IllegalArgumentException("该账单已有已确认的分摊结果，无法修改月份");
        }
        batch.setBillingMonth(request.getBillingMonth());
        billBatchRepository.save(batch);
        auditLogService.log(userId, "UPDATE_BILL_MONTH", "bill_batch", id,
                Map.of("billing_month", request.getBillingMonth()));
        return ResponseEntity.ok(ApiResponse.ok(batch));
    }

    @GetMapping("/bill/progress/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBillImportProgress(
            @PathVariable Long batchId) {
        var progress = billImportService.getProgress(batchId);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UNKNOWN", "message", "未找到导入任务")));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", progress.getStatus());
        result.put("total", progress.getTotal());
        result.put("processed", progress.getProcessed());
        result.put("elapsed_ms", progress.getElapsedMs());
        result.put("message", progress.getMessage() != null ? progress.getMessage() : "");
        if (progress.getSheetInfo() != null) {
            result.put("sheet_info", progress.getSheetInfo());
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/bill/batches")
    public ResponseEntity<ApiResponse<List<BillBatch>>> listBillBatches(
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        List<BillBatch> batches;
        if (billingMonth != null && !billingMonth.isBlank()) {
            batches = billBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
        } else {
            batches = billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        }
        return ResponseEntity.ok(ApiResponse.ok(batches));
    }

    @GetMapping("/bill/months")
    public ResponseEntity<ApiResponse<List<String>>> listBillMonths() {
        List<String> months = billBatchRepository.findDistinctBillingMonths();
        return ResponseEntity.ok(ApiResponse.ok(months));
    }

    @GetMapping("/bill/details/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listBillDetails(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(value = "sheet_type", required = false) String sheetType,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        // M-08: Cap page size to prevent OOM
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        org.springframework.data.domain.Page<BillDetail> paged;
        if (sheetType != null && !sheetType.isBlank()) {
            // Filter by sheet type
            if (scope.isAllScope()) {
                paged = billDetailRepository.findByBatchIdAndSheetTypeAndDeletedAtIsNull(batchId, sheetType, pageable);
            } else {
                var visibleIds = scope.getVisibleOrgIds();
                if (visibleIds != null && !visibleIds.isEmpty()) {
                    paged = billDetailRepository.findByBatchIdAndSheetTypeAndOrgIdInAndDeletedAtIsNull(batchId, sheetType, visibleIds, pageable);
                } else {
                    paged = org.springframework.data.domain.Page.empty(pageable);
                }
            }
        } else {
            // No sheet type filter — return all
            if (scope.isAllScope()) {
                paged = billDetailRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
            } else {
                var visibleIds = scope.getVisibleOrgIds();
                if (visibleIds != null && !visibleIds.isEmpty()) {
                    paged = billDetailRepository.findByBatchIdAndOrgIdInAndDeletedAtIsNull(batchId, visibleIds, pageable);
                } else {
                    paged = org.springframework.data.domain.Page.empty(pageable);
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", paged.getContent());
        result.put("total", paged.getTotalElements());
        result.put("page", paged.getNumber());
        result.put("size", paged.getSize());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 归属匹配 ====================

    @PostMapping("/match-ownership")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchOwnership(
            @Valid @RequestBody MatchOwnershipRequest req,
            @RequestAttribute("userId") Long userId) {
        Long billBatchId = req.getBillBatchId();
        Long ownershipBatchId = req.getOwnershipBatchId();
        Long directoryBatchId = req.getDirectoryBatchId();

        int matched = ownershipMatchService.matchOwnershipForBillBatch(
                billBatchId, ownershipBatchId, directoryBatchId);

        // Save or update snapshot record
        Optional<DataSnapshot> existing = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId);
        DataSnapshot snapshot;
        if (existing.isPresent()) {
            snapshot = existing.get();
            snapshot.setOwnershipBatchId(ownershipBatchId);
            snapshot.setDirectoryBatchId(directoryBatchId);
            snapshot.setMatchedCount(matched);
        } else {
            snapshot = DataSnapshot.builder()
                    .billBatchId(billBatchId)
                    .ownershipBatchId(ownershipBatchId)
                    .directoryBatchId(directoryBatchId)
                    .matchedCount(matched)
                    .build();
        }
        dataSnapshotRepository.save(snapshot);

        auditLogService.log(userId, "MATCH_OWNERSHIP", "bill_batch", billBatchId,
                Map.of("matched_count", matched));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "bill_batch_id", billBatchId,
                "matched_count", matched
        )));
    }

    // ==================== 通讯录快照 ====================

    @PutMapping("/directory/entries/{id}/clear-exception")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryEntry>> clearException(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        DirectoryEntry entry = directoryEntryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
        entry.setIsSeconded((byte) 0);
        entry.setSecondedKeyword("");
        directoryEntryRepository.save(entry);
        auditLogService.log(userId, "CLEAR_EXCEPTION", "directory_entry", id,
                Map.of("phone_number", entry.getPhoneNumber()));
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    @PutMapping("/directory/entries/{id}/sync-from-match")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryEntry>> syncFromMatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        DirectoryEntry entry = directoryEntryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
        // Find matching non-exception entry by phone number in same batch
        List<DirectoryEntry> matches = directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(entry.getBatchId());
        DirectoryEntry match = matches.stream()
                .filter(e -> e.getPhoneNumber().equals(entry.getPhoneNumber()) && !e.getId().equals(id) && e.getIsSeconded() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到匹配的当前数据记录"));
        entry.setDeptPath(match.getDeptPath());
        directoryEntryRepository.save(entry);
        auditLogService.log(userId, "SYNC_FROM_MATCH", "directory_entry", id,
                Map.of("phone_number", entry.getPhoneNumber()));
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    @PutMapping("/directory/entries/{id}/reason")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryEntry>> updateExceptionReason(
            @PathVariable Long id,
            @Valid @RequestBody UpdateExceptionReasonRequest req,
            @RequestAttribute("userId") Long userId) {
        String reason = req.getReason();
        DirectoryEntry entry = directoryEntryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
        entry.setSecondedKeyword(reason != null ? reason : "");
        directoryEntryRepository.save(entry);
        auditLogService.log(userId, "UPDATE_EXCEPTION_REASON", "directory_entry", id,
                Map.of("phone_number", entry.getPhoneNumber(), "reason", reason != null ? reason : ""));
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    @PutMapping("/directory/entries/batch-clear-exception")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchClearException(
            @Valid @RequestBody BatchClearExceptionRequest req,
            @RequestAttribute("userId") Long userId) {
        List<Long> ids = req.getIds();
        // M-39: Batch load + saveAll instead of N+1 individual findById+save
        List<DirectoryEntry> entries = directoryEntryRepository.findAllById(ids);
        for (DirectoryEntry entry : entries) {
            entry.setIsSeconded((byte) 0);
            entry.setSecondedKeyword("");
        }
        directoryEntryRepository.saveAll(entries);
        int count = entries.size();
        auditLogService.log(userId, "BATCH_CLEAR_EXCEPTION", "directory_entry", null,
                Map.of("count", count));
        return ResponseEntity.ok(ApiResponse.ok(Map.of("cleared", count)));
    }

    @PutMapping("/directory/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<DirectoryEntry>> updateDirectoryEntry(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDirectoryEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        DirectoryEntry entry = directoryEntryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + id));
        entry.setDeptPath(req.getDeptPath());
        entry.setAllocDept(req.getAllocDept() != null ? req.getAllocDept() : "");
        entry.setOrgCode(req.getOrgCode() != null ? req.getOrgCode() : "");
        entry.setCostCenter(req.getCostCenter() != null ? req.getCostCenter() : "");
        entry.setRemark(req.getRemark() != null ? req.getRemark() : "");
        // Re-match org_id from updated dept_path
        Map<String, Long> orgCodeMap = organizationRepository.findByDeletedAtIsNull().stream()
                .filter(o -> o.getCode() != null && !o.getCode().isEmpty())
                .collect(java.util.stream.Collectors.toMap(
                        o -> o.getCode().trim(), o -> o.getId(), (a, b) -> a));
        Long orgId = matchOrgFromPathFast(entry.getDeptPath(), orgCodeMap);
        if (orgId == null && req.getOrgCode() != null && !req.getOrgCode().trim().isEmpty()) {
            orgId = orgCodeMap.get(req.getOrgCode().trim());
        }
        entry.setOrgId(orgId);
        directoryEntryRepository.save(entry);
        auditLogService.log(userId, "UPDATE_DIR_ENTRY", "directory_entry", id,
                Map.of("dept_path", req.getDeptPath()));
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    private Long matchOrgFromPathFast(String deptPath, Map<String, Long> orgCodeMap) {
        if (deptPath == null || deptPath.isEmpty()) return null;
        String[] segments = deptPath.split("-");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i].trim();
            Long orgId = orgCodeMap.get(segment);
            if (orgId != null) return orgId;
        }
        return null;
    }

    @PutMapping("/directory/batches/{id}/month")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DirectoryBatch>> setDirectoryMonth(
            @PathVariable Long id,
            @Valid @RequestBody SetDirectoryMonthRequest req,
            @RequestAttribute("userId") Long userId) {
        DirectoryBatch batch = directoryBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在: " + id));
        String month = req.getBillingMonth();
        batch.setBillingMonth(month);
        directoryBatchRepository.save(batch);
        auditLogService.log(userId, "CREATE_SNAPSHOT", "directory_batch", id,
                Map.of("billing_month", month));
        return ResponseEntity.ok(ApiResponse.ok(batch));
    }

    @GetMapping("/directory/snapshots")
    public ResponseEntity<ApiResponse<List<DirectoryBatch>>> listDirectorySnapshots() {
        List<DirectoryBatch> snapshots = directoryBatchRepository.findByDeletedAtIsNull().stream()
                .filter(b -> b.getBillingMonth() != null)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(snapshots));
    }

    // ==================== 数据快照 ====================

    @GetMapping("/snapshots")
    public ResponseEntity<ApiResponse<List<DataSnapshot>>> listSnapshots() {
        List<DataSnapshot> snapshots = dataSnapshotRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        return ResponseEntity.ok(ApiResponse.ok(snapshots));
    }

    @GetMapping("/snapshots/{billBatchId}")
    public ResponseEntity<ApiResponse<DataSnapshot>> getSnapshot(@PathVariable Long billBatchId) {
        DataSnapshot snapshot = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId)
                .orElseThrow(() -> new IllegalArgumentException("未找到账单批次 " + billBatchId + " 的快照记录"));
        return ResponseEntity.ok(ApiResponse.ok(snapshot));
    }

    // ==================== 录音数据导入 ====================

    @PostMapping("/recording-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importRecordingData(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            RecordingDataBatch batch = recordingDataImportService.importRecordingData(file, userId);
            auditLogService.log(userId, "IMPORT_RECORDING_DATA", "recording_data_batch", batch.getId(),
                    "导入录音数据: " + file.getOriginalFilename());
            Map<String, Object> result = new HashMap<>();
            result.put("batch_id", batch.getId());
            result.put("batch_no", batch.getBatchNo());
            result.put("import_status", batch.getImportStatus());
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            throw new IllegalArgumentException("录音数据导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/recording-data/progress/{batchId}")
    public ResponseEntity<ApiResponse<RecordingDataImportService.ImportProgress>> getRecordingDataProgress(
            @PathVariable Long batchId) {
        RecordingDataImportService.ImportProgress progress = recordingDataImportService.getProgress(batchId);
        if (progress == null) {
            RecordingDataBatch batch = recordingDataBatchRepository.findById(batchId).orElse(null);
            RecordingDataImportService.ImportProgress p = new RecordingDataImportService.ImportProgress();
            if (batch != null && batch.getImportStatus() == 1) {
                p.setStatus("COMPLETED");
                p.setProcessed(batch.getTotalCount());
                p.setTotal(batch.getTotalCount());
            } else {
                p.setStatus("UNKNOWN");
            }
            return ResponseEntity.ok(ApiResponse.ok(p));
        }
        return ResponseEntity.ok(ApiResponse.ok(progress));
    }

    @GetMapping("/recording-data/template")
    public ResponseEntity<byte[]> downloadRecordingDataTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("录音数据");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("分机号");
            header.createCell(1).setCellValue("外线号码");
            header.createCell(2).setCellValue("所属部门");
            header.createCell(3).setCellValue("备注");
            for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recording_data_template.xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/recording-data/batches")
    public ResponseEntity<ApiResponse<List<RecordingDataBatch>>> listRecordingDataBatches() {
        return ResponseEntity.ok(ApiResponse.ok(recordingDataBatchRepository.findByDeletedAtIsNull()));
    }

    @GetMapping("/recording-data/entries/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listRecordingDataEntries(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<RecordingDataEntry> pageResult =
                recordingDataEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/recording-data/batches/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteRecordingDataBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        RecordingDataBatch batch = recordingDataBatchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        batch.setDeletedAt(java.time.LocalDateTime.now());
        recordingDataBatchRepository.save(batch);
        // Soft-delete entries
        recordingDataEntryRepository.softDeleteByBatchId(id, java.time.LocalDateTime.now());
        auditLogService.log(userId, "DELETE_RECORDING_DATA", "recording_data_batch", id,
                "删除录音数据批次: " + batch.getBatchNo());
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("batch_no", batch.getBatchNo());
        result.put("deleted", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
