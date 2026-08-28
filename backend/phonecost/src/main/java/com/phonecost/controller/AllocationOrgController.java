package com.phonecost.controller;

import com.phonecost.domain.AllocationOrgBatch;
import com.phonecost.domain.AllocationOrgEntry;
import com.phonecost.domain.SysOrganization;
import com.phonecost.repository.AllocationOrgBatchRepository;
import com.phonecost.repository.AllocationOrgEntryRepository;
import com.phonecost.repository.SysOrganizationRepository;
import com.phonecost.service.AllocationOrgImportService;
import com.phonecost.service.BranchNumberPushService;
import com.phonecost.service.ComparisonPushService;
import com.phonecost.service.DataScopeService;
import com.phonecost.dto.ApiResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/import/allocation-org")
@PreAuthorize("isAuthenticated()")
public class AllocationOrgController {

    private final AllocationOrgImportService importService;
    private final AllocationOrgBatchRepository batchRepo;
    private final AllocationOrgEntryRepository entryRepo;
    private final DataScopeService dataScopeService;
    private final ComparisonPushService pushService;
    private final BranchNumberPushService branchNumberPushService;
    private final SysOrganizationRepository orgRepo;

    /** Sentinel: 全量数据（admin/财务） */
    private static final Long SCOPE_ALL = -1L;

    public AllocationOrgController(AllocationOrgImportService importService,
                                   AllocationOrgBatchRepository batchRepo,
                                   AllocationOrgEntryRepository entryRepo,
                                   DataScopeService dataScopeService,
                                   ComparisonPushService pushService,
                                   BranchNumberPushService branchNumberPushService,
                                   SysOrganizationRepository orgRepo) {
        this.importService = importService;
        this.batchRepo = batchRepo;
        this.entryRepo = entryRepo;
        this.dataScopeService = dataScopeService;
        this.pushService = pushService;
        this.branchNumberPushService = branchNumberPushService;
        this.orgRepo = orgRepo;
    }

    /**
     * 解析当前用户可见的一级分行范围：
     * - ADMIN/FINANCE：返回 SCOPE_ALL（全量）
     * - 分行/部门用户：返回其所属一级分行 orgId；解析不到则返回 null（无可见数据）
     */
    private Long resolveScopeBranchOrg(Byte role, Long userId) {
        if (role != null && (role == (byte) 1 || role == (byte) 4)) {
            return SCOPE_ALL;
        }
        return dataScopeService.resolveBranchOrgId(userId);
    }

    // ==================== Push from Comparison ====================

