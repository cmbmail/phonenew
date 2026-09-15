package com.phonecost.controller;

import com.phonecost.domain.AllocationOrgBatch;
import com.phonecost.domain.AllocationOrgEntry;
import com.phonecost.domain.AllocationOrgMapping;
import com.phonecost.domain.DirectoryEntry;
import com.phonecost.repository.AllocationOrgBatchRepository;
import com.phonecost.repository.AllocationOrgEntryRepository;
import com.phonecost.repository.AllocationOrgMappingRepository;
import com.phonecost.repository.DirectoryEntryRepository;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
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
    private final AllocationOrgMappingRepository mappingRepo;
    private final DirectoryEntryRepository directoryEntryRepo;
    private final DataScopeService dataScopeService;
    private final ComparisonPushService pushService;
    private final BranchNumberPushService branchNumberPushService;

    /** Sentinel: 全量数据（admin/财务） */
    private static final Long SCOPE_ALL = -1L;

    public AllocationOrgController(AllocationOrgImportService importService,
                                   AllocationOrgBatchRepository batchRepo,
                                   AllocationOrgEntryRepository entryRepo,
                                   AllocationOrgMappingRepository mappingRepo,
                                   DirectoryEntryRepository directoryEntryRepo,
                                   DataScopeService dataScopeService,
                                   ComparisonPushService pushService,
                                   BranchNumberPushService branchNumberPushService) {
        this.importService = importService;
        this.batchRepo = batchRepo;
        this.entryRepo = entryRepo;
        this.mappingRepo = mappingRepo;
        this.directoryEntryRepo = directoryEntryRepo;
        this.dataScopeService = dataScopeService;
        this.pushService = pushService;
        this.branchNumberPushService = branchNumberPushService;
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

    // ==================== 实时匹配（通讯录 → 对照表） ====================

    /**
     * 查询时实时匹配器：
     * 1) 同月通讯录（directory_entry JOIN directory_batch）按号码聚合 extension/dept_path 多值（「、」分隔）
     * 2) 分摊机构对照表（allocation_org_mapping）构建 l1Branch+deptPath → orgName/orgCode/costCenterCode 映射
     */
    private static class AllocationOrgMatchResolver {
        final Map<String, String[]> extByPhone = new HashMap<>();
        final Map<String, String[]> deptByPhone = new HashMap<>();
        final Map<String, AllocationOrgMapping> mappingByBranchDept = new HashMap<>();

        /** 按号码取匹配结果：extension（多值）、dept_path（多值）、alloc_dept/org_code/cost_center（对照表匹配，匹配不到为空串） */
        Map<String, String> resolve(String phoneNumber, String l1Branch) {
            Map<String, String> r = new HashMap<>();
            String phone = phoneNumber != null ? phoneNumber.trim() : "";
            String[] exts = extByPhone.get(phone);
            String[] depts = deptByPhone.get(phone);
            r.put("extension", exts != null && exts.length > 0 ? String.join("、", exts) : "");
            r.put("dept_path", depts != null && depts.length > 0 ? String.join("、", depts) : "");
            // 匹配不到（无部门全路径或无对照记录）→ 三列为空，便于发现对照表缺口
            String allocDept = "";
            String orgCode = "";
            String costCenter = "";
            if (depts != null) {
                List<String> ad = new ArrayList<>();
                List<String> oc = new ArrayList<>();
                List<String> cc = new ArrayList<>();
                for (String dp : depts) {
                    if (dp == null || dp.isBlank()) continue;
                    AllocationOrgMapping m = mappingByBranchDept.get(key(l1Branch, dp.trim()));
                    if (m != null) {
                        if (m.getOrgName() != null && !m.getOrgName().isBlank() && !ad.contains(m.getOrgName())) ad.add(m.getOrgName());
                        if (m.getOrgCode() != null && !m.getOrgCode().isBlank() && !oc.contains(m.getOrgCode())) oc.add(m.getOrgCode());
                        if (m.getCostCenterCode() != null && !m.getCostCenterCode().isBlank() && !cc.contains(m.getCostCenterCode())) cc.add(m.getCostCenterCode());
                    }
                }
                allocDept = String.join("、", ad);
                orgCode = String.join("、", oc);
                costCenter = String.join("、", cc);
            }
            r.put("alloc_dept", allocDept);
            r.put("org_code", orgCode);
            r.put("cost_center", costCenter);
            return r;
        }

        private static String key(String l1Branch, String deptPath) {
            String b = l1Branch != null ? l1Branch.trim() : "";
            return b + "\u0001" + deptPath;
        }
    }

    /** 无月份上下文时的空匹配结果 */
    private Map<String, String> emptyMatch() {
        Map<String, String> r = new HashMap<>();
        r.put("extension", "");
        r.put("dept_path", "");
        r.put("alloc_dept", "");
        r.put("org_code", "");
        r.put("cost_center", "");
        return r;
    }

    /**
     * 构建指定月份的实时匹配器（每次调用实时读取，通讯录/对照表更新后立即生效）
     */
    private AllocationOrgMatchResolver buildMatchResolver(String billingMonth) {
        AllocationOrgMatchResolver resolver = new AllocationOrgMatchResolver();

        // 1) 同月通讯录：号码 → 聚合 extension / dept_path（多值去重保序）
        List<Object[]> dirRows = directoryEntryRepo.findPhoneExtAndDeptByMonth(billingMonth);
        Map<String, LinkedHashSet<String>> extMap = new HashMap<>();
        Map<String, LinkedHashSet<String>> deptMap = new HashMap<>();
        for (Object[] row : dirRows) {
            String pn = row[0] != null ? String.valueOf(row[0]).trim() : "";
            if (pn.isEmpty()) continue;
            String ext = row[1] != null ? String.valueOf(row[1]).trim() : "";
            String dp = row[2] != null ? String.valueOf(row[2]).trim() : "";
            if (!ext.isEmpty()) extMap.computeIfAbsent(pn, k -> new LinkedHashSet<>()).add(ext);
            if (!dp.isEmpty()) deptMap.computeIfAbsent(pn, k -> new LinkedHashSet<>()).add(dp);
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : extMap.entrySet()) {
            resolver.extByPhone.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : deptMap.entrySet()) {
            resolver.deptByPhone.put(e.getKey(), e.getValue().toArray(new String[0]));
        }

        // 2) 分摊机构对照表：l1Branch+deptPath → mapping（同一部门全路径在同一一级分行下唯一）
        List<AllocationOrgMapping> mappings = mappingRepo.findAllByDeletedAtIsNull();
        for (AllocationOrgMapping m : mappings) {
            if (m.getDeptFullPath() == null || m.getDeptFullPath().isBlank()) continue;
            String l1 = m.getL1Branch() != null ? m.getL1Branch().trim() : "";
            String[] paths = m.getDeptFullPath().split("、");
            for (String p : paths) {
                if (p == null || p.isBlank()) continue;
                resolver.mappingByBranchDept.putIfAbsent(l1 + "\u0001" + p.trim(), m);
            }
        }
        return resolver;
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
            // 模板简化为 3 列：号码、一级分行、备注（分摊部门/机构代码/成本中心自动匹配；分机号/部门全路径自动匹配）
            String[] headers = {"号码", "一级分行", "备注"};
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
        boolean isImport = "import".equalsIgnoreCase(source);

        if (isImport) {
            // 号码分摊机构 Tab：导入 ALLOC-ORG-/推送 COMP-/BRN- 批次（排除 PUSH- 例外批次）
            if (scopeBranch == SCOPE_ALL) {
                batches = hasMonth
                        ? batchRepo.findByBillingMonthAndSourceImport(billingMonth)
                        : batchRepo.findBySourceImport();
            } else if (scopeBranch == null) {
                batches = List.of();
            } else {
                batches = hasMonth
                        ? batchRepo.findByBillingMonthAndSourceImportAndBranchOrgId(billingMonth, scopeBranch)
                        : batchRepo.findBySourceImportAndBranchOrgId(scopeBranch);
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
        boolean isImport = "import".equalsIgnoreCase(source);
        boolean isException = "exception".equalsIgnoreCase(source);

        if (isImport) {
            if (scopeBranch == SCOPE_ALL) {
                months = batchRepo.findDistinctBillingMonthsBySourceImport();
            } else if (scopeBranch == null) {
                months = List.of();
            } else {
                months = batchRepo.findDistinctBillingMonthsBySourceImportAndBranchOrgId(scopeBranch);
            }
        } else if (isException) {
            // 例外号码清单：仅取 PUSH-EXC- 推送批次所在月份
            if (scopeBranch == SCOPE_ALL) {
                months = batchRepo.findDistinctBillingMonthsBySourceException();
            } else if (scopeBranch == null) {
                months = List.of();
            } else {
                months = batchRepo.findDistinctBillingMonthsBySourceExceptionAndBranchOrgId(scopeBranch);
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

        // 批次所属月份：用于实时匹配同月通讯录与分摊机构对照表
        AllocationOrgBatch batch = batchRepo.findByIdAndDeletedAtIsNull(batchId).orElse(null);
        String batchMonth = batch != null ? batch.getBillingMonth() : null;
        AllocationOrgMatchResolver resolver = batchMonth != null && !batchMonth.isBlank()
                ? buildMatchResolver(batchMonth)
                : null;

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
            // 实时匹配：分机号/部门全路径来自同月通讯录，分摊部门/机构代码/成本中心取自分摊机构对照表（匹配不到为空）
            Map<String, String> matched = resolver != null
                    ? resolver.resolve(entry.getPhoneNumber(), entry.getL1Branch())
                    : emptyMatch();
            e.put("extension", matched.get("extension"));
            e.put("dept_path", matched.get("dept_path"));
            e.put("alloc_dept", matched.get("alloc_dept"));
            e.put("org_code", matched.get("org_code"));
            e.put("cost_center", matched.get("cost_center"));
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

    /**
     * 按月份查询号码分摊机构明细（分页 + 搜索）
     * source=import：号码分摊机构 Tab（导入 ALLOC-ORG-/推送 COMP-/BRN- 批次），实时匹配同月通讯录与对照表
     * source=exception：例外号码清单 Tab（PUSH-EXC- 批次），仅展示与上一自然月通讯录无差异的号码
     * source=exception-diff：差异数据 Tab，仅展示与上一自然月通讯录有差异的号码（含原值 vs 上月值对比）
     */
    @GetMapping("/entries-by-month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listEntriesByMonth(
            @RequestParam("billing_month") String billingMonth,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("role") Byte role) {
        size = Math.min(size, 200);
        var pageable = PageRequest.of(page, size);
        boolean hasSearch = search != null && !search.isBlank();
        String keyword = hasSearch ? search.trim() : "";
        Long scopeBranch = resolveScopeBranchOrg(role, userId);
        boolean isImport = "import".equalsIgnoreCase(source);
        boolean isException = "exception".equalsIgnoreCase(source) || "exception-diff".equalsIgnoreCase(source);
        boolean isDiffOnly = "exception-diff".equalsIgnoreCase(source);

        if (isException) {
            // 例外号码清单/差异数据：全量加载后与上一自然月通讯录对比，内存分页
            return ResponseEntity.ok(ApiResponse.ok(
                    buildExceptionDiffResult(billingMonth, keyword, page, size, scopeBranch, isDiffOnly)));
        }

        Page<AllocationOrgEntry> pageResult;

        if (isImport) {
            // 号码分摊机构 Tab（含导入/COMP-/BRN- 推送批次）
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
        // 实时匹配：仅对 import 来源生效
        AllocationOrgMatchResolver resolver = isImport
                ? buildMatchResolver(billingMonth)
                : null;
        for (AllocationOrgEntry entry : pageResult.getContent()) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entry.getId());
            e.put("batch_id", entry.getBatchId());
            e.put("phone_number", entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
            e.put("l1_branch", entry.getL1Branch() != null ? entry.getL1Branch() : "");
            if (isImport) {
                Map<String, String> matched = resolver.resolve(entry.getPhoneNumber(), entry.getL1Branch());
                e.put("extension", matched.get("extension"));
                e.put("dept_path", matched.get("dept_path"));
                e.put("alloc_dept", matched.get("alloc_dept"));
                e.put("org_code", matched.get("org_code"));
                e.put("cost_center", matched.get("cost_center"));
            } else {
                e.put("alloc_dept", entry.getAllocDept() != null ? entry.getAllocDept() : "");
                e.put("org_code", entry.getOrgCode() != null ? entry.getOrgCode() : "");
                e.put("cost_center", entry.getCostCenter() != null ? entry.getCostCenter() : "");
            }
            e.put("remark", entry.getRemark() != null ? entry.getRemark() : "");
            entries.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", entries);
        result.put("total", pageResult.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ==================== Exception list vs 上月通讯录对比 ====================

    /** 例外清单条目与上月通讯录的对比结果 */
    private static class ExceptionDiffItem {
        String id;                 // entry id
        String phoneNumber;
        String username;           // 清单中的用户名称
        String extension;          // 清单中的分机号
        String deptPath;           // 清单中的部门全路径
        String l1Branch;
        String remark;
        List<String> changedCols;  // 差异列（用户名称/分机号/部门全路径/上月通讯录未找到）
        String prevUsername;       // 上月通讯录值
        String prevExtension;
        String prevDeptPath;
    }

    /**
     * 构建例外号码清单/差异数据查询结果：
     * 例外清单（PUSH-EXC- 批次）按号码与上一自然月通讯录对比：
     * - 比较用户名称、分机号、部门全路径三项
     * - 清单 Tab（diffOnly=false）：仅返回无差异条目（有差异的移入差异数据 Tab）
     * - 差异 Tab（diffOnly=true）：仅返回有差异条目，附上月值与差异列
     */
    private Map<String, Object> buildExceptionDiffResult(String billingMonth, String keyword,
                                                          int page, int size, Long scopeBranch, boolean diffOnly) {
        // 1. 加载例外清单条目（PUSH-EXC-）
        List<AllocationOrgEntry> entries;
        if (scopeBranch == SCOPE_ALL) {
            entries = entryRepo.findAllByBillingMonthAndSourceException(billingMonth);
        } else if (scopeBranch == null) {
            entries = List.of();
        } else {
            entries = entryRepo.findAllByBillingMonthAndSourceExceptionAndBranchOrgId(billingMonth, scopeBranch);
        }

        // 2. 加载上一自然月通讯录（按号码聚合）
        String prevMonth = previousNaturalMonth(billingMonth);
        Map<String, String[]> prevByPhone = loadDirectoryByPhone(prevMonth);

        // 3. 逐条对比
        List<ExceptionDiffItem> items = new ArrayList<>();
        for (AllocationOrgEntry entry : entries) {
            ExceptionDiffItem item = new ExceptionDiffItem();
            item.id = String.valueOf(entry.getId());
            item.phoneNumber = entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "";
            item.username = entry.getUsername() != null ? entry.getUsername() : "";
            item.extension = entry.getExtension() != null ? entry.getExtension() : "";
            item.deptPath = entry.getDeptPath() != null ? entry.getDeptPath() : "";
            item.l1Branch = entry.getL1Branch() != null ? entry.getL1Branch() : "";
            item.remark = entry.getRemark() != null ? entry.getRemark() : "";

            String[] prev = prevByPhone.get(item.phoneNumber.trim());
            List<String> changedCols = new ArrayList<>();
            if (prev == null) {
                changedCols.add("上月通讯录未找到");
                item.prevUsername = "";
                item.prevExtension = "";
                item.prevDeptPath = "";
            } else {
                item.prevUsername = prev[0];
                item.prevExtension = prev[1];
                item.prevDeptPath = prev[2];
                if (!item.username.trim().equals(item.prevUsername)) changedCols.add("用户名称");
                if (!item.extension.trim().equals(item.prevExtension)) changedCols.add("分机号");
                if (!item.deptPath.trim().equals(item.prevDeptPath)) changedCols.add("部门全路径");
            }
            item.changedCols = changedCols;

            boolean hasDiff = !changedCols.isEmpty();
            if (diffOnly == hasDiff) {
                items.add(item);
            }
        }

        // 4. 关键词过滤（号码/用户名称/分机号/部门全路径）
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            items = items.stream().filter(i ->
                    i.phoneNumber.toLowerCase().contains(kw)
                            || i.username.toLowerCase().contains(kw)
                            || i.extension.toLowerCase().contains(kw)
                            || i.deptPath.toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }

        // 5. 内存分页
        int start = page * size;
        List<ExceptionDiffItem> pageItems = (start < items.size())
                ? items.subList(start, Math.min(start + size, items.size()))
                : List.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExceptionDiffItem item : pageItems) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", item.id);
            e.put("phone_number", item.phoneNumber);
            e.put("username", item.username);
            e.put("extension", item.extension);
            e.put("dept_path", item.deptPath);
            e.put("l1_branch", item.l1Branch);
            e.put("remark", item.remark);
            e.put("prev_username", item.prevUsername);
            e.put("prev_extension", item.prevExtension);
            e.put("prev_dept_path", item.prevDeptPath);
            e.put("changed_columns", item.changedCols);
            e.put("has_diff", !item.changedCols.isEmpty());
            e.put("prev_month", prevMonth);
            rows.add(e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", rows);
        result.put("total", (long) items.size());
        result.put("page", page);
        result.put("size", size);
        result.put("prev_month", prevMonth);
        return result;
    }

    /** 计算上一自然月（YYYY-MM 格式） */
    private String previousNaturalMonth(String billingMonth) {
        try {
            YearMonth ym = YearMonth.parse(billingMonth);
            return ym.minusMonths(1).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 加载指定月份通讯录，按号码聚合 username/extension/dept_path（多值「、」拼接）
     * 返回 phone → [username, extension, dept_path]
     */
    private Map<String, String[]> loadDirectoryByPhone(String month) {
        Map<String, LinkedHashSet<String>> nameByPhone = new HashMap<>();
        Map<String, LinkedHashSet<String>> extByPhone = new HashMap<>();
        Map<String, LinkedHashSet<String>> deptByPhone = new HashMap<>();
        if (month != null && !month.isBlank()) {
            List<DirectoryEntry> dirEntries = directoryEntryRepo.findByBillingMonth(month);
            for (DirectoryEntry d : dirEntries) {
                String pn = d.getPhoneNumber() != null ? d.getPhoneNumber().trim() : "";
                if (pn.isEmpty()) continue;
                if (d.getUsername() != null && !d.getUsername().isBlank()) {
                    nameByPhone.computeIfAbsent(pn, k -> new LinkedHashSet<>()).add(d.getUsername().trim());
                }
                if (d.getExtension() != null && !d.getExtension().isBlank()) {
                    extByPhone.computeIfAbsent(pn, k -> new LinkedHashSet<>()).add(d.getExtension().trim());
                }
                if (d.getDeptPath() != null && !d.getDeptPath().isBlank()) {
                    deptByPhone.computeIfAbsent(pn, k -> new LinkedHashSet<>()).add(d.getDeptPath().trim());
                }
            }
        }
        Map<String, String[]> result = new HashMap<>();
        Set<String> phones = new LinkedHashSet<>();
        phones.addAll(nameByPhone.keySet());
        phones.addAll(extByPhone.keySet());
        phones.addAll(deptByPhone.keySet());
        for (String pn : phones) {
            String names = String.join("、", nameByPhone.getOrDefault(pn, new LinkedHashSet<>()));
            String exts = String.join("、", extByPhone.getOrDefault(pn, new LinkedHashSet<>()));
            String depts = String.join("、", deptByPhone.getOrDefault(pn, new LinkedHashSet<>()));
            result.put(pn, new String[]{names, exts, depts});
        }
        return result;
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
    @Transactional
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
        boolean isImport = "import".equalsIgnoreCase(source);
        boolean isException = "exception".equalsIgnoreCase(source) || "exception-diff".equalsIgnoreCase(source);
        boolean isDiffOnly = "exception-diff".equalsIgnoreCase(source);

        // 例外号码清单/差异数据导出（附上月对比列）
        if (isException) {
            Map<String, Object> data = buildExceptionDiffResult(billingMonth, null, 0, Integer.MAX_VALUE, scopeBranch, isDiffOnly);
            return exportExceptionList(billingMonth, isDiffOnly, data);
        }

        List<AllocationOrgEntry> entries;

        if (isImport) {
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

            // 导出列：号码、分机号、部门全路径、一级分行、分摊部门、机构代码、成本中心、备注
            // import 来源：分机号/部门全路径来自同月通讯录实时匹配；分摊部门/机构代码/成本中心取自分摊机构对照表（匹配不到为空）
            // 非来源（全量）：保持原值
            String[] headers = {"号码", "分机号", "部门全路径", "一级分行", "分摊部门", "机构代码", "成本中心", "备注"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            AllocationOrgMatchResolver resolver = isImport ? buildMatchResolver(billingMonth) : null;
            int rowIdx = 1;
            for (AllocationOrgEntry entry : entries) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getPhoneNumber() != null ? entry.getPhoneNumber() : "");
                if (isImport) {
                    Map<String, String> matched = resolver.resolve(entry.getPhoneNumber(), entry.getL1Branch());
                    row.createCell(1).setCellValue(matched.get("extension"));
                    row.createCell(2).setCellValue(matched.get("dept_path"));
                    row.createCell(3).setCellValue(entry.getL1Branch() != null ? entry.getL1Branch() : "");
                    row.createCell(4).setCellValue(matched.get("alloc_dept"));
                    row.createCell(5).setCellValue(matched.get("org_code"));
                    row.createCell(6).setCellValue(matched.get("cost_center"));
                    row.createCell(7).setCellValue(entry.getRemark() != null ? entry.getRemark() : "");
                } else {
                    row.createCell(1).setCellValue(entry.getExtension() != null ? entry.getExtension() : "");
                    row.createCell(2).setCellValue(entry.getDeptPath() != null ? entry.getDeptPath() : "");
                    row.createCell(3).setCellValue(entry.getL1Branch() != null ? entry.getL1Branch() : "");
                    row.createCell(4).setCellValue(entry.getAllocDept() != null ? entry.getAllocDept() : "");
                    row.createCell(5).setCellValue(entry.getOrgCode() != null ? entry.getOrgCode() : "");
                    row.createCell(6).setCellValue(entry.getCostCenter() != null ? entry.getCostCenter() : "");
                    row.createCell(7).setCellValue(entry.getRemark() != null ? entry.getRemark() : "");
                }
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

    /**
     * 例外号码清单/差异数据导出（附上月对比列）
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<byte[]> exportExceptionList(String billingMonth, boolean diffOnly, Map<String, Object> data) {
        List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
        String prevMonth = String.valueOf(data.getOrDefault("prev_month", ""));

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String sheetName = diffOnly ? "差异数据" : "例外号码清单";
            Sheet sheet = wb.createSheet(sheetName);

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = diffOnly
                    ? new String[]{"号码", "用户名称", "分机号", "部门全路径", "一级分行",
                    "上月用户名称(" + prevMonth + ")", "上月分机号", "上月部门全路径", "差异列", "备注"}
                    : new String[]{"号码", "用户名称", "分机号", "部门全路径", "一级分行", "备注"};
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
                row.createCell(0).setCellValue(strOrEmpty(e.get("phone_number")));
                row.createCell(1).setCellValue(strOrEmpty(e.get("username")));
                row.createCell(2).setCellValue(strOrEmpty(e.get("extension")));
                row.createCell(3).setCellValue(strOrEmpty(e.get("dept_path")));
                row.createCell(4).setCellValue(strOrEmpty(e.get("l1_branch")));
                if (diffOnly) {
                    row.createCell(5).setCellValue(strOrEmpty(e.get("prev_username")));
                    row.createCell(6).setCellValue(strOrEmpty(e.get("prev_extension")));
                    row.createCell(7).setCellValue(strOrEmpty(e.get("prev_dept_path")));
                    Object cols = e.get("changed_columns");
                    String colsStr = (cols instanceof List)
                            ? String.join(",", ((List<?>) cols).stream().map(String::valueOf).toArray(String[]::new))
                            : "";
                    row.createCell(8).setCellValue(colsStr);
                    row.createCell(9).setCellValue(strOrEmpty(e.get("remark")));
                } else {
                    row.createCell(5).setCellValue(strOrEmpty(e.get("remark")));
                }
            }

            wb.write(out);
            String fileTitle = diffOnly ? "差异数据导出_" : "例外号码清单导出_";
            String fileName = URLEncoder.encode(fileTitle + billingMonth + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("导出失败", e);
        }
    }

    private String strOrEmpty(Object v) {
        return v != null ? String.valueOf(v) : "";
    }
}
