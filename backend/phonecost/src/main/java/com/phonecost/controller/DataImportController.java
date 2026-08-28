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
import com.phonecost.service.PhoneOwnershipGeneratorService;
import com.phonecost.service.RecordingDataImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
    private final PhoneOwnershipGeneratorService ownershipGeneratorService;
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
    private final AllocationDeptEntryRepository allocationDeptEntryRepository;
    private final AllocationOrgEntryRepository allocationOrgEntryRepository;
    private final ComparisonArchiveRepository comparisonArchiveRepository;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== 号码归属导入 ====================

    @PostMapping("/ownership")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importOwnership(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        try {
            PhoneOwnershipBatch batch = ownershipImportService.importOwnership(file, userId, billingMonth);
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

    // ==================== 号码归属自动生成（4步匹配） ====================

    @PostMapping("/ownership/generate")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateOwnership(
            @RequestParam("billing_month") String billingMonth,
            @RequestAttribute("userId") Long userId) {
        try {
            Map<String, Object> result = ownershipGeneratorService.generate(billingMonth, userId);
            Long batchId = result.get("batch_id") != null ? ((Number) result.get("batch_id")).longValue() : null;
            auditLogService.log(userId, "GENERATE_OWNERSHIP", "ownership_batch", batchId,
                    Map.of("billing_month", billingMonth, "total_count", result.get("total_count")));
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            throw new IllegalArgumentException("号码归属生成失败: " + e.getMessage());
        }
    }

    // ==================== 号码归属同步分摊机构数据 ====================

    @PostMapping("/ownership/sync-allocation-org")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncAllocationOrg(
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId) {
        String billingMonth = body.get("billing_month");
        if (billingMonth == null || billingMonth.isEmpty()) {
            throw new IllegalArgumentException("billing_month 不能为空");
        }
        try {
            // 1. 加载该月份所有号码归属记录（非软删除）
            List<PhoneOwnershipEntry> ownershipEntries = ownershipEntryRepository.findAllByBillingMonth(billingMonth);
            if (ownershipEntries.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of(
                        "total", 0, "updated", 0, "skipped", 0,
                        "message", "该月份无号码归属数据"
                )));
            }

            // 2. 加载所有号码分摊机构记录（跨月），按 billingMonth DESC 排序，
            //    同号码取最近月份有值的记录：优先取 alloc_dept/org_code/cost_center 非空且最新的记录
            Map<String, AllocationOrgEntry> allocOrgMap = new HashMap<>();
            List<AllocationOrgEntry> allocOrgEntries = allocationOrgEntryRepository.findAllActiveOrderedByMonthDesc();
            for (AllocationOrgEntry aoe : allocOrgEntries) {
                String phone = aoe.getPhoneNumber();
                if (phone == null || phone.isEmpty()) continue;
                AllocationOrgEntry existing = allocOrgMap.get(phone);
                if (existing == null) {
                    // 首次出现，直接放入（最近月份的记录）
                    allocOrgMap.put(phone, aoe);
                } else {
                    // 已有记录，如果现有的字段为空而当前记录有值，则用当前记录覆盖
                    boolean existingHasAlloc = existing.getAllocDept() != null && !existing.getAllocDept().isEmpty();
                    boolean existingHasOrg = existing.getOrgCode() != null && !existing.getOrgCode().isEmpty();
                    boolean existingHasCost = existing.getCostCenter() != null && !existing.getCostCenter().isEmpty();
                    if (!existingHasAlloc || !existingHasOrg || !existingHasCost) {
                        // 现有记录缺少某些字段，尝试从当前记录补充
                        if (!existingHasAlloc && aoe.getAllocDept() != null && !aoe.getAllocDept().isEmpty()) {
                            existing.setAllocDept(aoe.getAllocDept());
                        }
                        if (!existingHasOrg && aoe.getOrgCode() != null && !aoe.getOrgCode().isEmpty()) {
                            existing.setOrgCode(aoe.getOrgCode());
                        }
                        if (!existingHasCost && aoe.getCostCenter() != null && !aoe.getCostCenter().isEmpty()) {
                            existing.setCostCenter(aoe.getCostCenter());
                        }
                    }
                }
            }

            // 3. 按号码匹配，将 alloc_dept / org_code / cost_center 写入 phone_ownership_entry
            int updated = 0;
            int skipped = 0;
            for (PhoneOwnershipEntry entry : ownershipEntries) {
                String phone = entry.getPhoneNumber();
                if (phone == null || phone.isEmpty()) {
                    skipped++;
                    continue;
                }
                AllocationOrgEntry match = allocOrgMap.get(phone);
                if (match == null) {
                    skipped++;
                    continue;
                }
                boolean changed = false;
                if (match.getAllocDept() != null && !match.getAllocDept().isEmpty()) {
                    String old = entry.getAllocDept();
                    entry.setAllocDept(match.getAllocDept());
                    if (old == null || !old.equals(match.getAllocDept())) changed = true;
                }
                if (match.getOrgCode() != null && !match.getOrgCode().isEmpty()) {
                    String old = entry.getOrgCode();
                    entry.setOrgCode(match.getOrgCode());
                    if (old == null || !old.equals(match.getOrgCode())) changed = true;
                }
                if (match.getCostCenter() != null && !match.getCostCenter().isEmpty()) {
                    String old = entry.getCostCenter();
                    entry.setCostCenter(match.getCostCenter());
                    if (old == null || !old.equals(match.getCostCenter())) changed = true;
                }
                if (changed) {
                    updated++;
                }
            }

            // 4. 批量保存
            ownershipEntryRepository.saveAll(ownershipEntries);
            ownershipEntryRepository.flush();

            auditLogService.log(userId, "SYNC_ALLOCATION_ORG", "ownership_batch", null,
                    Map.of("billing_month", billingMonth, "total", ownershipEntries.size(),
                           "updated", updated, "skipped", skipped));

            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "total", ownershipEntries.size(),
                    "updated", updated,
                    "skipped", skipped,
                    "message", "同步完成"
            )));
        } catch (Exception e) {
            throw new IllegalArgumentException("同步分摊机构数据失败: " + e.getMessage());
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

            String[] headers = {"号码", "一级分行", "二级分行", "当前状态"};
            String[] examples = {"037131168014", "郑州分行", "城东信贷支行", "正常"};
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

            String fileName = java.net.URLEncoder.encode("归属分行导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成归属分行模板失败: " + e.getMessage());
        }
    }

    @GetMapping("/ownership/batches")
    public ResponseEntity<ApiResponse<List<PhoneOwnershipBatch>>> listOwnershipBatches(
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        List<PhoneOwnershipBatch> batches;
        if (billingMonth != null && !billingMonth.isBlank()) {
            batches = ownershipBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
        } else {
            batches = ownershipBatchRepository.findByDeletedAtIsNullOrderByIdDesc();
        }
        return ResponseEntity.ok(ApiResponse.ok(batches));
    }

    @GetMapping("/ownership/months")
    public ResponseEntity<ApiResponse<List<String>>> listOwnershipMonths() {
        List<String> months = ownershipBatchRepository.findDistinctBillingMonths();
        return ResponseEntity.ok(ApiResponse.ok(months));
    }

    @GetMapping("/ownership/entries-by-month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOwnershipEntriesByMonth(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        org.springframework.data.domain.Page<PhoneOwnershipEntry> pageResult;
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        if (scope.isAllScope()) {
            pageResult = hasSearch
                    ? ownershipEntryRepository.searchByBillingMonthAndKeyword(billingMonth, keyword, pageable)
                    : ownershipEntryRepository.findByBillingMonth(billingMonth, pageable);
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null && !visibleIds.isEmpty()) {
                pageResult = hasSearch
                        ? ownershipEntryRepository.searchByBillingMonthAndKeywordAndOrgIdIn(billingMonth, keyword, visibleIds, pageable)
                        : ownershipEntryRepository.findByBillingMonthAndOrgIdIn(billingMonth, visibleIds, pageable);
            } else {
                pageResult = org.springframework.data.domain.Page.empty(pageable);
            }
        }

        // Build full_path → {branch, dept_name} map from allocation_dept_entry (matched by billing_month)
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth);
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        // Build exception phone → {full_path, matched_branch, matched_dept} map for cross-check
        Map<String, PhoneOwnershipEntry> exceptionByPhone = new HashMap<>();
        List<PhoneOwnershipEntry> allExceptions = ownershipEntryRepository.findAllExceptionsByBillingMonth(billingMonth);
        for (PhoneOwnershipEntry ex : allExceptions) {
            if (ex.getPhoneNumber() != null && !exceptionByPhone.containsKey(ex.getPhoneNumber())) {
                exceptionByPhone.put(ex.getPhoneNumber(), ex);
            }
        }

        // Load all entries of the month (no pagination) for statistics computation
        List<PhoneOwnershipEntry> entriesAll;
        if (scope.isAllScope()) {
            entriesAll = ownershipEntryRepository.findAllByBillingMonth(billingMonth);
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            entriesAll = (visibleIds != null && !visibleIds.isEmpty())
                    ? ownershipEntryRepository.findAllByBillingMonthAndOrgIdIn(billingMonth, visibleIds)
                    : List.of();
        }

        // Build directory_entry phone → extensions & phone → dept_paths maps (same billing month)
        Map<String, java.util.LinkedHashSet<String>> dirExtMap = new HashMap<>();
        Map<String, java.util.LinkedHashSet<String>> dirDeptMap = new HashMap<>();
        List<Object[]> dirRows = directoryEntryRepository.findPhoneExtAndDeptByMonth(billingMonth);
        for (Object[] row : dirRows) {
            String phone = (String) row[0];
            String ext = (String) row[1];
            String dept = (String) row[2];
            if (phone != null && !phone.isEmpty()) {
                if (ext != null && !ext.isEmpty()) {
                    dirExtMap.computeIfAbsent(phone, k -> new java.util.LinkedHashSet<>()).add(ext);
                }
                if (dept != null && !dept.isEmpty()) {
                    dirDeptMap.computeIfAbsent(phone, k -> new java.util.LinkedHashSet<>()).add(dept);
                }
            }
        }

        // Build allocation_org_entry phone → {alloc_dept, org_code, cost_center} map (same billing month)
        Map<String, AllocationOrgEntry> allocOrgMap = new HashMap<>();
        List<AllocationOrgEntry> allocOrgEntries = allocationOrgEntryRepository.findAllByBillingMonth(billingMonth);
        for (AllocationOrgEntry aoe : allocOrgEntries) {
            String phone = aoe.getPhoneNumber();
            if (phone != null && !phone.isEmpty() && !allocOrgMap.containsKey(phone)) {
                allocOrgMap.put(phone, aoe);
            }
        }

        // Build per-entry branch/dept matched from allocation dept
        List<Map<String, Object>> enrichedEntries = new ArrayList<>();
        for (PhoneOwnershipEntry entry : pageResult.getContent()) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber());
            // Extension: lookup from directory_entry (same month, matched by phone_number), joined by "、"
            java.util.LinkedHashSet<String> dirExts = entry.getPhoneNumber() != null ? dirExtMap.get(entry.getPhoneNumber()) : null;
            String extValue = (dirExts != null && !dirExts.isEmpty()) ? String.join("、", dirExts) : "";
            e.put("extension", extValue);
            // Full path: lookup from directory_entry (same month, matched by phone_number), joined by "、"
            java.util.LinkedHashSet<String> dirDepts = entry.getPhoneNumber() != null ? dirDeptMap.get(entry.getPhoneNumber()) : null;
            String fpValue = (dirDepts != null && !dirDepts.isEmpty()) ? String.join("、", dirDepts) : "";
            e.put("full_path", fpValue);
            e.put("description", entry.getDescription() != null ? entry.getDescription() : "");
            e.put("is_exception", entry.getIsException());
            e.put("org_id", entry.getOrgId());
            e.put("match_level", entry.getMatchLevel() != null ? entry.getMatchLevel() : "");

            // Match branch/dept: prefer stored values, fallback to dynamic matching
            String storedL1 = entry.getL1Branch();
            String storedL2 = entry.getL2Branch();
            String matchedBranch;
            String matchedDept;

            if (storedL1 != null && !storedL1.isEmpty()) {
                // Use stored values (user may have edited them)
                matchedBranch = storedL1;
                matchedDept = storedL2 != null ? storedL2 : "";
            } else {
                // Fallback to dynamic matching from allocation_dept_entry
                String fp = entry.getFullPath();
                String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
                matchedBranch = match != null ? match[0] : "";
                matchedDept = match != null ? match[1] : "";
            }

            // Check against exception list: if same phone exists in exceptions
            PhoneOwnershipEntry exEntry = exceptionByPhone.get(entry.getPhoneNumber());
            boolean exceptionMismatch = false;
            if (exEntry != null && !Byte.valueOf((byte) 1).equals(entry.getIsException())) {
                // This entry is NOT an exception, but the same phone IS in exception list
                String exFullPath = exEntry.getFullPath() != null ? exEntry.getFullPath() : "";
                String entryFullPath = entry.getFullPath() != null ? entry.getFullPath() : "";
                if (!exFullPath.isEmpty() && !entryFullPath.isEmpty() && !exFullPath.equals(entryFullPath)) {
                    // full_path mismatch → use exception data and flag warning
                    exceptionMismatch = true;
                    String[] exMatch = allocDeptMap.get(exFullPath);
                    if (exMatch != null) {
                        matchedBranch = exMatch[0];
                        matchedDept = exMatch[1];
                    }
                } else if (!exFullPath.isEmpty() && exFullPath.equals(entryFullPath)) {
                    // full_path matches → use exception data (P0 priority)
                    String[] exMatch = allocDeptMap.get(exFullPath);
                    if (exMatch != null) {
                        matchedBranch = exMatch[0];
                        matchedDept = exMatch[1];
                    }
                }
            }

            e.put("l1_branch", matchedBranch);
            e.put("l2_branch", matchedDept);
            e.put("exception_mismatch", exceptionMismatch);
             e.put("status", entry.getStatus() != null ? entry.getStatus() : 0);

            // New fields for 4-step matching output (号码归属8列)
            // alloc_dept, org_code, cost_center: prefer same-month allocation_org_entry, fallback to stored value in ownership entry
            AllocationOrgEntry allocOrgMatch = entry.getPhoneNumber() != null ? allocOrgMap.get(entry.getPhoneNumber()) : null;
            String allocDeptVal = (allocOrgMatch != null && allocOrgMatch.getAllocDept() != null && !allocOrgMatch.getAllocDept().isEmpty()) ? allocOrgMatch.getAllocDept()
                    : (entry.getAllocDept() != null ? entry.getAllocDept() : "");
            String orgCodeVal = (allocOrgMatch != null && allocOrgMatch.getOrgCode() != null && !allocOrgMatch.getOrgCode().isEmpty()) ? allocOrgMatch.getOrgCode()
                    : (entry.getOrgCode() != null ? entry.getOrgCode() : "");
            String costCenterVal = (allocOrgMatch != null && allocOrgMatch.getCostCenter() != null && !allocOrgMatch.getCostCenter().isEmpty()) ? allocOrgMatch.getCostCenter()
                    : (entry.getCostCenter() != null ? entry.getCostCenter() : "");
            e.put("alloc_dept", allocDeptVal);
            e.put("org_code", orgCodeVal);
            e.put("cost_center", costCenterVal);

             e.put("updated_at", entry.getUpdatedAt() != null ? entry.getUpdatedAt().toString() : "");

             enrichedEntries.add(e);
         }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", enrichedEntries);
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);

        // Statistics: compute from all entries of the month (not just the current page)
        long totalCount = entriesAll.size();
        long exceptionCount = entriesAll.stream().filter(e -> e.getIsException() != null && e.getIsException() == 1).count();
        java.util.Set<String> distinctL1 = new java.util.HashSet<>();
        java.util.Set<String> distinctAlloc = new java.util.HashSet<>();
        long usedCount = 0;
        for (PhoneOwnershipEntry entry : entriesAll) {
            String phone = entry.getPhoneNumber();
            if (phone != null && !phone.isEmpty()) {
                java.util.LinkedHashSet<String> exts = dirExtMap.get(phone);
                if (exts != null && !exts.isEmpty()) usedCount++;
            }
            String l1 = entry.getL1Branch();
            if (l1 != null && !l1.isEmpty()) distinctL1.add(l1);
            String ad = entry.getAllocDept();
            if (ad != null && !ad.isEmpty()) distinctAlloc.add(ad);
        }
        result.put("stats_total", totalCount);
        result.put("stats_used", usedCount);
        result.put("stats_idle", totalCount - usedCount);
        result.put("stats_l1_branches", distinctL1.size());
        result.put("stats_alloc_depts", distinctAlloc.size());
        result.put("stats_exceptions", exceptionCount);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/ownership/exceptions-by-month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOwnershipExceptionsByMonth(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        org.springframework.data.domain.Page<PhoneOwnershipEntry> pageResult = hasSearch
                ? ownershipEntryRepository.searchExceptionsByBillingMonthAndKeyword(billingMonth, keyword, pageable)
                : ownershipEntryRepository.findExceptionsByBillingMonth(billingMonth, pageable);

        // Build full_path → {branch, dept_name} map from allocation_dept_entry
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth);
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        List<Map<String, Object>> enrichedEntries = new ArrayList<>();
        for (PhoneOwnershipEntry entry : pageResult.getContent()) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("phone_number", entry.getPhoneNumber());
            e.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
            e.put("full_path", entry.getFullPath() != null ? entry.getFullPath() : "");
            e.put("description", entry.getDescription() != null ? entry.getDescription() : "");
            e.put("match_level", entry.getMatchLevel() != null ? entry.getMatchLevel() : "");

            // Match branch/dept from allocation_dept_entry via full_path
            String fp = entry.getFullPath();
            String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
            e.put("matched_branch", match != null ? match[0] : "");
            e.put("matched_dept", match != null ? match[1] : "");
            // Exception reason stored in description field (after [例外] prefix)
            e.put("exception_reason", entry.getDescription() != null ? entry.getDescription() : "");

            enrichedEntries.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", enrichedEntries);
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 例外号码 CRUD ====================

    @PostMapping("/ownership/exceptions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> addExceptionEntry(
            @Valid @RequestBody ExceptionEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        String billingMonth = req.getBillingMonth();
        String phoneNumber = req.getPhoneNumber();
        String extension = req.getExtension() != null ? req.getExtension() : "";
        String fullPath = req.getFullPath() != null ? req.getFullPath() : "";
        String l1Branch = req.getL1Branch() != null ? req.getL1Branch() : "";
        String l2Branch = req.getL2Branch() != null ? req.getL2Branch() : "";
        String description = req.getDescription() != null ? req.getDescription() : "";

        // Find or create a "manual" batch for exception entries
        String batchNo = "OWN-" + billingMonth.replace("-", "") + "-EX-MANUAL";
        PhoneOwnershipBatch batch = ownershipBatchRepository.findByBatchNoAndDeletedAtIsNull(batchNo)
                .orElseGet(() -> {
                    PhoneOwnershipBatch b = new PhoneOwnershipBatch();
                    b.setBatchNo(batchNo);
                    b.setFileName("手动添加例外");
                    b.setTotalCount(0);
                    b.setExceptionCount(0);
                    b.setBillingMonth(billingMonth);
                    b.setImportStatus((byte) 1);
                    b.setImportedBy(userId);
                    return ownershipBatchRepository.save(b);
                });

        PhoneOwnershipEntry entry = new PhoneOwnershipEntry();
        entry.setBatchId(batch.getId());
        entry.setPhoneNumber(phoneNumber);
        entry.setExtension(extension);
        entry.setFullPath(fullPath);
        entry.setL1Branch(l1Branch);
        entry.setL2Branch(l2Branch);
        entry.setDescription(description);
        entry.setIsException((byte) 1);
        entry.setMatchLevel("P0");
        entry = ownershipEntryRepository.save(entry);

        // Update batch counts
        long count = ownershipEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
        batch.setTotalCount((int) count);
        batch.setExceptionCount((int) count);
        ownershipBatchRepository.save(batch);

        auditLogService.log(userId, "ADD_EXCEPTION_ENTRY", "phone_ownership_entry", entry.getId(),
                Map.of("phone_number", phoneNumber, "billing_month", billingMonth));

        Map<String, Object> result = new HashMap<>();
        result.put("id", entry.getId());
        result.put("batch_id", entry.getBatchId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PutMapping("/ownership/exceptions/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateExceptionEntry(
            @PathVariable Long id,
            @Valid @RequestBody ExceptionEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        PhoneOwnershipEntry entry = ownershipEntryRepository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在或已被删除: " + id));

        if (!Byte.valueOf((byte) 1).equals(entry.getIsException())) {
            throw new IllegalArgumentException("该记录不是例外号码: " + id);
        }

        if (req.getPhoneNumber() != null) entry.setPhoneNumber(req.getPhoneNumber());
        if (req.getExtension() != null) entry.setExtension(req.getExtension());
        if (req.getFullPath() != null) entry.setFullPath(req.getFullPath());
        if (req.getL1Branch() != null) entry.setL1Branch(req.getL1Branch());
        if (req.getL2Branch() != null) entry.setL2Branch(req.getL2Branch());
        if (req.getDescription() != null) entry.setDescription(req.getDescription());

        ownershipEntryRepository.save(entry);

        auditLogService.log(userId, "UPDATE_EXCEPTION_ENTRY", "phone_ownership_entry", id,
                Map.of("phone_number", entry.getPhoneNumber()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", id, "updated", true)));
    }

    @DeleteMapping("/ownership/exceptions/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteExceptionEntry(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        PhoneOwnershipEntry entry = ownershipEntryRepository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在或已被删除: " + id));

        if (!Byte.valueOf((byte) 1).equals(entry.getIsException())) {
            throw new IllegalArgumentException("该记录不是例外号码: " + id);
        }

        entry.setDeletedAt(java.time.LocalDateTime.now());
        ownershipEntryRepository.save(entry);

        // Update batch counts
        PhoneOwnershipBatch batch = ownershipBatchRepository.findByIdAndDeletedAtIsNull(entry.getBatchId()).orElse(null);
        if (batch != null) {
            long count = ownershipEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
            batch.setTotalCount((int) count);
            // Count exception entries
            long excCount = ownershipEntryRepository.countByBatchIdAndIsExceptionAndDeletedAtIsNull(batch.getId(), (byte) 1);
            batch.setExceptionCount((int) excCount);
            ownershipBatchRepository.save(batch);
        }

        auditLogService.log(userId, "DELETE_EXCEPTION_ENTRY", "phone_ownership_entry", id,
                Map.of("phone_number", entry.getPhoneNumber()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", id, "deleted", true)));
    }

    @GetMapping("/ownership/exceptions/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportExceptions(@RequestParam("billing_month") String billingMonth) {
        List<PhoneOwnershipEntry> entries = ownershipEntryRepository.findAllExceptionsByBillingMonth(billingMonth);

        // Build allocDeptMap for branch/dept matching
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth);
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外号码");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "分机号", "部门全路径", "分行", "部门", "例外原因"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (PhoneOwnershipEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
                row.createCell(1).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(2).setCellValue(e.getFullPath() != null ? e.getFullPath() : "");

                // Prefer stored l1_branch/l2_branch, fallback to allocation_dept_entry matching
                String storedBranch = e.getL1Branch();
                String storedDept = e.getL2Branch();
                boolean hasStoredBranch = storedBranch != null && !storedBranch.isBlank();
                boolean hasStoredDept = storedDept != null && !storedDept.isBlank();
                String[] bd = allocDeptMap.getOrDefault(e.getFullPath(), new String[]{"", ""});
                row.createCell(3).setCellValue(hasStoredBranch ? storedBranch : bd[0]);
                row.createCell(4).setCellValue(hasStoredDept ? storedDept : bd[1]);

                String reason = e.getDescription() != null ? e.getDescription() : "";
                row.createCell(5).setCellValue(reason);
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("例外号码_" + billingMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出例外号码失败: " + e.getMessage());
        }
    }

    @GetMapping("/ownership/exceptions/template")
    public ResponseEntity<byte[]> downloadExceptionTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外号码");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "分机号", "部门全路径", "分行", "部门", "例外原因"};
            String[] examples = {"037131168014", "8001", "/郑州分行/城东信贷支行", "郑州分行", "城东信贷支行", "特殊标记"};
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

            String fileName = java.net.URLEncoder.encode("例外号码导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成例外号码模板失败: " + e.getMessage());
        }
    }

    @PostMapping("/ownership/exceptions/import")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importExceptions(
            @RequestParam("file") MultipartFile file,
            @RequestParam("billing_month") String billingMonth,
            @RequestAttribute("userId") Long userId) {
        if (billingMonth.isBlank()) throw new IllegalArgumentException("月份不能为空");

        // Parse Excel OUTSIDE of transaction to avoid holding DB lock during file IO
        List<String[]> rows = new ArrayList<>();
        try (java.io.InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String phoneNumber = getCellStringValue(row, 0);
                if (phoneNumber.isBlank()) continue;
                String extension = getCellStringValue(row, 1);
                String fullPath = getCellStringValue(row, 2);
                String l1Branch = getCellStringValue(row, 3);
                String l2Branch = getCellStringValue(row, 4);
                String description = getCellStringValue(row, 5);
                rows.add(new String[]{phoneNumber, extension, fullPath, l1Branch, l2Branch, description});
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("例外号码导入失败: " + e.getMessage());
        }

        // DB writes inside transaction
        int imported = saveExceptionEntries(rows, billingMonth, userId);

        auditLogService.log(userId, "IMPORT_EXCEPTION_ENTRIES", "phone_ownership_batch", null,
                Map.of("imported", imported, "billing_month", billingMonth));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "imported", imported,
                "message", "导入成功"
        )));
    }

    @Transactional
    protected int saveExceptionEntries(List<String[]> rows, String billingMonth, Long userId) {
        String batchNo = "OWN-" + billingMonth.replace("-", "") + "-EX-IMPORT";
        PhoneOwnershipBatch batch = ownershipBatchRepository.findByBatchNoAndDeletedAtIsNull(batchNo)
                .orElseGet(() -> {
                    PhoneOwnershipBatch b = new PhoneOwnershipBatch();
                    b.setBatchNo(batchNo);
                    b.setFileName("例外号码导入");
                    b.setTotalCount(0);
                    b.setExceptionCount(0);
                    b.setBillingMonth(billingMonth);
                    b.setImportStatus((byte) 1);
                    b.setImportedBy(userId);
                    return ownershipBatchRepository.save(b);
                });

        int imported = 0;
        for (String[] r : rows) {
            PhoneOwnershipEntry entry = new PhoneOwnershipEntry();
            entry.setBatchId(batch.getId());
            entry.setPhoneNumber(r[0]);
            entry.setExtension(r[1]);
            entry.setFullPath(r[2]);
            entry.setL1Branch(r[3]);
            entry.setL2Branch(r[4]);
            entry.setDescription(r[5]);
            entry.setIsException((byte) 1);
            entry.setMatchLevel("P0");
            ownershipEntryRepository.save(entry);
            imported++;
        }

        // Update batch counts
        long count = ownershipEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
        batch.setTotalCount((int) count);
        batch.setExceptionCount((int) count);
        ownershipBatchRepository.save(batch);

        return imported;
    }

    private String getCellStringValue(Row row, int colIdx) {
        Cell cell = row.getCell(colIdx);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield (v == Math.floor(v) && !Double.isInfinite(v))
                        ? String.valueOf((long) v)
                        : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    String s = cell.getStringCellValue();
                    yield s != null ? s.trim() : "";
                } catch (Exception ex) {
                    try {
                        double v = cell.getNumericCellValue();
                        yield (v == Math.floor(v) && !Double.isInfinite(v))
                                ? String.valueOf((long) v)
                                : String.valueOf(v);
                    } catch (Exception ex2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }

    @GetMapping("/ownership/entries/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOwnershipEntries(
            @PathVariable Long batchId,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        org.springframework.data.domain.Page<PhoneOwnershipEntry> paged;
        if (scope.isAllScope()) {
            paged = hasSearch
                    ? ownershipEntryRepository.searchByBatchIdAndKeyword(batchId, keyword, pageable)
                    : ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null && !visibleIds.isEmpty()) {
                paged = hasSearch
                        ? ownershipEntryRepository.searchByBatchIdAndKeyword(batchId, keyword, pageable)
                        : ownershipEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
            } else {
                paged = org.springframework.data.domain.Page.empty(pageable);
            }
        }

        // Build full_path → {branch, dept_name} map from allocation_dept_entry
        String billingMonth2 = ownershipBatchRepository.findById(batchId)
                .map(b -> b.getBillingMonth()).orElse(null);
        Map<String, String[]> allocDeptMap2 = new HashMap<>();
        if (billingMonth2 != null) {
            List<AllocationDeptEntry> allocEntries2 = allocationDeptEntryRepository.findAllByBillingMonth(billingMonth2);
            for (AllocationDeptEntry ae : allocEntries2) {
                String fp = ae.getFullPath();
                if (fp != null && !fp.isEmpty() && !allocDeptMap2.containsKey(fp)) {
                    allocDeptMap2.put(fp, new String[]{
                            ae.getBranch() != null ? ae.getBranch() : "",
                            ae.getDeptName() != null ? ae.getDeptName() : ""
                    });
                }
            }
        }

        // Build exception phone → entry map for cross-check (from same batch)
        Map<String, PhoneOwnershipEntry> exceptionByPhone = new HashMap<>();
        List<PhoneOwnershipEntry> allExceptions = ownershipEntryRepository.findExceptionsByBatchId(batchId);
        for (PhoneOwnershipEntry ex : allExceptions) {
            if (ex.getPhoneNumber() != null && !exceptionByPhone.containsKey(ex.getPhoneNumber())) {
                exceptionByPhone.put(ex.getPhoneNumber(), ex);
            }
        }

        // Build per-entry with l1_branch/l2_branch/status (same logic as entries-by-month)
        List<Map<String, Object>> enrichedEntries2 = new ArrayList<>();
        for (PhoneOwnershipEntry entry : paged.getContent()) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber());
            e.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
            e.put("full_path", entry.getFullPath() != null ? entry.getFullPath() : "");
            e.put("description", entry.getDescription() != null ? entry.getDescription() : "");
            e.put("is_exception", entry.getIsException());
            e.put("org_id", entry.getOrgId());
            e.put("match_level", entry.getMatchLevel() != null ? entry.getMatchLevel() : "");

            // Match branch/dept: prefer stored values, fallback to dynamic matching
            String storedL1 = entry.getL1Branch();
            String storedL2 = entry.getL2Branch();
            String matchedBranch;
            String matchedDept;

            if (storedL1 != null && !storedL1.isEmpty()) {
                matchedBranch = storedL1;
                matchedDept = storedL2 != null ? storedL2 : "";
            } else {
                String fp2 = entry.getFullPath();
                String[] match = (fp2 != null && !fp2.isEmpty()) ? allocDeptMap2.get(fp2) : null;
                matchedBranch = match != null ? match[0] : "";
                matchedDept = match != null ? match[1] : "";
            }

            // Check against exception list: if same phone exists in exceptions
            boolean exceptionMismatch = false;
            PhoneOwnershipEntry exEntry = exceptionByPhone.get(entry.getPhoneNumber());
            if (exEntry != null && !Byte.valueOf((byte) 1).equals(entry.getIsException())) {
                String exFullPath = exEntry.getFullPath() != null ? exEntry.getFullPath() : "";
                String entryFullPath = entry.getFullPath() != null ? entry.getFullPath() : "";
                if (!exFullPath.isEmpty() && !entryFullPath.isEmpty() && !exFullPath.equals(entryFullPath)) {
                    exceptionMismatch = true;
                    String[] exMatch = allocDeptMap2.get(exFullPath);
                    if (exMatch != null) {
                        matchedBranch = exMatch[0];
                        matchedDept = exMatch[1];
                    }
                } else if (!exFullPath.isEmpty() && exFullPath.equals(entryFullPath)) {
                    String[] exMatch = allocDeptMap2.get(exFullPath);
                    if (exMatch != null) {
                        matchedBranch = exMatch[0];
                        matchedDept = exMatch[1];
                    }
                }
            }

            e.put("l1_branch", matchedBranch);
            e.put("l2_branch", matchedDept);
            e.put("exception_mismatch", exceptionMismatch);
            e.put("status", entry.getStatus() != null ? entry.getStatus() : 0);

            // New fields for 4-step matching output (号码归属8列)
            e.put("alloc_dept", entry.getAllocDept() != null ? entry.getAllocDept() : "");
            e.put("org_code", entry.getOrgCode() != null ? entry.getOrgCode() : "");
            e.put("cost_center", entry.getCostCenter() != null ? entry.getCostCenter() : "");

            e.put("updated_at", entry.getUpdatedAt() != null ? entry.getUpdatedAt().toString() : "");

            enrichedEntries2.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", enrichedEntries2);
        result.put("total", paged.getTotalElements());
        result.put("filtered", paged.getTotalElements());
        result.put("page", paged.getNumber());
        result.put("size", paged.getSize());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PutMapping("/ownership/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateOwnershipEntry(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOwnershipEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        PhoneOwnershipEntry entry = ownershipEntryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("号码归属记录不存在: " + id));

        // phone_number is read-only in edit — only l1_branch, l2_branch, status are editable
        if (req.getL1Branch() != null)
            entry.setL1Branch(req.getL1Branch());
        if (req.getL2Branch() != null)
            entry.setL2Branch(req.getL2Branch());
        if (req.getStatus() != null) {
            entry.setStatus(req.getStatus());
        }

        ownershipEntryRepository.save(entry);

        auditLogService.log(userId, "UPDATE_OWNERSHIP_ENTRY", "ownership_entry", id,
                Map.of("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "",
                       "status", String.valueOf(entry.getStatus())));

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("phone_number", entry.getPhoneNumber());
        result.put("full_path", entry.getFullPath() != null ? entry.getFullPath() : "");
        result.put("l1_branch", entry.getL1Branch() != null ? entry.getL1Branch() : "");
        result.put("l2_branch", entry.getL2Branch() != null ? entry.getL2Branch() : "");
        result.put("status", entry.getStatus() != null ? entry.getStatus() : 0);
        result.put("updated", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/ownership/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteOwnershipEntry(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        PhoneOwnershipEntry entry = ownershipEntryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("号码归属记录不存在或已被删除: " + id));

        entry.setDeletedAt(java.time.LocalDateTime.now());
        ownershipEntryRepository.save(entry);

        // Update batch total count
        PhoneOwnershipBatch batch = ownershipBatchRepository.findByIdAndDeletedAtIsNull(entry.getBatchId())
                .orElse(null);
        if (batch != null) {
            long count = ownershipEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
            batch.setTotalCount((int) count);
            ownershipBatchRepository.save(batch);
        }

        auditLogService.log(userId, "DELETE_OWNERSHIP_ENTRY", "phone_ownership_entry", id,
                Map.of("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : ""));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", id,
                "phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "",
                "deleted", true
        )));
    }

    // ==================== 号码归属：全量查询（跨批次，号码去重） ====================

    @GetMapping("/ownership/all-entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAllOwnershipEntries(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        size = Math.min(size, 200);

        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        // Deduplicated: one row per phone_number (latest record)
        List<Long> allIds = hasSearch
                ? ownershipEntryRepository.searchLatestEntryIdsPerPhoneNumber(keyword)
                : ownershipEntryRepository.findLatestEntryIdsPerPhoneNumber();
        long totalDistinct = hasSearch
                ? ownershipEntryRepository.searchDistinctNonExceptionPhoneNumbers(keyword)
                : ownershipEntryRepository.countDistinctNonExceptionPhoneNumbers();

        // Manual pagination on deduplicated IDs
        int start = page * size;
        List<Long> pageIds = (start < allIds.size())
                ? allIds.subList(start, Math.min(start + size, allIds.size()))
                : List.of();

        List<PhoneOwnershipEntry> entries = pageIds.isEmpty()
                ? List.of()
                : ownershipEntryRepository.findAllById(pageIds);

        // Sort by id DESC to match original order
        entries.sort((a, b) -> Long.compare(b.getId(), a.getId()));

        // Build allocDeptMap from all allocation_dept_entry records (not filtered by month)
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllActiveEntries();
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        List<Map<String, Object>> enrichedEntries = new ArrayList<>();
        for (PhoneOwnershipEntry entry : entries) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber());
            e.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
            e.put("full_path", entry.getFullPath() != null ? entry.getFullPath() : "");
            e.put("description", entry.getDescription() != null ? entry.getDescription() : "");
            e.put("is_exception", entry.getIsException());
            e.put("org_id", entry.getOrgId());
            e.put("match_level", entry.getMatchLevel() != null ? entry.getMatchLevel() : "");

            // Match branch/dept: prefer stored values, fallback to dynamic matching
            String storedL1 = entry.getL1Branch();
            String storedL2 = entry.getL2Branch();
            String matchedBranch;
            String matchedDept;

            if (storedL1 != null && !storedL1.isEmpty()) {
                matchedBranch = storedL1;
                matchedDept = storedL2 != null ? storedL2 : "";
            } else {
                String fp = entry.getFullPath();
                String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
                matchedBranch = match != null ? match[0] : "";
                matchedDept = match != null ? match[1] : "";
            }

            e.put("l1_branch", matchedBranch);
            e.put("l2_branch", matchedDept);
            e.put("status", entry.getStatus() != null ? entry.getStatus() : 0);
            e.put("updated_at", entry.getUpdatedAt() != null ? entry.getUpdatedAt().toString() : "");

            enrichedEntries.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", enrichedEntries);
        result.put("total", totalDistinct);
        result.put("filtered", totalDistinct);
        result.put("page", page);
        result.put("size", size);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 号码归属：单条新增 ====================

    @PostMapping("/ownership/entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> addOwnershipEntry(
            @Valid @RequestBody AddOwnershipEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        String phoneNumber = req.getPhoneNumber() != null ? req.getPhoneNumber() : "";
        if (phoneNumber.isBlank()) throw new IllegalArgumentException("号码不能为空");

        String l1Branch = req.getL1Branch() != null ? req.getL1Branch() : "";
        String l2Branch = req.getL2Branch() != null ? req.getL2Branch() : "";
        String fullPath = req.getFullPath() != null ? req.getFullPath() : "";
        String extension = req.getExtension() != null ? req.getExtension() : "";
        String description = req.getDescription() != null ? req.getDescription() : "";
        Byte status = req.getStatus() != null ? req.getStatus() : 0;

        // Find or create a MANUAL batch for single-entry additions
        String batchNo = "OWN-MANUAL";
        PhoneOwnershipBatch batch = ownershipBatchRepository.findByBatchNoAndDeletedAtIsNull(batchNo)
                .orElseGet(() -> {
                    PhoneOwnershipBatch b = new PhoneOwnershipBatch();
                    b.setBatchNo(batchNo);
                    b.setFileName("手动添加");
                    b.setTotalCount(0);
                    b.setExceptionCount(0);
                    b.setImportStatus((byte) 1);
                    b.setImportedBy(userId);
                    return ownershipBatchRepository.save(b);
                });

        PhoneOwnershipEntry entry = new PhoneOwnershipEntry();
        entry.setBatchId(batch.getId());
        entry.setPhoneNumber(phoneNumber);
        entry.setExtension(extension);
        entry.setFullPath(fullPath);
        entry.setDescription(description);
        entry.setL1Branch(l1Branch);
        entry.setL2Branch(l2Branch);
        entry.setStatus(status);
        entry.setIsException((byte) 0);
        entry.setMatchLevel("");
        entry = ownershipEntryRepository.save(entry);

        // Update batch counts
        long count = ownershipEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
        batch.setTotalCount((int) count);
        ownershipBatchRepository.save(batch);

        auditLogService.log(userId, "ADD_OWNERSHIP_ENTRY", "phone_ownership_entry", entry.getId(),
                Map.of("phone_number", phoneNumber));

        Map<String, Object> result = new HashMap<>();
        result.put("id", entry.getId());
        result.put("batch_id", entry.getBatchId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 号码归属：导出 ====================

    @GetMapping("/ownership/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportOwnershipEntries(
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {

        List<PhoneOwnershipEntry> entries;
        if (billingMonth != null && !billingMonth.isBlank()) {
            entries = ownershipEntryRepository.findAllByBillingMonth(billingMonth);
        } else {
            entries = ownershipEntryRepository.findAllNonExceptionEntriesForExport();
            billingMonth = ""; // no month filter for directory lookup
        }

        // Build directory_entry phone → extensions & phone → dept_paths maps (same billing month)
        Map<String, java.util.LinkedHashSet<String>> dirExtMap = new HashMap<>();
        Map<String, java.util.LinkedHashSet<String>> dirDeptMap = new HashMap<>();
        if (!billingMonth.isEmpty()) {
            List<Object[]> dirRows = directoryEntryRepository.findPhoneExtAndDeptByMonth(billingMonth);
            for (Object[] row : dirRows) {
                String phone = (String) row[0];
                String ext = (String) row[1];
                String dept = (String) row[2];
                if (phone != null && !phone.isEmpty()) {
                    if (ext != null && !ext.isEmpty()) {
                        dirExtMap.computeIfAbsent(phone, k -> new java.util.LinkedHashSet<>()).add(ext);
                    }
                    if (dept != null && !dept.isEmpty()) {
                        dirDeptMap.computeIfAbsent(phone, k -> new java.util.LinkedHashSet<>()).add(dept);
                    }
                }
            }
        }

        // Build allocDeptMap
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllActiveEntries();
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        // Build allocation_org_entry phone → {alloc_dept, org_code, cost_center} map (same billing month)
        Map<String, AllocationOrgEntry> allocOrgMap = new HashMap<>();
        if (!billingMonth.isEmpty()) {
            List<AllocationOrgEntry> allocOrgEntries = allocationOrgEntryRepository.findAllByBillingMonth(billingMonth);
            for (AllocationOrgEntry aoe : allocOrgEntries) {
                String p = aoe.getPhoneNumber();
                if (p != null && !p.isEmpty() && !allocOrgMap.containsKey(p)) {
                    allocOrgMap.put(p, aoe);
                }
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("号码归属");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "分机号", "一级分行", "分摊部门", "部门全路径", "机构代码", "成本中心", "例外"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (PhoneOwnershipEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                String phone = entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "";
                row.createCell(0).setCellValue(phone);

                // Extension: from directory_entry (same month) if available, else from entry
                java.util.LinkedHashSet<String> dirExts = dirExtMap.get(phone);
                String extValue = (dirExts != null && !dirExts.isEmpty()) ? String.join("、", dirExts)
                        : (entry.getExtension() != null ? entry.getExtension() : "");
                row.createCell(1).setCellValue(extValue);

                // l1_branch: prefer stored value, fallback to allocDeptMap match
                String l1 = entry.getL1Branch();
                if (l1 == null || l1.isEmpty()) {
                    String fp = entry.getFullPath();
                    String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
                    l1 = match != null ? match[0] : "";
                }
                row.createCell(2).setCellValue(l1 != null ? l1 : "");

                // alloc_dept, org_code, cost_center: prefer same-month allocation_org_entry, fallback to stored value in ownership entry
                AllocationOrgEntry allocOrgMatch = allocOrgMap.get(phone);
                String allocDept = (allocOrgMatch != null && allocOrgMatch.getAllocDept() != null && !allocOrgMatch.getAllocDept().isEmpty()) ? allocOrgMatch.getAllocDept()
                        : (entry.getAllocDept() != null ? entry.getAllocDept() : "");
                row.createCell(3).setCellValue(allocDept);

                // Full path: from directory_entry (same month) if available, else from entry
                java.util.LinkedHashSet<String> dirDepts = dirDeptMap.get(phone);
                String fpValue = (dirDepts != null && !dirDepts.isEmpty()) ? String.join("、", dirDepts)
                        : (entry.getFullPath() != null ? entry.getFullPath() : "");
                row.createCell(4).setCellValue(fpValue);

                String orgCodeExp = (allocOrgMatch != null && allocOrgMatch.getOrgCode() != null && !allocOrgMatch.getOrgCode().isEmpty()) ? allocOrgMatch.getOrgCode()
                        : (entry.getOrgCode() != null ? entry.getOrgCode() : "");
                String costCenterExp = (allocOrgMatch != null && allocOrgMatch.getCostCenter() != null && !allocOrgMatch.getCostCenter().isEmpty()) ? allocOrgMatch.getCostCenter()
                        : (entry.getCostCenter() != null ? entry.getCostCenter() : "");
                row.createCell(5).setCellValue(orgCodeExp);
                row.createCell(6).setCellValue(costCenterExp);
                row.createCell(7).setCellValue(entry.getIsException() != null && entry.getIsException() == 1 ? "是" : "否");
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("号码归属导出.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出号码归属失败: " + e.getMessage());
        }
    }

    // ==================== 号码归属分行：导出（与页面表格一致） ====================

    @GetMapping("/ownership/export-branch")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportBranchOwnershipEntries(
            @RequestAttribute("userId") Long userId) {
        // Deduplicated: one row per phone_number (latest record)
        List<Long> allIds = ownershipEntryRepository.findLatestEntryIdsPerPhoneNumber();
        List<PhoneOwnershipEntry> entries = allIds.isEmpty()
                ? List.of()
                : ownershipEntryRepository.findAllById(allIds);
        entries.sort((a, b) -> Long.compare(b.getId(), a.getId()));

        // Build allocDeptMap for l1_branch/l2_branch fallback matching
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllActiveEntries();
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("归属分行");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "一级分行", "二级分行", "状态", "更新时间"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (PhoneOwnershipEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");

                // l1_branch: prefer stored value, fallback to allocDeptMap match
                String l1 = entry.getL1Branch();
                if (l1 == null || l1.isEmpty()) {
                    String fp = entry.getFullPath();
                    String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
                    l1 = match != null ? match[0] : "";
                }
                row.createCell(1).setCellValue(l1 != null ? l1 : "");

                // l2_branch: prefer stored value, fallback to allocDeptMap match
                String l2 = entry.getL2Branch();
                if (l2 == null || l2.isEmpty()) {
                    String fp = entry.getFullPath();
                    String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
                    l2 = match != null ? match[1] : "";
                }
                row.createCell(2).setCellValue(l2 != null ? l2 : "");

                // status: 0=正常, 1=拆机
                String statusText = Byte.valueOf((byte)1).equals(entry.getStatus()) ? "拆机" : "正常";
                row.createCell(3).setCellValue(statusText);

                // updated_at: format as yyyy-MM-dd HH:mm
                String updatedAt = entry.getUpdatedAt() != null
                        ? entry.getUpdatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        : "";
                row.createCell(4).setCellValue(updatedAt);
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("归属分行导出.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出归属分行失败: " + e.getMessage());
        }
    }

    // ==================== 例外号码：全量查询（跨批次） ====================

    @GetMapping("/ownership/all-exceptions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAllExceptionEntries(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        org.springframework.data.domain.Page<PhoneOwnershipEntry> pageResult = hasSearch
                ? ownershipEntryRepository.searchAllExceptionEntries(keyword, pageable)
                : ownershipEntryRepository.findAllExceptionEntries(pageable);

        // Build allocDeptMap from all allocation_dept_entry records
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllActiveEntries();
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        List<Map<String, Object>> enrichedEntries = new ArrayList<>();
        for (PhoneOwnershipEntry entry : pageResult.getContent()) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber());
            e.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
            e.put("full_path", entry.getFullPath() != null ? entry.getFullPath() : "");
            e.put("description", entry.getDescription() != null ? entry.getDescription() : "");
            e.put("is_exception", entry.getIsException());
            e.put("match_level", entry.getMatchLevel() != null ? entry.getMatchLevel() : "");

            // Prefer stored l1_branch/l2_branch, fallback to allocation_dept_entry matching
            String storedBranch = entry.getL1Branch();
            String storedDept = entry.getL2Branch();
            boolean hasStoredBranch = storedBranch != null && !storedBranch.isBlank();
            boolean hasStoredDept = storedDept != null && !storedDept.isBlank();

            String fallbackBranch = "";
            String fallbackDept = "";
            if (!hasStoredBranch || !hasStoredDept) {
                String fp = entry.getFullPath();
                String[] match = (fp != null && !fp.isEmpty()) ? allocDeptMap.get(fp) : null;
                if (match != null) {
                    fallbackBranch = match[0];
                    fallbackDept = match[1];
                }
            }
            e.put("matched_branch", hasStoredBranch ? storedBranch : fallbackBranch);
            e.put("matched_dept", hasStoredDept ? storedDept : fallbackDept);
            e.put("exception_reason", entry.getDescription() != null ? entry.getDescription() : "");
            e.put("updated_at", entry.getUpdatedAt() != null ? entry.getUpdatedAt().toString() : "");

            enrichedEntries.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", enrichedEntries);
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 例外号码：全量导出 ====================

    @GetMapping("/ownership/exceptions/export-all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportAllExceptions() {
        List<PhoneOwnershipEntry> entries = ownershipEntryRepository.findAllExceptionEntriesForExport();

        // Build allocDeptMap
        Map<String, String[]> allocDeptMap = new HashMap<>();
        List<AllocationDeptEntry> allocEntries = allocationDeptEntryRepository.findAllActiveEntries();
        for (AllocationDeptEntry ae : allocEntries) {
            String fp = ae.getFullPath();
            if (fp != null && !fp.isEmpty() && !allocDeptMap.containsKey(fp)) {
                allocDeptMap.put(fp, new String[]{
                        ae.getBranch() != null ? ae.getBranch() : "",
                        ae.getDeptName() != null ? ae.getDeptName() : ""
                });
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外号码");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "分机号", "部门全路径", "分行", "部门", "例外原因"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (PhoneOwnershipEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
                row.createCell(1).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(2).setCellValue(e.getFullPath() != null ? e.getFullPath() : "");

                // Prefer stored l1_branch/l2_branch, fallback to allocation_dept_entry matching
                String storedBranch = e.getL1Branch();
                String storedDept = e.getL2Branch();
                boolean hasStoredBranch = storedBranch != null && !storedBranch.isBlank();
                boolean hasStoredDept = storedDept != null && !storedDept.isBlank();
                String[] bd = allocDeptMap.getOrDefault(e.getFullPath(), new String[]{"", ""});
                row.createCell(3).setCellValue(hasStoredBranch ? storedBranch : bd[0]);
                row.createCell(4).setCellValue(hasStoredDept ? storedDept : bd[1]);

                String reason = e.getDescription() != null ? e.getDescription() : "";
                row.createCell(5).setCellValue(reason);
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("例外号码导出.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出例外号码失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/ownership/batches/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteOwnershipBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        PhoneOwnershipBatch batch = ownershipBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("号码归属批次不存在或已被删除: " + id));

        ownershipEntryRepository.softDeleteByBatchId(id, java.time.LocalDateTime.now());

        batch.setDeletedAt(java.time.LocalDateTime.now());
        ownershipBatchRepository.save(batch);

        auditLogService.log(userId, "DELETE_OWNERSHIP_BATCH", "ownership_batch", id,
                Map.of("batch_no", batch.getBatchNo()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", id,
                "batch_no", batch.getBatchNo(),
                "deleted", true
        )));
    }

    // ==================== 通讯录导入 ====================

    @PostMapping("/directory")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importDirectory(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        try {
            DirectoryBatch batch = directoryImportService.importDirectory(file, userId, billingMonth);
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
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        List<DirectoryBatch> batches = billingMonth != null && !billingMonth.isBlank()
                ? directoryBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth)
                : directoryBatchRepository.findByDeletedAtIsNull();
        return ResponseEntity.ok(ApiResponse.ok(batches));
    }

    @GetMapping("/directory/months")
    public ResponseEntity<ApiResponse<List<String>>> listDirectoryMonths() {
        return ResponseEntity.ok(ApiResponse.ok(directoryBatchRepository.findDistinctMonths()));
    }

    @GetMapping("/directory/exception-months")
    public ResponseEntity<ApiResponse<List<String>>> listExceptionMonths() {
        return ResponseEntity.ok(ApiResponse.ok(directoryBatchRepository.findExceptionDistinctMonths()));
    }

    @GetMapping("/directory/template")
    public ResponseEntity<byte[]> downloadDirectoryTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("通讯录");

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Headers: 部门全路径, 用户名称, 分机号, 号码, 备注
            String[] headers = {"部门全路径", "用户名称", "分机号", "号码", "备注"};
            String[] examples = {"集团/北京分行/信息科技部", "张三", "8001", "01088881234", ""};
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

            String fileName = java.net.URLEncoder.encode("通讯录导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成通讯录模板失败: " + e.getMessage());
        }
    }

    // ==================== 成本中心模板 ====================

    @GetMapping("/directory/cost-center-template")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> downloadCostCenterTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("成本中心");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Columns match DepartmentOwnership page table: 一级分行, 部门路径, 分摊部门, 组织代码, 成本中心, 备注
            String[] headers = {"一级分行", "部门路径", "分摊部门", "组织代码", "成本中心", "备注"};
            String[] examples = {"广州分行", "100014-广州分行-100282-代管零售银行部", "代管零售银行部", "100282", "CC-100282", ""};
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

            String fileName = java.net.URLEncoder.encode("成本中心导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成成本中心模板失败: " + e.getMessage());
        }
    }

    @GetMapping("/directory/entries/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listDirectoryEntries(
            @PathVariable Long batchId,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        // M-08: Cap page size to prevent OOM with large datasets
        size = Math.min(size, 200);
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim().toLowerCase() : "";

        // Load all (soft-delete filtered) entries for the batch, then apply DataScope + keyword filter in memory
        List<DirectoryEntry> all = directoryEntryRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<Long> visibleIds = scope.isAllScope() ? null : scope.getVisibleOrgIds();
        List<DirectoryEntry> scoped = (visibleIds == null)
                ? all
                : all.stream().filter(e -> e.getOrgId() != null && visibleIds.contains(e.getOrgId())).toList();
        List<DirectoryEntry> filtered = hasSearch
                ? scoped.stream().filter(e -> {
                    String dp = e.getDeptPath() != null ? e.getDeptPath().toLowerCase() : "";
                    String un = e.getUsername() != null ? e.getUsername().toLowerCase() : "";
                    String ext = e.getExtension() != null ? e.getExtension().toLowerCase() : "";
                    String pn = e.getPhoneNumber() != null ? e.getPhoneNumber().toLowerCase() : "";
                    return dp.contains(keyword) || un.contains(keyword) || ext.contains(keyword) || pn.contains(keyword);
                }).toList()
                : scoped;

        int start = page * size;
        List<DirectoryEntry> pageEntries = (start < filtered.size())
                ? filtered.subList(start, Math.min(start + size, filtered.size()))
                : List.of();

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
        result.put("entries", pageEntries);
        result.put("total", (long) filtered.size());
        result.put("filtered", (long) filtered.size());
        result.put("page", page);
        result.put("size", size);
        result.put("codeToNameMap", codeToNameMap);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/directory/batches/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteDirectoryBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        DirectoryBatch batch = directoryBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("通讯录批次不存在或已被删除: " + id));

        directoryEntryRepository.softDeleteByBatchId(id, java.time.LocalDateTime.now());

        batch.setDeletedAt(java.time.LocalDateTime.now());
        directoryBatchRepository.save(batch);

        auditLogService.log(userId, "DELETE_DIRECTORY_BATCH", "directory_batch", id,
                Map.of("batch_no", batch.getBatchNo()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", id,
                "batch_no", batch.getBatchNo(),
                "deleted", true
        )));
    }

    @DeleteMapping("/directory/batches/month/{billingMonth}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteDirectoryBatchesByMonth(
            @PathVariable String billingMonth,
            @RequestAttribute("userId") Long userId) {
        List<DirectoryBatch> batches = directoryBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth);
        int count = batches.size();
        for (DirectoryBatch batch : batches) {
            directoryEntryRepository.softDeleteByBatchId(batch.getId(), java.time.LocalDateTime.now());
            batch.setDeletedAt(java.time.LocalDateTime.now());
            directoryBatchRepository.save(batch);
        }
        auditLogService.log(userId, "DELETE_DIRECTORY_BATCHES_BY_MONTH", "directory_batch", null,
                Map.of("billing_month", billingMonth, "deleted_count", count));
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "billing_month", billingMonth,
                "deleted_count", count,
                "deleted", true
        )));
    }

    // ==================== 跨月通讯录对比 ====================

    // 全量构建跨月对比结果 (抽为私有方法, 供分页接口与归档快照复用, 避免重复逻辑)
    private Map<String, Object> buildDirectoryCompareFull(String month1, String month2) {
        if (month1.equals(month2)) {
            throw new IllegalArgumentException("两个月份不能相同");
        }

        List<DirectoryEntry> entries1 = directoryEntryRepository.findByBillingMonth(month1);
        List<DirectoryEntry> entries2 = directoryEntryRepository.findByBillingMonth(month2);

        // Key = extension (分机号, 唯一主键). 一个号码可对应多个分机号, 每个分机号唯一标识一条记录
        // For each extension, compare dept_path / username / extension across months
        java.util.function.Function<DirectoryEntry, String> extKeyFn = e ->
                e.getExtension() != null ? e.getExtension() : "";

        // Build phone -> latest entry map (by id desc) for each month
        Map<String, DirectoryEntry> map1 = new java.util.LinkedHashMap<>();
        for (DirectoryEntry e : entries1) {
            String key = extKeyFn.apply(e);
            if (!key.isEmpty()) {
                map1.putIfAbsent(key, e); // first seen = latest due to query order
            }
        }

        Map<String, DirectoryEntry> map2 = new java.util.LinkedHashMap<>();
        for (DirectoryEntry e : entries2) {
            String key = extKeyFn.apply(e);
            if (!key.isEmpty()) {
                map2.putIfAbsent(key, e);
            }
        }

        List<Map<String, Object>> diffs = new ArrayList<>();
        int added = 0, removed = 0, changed = 0, unchanged = 0;

        // Entries only in month2 (added — 新增的分机号)
        for (Map.Entry<String, DirectoryEntry> e2 : map2.entrySet()) {
            if (!map1.containsKey(e2.getKey())) {
                DirectoryEntry entry = e2.getValue();
                Map<String, Object> d = new HashMap<>();
                d.put("type", "added");
                d.put("username", entry.getUsername() != null ? entry.getUsername() : "");
                d.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                d.put("dept_path", entry.getDeptPath() != null ? entry.getDeptPath() : "");
                d.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
                d.put("month1_username", "");
                d.put("month1_phone_number", "");
                d.put("month1_dept_path", "");
                d.put("changed_columns", List.of("用户名称", "号码", "部门全路径"));
                diffs.add(d);
                added++;
            }
        }

        // Entries only in month1 (removed — 参考月份有但待核对月份没有)
        for (Map.Entry<String, DirectoryEntry> e1 : map1.entrySet()) {
            if (!map2.containsKey(e1.getKey())) {
                DirectoryEntry entry = e1.getValue();
                Map<String, Object> d = new HashMap<>();
                d.put("type", "removed");
                d.put("username", entry.getUsername() != null ? entry.getUsername() : "");
                d.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                d.put("dept_path", entry.getDeptPath() != null ? entry.getDeptPath() : "");
                d.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
                d.put("month1_username", entry.getUsername() != null ? entry.getUsername() : "");
                d.put("month1_phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                d.put("month1_dept_path", entry.getDeptPath() != null ? entry.getDeptPath() : "");
                d.put("changed_columns", List.of("用户名称", "号码", "部门全路径"));
                diffs.add(d);
                removed++;
            }
        }

        // Entries in both months — compare username, phone_number, dept_path (by extension key)
        for (Map.Entry<String, DirectoryEntry> e1 : map1.entrySet()) {
            DirectoryEntry entry1 = e1.getValue();
            DirectoryEntry entry2 = map2.get(e1.getKey());
            if (entry2 == null) continue;

            String un1 = entry1.getUsername() != null ? entry1.getUsername() : "";
            String un2 = entry2.getUsername() != null ? entry2.getUsername() : "";
            String pn1 = entry1.getPhoneNumber() != null ? entry1.getPhoneNumber() : "";
            String pn2 = entry2.getPhoneNumber() != null ? entry2.getPhoneNumber() : "";
            String dp1 = entry1.getDeptPath() != null ? entry1.getDeptPath() : "";
            String dp2 = entry2.getDeptPath() != null ? entry2.getDeptPath() : "";
            String ex1 = entry1.getExtension() != null ? entry1.getExtension() : "";
            String ex2 = entry2.getExtension() != null ? entry2.getExtension() : "";

            boolean userChanged = !un1.equals(un2);
            boolean phoneChanged = !pn1.equals(pn2);
            boolean deptChanged = !dp1.equals(dp2);

            List<String> changedCols = new ArrayList<>();
            if (userChanged) changedCols.add("用户名称");
            if (phoneChanged) changedCols.add("号码");
            if (deptChanged) changedCols.add("部门全路径");
            // 分机号(extension)作为主键, 存在则必然一致, 不参与对比列

            if (!changedCols.isEmpty()) {
                Map<String, Object> d = new HashMap<>();
                d.put("type", "changed");
                d.put("username", un2);
                d.put("phone_number", pn2);
                d.put("dept_path", dp2);
                d.put("extension", ex2);
                d.put("month1_username", un1);
                d.put("month1_phone_number", pn1);
                d.put("month1_dept_path", dp1);
                d.put("changed_columns", changedCols);
                diffs.add(d);
                changed++;
            } else {
                unchanged++;
            }
        }

        // Sort: changed first, then added, then removed
        Map<String, Integer> typeOrder = Map.of("changed", 0, "added", 1, "removed", 2);
        diffs.sort((a, b) -> Integer.compare(
                typeOrder.getOrDefault(a.get("type"), 99),
                typeOrder.getOrDefault(b.get("type"), 99)));

        Map<String, Object> result = new HashMap<>();
        result.put("diffs", diffs);
        result.put("month1", month1);
        result.put("month2", month2);
        result.put("month1_count", entries1.size());
        result.put("month2_count", entries2.size());
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        result.put("unchanged", unchanged);
        result.put("total", diffs.size());
        return result;
    }

    @GetMapping("/directory/compare")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compareDirectory(
            @RequestParam("month1") String month1,
            @RequestParam("month2") String month2,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "type", required = false) String type,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        Map<String, Object> full = buildDirectoryCompareFull(month1, month2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) full.get("diffs");

        // 按类型过滤
        boolean hasType = type != null && !type.isBlank();
        if (hasType) {
            String typeVal = type.trim();
            diffs = diffs.stream().filter(d -> typeVal.equals(String.valueOf(d.get("type")))).toList();
            full.put("diffs", diffs);
        }

        // 搜索过滤（在归档保存和分页之前）
        boolean hasSearch = search != null && !search.isBlank();
        if (hasSearch) {
            String kw = search.trim().toLowerCase();
            diffs = diffs.stream().filter(d -> {
                String username = String.valueOf(d.getOrDefault("username", "")).toLowerCase();
                String phone = String.valueOf(d.getOrDefault("phone_number", "")).toLowerCase();
                String ext = String.valueOf(d.getOrDefault("extension", "")).toLowerCase();
                String dept = String.valueOf(d.getOrDefault("dept_path", "")).toLowerCase();
                return username.contains(kw) || phone.contains(kw) || ext.contains(kw) || dept.contains(kw);
            }).toList();
            full.put("diffs", diffs);
        }

        boolean hasFilter = hasSearch || hasType;
        int total = diffs.size();
        full.put("total", total);

        // 自动保存归档：首页请求（page=0 或 null）且无搜索/类型过滤时保存全量快照
        if ((page == null || page == 0) && !hasFilter) {
            try {
                ComparisonArchive archive = new ComparisonArchive();
                archive.setCompareType("month");
                archive.setMonth1(month1);
                archive.setMonth2(month2);
                archive.setAddedCount((Integer) full.get("added"));
                archive.setRemovedCount((Integer) full.get("removed"));
                archive.setChangedCount((Integer) full.get("changed"));
                archive.setUnchangedCount((Integer) full.get("unchanged"));
                archive.setTotalCount(total);
                archive.setArchivedBy(userId);
                archive.setResultJson(JSON_MAPPER.writeValueAsString(full));
                comparisonArchiveRepository.save(archive);
            } catch (Exception ignore) {
                // 归档保存失败不影响对比结果返回
            }
        }

        // 分页截断（在归档保存之后）
        if (page != null && size != null && size > 0) {
            int from = Math.min(page * size, total);
            int to = Math.min(from + size, total);
            full.put("diffs", diffs.subList(from, to));
            full.put("page", page);
            full.put("size", size);
            full.put("total_pages", (total + size - 1) / size);
        }

        return ResponseEntity.ok(ApiResponse.ok(full));
    }

    @GetMapping("/directory/compare/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportDirectoryComparison(
            @RequestParam("month1") String month1,
            @RequestParam("month2") String month2,
            @RequestParam(value = "types", required = false) String types) {
        // Reuse the same comparison logic
        Map<String, Object> data = buildDirectoryCompareFull(month1, month2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allDiffs = (List<Map<String, Object>>) data.get("diffs");

        // Filter by types if specified (comma-separated: added,removed,changed)
        List<Map<String, Object>> diffs;
        if (types != null && !types.isBlank()) {
            java.util.Set<String> typeSet = java.util.Arrays.stream(types.split(","))
                    .map(String::trim).collect(java.util.stream.Collectors.toSet());
            diffs = allDiffs.stream()
                    .filter(d -> typeSet.contains(String.valueOf(d.get("type"))))
                    .toList();
        } else {
            diffs = allDiffs;
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("通讯录对比");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"变更类型", "用户名称", "号码", "部门全路径", "分机号", "参考用户名称", "参考号码", "参考部门全路径", "差异列"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            // Map type to Chinese
            Map<String, String> typeLabels = Map.of("added", "新增", "removed", "删除", "changed", "变更");

            int rowIdx = 1;
            for (Map<String, Object> d : diffs) {
                Row row = sheet.createRow(rowIdx++);
                String type = (String) d.get("type");
                row.createCell(0).setCellValue(typeLabels.getOrDefault(type, type));
                row.createCell(1).setCellValue((String) d.get("username"));
                row.createCell(2).setCellValue((String) d.get("phone_number"));
                row.createCell(3).setCellValue((String) d.get("dept_path"));
                row.createCell(4).setCellValue((String) d.get("extension"));
                row.createCell(5).setCellValue((String) d.get("month1_username"));
                row.createCell(6).setCellValue((String) d.get("month1_phone_number"));
                row.createCell(7).setCellValue((String) d.get("month1_dept_path"));
                @SuppressWarnings("unchecked")
                List<String> changedCols = (List<String>) d.get("changed_columns");
                String changedColsStr = changedCols != null
                        ? String.join(",", changedCols)
                        : "";
                row.createCell(8).setCellValue(changedColsStr);
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode(
                    "通讯录对比_" + month1 + "_vs_" + month2 + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出通讯录对比失败: " + e.getMessage());
        }
    }

    // ==================== 通讯录：当前数据（最新月份） ====================

    @GetMapping("/directory/current-entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCurrentDirectoryEntries(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        // Find latest month
        List<String> months = directoryBatchRepository.findDistinctMonths();
        if (months.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("entries", List.of());
            empty.put("total", 0);
            empty.put("page", page);
            empty.put("size", size);
            empty.put("billing_month", "");
            return ResponseEntity.ok(ApiResponse.ok(empty));
        }

        String latestMonth = months.get(0);
        List<DirectoryEntry> allEntries = directoryEntryRepository.findByBillingMonth(latestMonth);

        // Search filter
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim().toLowerCase() : "";
        List<DirectoryEntry> filtered = hasSearch
                ? allEntries.stream().filter(e -> {
                    String dp = e.getDeptPath() != null ? e.getDeptPath().toLowerCase() : "";
                    String un = e.getUsername() != null ? e.getUsername().toLowerCase() : "";
                    String ext = e.getExtension() != null ? e.getExtension().toLowerCase() : "";
                    String pn = e.getPhoneNumber() != null ? e.getPhoneNumber().toLowerCase() : "";
                    return dp.contains(keyword) || un.contains(keyword) || ext.contains(keyword) || pn.contains(keyword);
                }).toList()
                : allEntries;

        long total = filtered.size();
        int start = page * size;
        List<DirectoryEntry> pageEntries = (start < filtered.size())
                ? filtered.subList(start, Math.min(start + size, filtered.size()))
                : List.of();

        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageEntries);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("billing_month", latestMonth);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 通讯录：例外数据 ====================

    @GetMapping("/directory/exception-entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listDirectoryExceptionEntries(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        org.springframework.data.domain.Page<DirectoryEntry> pageResult;
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        if (hasSearch) {
            pageResult = directoryEntryRepository.searchExceptionEntries(keyword, pageable);
        } else {
            pageResult = directoryEntryRepository.findExceptionEntries(pageable);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("page", pageResult.getNumber());
        result.put("size", pageResult.getSize());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 例外数据与最新通讯录对比 ====================

    // 全量构建例外与最新通讯录对比结果 (抽为私有方法, 供分页接口与归档快照复用)
    // onlyDiff=true 时仅返回有差异的记录 (配合分页, BUG-4)
    private Map<String, Object> buildExceptionCompareFull(boolean onlyDiff) {
        return buildExceptionCompareFull(onlyDiff, null);
    }

    private Map<String, Object> buildExceptionCompareFull(boolean onlyDiff, String month) {
        // Use specified month, or find latest month
        String compareMonth;
        if (month != null && !month.isBlank()) {
            compareMonth = month;
        } else {
            // Find latest month
            List<String> months = directoryBatchRepository.findDistinctMonths();
            if (months.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("entries", List.of());
                empty.put("total", 0);
                empty.put("total_all", 0);
                empty.put("changed", 0);
                empty.put("unchanged", 0);
                empty.put("billing_month", "");
                return empty;
            }
            compareMonth = months.get(0);
        }

        // Get latest directory entries by extension (分机号, 唯一主键, dedup: keep latest by id)
        List<DirectoryEntry> latestEntries = directoryEntryRepository.findByBillingMonth(compareMonth);
        Map<String, DirectoryEntry> latestMap = new java.util.LinkedHashMap<>();
        for (DirectoryEntry e : latestEntries) {
            String key = e.getExtension() != null ? e.getExtension() : "";
            if (!key.isEmpty()) {
                latestMap.putIfAbsent(key, e);
            }
        }

        // Get all exception entries (is_seconded=1)
        List<DirectoryEntry> exceptionEntries = directoryEntryRepository.findExceptionEntriesAll();

        List<Map<String, Object>> results = new ArrayList<>();
        int changed = 0, unchanged = 0;

        for (DirectoryEntry exc : exceptionEntries) {
            String ext = exc.getExtension() != null ? exc.getExtension() : "";
            Map<String, Object> item = new HashMap<>();
            item.put("id", exc.getId());
            item.put("phone_number", exc.getPhoneNumber() != null ? exc.getPhoneNumber() : "");
            item.put("dept_path", exc.getDeptPath() != null ? exc.getDeptPath() : "");
            item.put("username", exc.getUsername() != null ? exc.getUsername() : "");
            item.put("extension", exc.getExtension() != null ? exc.getExtension() : "");
            item.put("seconded_keyword", exc.getSecondedKeyword() != null ? exc.getSecondedKeyword() : "");
            item.put("billing_month", "");

            DirectoryEntry latest = latestMap.get(ext);
            List<String> changedCols = new ArrayList<>();

            if (latest != null) {
                String excDp = exc.getDeptPath() != null ? exc.getDeptPath() : "";
                String latDp = latest.getDeptPath() != null ? latest.getDeptPath() : "";
                String excUn = exc.getUsername() != null ? exc.getUsername() : "";
                String latUn = latest.getUsername() != null ? latest.getUsername() : "";
                String excPn = exc.getPhoneNumber() != null ? exc.getPhoneNumber() : "";
                String latPn = latest.getPhoneNumber() != null ? latest.getPhoneNumber() : "";

                if (!excUn.equals(latUn)) changedCols.add("用户名称");
                if (!excPn.equals(latPn)) changedCols.add("号码");
                if (!excDp.equals(latDp)) changedCols.add("部门全路径");
                // 分机号(extension)作为唯一主键, 与最新记录必然一致, 不参与对比列

                item.put("latest_dept_path", latDp);
                item.put("latest_username", latUn);
                item.put("latest_phone_number", latPn);
                item.put("latest_extension", exc.getExtension() != null ? exc.getExtension() : "");
            } else {
                // BUG-1: 例外分机号在最新通讯录中不存在 -> 标记为差异并提示
                changedCols.add("最新通讯录未找到");
                item.put("latest_dept_path", "");
                item.put("latest_username", "");
                item.put("latest_phone_number", "");
                item.put("latest_extension", "");
            }

            item.put("changed_columns", changedCols);
            item.put("has_diff", !changedCols.isEmpty());

            if (!changedCols.isEmpty()) {
                changed++;
            } else {
                unchanged++;
            }

            results.add(item);
        }

        // Sort: entries with differences first
        results.sort((a, b) -> {
            boolean aDiff = (boolean) a.get("has_diff");
            boolean bDiff = (boolean) b.get("has_diff");
            return Boolean.compare(bDiff, aDiff);
        });

        int totalAll = results.size();
        List<Map<String, Object>> entries = onlyDiff
                ? results.stream().filter(r -> (boolean) r.get("has_diff")).collect(java.util.stream.Collectors.toList())
                : results;
        Map<String, Object> result = new HashMap<>();
        result.put("entries", entries);
        result.put("total", entries.size());
        result.put("total_all", totalAll);
        result.put("changed", changed);
        result.put("unchanged", unchanged);
        result.put("billing_month", compareMonth);
        return result;
    }

    @GetMapping("/directory/exception-compare")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compareExceptionWithLatest(
            @RequestParam(value = "only_diff", required = false) Boolean onlyDiff,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "search", required = false) String search,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        Map<String, Object> full = buildExceptionCompareFull(onlyDiff != null && onlyDiff, month);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) full.get("entries");

        // 搜索过滤（在归档保存和分页之前）
        boolean hasSearch = search != null && !search.isBlank();
        if (hasSearch) {
            String kw = search.trim().toLowerCase();
            entries = entries.stream().filter(d -> {
                String username = String.valueOf(d.getOrDefault("username", "")).toLowerCase();
                String phone = String.valueOf(d.getOrDefault("phone_number", "")).toLowerCase();
                String ext = String.valueOf(d.getOrDefault("extension", "")).toLowerCase();
                String dept = String.valueOf(d.getOrDefault("dept_path", "")).toLowerCase();
                return username.contains(kw) || phone.contains(kw) || ext.contains(kw) || dept.contains(kw);
            }).toList();
            full.put("entries", entries);
        }

        int total = entries.size();
        full.put("total", total);

        // 自动保存归档：首页请求时保存全量快照（必须在分页截断之前）
        if ((page == null || page == 0) && !hasSearch) {
            try {
                ComparisonArchive archive = new ComparisonArchive();
                archive.setCompareType("exception");
                archive.setLatestMonth((String) full.get("billing_month"));
                archive.setChangedCount((Integer) full.get("changed"));
                archive.setUnchangedCount((Integer) full.get("unchanged"));
                archive.setTotalCount((Integer) full.get("total_all"));
                archive.setArchivedBy(userId);
                archive.setResultJson(JSON_MAPPER.writeValueAsString(full));
                comparisonArchiveRepository.save(archive);
            } catch (Exception ignore) {
            }
        }

        // 分页截断（在归档保存之后）
        if (page != null && size != null && size > 0) {
            int from = Math.min(page * size, total);
            int to = Math.min(from + size, total);
            full.put("entries", entries.subList(from, to));
            full.put("page", page);
            full.put("size", size);
            full.put("total_pages", (total + size - 1) / size);
        }

        return ResponseEntity.ok(ApiResponse.ok(full));
    }

    // ==================== 例外数据差异导出 ====================

    @GetMapping("/directory/exception-compare-export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportExceptionCompare(
            @RequestParam(value = "only_diff", required = false) Boolean onlyDiff,
            @RequestParam(value = "month", required = false) String month) {
        Map<String, Object> full = buildExceptionCompareFull(onlyDiff != null && onlyDiff, month);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) full.get("entries");

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外数据差异");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"分机号", "用户名称", "最新用户名称", "号码", "最新号码", "部门全路径", "最新部门全路径", "例外关键词", "差异列", "是否有差异"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (Map<String, Object> e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getOrDefault("extension", "").toString());
                row.createCell(1).setCellValue(e.getOrDefault("username", "").toString());
                row.createCell(2).setCellValue(e.getOrDefault("latest_username", "").toString());
                row.createCell(3).setCellValue(e.getOrDefault("phone_number", "").toString());
                row.createCell(4).setCellValue(e.getOrDefault("latest_phone_number", "").toString());
                row.createCell(5).setCellValue(e.getOrDefault("dept_path", "").toString());
                row.createCell(6).setCellValue(e.getOrDefault("latest_dept_path", "").toString());
                row.createCell(7).setCellValue(e.getOrDefault("seconded_keyword", "").toString());
                row.createCell(8).setCellValue(String.join(",", (List<String>) e.getOrDefault("changed_columns", List.of())));
                row.createCell(9).setCellValue(Boolean.TRUE.equals(e.get("has_diff")) ? "是" : "否");
            }

            wb.write(out);
            String billingMonth = full.getOrDefault("billing_month", "").toString();
            String fileName = java.net.URLEncoder.encode(
                    "例外数据差异_" + billingMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出例外数据差异失败: " + e.getMessage());
        }
    }

    // ==================== 彽档：创建归彉 ====================

    @PostMapping("/directory/comparison-archive")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createComparisonArchive(
            @RequestBody Map<String, Object> body) {
        String compareType = (String) body.getOrDefault("compare_type", "exception");

        ComparisonArchive archive = new ComparisonArchive();
        archive.setCompareType(compareType);

        // BUG-3: 后端重算全量对比结果并存储快照, 查看归档时直接读取快照避免实时重算导致数据漂移

        if ("month".equals(compareType)) {
            String m1 = (String) body.get("month1");
            String m2 = (String) body.get("month2");
            archive.setMonth1(m1);
            archive.setMonth2(m2);
            Map<String, Object> snap = buildDirectoryCompareFull(m1, m2);
            archive.setAddedCount((Integer) snap.get("added"));
            archive.setRemovedCount((Integer) snap.get("removed"));
            archive.setChangedCount((Integer) snap.get("changed"));
            archive.setUnchangedCount((Integer) snap.get("unchanged"));
            archive.setTotalCount((Integer) snap.get("total"));
            try { archive.setResultJson(JSON_MAPPER.writeValueAsString(snap)); } catch (Exception ignore) {}
        } else {
            archive.setLatestMonth((String) body.get("latest_month"));
            Map<String, Object> snap = buildExceptionCompareFull(false);
            archive.setChangedCount((Integer) snap.get("changed"));
            archive.setUnchangedCount((Integer) snap.get("unchanged"));
            archive.setTotalCount((Integer) snap.get("total"));
            try { archive.setResultJson(JSON_MAPPER.writeValueAsString(snap)); } catch (Exception ignore) {}
        }

        archive.setRemark((String) body.get("remark"));

        comparisonArchiveRepository.save(archive);

        Map<String, Object> result = new HashMap<>();
        result.put("id", archive.getId());
        result.put("compare_type", archive.getCompareType());
        result.put("created", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 归档：列表 ====================

    @GetMapping("/directory/comparison-archives")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listComparisonArchives() {
        List<ComparisonArchive> archives = comparisonArchiveRepository.findAllArchives();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ComparisonArchive a : archives) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", a.getId());
            item.put("compare_type", a.getCompareType());
            item.put("month1", a.getMonth1() != null ? a.getMonth1() : "");
            item.put("month2", a.getMonth2() != null ? a.getMonth2() : "");
            item.put("latest_month", a.getLatestMonth() != null ? a.getLatestMonth() : "");
            item.put("added", a.getAddedCount());
            item.put("removed", a.getRemovedCount());
            item.put("changed", a.getChangedCount());
            item.put("unchanged", a.getUnchangedCount());
            item.put("total", a.getTotalCount());
            item.put("remark", a.getRemark() != null ? a.getRemark() : "");
            item.put("created_at", a.getCreatedAt() != null ? a.getCreatedAt().toString() : "");
            item.put("result_json", a.getResultJson() != null ? a.getResultJson() : "");
            result.add(item);
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 归档：最后一次对比结果 ====================

    @GetMapping("/directory/comparison-archive/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestComparisonArchive() {
        Map<String, Object> result = new HashMap<>();

        // 通讯录差异最新归档
        Optional<ComparisonArchive> monthOpt = comparisonArchiveRepository.findLatestByType("month");
        if (monthOpt.isPresent()) {
            ComparisonArchive a = monthOpt.get();
            Map<String, Object> item = new HashMap<>();
            item.put("id", a.getId());
            item.put("compare_type", a.getCompareType());
            item.put("month1", a.getMonth1() != null ? a.getMonth1() : "");
            item.put("month2", a.getMonth2() != null ? a.getMonth2() : "");
            item.put("added", a.getAddedCount());
            item.put("removed", a.getRemovedCount());
            item.put("changed", a.getChangedCount());
            item.put("unchanged", a.getUnchangedCount());
            item.put("total", a.getTotalCount());
            item.put("remark", a.getRemark() != null ? a.getRemark() : "");
            item.put("created_at", a.getCreatedAt() != null ? a.getCreatedAt().toString() : "");
            item.put("result_json", a.getResultJson() != null ? a.getResultJson() : "");
            result.put("month_archive", item);
        } else {
            result.put("month_archive", null);
        }

        // 例外数据差异最新归档
        Optional<ComparisonArchive> excOpt = comparisonArchiveRepository.findLatestByType("exception");
        if (excOpt.isPresent()) {
            ComparisonArchive a = excOpt.get();
            Map<String, Object> item = new HashMap<>();
            item.put("id", a.getId());
            item.put("compare_type", a.getCompareType());
            item.put("latest_month", a.getLatestMonth() != null ? a.getLatestMonth() : "");
            item.put("changed", a.getChangedCount());
            item.put("unchanged", a.getUnchangedCount());
            item.put("total", a.getTotalCount());
            item.put("remark", a.getRemark() != null ? a.getRemark() : "");
            item.put("created_at", a.getCreatedAt() != null ? a.getCreatedAt().toString() : "");
            item.put("result_json", a.getResultJson() != null ? a.getResultJson() : "");
            result.put("exception_archive", item);
        } else {
            result.put("exception_archive", null);
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 当前数据导出 ====================

    @GetMapping("/directory/current-entries/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportCurrentDirectoryEntries() {
        List<String> months = directoryBatchRepository.findDistinctMonths();
        if (months.isEmpty()) {
            throw new IllegalStateException("无通讯录数据");
        }
        String latestMonth = months.get(0);
        List<DirectoryEntry> entries = directoryEntryRepository.findByBillingMonth(latestMonth);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("当前通讯录_" + latestMonth);

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "用户名称", "分机号", "号码"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (DirectoryEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getDeptPath() != null ? e.getDeptPath() : "");
                row.createCell(1).setCellValue(e.getUsername() != null ? e.getUsername() : "");
                row.createCell(2).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(3).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
            }

            wb.write(out);
            String fileName = java.net.URLEncoder.encode(
                    "当前通讯录_" + latestMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出当前数据失败: " + e.getMessage());
        }
    }

    // ==================== 按月份导出通讯录 ====================

    @GetMapping("/directory/month-entries/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportDirectoryByMonth(@RequestParam("billing_month") String billingMonth) {
        List<DirectoryEntry> entries = directoryEntryRepository.findByBillingMonth(billingMonth);
        if (entries.isEmpty()) {
            throw new IllegalStateException("该月份无通讯录数据: " + billingMonth);
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("通讯录_" + billingMonth);

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "用户名称", "分机号", "号码"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (DirectoryEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getDeptPath() != null ? e.getDeptPath() : "");
                row.createCell(1).setCellValue(e.getUsername() != null ? e.getUsername() : "");
                row.createCell(2).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(3).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
            }

            wb.write(out);
            String fileName = java.net.URLEncoder.encode(
                    "通讯录_" + billingMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出通讯录失败: " + e.getMessage());
        }
    }

    // ==================== 例外数据导出 ====================

    @GetMapping("/directory/exception-entries/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportExceptionDirectoryEntries() {
        List<DirectoryEntry> entries = directoryEntryRepository.findExceptionEntriesAll();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外数据");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "用户名称", "分机号", "号码", "例外关键词"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (DirectoryEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getDeptPath() != null ? e.getDeptPath() : "");
                row.createCell(1).setCellValue(e.getUsername() != null ? e.getUsername() : "");
                row.createCell(2).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(3).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
                row.createCell(4).setCellValue(e.getSecondedKeyword() != null ? e.getSecondedKeyword() : "");
            }

            wb.write(out);
            String fileName = java.net.URLEncoder.encode("例外数据.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出例外数据失败: " + e.getMessage());
        }
    }

    // ==================== 例外数据按月份导出 ====================

    @GetMapping("/directory/exception-entries/month-export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportExceptionEntriesByMonth(@RequestParam("billing_month") String billingMonth) {
        List<DirectoryEntry> entries = directoryEntryRepository.findExceptionEntriesByMonth(billingMonth);
        if (entries.isEmpty()) {
            throw new IllegalStateException("该月份无例外数据: " + billingMonth);
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外数据_" + billingMonth);

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "用户名称", "分机号", "号码", "例外关键词"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (DirectoryEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getDeptPath() != null ? e.getDeptPath() : "");
                row.createCell(1).setCellValue(e.getUsername() != null ? e.getUsername() : "");
                row.createCell(2).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(3).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
                row.createCell(4).setCellValue(e.getSecondedKeyword() != null ? e.getSecondedKeyword() : "");
            }

            wb.write(out);
            String fileName = java.net.URLEncoder.encode(
                    "例外数据_" + billingMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出例外数据失败: " + e.getMessage());
        }
    }

    // ==================== 例外数据导入模板 ====================

    @GetMapping("/directory/exception-template")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> downloadDirectoryExceptionTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("例外数据");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "用户名称", "分机号", "号码", "例外关键词"};
            String[] examples = {"集团/北京分行/信息科技部", "张三", "8001", "01088881234", "借调"};
            Row headerRow = sheet.createRow(0);
            Row exampleRow = sheet.createRow(1);

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 5000);

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

            String fileName = java.net.URLEncoder.encode("例外数据导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成例外数据模板失败: " + e.getMessage());
        }
    }

    // ==================== 例外数据导入 ====================

    @PostMapping("/directory/exception-import")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> importExceptionEntries(
            @RequestParam("file") MultipartFile file,
            @RequestParam("billing_month") String billingMonth,
            @RequestAttribute("userId") Long userId) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        // Resolve or create batch BEFORE the loop (avoid N+1 batch lookups)
        Long batchId = resolveExceptionImportBatchId(billingMonth, file.getOriginalFilename(), userId);

        int imported = 0;
        int skipped = 0;
        List<DirectoryEntry> pending = new ArrayList<>();

        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String phoneNumber = getCellString(row, 3);
                if (phoneNumber == null || phoneNumber.isBlank()) {
                    skipped++;
                    continue;
                }

                DirectoryEntry entry = new DirectoryEntry();
                entry.setBatchId(batchId);
                entry.setDeptPath(getCellString(row, 0));
                entry.setUsername(getCellString(row, 1));
                entry.setExtension(getCellString(row, 2));
                entry.setPhoneNumber(phoneNumber);
                entry.setSecondedKeyword(getCellString(row, 4));
                entry.setIsSeconded((byte) 1);

                pending.add(entry);
                if (pending.size() >= 500) {
                    directoryEntryRepository.saveAll(pending);
                    pending.clear();
                }
                imported++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("导入例外数据失败: " + e.getMessage());
        }
        if (!pending.isEmpty()) {
            directoryEntryRepository.saveAll(pending);
            pending.clear();
        }

        // 回写批次记录数（BUG修复：例外导入后 totalCount 始终为 0）
        if (batchId != null && imported > 0) {
            final int totalImported = imported + skipped;
            directoryBatchRepository.findById(batchId).ifPresent(b -> {
                b.setTotalCount(totalImported);
                directoryBatchRepository.save(b);
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Resolve batch ID for exception import: reuse existing batch for the billing month,
     * or create a new one if none exists.
     */
    private Long resolveExceptionImportBatchId(String billingMonth, String fileName, Long userId) {
        // 例外数据使用独立批次, 不复用普通通讯录批次, 避免批次语义混淆 (BUG-5)
        DirectoryBatch batch = new DirectoryBatch();
        batch.setBatchNo("EXC-" + billingMonth.replace("-", "") + "-" + System.currentTimeMillis());
        batch.setFileName(fileName != null ? fileName : "例外数据导入");
        batch.setBillingMonth(billingMonth);
        batch.setTotalCount(0);
        batch.setSecondedCount(0);
        batch.setImportStatus((byte) 1);
        batch.setImportedBy(userId != null ? userId : 1L);
        directoryBatchRepository.save(batch);
        return batch.getId();
    }

    private String getCellString(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
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
                new String[]{"分机号", "号码", "录音目录", "费用小计"},
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
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        // M-08: Cap page size to prevent OOM
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        // Check if keyword search is needed
        boolean hasKeyword = keyword != null && !keyword.isBlank();

        org.springframework.data.domain.Page<BillDetail> paged;
        if (hasKeyword) {
            // Search mode: filter by keyword (phone_number OR extension)
            String kw = keyword.trim();
            if (sheetType != null && !sheetType.isBlank()) {
                if (scope.isAllScope()) {
                    paged = billDetailRepository.searchByBatchIdAndSheetTypeAndKeyword(batchId, sheetType, kw, pageable);
                } else {
                    var visibleIds = scope.getVisibleOrgIds();
                    if (visibleIds != null && !visibleIds.isEmpty()) {
                        paged = billDetailRepository.searchByBatchIdAndSheetTypeAndOrgIdsAndKeyword(batchId, sheetType, visibleIds, kw, pageable);
                    } else {
                        paged = org.springframework.data.domain.Page.empty(pageable);
                    }
                }
            } else {
                if (scope.isAllScope()) {
                    paged = billDetailRepository.searchByBatchIdAndKeyword(batchId, kw, pageable);
                } else {
                    var visibleIds = scope.getVisibleOrgIds();
                    if (visibleIds != null && !visibleIds.isEmpty()) {
                        paged = billDetailRepository.searchByBatchIdAndOrgIdsAndKeyword(batchId, visibleIds, kw, pageable);
                    } else {
                        paged = org.springframework.data.domain.Page.empty(pageable);
                    }
                }
            }
        } else {
            // Normal mode: no keyword search
            if (sheetType != null && !sheetType.isBlank()) {
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
                billBatchId, ownershipBatchId, directoryBatchId, null);

        // Save or update snapshot record
        Optional<DataSnapshot> existing = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId);
        DataSnapshot snapshot;
        if (existing.isPresent()) {
            snapshot = existing.get();
            snapshot.setOwnershipBatchId(ownershipBatchId);
            snapshot.setDirectoryBatchId(directoryBatchId);
            snapshot.setAllocationDeptBatchId(null);
            snapshot.setMatchedCount(matched);
        } else {
            snapshot = DataSnapshot.builder()
                    .billBatchId(billBatchId)
                    .ownershipBatchId(ownershipBatchId)
                    .directoryBatchId(directoryBatchId)
                    .allocationDeptBatchId(null)
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
                .filter(e -> e.getPhoneNumber().equals(entry.getPhoneNumber()) && !e.getId().equals(id) && e.getIsSeconded() != null && e.getIsSeconded() == 0)
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

    // ==================== 部门归属/成本中心 全量查询 ====================

    @GetMapping("/directory/all-entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAllDirectoryEntries(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId) {
        size = Math.min(size, 200);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? escapeLikeKeyword(search.trim()) : "";

        org.springframework.data.domain.Page<DirectoryEntry> pageResult = hasSearch
                ? directoryEntryRepository.searchAllActiveEntries(keyword, pageable)
                : directoryEntryRepository.findAllActiveEntriesPaged(pageable);

        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 部门归属/成本中心 全量导出 ====================

    @GetMapping("/directory/export-all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportAllDirectoryEntries() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("通讯录");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"部门全路径", "用户名称", "分机号", "号码", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            List<DirectoryEntry> entries = directoryEntryRepository.findAllActiveEntries();
            int rowIdx = 1;
            for (DirectoryEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getDeptPath() != null ? entry.getDeptPath() : "");
                row.createCell(1).setCellValue(entry.getUsername() != null ? entry.getUsername() : "");
                row.createCell(2).setCellValue(entry.getExtension() != null ? entry.getExtension() : "");
                row.createCell(3).setCellValue(entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                row.createCell(4).setCellValue(entry.getRemark() != null ? entry.getRemark() : "");
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("通讯录.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出通讯录失败: " + e.getMessage());
        }
    }

    // ==================== 成本中心导出 ====================

    @GetMapping("/directory/export-cost-center")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportCostCenterEntries() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("成本中心");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Columns match DepartmentOwnership page table: 一级分行, 部门路径, 分摊部门, 组织代码, 成本中心, 备注
            String[] headers = {"一级分行", "部门路径", "分摊部门", "组织代码", "成本中心", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            List<DirectoryEntry> entries = directoryEntryRepository.findAllActiveEntries();

            int rowIdx = 1;
            for (DirectoryEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                // Parse l1 branch from dept_path (e.g. "100014-广州分行-100282-代管零售银行部" -> "广州分行")
                String deptPath = entry.getDeptPath() != null ? entry.getDeptPath() : "";
                String l1Branch = "";
                String[] parts = deptPath.split("-");
                if (parts.length >= 2) l1Branch = parts[1];
                row.createCell(0).setCellValue(l1Branch);
                row.createCell(1).setCellValue(deptPath);
                row.createCell(2).setCellValue(entry.getAllocDept() != null ? entry.getAllocDept() : "");
                row.createCell(3).setCellValue(entry.getOrgCode() != null ? entry.getOrgCode() : "");
                row.createCell(4).setCellValue(entry.getCostCenter() != null ? entry.getCostCenter() : "");
                row.createCell(5).setCellValue(entry.getRemark() != null ? entry.getRemark() : "");
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

    // ==================== 部门归属/成本中心 添加单条 ====================

    @PostMapping("/directory/entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> addDirectoryEntry(
            @Valid @RequestBody AddDirectoryEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        String deptPath = req.getDeptPath();
        String username = req.getUsername() != null ? req.getUsername() : "";
        String extension = req.getExtension() != null ? req.getExtension() : "";
        String phoneNumber = req.getPhoneNumber() != null ? req.getPhoneNumber() : "";
        String allocDept = req.getAllocDept() != null ? req.getAllocDept() : "";
        String orgCode = req.getOrgCode() != null ? req.getOrgCode() : "";
        String costCenter = req.getCostCenter() != null ? req.getCostCenter() : "";
        String remark = req.getRemark() != null ? req.getRemark() : "";

        // Find or create a "manual" batch
        String batchNo = "DIR-MANUAL";
        DirectoryBatch batch = directoryBatchRepository.findByBatchNoAndDeletedAtIsNull(batchNo)
                .orElseGet(() -> {
                    DirectoryBatch b = new DirectoryBatch();
                    b.setBatchNo(batchNo);
                    b.setFileName("manual_entry");
                    b.setTotalCount(0);
                    b.setImportStatus((byte) 1);
                    b.setImportedBy(userId);
                    return directoryBatchRepository.save(b);
                });

        DirectoryEntry entry = new DirectoryEntry();
        entry.setBatchId(batch.getId());
        entry.setDeptPath(deptPath);
        entry.setUsername(username);
        entry.setExtension(extension);
        entry.setPhoneNumber(phoneNumber);
        entry.setAllocDept(allocDept);
        entry.setOrgCode(orgCode);
        entry.setCostCenter(costCenter);
        entry.setRemark(remark);
        entry = directoryEntryRepository.save(entry);

        // Update batch total count
        long count = directoryEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
        batch.setTotalCount((int) count);
        directoryBatchRepository.save(batch);

        auditLogService.log(userId, "ADD_DIRECTORY_ENTRY", "directory_entry", entry.getId(),
                Map.of("dept_path", deptPath, "alloc_dept", allocDept));

        Map<String, Object> result = new HashMap<>();
        result.put("id", entry.getId());
        result.put("batch_id", entry.getBatchId());
        result.put("dept_path", entry.getDeptPath());
        result.put("username", entry.getUsername());
        result.put("extension", entry.getExtension());
        result.put("phone_number", entry.getPhoneNumber());
        result.put("alloc_dept", entry.getAllocDept());
        result.put("org_code", entry.getOrgCode());
        result.put("cost_center", entry.getCostCenter());
        result.put("remark", entry.getRemark());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 部门归属/成本中心 删除单条 ====================

    @DeleteMapping("/directory/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteDirectoryEntry(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        DirectoryEntry entry = directoryEntryRepository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("成本中心记录不存在或已被删除: " + id));

        entry.setDeletedAt(java.time.LocalDateTime.now());
        directoryEntryRepository.save(entry);

        // Update batch total count
        DirectoryBatch batch = directoryBatchRepository.findByIdAndDeletedAtIsNull(entry.getBatchId())
                .orElse(null);
        if (batch != null) {
            long count = directoryEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
            batch.setTotalCount((int) count);
            directoryBatchRepository.save(batch);
        }

        auditLogService.log(userId, "DELETE_DIRECTORY_ENTRY", "directory_entry", id,
                Map.of("dept_path", entry.getDeptPath(), "alloc_dept", entry.getAllocDept()));

        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", id, "deleted", true)));
    }

    // ==================== 数据快照 ====================

    @GetMapping("/snapshots")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<List<DataSnapshot>>> listSnapshots() {
        List<DataSnapshot> snapshots = dataSnapshotRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        return ResponseEntity.ok(ApiResponse.ok(snapshots));
    }

    @GetMapping("/snapshots/{billBatchId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<DataSnapshot>> getSnapshot(@PathVariable Long billBatchId) {
        DataSnapshot snapshot = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId)
                .orElseThrow(() -> new IllegalArgumentException("未找到账单批次 " + billBatchId + " 的快照记录"));
        return ResponseEntity.ok(ApiResponse.ok(snapshot));
    }

    // ==================== 录音数据导入 ====================

    @PostMapping("/recording-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importRecordingData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        try {
            RecordingDataBatch batch = recordingDataImportService.importRecordingData(file, userId, billingMonth);
            Map<String, Object> auditDetail = new HashMap<>();
            auditDetail.put("file_name", file.getOriginalFilename());
            auditDetail.put("batch_no", batch.getBatchNo());
            auditLogService.log(userId, "IMPORT_RECORDING_DATA", "recording_data_batch", batch.getId(), auditDetail);
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
            RecordingDataBatch batch = recordingDataBatchRepository.findByIdAndDeletedAtIsNull(batchId).orElse(null);
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
            header.createCell(1).setCellValue("号码");
            header.createCell(2).setCellValue("部门");
            header.createCell(3).setCellValue("当前状态");
            header.createCell(4).setCellValue("关闭时间");
            header.createCell(5).setCellValue("备注");
            for (int i = 0; i < 6; i++) sheet.autoSizeColumn(i);
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
    public ResponseEntity<ApiResponse<List<RecordingDataBatch>>> listRecordingDataBatches(
            @RequestParam(value = "billing_month", required = false) String billingMonth) {
        if (billingMonth != null && !billingMonth.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(recordingDataBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth)));
        }
        return ResponseEntity.ok(ApiResponse.ok(recordingDataBatchRepository.findByDeletedAtIsNull()));
    }

    @GetMapping("/recording-data/months")
    public ResponseEntity<ApiResponse<List<String>>> listRecordingDataMonths() {
        return ResponseEntity.ok(ApiResponse.ok(recordingDataBatchRepository.findDistinctBillingMonths()));
    }

    @GetMapping("/recording-data/entries-by-month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listRecordingDataEntriesByMonth(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 200);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<RecordingDataEntry> pageResult;
        if (search != null && !search.isBlank()) {
            pageResult = recordingDataEntryRepository.searchByBillingMonthAndKeyword(billingMonth, escapeLikeKeyword(search.trim()), pageable);
        } else {
            pageResult = recordingDataEntryRepository.findByBillingMonth(billingMonth, pageable);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("entries", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("filtered", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/recording-data/entries/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listRecordingDataEntries(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 200);
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteRecordingDataBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        RecordingDataBatch batch = recordingDataBatchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        batch.setDeletedAt(java.time.LocalDateTime.now());
        recordingDataBatchRepository.save(batch);
        // Soft-delete entries
        recordingDataEntryRepository.softDeleteByBatchId(id, java.time.LocalDateTime.now());
        Map<String, Object> auditDetail = new HashMap<>();
        auditDetail.put("batch_no", batch.getBatchNo());
        auditLogService.log(userId, "DELETE_RECORDING_DATA", "recording_data_batch", id, auditDetail);
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("batch_no", batch.getBatchNo());
        result.put("deleted", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 录音数据：单条新增 ====================

    @PostMapping("/recording-data/entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> addRecordingDataEntry(
            @Valid @RequestBody AddRecordingDataEntryRequest req,
            @RequestAttribute("userId") Long userId) {
        String billingMonth = req.getBillingMonth();
        String extension = req.getExtension() != null ? req.getExtension() : "";
        String phoneNumber = req.getPhoneNumber() != null ? req.getPhoneNumber() : "";
        String deptName = req.getDeptName() != null ? req.getDeptName() : "";
        String remark = req.getRemark() != null ? req.getRemark() : "";

        if (extension.isBlank() && phoneNumber.isBlank()) throw new IllegalArgumentException("分机号和号码至少填一个");

        // Find or create a "manual" batch for single-entry additions
        String batchNo = "REC-" + billingMonth.replace("-", "") + "-MANUAL";
        RecordingDataBatch batch = recordingDataBatchRepository.findByBatchNoAndDeletedAtIsNull(batchNo)
                .orElseGet(() -> {
                    RecordingDataBatch b = new RecordingDataBatch();
                    b.setBatchNo(batchNo);
                    b.setFileName("手动添加");
                    b.setTotalCount(0);
                    b.setBillingMonth(billingMonth);
                    b.setImportStatus((byte) 1);
                    b.setImportedBy(userId);
                    return recordingDataBatchRepository.save(b);
                });

        RecordingDataEntry entry = new RecordingDataEntry();
        entry.setBatchId(batch.getId());
        entry.setExtension(extension);
        entry.setPhoneNumber(phoneNumber);
        entry.setDeptName(deptName);
        entry.setRemark(remark);
        entry.setStatus((byte) 0);  // H-DB09: INT→TINYINT
        entry = recordingDataEntryRepository.save(entry);

        // Update batch counts
        long count = recordingDataEntryRepository.countByBatchIdAndDeletedAtIsNull(batch.getId());
        batch.setTotalCount((int) count);
        recordingDataBatchRepository.save(batch);

        auditLogService.log(userId, "ADD_RECORDING_DATA_ENTRY", "recording_data_entry", entry.getId(),
                Map.of("extension", extension, "phone_number", phoneNumber, "billing_month", billingMonth));

        Map<String, Object> result = new HashMap<>();
        result.put("id", entry.getId());
        result.put("batch_id", entry.getBatchId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== 录音数据：全量导出 ====================

    @GetMapping("/recording-data/export")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportRecordingData() {
        List<RecordingDataEntry> entries = recordingDataEntryRepository.findAllActiveEntriesForExport();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("录音数据");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"分机号", "号码", "部门", "当前状态", "关闭时间", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (RecordingDataEntry e : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(e.getExtension() != null ? e.getExtension() : "");
                row.createCell(1).setCellValue(e.getPhoneNumber() != null ? e.getPhoneNumber() : "");
                row.createCell(2).setCellValue(e.getDeptName() != null ? e.getDeptName() : "");
                row.createCell(3).setCellValue(e.getStatus() != null && e.getStatus() == 1 ? "关闭" : "正常");
                row.createCell(4).setCellValue(e.getCloseTime() != null ? e.getCloseTime().toString() : "");
                row.createCell(5).setCellValue(e.getRemark() != null ? e.getRemark() : "");
            }

            wb.write(out);

            String fileName = java.net.URLEncoder.encode("录音数据导出.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出录音数据失败: " + e.getMessage());
        }
    }

    /**
     * Escape LIKE wildcard characters in keyword to prevent LIKE injection.
     * MySQL uses '\' as the default escape character in LIKE patterns.
     */
    private static String escapeLikeKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) return keyword;
        StringBuilder sb = new StringBuilder(keyword.length() * 2);
        for (char c : keyword.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