    @PostMapping("/push-from-comparison")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pushFromComparison(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        String pushType = (String) body.getOrDefault("push_type", "directory");
        Map<String, Object> result;

        if ("exception".equals(pushType)) {
            String month = (String) body.get("month");
            result = pushService.pushExceptionComparison(month, userId);
        } else {
            String month1 = (String) body.get("month1");
            String month2 = (String) body.get("month2");
            if (month1 == null || month2 == null || month1.isBlank() || month2.isBlank()) {
                throw new IllegalArgumentException("推送通讯录差异需要提供 month1 和 month2");
            }
            @SuppressWarnings("unchecked")
            List<String> typeList = (List<String>) body.get("types");
            Set<String> types = typeList != null ? new HashSet<>(typeList) : null;
            result = pushService.pushDirectoryComparison(month1, month2, types, userId);
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 分行号码 → 号码分摊机构 推送
     * 用号码去目标月份之前最近有数据的月份匹配分摊部门，未匹配的置顶展示
     */
    @PostMapping("/push-from-branch-number")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pushFromBranchNumber(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        String sourceMonth = (String) body.get("source_month");
        String targetMonth = (String) body.get("target_month");
        if (sourceMonth == null || sourceMonth.isBlank()) {
            throw new IllegalArgumentException("推送需要提供 source_month");
        }
        Map<String, Object> result = branchNumberPushService.pushFromBranchNumber(sourceMonth, targetMonth, userId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Import ====================

    @PostMapping("")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importAllocationOrg(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        AllocationOrgBatch batch = importService.importAllocationOrg(file, userId, billingMonth);
        Map<String, Object> result = new HashMap<>();
        result.put("batch_id", batch.getId());
        result.put("batch_no", batch.getBatchNo());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Progress ====================

    @GetMapping("/progress/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getImportProgress(@PathVariable Long batchId) {
        AllocationOrgImportService.ImportProgress p = importService.getProgress(batchId);
        Map<String, Object> result = new HashMap<>();
        if (p != null) {
            result.put("total", p.getTotal());
            result.put("processed", p.getProcessed());
            result.put("status", p.getStatus());
            result.put("message", p.getMessage() != null ? p.getMessage() : "");
        } else {
            result.put("total", 0);
            result.put("processed", 0);
            result.put("status", "UNKNOWN");
            result.put("message", "批次不存在");
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Template ====================

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("号码分摊机构");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"号码", "一级分行", "分摊部门", "机构代码", "成本中心", "备注"};
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, 6000);
            }
            wb.write(out);
            String fileName = URLEncoder.encode("号码分摊机构导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成模板失败", e);
        }
    }

    // ==================== Batches & Months ====================

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<AllocationOrgBatch>>> listBatches(
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestParam(value = "source", required = false) String source,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        List<AllocationOrgBatch> batches;
        boolean hasMonth = billingMonth != null && !billingMonth.isBlank();
        boolean isPush = "push".equalsIgnoreCase(source);
        boolean isImport = "import".equalsIgnoreCase(source);

        if (isPush || isImport) {
            // 按来源过滤：import=导入(非PUSH-)，push=推送(PUSH-)
            if (scopeBranch == SCOPE_ALL) {
                batches = isPush
                        ? (hasMonth
                            ? batchRepo.findByBillingMonthAndSourcePush(billingMonth)
                            : batchRepo.findBySourcePush())
                        : (hasMonth
                            ? batchRepo.findByBillingMonthAndSourceImport(billingMonth)
                            : batchRepo.findBySourceImport());
            } else if (scopeBranch == null) {
                batches = List.of();
            } else {
                batches = isPush
                        ? (hasMonth
                            ? batchRepo.findByBillingMonthAndSourcePushAndBranchOrgId(billingMonth, scopeBranch)
                            : batchRepo.findBySourcePushAndBranchOrgId(scopeBranch))
                        : (hasMonth
                            ? batchRepo.findByBillingMonthAndSourceImportAndBranchOrgId(billingMonth, scopeBranch)
                            : batchRepo.findBySourceImportAndBranchOrgId(scopeBranch));
            }
        } else {
            // 原有逻辑：不区分来源
            if (scopeBranch == SCOPE_ALL) {
                batches = hasMonth
                        ? batchRepo.findByBillingMonthAndDeletedAtIsNull(billingMonth)
                        : batchRepo.findByDeletedAtIsNull();
            } else if (scopeBranch == null) {
                batches = List.of();
            } else {
                batches = hasMonth
                        ? batchRepo.findByBillingMonthAndBranchOrgIdAndDeletedAtIsNull(billingMonth, scopeBranch)
                        : batchRepo.findByBranchOrgIdAndDeletedAtIsNull(scopeBranch);
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(batches));
    }

    @GetMapping("/months")
    public ResponseEntity<ApiResponse<List<String>>> listMonths(
            @RequestParam(value = "source", required = false) String source,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        List<String> months;
        boolean isPush = "push".equalsIgnoreCase(source);
        boolean isImport = "import".equalsIgnoreCase(source);

        if (isPush || isImport) {
            if (scopeBranch == SCOPE_ALL) {
                months = isPush
                        ? batchRepo.findDistinctBillingMonthsBySourcePush()
                        : batchRepo.findDistinctBillingMonthsBySourceImport();
            } else if (scopeBranch == null) {
                months = List.of();
            } else {
                months = isPush
                        ? batchRepo.findDistinctBillingMonthsBySourcePushAndBranchOrgId(scopeBranch)
                        : batchRepo.findDistinctBillingMonthsBySourceImportAndBranchOrgId(scopeBranch);
            }
        } else {
            // 原有逻辑：不区分来源
            if (scopeBranch == SCOPE_ALL) {
                months = batchRepo.findDistinctBillingMonths();
            } else if (scopeBranch == null) {
                months = List.of();
            } else {
                months = batchRepo.findDistinctBillingMonthsByBranchOrgId(scopeBranch);
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(months));
    }

    // ==================== Entries by batch ====================

    /**
     * 按批次查询号码分摊机构明细（分页 + 搜索）
     * 数据隔离：admin/财务全量；分行/部门用户仅可见本行 entry（entry 级 branchOrgId）
     */
    @GetMapping("/entries-by-batch/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listEntriesByBatch(
            @PathVariable Long batchId,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        size = Math.min(size, 200);
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim() : "";
        Long scopeBranch = resolveScopeBranchOrg(role, userId);

        // 全量加载该批次未删除条目（<=200/页由内存分页处理）
        List<AllocationOrgEntry> all = entryRepo.findByBatchIdAndDeletedAtIsNull(batchId);

        // 数据隔离过滤
        List<AllocationOrgEntry> scoped;
        if (scopeBranch == SCOPE_ALL) {
            scoped = all;
        } else if (scopeBranch == null) {
            scoped = List.of();
        } else {
            scoped = all.stream().filter(e -> scopeBranch.equals(e.getBranchOrgId())).toList();
        }

        // 关键词过滤：号码/一级分行/分摊部门/机构代码/备注
        List<AllocationOrgEntry> filtered = hasSearch
                ? scoped.stream().filter(e -> {
                    String pn = e.getPhoneNumber() != null ? e.getPhoneNumber().toLowerCase() : "";
                    String lb = e.getL1Branch() != null ? e.getL1Branch().toLowerCase() : "";
                    String ad = e.getAllocDept() != null ? e.getAllocDept().toLowerCase() : "";
                    String oc = e.getOrgCode() != null ? e.getOrgCode().toLowerCase() : "";
                    String rm = e.getRemark() != null ? e.getRemark().toLowerCase() : "";
                    String kw = keyword.toLowerCase();
                    return pn.contains(kw) || lb.contains(kw) || ad.contains(kw) || oc.contains(kw) || rm.contains(kw);
                }).toList()
                : scoped;

        int start = page * size;
        List<AllocationOrgEntry> pageEntries = (start < filtered.size())
                ? filtered.subList(start, Math.min(start + size, filtered.size()))
                : List.of();

        List<Map<String, Object>> entries = new ArrayList<>();
        for (AllocationOrgEntry entry : pageEntries) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
            e.put("l1_branch", entry.getL1Branch() != null ? entry.getL1Branch() : "");
            e.put("alloc_dept", entry.getAllocDept() != null ? entry.getAllocDept() : "");
            e.put("org_code", entry.getOrgCode() != null ? entry.getOrgCode() : "");
            e.put("cost_center", entry.getCostCenter() != null ? entry.getCostCenter() : "");
            e.put("remark", entry.getRemark() != null ? entry.getRemark() : "");
            entries.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", entries);
        result.put("total", (long) filtered.size());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Entries by month ====================

    @GetMapping("/entries-by-month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listEntriesByMonth(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "change_type", required = false) String changeType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        size = Math.min(size, 200);
        var pageable = PageRequest.of(page, size);
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim() : "";
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        boolean isPush = "push".equalsIgnoreCase(source);
        boolean isImport = "import".equalsIgnoreCase(source);
        boolean hasChangeType = changeType != null && !changeType.isBlank();
        String ctVal = hasChangeType ? changeType.trim() : null;

        Page<AllocationOrgEntry> pageResult;

        if (isPush) {
            // 推送来源数据
            if (hasChangeType) {
                // 按类型过滤
                if (scopeBranch == SCOPE_ALL) {
                    pageResult = hasSearch
                            ? entryRepo.searchByBillingMonthAndSourcePushAndChangeType(billingMonth, ctVal, keyword, pageable)
                            : entryRepo.findByBillingMonthAndSourcePushAndChangeType(billingMonth, ctVal, pageable);
                } else if (scopeBranch == null) {
                    pageResult = Page.empty(pageable);
                } else {
                    pageResult = hasSearch
                            ? entryRepo.searchByBillingMonthAndSourcePushAndBranchOrgIdAndChangeType(billingMonth, scopeBranch, ctVal, keyword, pageable)
                            : entryRepo.findByBillingMonthAndSourcePushAndBranchOrgIdAndChangeType(billingMonth, scopeBranch, ctVal, pageable);
                }
            } else {
                // 不按类型过滤（原有逻辑）
                if (scopeBranch == SCOPE_ALL) {
                    pageResult = hasSearch
                            ? entryRepo.searchByBillingMonthAndSourcePush(billingMonth, keyword, pageable)
                            : entryRepo.findByBillingMonthAndSourcePush(billingMonth, pageable);
                } else if (scopeBranch == null) {
                    pageResult = Page.empty(pageable);
                } else {
                    pageResult = hasSearch
                            ? entryRepo.searchByBillingMonthAndSourcePushAndBranchOrgId(billingMonth, scopeBranch, keyword, pageable)
                            : entryRepo.findByBillingMonthAndSourcePushAndBranchOrgId(billingMonth, scopeBranch, pageable);
                }
            }
        } else if (isImport) {
            // 导入来源数据
            if (scopeBranch == SCOPE_ALL) {
                pageResult = hasSearch
                        ? entryRepo.searchByBillingMonthAndSourceImport(billingMonth, keyword, pageable)
                        : entryRepo.findByBillingMonthAndSourceImport(billingMonth, pageable);
            } else if (scopeBranch == null) {
                pageResult = Page.empty(pageable);
            } else {
                pageResult = hasSearch
                        ? entryRepo.searchByBillingMonthAndSourceImportAndBranchOrgId(billingMonth, scopeBranch, keyword, pageable)
                        : entryRepo.findByBillingMonthAndSourceImportAndBranchOrgId(billingMonth, scopeBranch, pageable);
            }
        } else {
            // 原有逻辑：不区分来源
            if (scopeBranch == SCOPE_ALL) {
                pageResult = hasSearch
                        ? entryRepo.searchByBillingMonthAndKeyword(billingMonth, keyword, pageable)
                        : entryRepo.findByBillingMonth(billingMonth, pageable);
            } else if (scopeBranch == null) {
                pageResult = Page.empty(pageable);
            } else {
                pageResult = hasSearch
                        ? entryRepo.searchByBillingMonthAndKeywordAndBranchOrgId(billingMonth, scopeBranch, keyword, pageable)
                        : entryRepo.findByBillingMonthAndBranchOrgId(billingMonth, scopeBranch, pageable);
            }
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (AllocationOrgEntry entry : pageResult.getContent()) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
            e.put("l1_branch", entry.getL1Branch() != null ? entry.getL1Branch() : "");
            e.put("alloc_dept", entry.getAllocDept() != null ? entry.getAllocDept() : "");
            e.put("org_code", entry.getOrgCode() != null ? entry.getOrgCode() : "");
            e.put("cost_center", entry.getCostCenter() != null ? entry.getCostCenter() : "");
            e.put("remark", entry.getRemark() != null ? entry.getRemark() : "");
            // 差异推送数据 Tab 额外返回差异数据列
            if (isPush) {
                e.put("username", entry.getUsername() != null ? entry.getUsername() : "");
                e.put("dept_path", entry.getDeptPath() != null ? entry.getDeptPath() : "");
                e.put("extension", entry.getExtension() != null ? entry.getExtension() : "");
                e.put("change_type", entry.getChangeType() != null ? entry.getChangeType() : "");
                String changedCols = entry.getChangedColumns();
                if (changedCols != null && !changedCols.isEmpty()) {
                    e.put("changed_columns", java.util.Arrays.asList(changedCols.split(",")));
                } else {
                    e.put("changed_columns", java.util.Collections.emptyList());
                }
                e.put("verified", entry.getVerified() != null && entry.getVerified());
            }
            entries.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", entries);
        result.put("total", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Verify entry (待核对) ====================

    /**
     * 确认核对（不改分摊部门）
     */
    @PostMapping("/entries/{id}/verify")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEntry(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        AllocationOrgEntry entry = entryRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));
        // 数据隔离校验
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        if (scopeBranch != SCOPE_ALL) {
            if (scopeBranch == null || !scopeBranch.equals(entry.getBranchOrgId())) {
                throw new RuntimeException("无权操作该记录");
            }
        }
        entry.setVerified(true);
        entry.setVerifiedAt(LocalDateTime.now());
        entry.setVerifiedBy(userId);
        entryRepo.save(entry);
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("verified", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 修改分摊部门并完成核对
     */
    @PostMapping("/entries/{id}/verify-edit")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEditEntry(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        AllocationOrgEntry entry = entryRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));
        // 数据隔离校验
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        if (scopeBranch != SCOPE_ALL) {
            if (scopeBranch == null || !scopeBranch.equals(entry.getBranchOrgId())) {
                throw new RuntimeException("无权操作该记录");
            }
        }
        // 更新分摊部门
        if (body.containsKey("alloc_dept")) {
            String allocDept = str(body.get("alloc_dept"));
            entry.setAllocDept(allocDept);
        }
        // 可选：同步一级分行（branch_id = 选中分行 orgId）
        if (body.containsKey("branch_id") && body.get("branch_id") != null) {
            Long branchId = Long.valueOf(String.valueOf(body.get("branch_id")));
            // 校验该 org 存在且为一级分行（type=2）
            SysOrganization branch = orgRepo.findByIdAndDeletedAtIsNull(branchId)
                    .orElseThrow(() -> new RuntimeException("一级分行不存在: " + branchId));
            if (branch.getType() == null || branch.getType() != 2) {
                throw new RuntimeException("所选机构不是一级分行: " + branchId);
            }
            // 数据隔离校验：非全量用户只能选择自己的分行
            if (scopeBranch != SCOPE_ALL && !branchId.equals(scopeBranch)) {
                throw new RuntimeException("无权选择该一级分行");
            }
            entry.setBranchOrgId(branchId);
            entry.setL1Branch(branch.getName());
        }
        entry.setVerified(true);
        entry.setVerifiedAt(LocalDateTime.now());
        entry.setVerifiedBy(userId);
        entryRepo.save(entry);
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("verified", true);
        result.put("alloc_dept", entry.getAllocDept() != null ? entry.getAllocDept() : "");
        result.put("l1_branch", entry.getL1Branch() != null ? entry.getL1Branch() : "");
        result.put("branch_org_id", entry.getBranchOrgId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Update entry ====================

    /**
     * 编辑号码分摊机构单条记录
     * 权限：ADMIN/BRANCH；仅可编辑当前用户可见范围（admin 全量，分行用户限本行批次）
     */
    @PutMapping("/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateEntry(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        AllocationOrgEntry entry = entryRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));

        // 数据隔离校验：admin/财务全量；分行/部门用户仅可编辑本行 entry（按 entry 级 branchOrgId）
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        if (scopeBranch != SCOPE_ALL) {
            if (scopeBranch == null || !scopeBranch.equals(entry.getBranchOrgId())) {
                throw new RuntimeException("无权编辑该记录");
            }
        }

        // 逐字段更新：用 containsKey 区分"未传"与"传空串"（空串=清空字段）
        if (body.containsKey("phone_number")) entry.setPhoneNumber(str(body.get("phone_number")));
        if (body.containsKey("l1_branch")) entry.setL1Branch(str(body.get("l1_branch")));
        if (body.containsKey("alloc_dept")) entry.setAllocDept(str(body.get("alloc_dept")));
        if (body.containsKey("org_code")) entry.setOrgCode(str(body.get("org_code")));
        if (body.containsKey("cost_center")) entry.setCostCenter(str(body.get("cost_center")));
        if (body.containsKey("remark")) entry.setRemark(str(body.get("remark")));

        // 至少有一个字段被提交才允许更新
        if (!body.containsKey("phone_number") && !body.containsKey("l1_branch")
                && !body.containsKey("alloc_dept") && !body.containsKey("org_code")
                && !body.containsKey("cost_center") && !body.containsKey("remark")) {
            throw new IllegalArgumentException("没有可更新的字段");
        }

        entryRepo.save(entry);

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("updated", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** 空值转 null */
    private String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s.trim();
    }

    // ==================== Delete entry ====================

    /**
     * 删除号码分摊机构单条记录（软删除）
     * 权限：ADMIN/BRANCH；仅可删除当前用户可见范围（admin 全量，分行用户限本行 entry）
     */
    @DeleteMapping("/entries/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteEntry(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        AllocationOrgEntry entry = entryRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("记录不存在: " + id));

        // 数据隔离校验：admin/财务全量；分行/部门用户仅可删除本行 entry（按 entry 级 branchOrgId）
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        if (scopeBranch != SCOPE_ALL) {
            if (scopeBranch == null || !scopeBranch.equals(entry.getBranchOrgId())) {
                throw new RuntimeException("无权删除该记录");
            }
        }

        entry.setDeletedAt(LocalDateTime.now());
        entryRepo.save(entry);

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("deleted", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Delete batch ====================

    @DeleteMapping("/batches/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        AllocationOrgBatch batch = batchRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RuntimeException("批次不存在: " + id));
        entryRepo.softDeleteByBatchId(id, LocalDateTime.now());
        batch.setDeletedAt(LocalDateTime.now());
        batchRepo.save(batch);
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("deleted", true);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Export ====================

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEntries(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "source", required = false) String source,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        boolean isPush = "push".equalsIgnoreCase(source);
        boolean isImport = "import".equalsIgnoreCase(source);
        List<AllocationOrgEntry> entries;

        if (isPush) {
            if (scopeBranch == SCOPE_ALL) {
                entries = entryRepo.findAllByBillingMonthAndSourcePush(billingMonth);
            } else if (scopeBranch == null) {
                entries = List.of();
            } else {
                entries = entryRepo.findAllByBillingMonthAndSourcePushAndBranchOrgId(billingMonth, scopeBranch);
            }
        } else if (isImport) {
            if (scopeBranch == SCOPE_ALL) {
                entries = entryRepo.findAllByBillingMonthAndSourceImport(billingMonth);
            } else if (scopeBranch == null) {
                entries = List.of();
            } else {
                entries = entryRepo.findAllByBillingMonthAndSourceImportAndBranchOrgId(billingMonth, scopeBranch);
            }
        } else {
            if (scopeBranch == SCOPE_ALL) {
                entries = entryRepo.findAllByBillingMonth(billingMonth);
            } else if (scopeBranch == null) {
                entries = List.of();
            } else {
                entries = entryRepo.findAllByBillingMonthAndBranchOrgId(billingMonth, scopeBranch);
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("号码分摊机构");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"号码", "一级分行", "分摊部门", "机构代码", "成本中心", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            int rowIdx = 1;
            for (AllocationOrgEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                row.createCell(1).setCellValue(entry.getL1Branch() != null ? entry.getL1Branch() : "");
                row.createCell(2).setCellValue(entry.getAllocDept() != null ? entry.getAllocDept() : "");
                row.createCell(3).setCellValue(entry.getOrgCode() != null ? entry.getOrgCode() : "");
                row.createCell(4).setCellValue(entry.getCostCenter() != null ? entry.getCostCenter() : "");
                row.createCell(5).setCellValue(entry.getRemark() != null ? entry.getRemark() : "");
            }

            wb.write(out);
            String fileName = URLEncoder.encode("号码分摊机构导出.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出失败", e);
        }
    }
}
