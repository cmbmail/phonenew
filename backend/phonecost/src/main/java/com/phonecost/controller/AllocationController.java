package com.phonecost.controller;

import com.phonecost.domain.*;
import com.phonecost.dto.*;
import com.phonecost.repository.*;
import com.phonecost.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 费用分摊Controller
 * 分摊计算 + 确认/撤回 + 导出
 * 支持按角色数据范围过滤
 */
@RestController
@RequestMapping("/allocation")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AllocationController {

    private final AllocationService allocationService;
    private final AllocationConfirmService confirmService;
    private final AllocationExportService exportService;
    private final BranchBillExportService branchBillExportService;
    private final AllocationAdjustService adjustService;
    private final AuditLogService auditLogService;
    private final AllocationResultRepository resultRepository;
    private final BillBatchRepository billBatchRepository;
    private final DataScopeService dataScopeService;
    private final SysUserRepository userRepository;
    private final SysOrganizationRepository orgRepository;
    private final OwnershipMatchService ownershipMatchService;
    private final PhoneOwnershipBatchRepository ownershipBatchRepository;
    private final DirectoryBatchRepository directoryBatchRepository;
    private final DataSnapshotRepository dataSnapshotRepository;
    private final FeeAnalysisService feeAnalysisService;

    // ==================== 分摊计算 ====================

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculate(
            @Valid @RequestBody AllocationCalculateRequest req,
            @RequestAttribute("userId") Long userId) {
        Long billBatchId = req.getBillBatchId();
        Long ownershipBatchId = req.getOwnershipBatchId();
        Long directoryBatchId = req.getDirectoryBatchId();
        Long allocationDeptBatchId = req.getAllocationDeptBatchId();

        // If not explicitly provided, try to load from existing DataSnapshot
        if (ownershipBatchId == null || directoryBatchId == null || allocationDeptBatchId == null) {
            var existingSnapshot = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId);
            if (existingSnapshot.isPresent()) {
                var snap = existingSnapshot.get();
                if (ownershipBatchId == null) ownershipBatchId = snap.getOwnershipBatchId();
                if (directoryBatchId == null) directoryBatchId = snap.getDirectoryBatchId();
                if (allocationDeptBatchId == null) allocationDeptBatchId = snap.getAllocationDeptBatchId();
            }
        }

        // Step 1: Run ownership matching with the specified (or snapshot) batches
        int matchedCount = ownershipMatchService.matchOwnershipForBillBatch(
                billBatchId, ownershipBatchId, directoryBatchId, allocationDeptBatchId);

        // Step 2: Save/update DataSnapshot
        var existingSnap = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(billBatchId);
        DataSnapshot snapshot;
        if (existingSnap.isPresent()) {
            snapshot = existingSnap.get();
            snapshot.setOwnershipBatchId(ownershipBatchId);
            snapshot.setDirectoryBatchId(directoryBatchId);
            snapshot.setAllocationDeptBatchId(allocationDeptBatchId);
            snapshot.setMatchedCount(matchedCount);
        } else {
            snapshot = DataSnapshot.builder()
                    .billBatchId(billBatchId)
                    .ownershipBatchId(ownershipBatchId)
                    .directoryBatchId(directoryBatchId)
                    .allocationDeptBatchId(allocationDeptBatchId)
                    .matchedCount(matchedCount)
                    .build();
        }
        dataSnapshotRepository.save(snapshot);

        // Step 3: Calculate allocation based on matched results
        List<AllocationResult> results = allocationService.calculateAllocation(billBatchId);

        auditLogService.log(userId, "ALLOCATION_CALCULATE", "bill_batch", billBatchId,
                Map.of("org_count", results.size(), "matched_count", matchedCount,
                        "ownership_batch_id", ownershipBatchId != null ? ownershipBatchId : 0L,
                        "directory_batch_id", directoryBatchId != null ? directoryBatchId : 0L,
                        "allocation_dept_batch_id", allocationDeptBatchId != null ? allocationDeptBatchId : 0L));

        Map<String, Object> calcResult = new HashMap<>();
        calcResult.put("bill_batch_id", billBatchId);
        calcResult.put("org_count", results.size());
        calcResult.put("matched_count", matchedCount);
        calcResult.put("ownership_batch_id", ownershipBatchId);
        calcResult.put("directory_batch_id", directoryBatchId);
        calcResult.put("allocation_dept_batch_id", allocationDeptBatchId);

        return ResponseEntity.ok(ApiResponse.ok(calcResult));
    }

    // ==================== 查询结果 ====================

    @GetMapping("/snapshot/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSnapshot(
            @PathVariable Long batchId) {
        // Return snapshot info for a bill batch, plus available ownership/directory batches
        var snapshot = dataSnapshotRepository.findByBillBatchIdAndDeletedAtIsNull(batchId);
        Map<String, Object> result = new HashMap<>();
        if (snapshot.isPresent()) {
            DataSnapshot snap = snapshot.get();
            result.put("ownership_batch_id", snap.getOwnershipBatchId());
            result.put("directory_batch_id", snap.getDirectoryBatchId());
            result.put("allocation_dept_batch_id", snap.getAllocationDeptBatchId());
            result.put("matched_count", snap.getMatchedCount());
        }
        // Available batches for selection
        result.put("ownership_batches", ownershipBatchRepository.findByDeletedAtIsNull());
        result.put("directory_batches", directoryBatchRepository.findByDeletedAtIsNull());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/results/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getResults(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size,
            @RequestAttribute("userId") Long userId) {
        // Cap page size to prevent OOM
        if (size > 500) size = 500;
        if (size < 1) size = 200;

        DataScope scope = dataScopeService.getDataScope(userId);
        Pageable pageable = PageRequest.of(page, size);
        Page<AllocationResult> resultPage;

        if (scope.isAllScope()) {
            resultPage = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId, pageable);
        } else {
            List<Long> visibleIds = scope.getVisibleOrgIds();
            if (visibleIds == null || visibleIds.isEmpty()) {
                resultPage = Page.empty(pageable);
            } else {
                resultPage = resultRepository.findByBatchIdAndOrgIdInAndDeletedAtIsNull(batchId, visibleIds, pageable);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", resultPage.getContent());
        response.put("total_elements", resultPage.getTotalElements());
        response.put("total_pages", resultPage.getTotalPages());
        response.put("page", resultPage.getNumber());
        response.put("size", resultPage.getSize());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<BillBatch>>> listBatches(
            @RequestParam(value = "billing_month", required = false) String billingMonth,
            @RequestAttribute("userId") Long userId) {
        if (billingMonth != null && !billingMonth.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(billBatchRepository.findByBillingMonthAndDeletedAtIsNull(billingMonth)));
        }
        return ResponseEntity.ok(ApiResponse.ok(billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc()));
    }

    // ==================== 确认/撤回 ====================

    @PostMapping("/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @Valid @RequestBody AllocationConfirmRequest req,
            @RequestAttribute("userId") Long userId) {
        Long batchId = req.getBatchId();
        Long orgId = req.getOrgId();

        // 校验数据范围：分行管理员只能确认自己管辖范围内的组织
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(orgId)) {
            throw new IllegalArgumentException("无权操作该组织的分摊数据");
        }

        AllocationResult result = confirmService.confirm(batchId, orgId, userId);
        auditLogService.log(userId, "ALLOCATION_CONFIRM", "allocation_result", result.getId(),
                Map.of("batch_id", batchId, "org_id", orgId));
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_id", result.getOrgId(),
                "confirm_status", (int) result.getConfirmStatus()
        )));
    }

    @PostMapping("/confirm-all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmAll(
            @Valid @RequestBody AllocationConfirmAllRequest req,
            @RequestAttribute("userId") Long userId) {
        Long batchId = req.getBatchId();

        // 分行管理员只确认自己范围内的
        DataScope scope = dataScopeService.getDataScope(userId);
        int count = confirmService.confirmAllInScope(batchId, userId, scope);
        auditLogService.log(userId, "ALLOCATION_CONFIRM_ALL", "bill_batch", batchId,
                Map.of("confirmed_count", count));
        return ResponseEntity.ok(ApiResponse.ok(Map.of("confirmed_count", count)));
    }

    @PostMapping("/withdraw")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> withdraw(
            @Valid @RequestBody AllocationWithdrawRequest req,
            @RequestAttribute("userId") Long userId) {
        Long batchId = req.getBatchId();
        Long orgId = req.getOrgId();
        String reason = req.getReason();

        // 校验数据范围：分行管理员只能撤回自己管辖范围内的组织
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(orgId)) {
            throw new IllegalArgumentException("无权操作该组织的分摊数据");
        }

        List<AllocationResult> results = confirmService.withdraw(batchId, orgId, userId, reason);
        auditLogService.log(userId, "ALLOCATION_WITHDRAW", "bill_batch", batchId,
                Map.of("org_id", orgId, "result_count", results.size()));
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_id", orgId,
                "result_count", results.size()
        )));
    }

    // ==================== 费用调整 ====================

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<ApiResponse<AllocationAdjustment>> adjust(
            @Valid @RequestBody AllocationAdjustRequest req,
            @RequestAttribute("userId") Long userId) {
        Long batchId = req.getBatchId();
        String phoneNumber = req.getPhoneNumber();
        Long fromOrgId = req.getFromOrgId();
        Long toOrgId = req.getToOrgId();
        String reason = req.getReason();

        // 校验数据范围：from/to 组织至少一个在管辖范围内
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(fromOrgId) && !scope.isOrgVisible(toOrgId)) {
            throw new IllegalArgumentException("调整涉及的组织不在您的管辖范围内");
        }

        AllocationAdjustment adjustment = adjustService.adjust(
                batchId, phoneNumber, fromOrgId, toOrgId, reason, userId);
        auditLogService.log(userId, "ALLOCATION_ADJUST", "allocation_adjustment", adjustment.getId(),
                Map.of("phone_number", phoneNumber, "from_org_id", fromOrgId, "to_org_id", toOrgId));
        return ResponseEntity.ok(ApiResponse.ok(adjustment));
    }

    @GetMapping("/adjustments/{batchId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<List<AllocationAdjustment>>> listAdjustments(
            @PathVariable Long batchId,
            @RequestAttribute("userId") Long userId) {
        List<AllocationAdjustment> adjustments = adjustService.listAdjustments(batchId);
        return ResponseEntity.ok(ApiResponse.ok(adjustments));
    }

    // ==================== 导出 ====================

    @GetMapping("/export/summary")
    public ResponseEntity<byte[]> exportSummary(
            @RequestParam Long batchId,
            @RequestParam(required = false) Long branchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        Long effectiveBranchOrgId = resolveEffectiveBranchOrgId(branchOrgId, userId);

        byte[] data = exportService.exportSummary(batchId, effectiveBranchOrgId);
        java.util.HashMap<String, Object> summaryDetail = new java.util.HashMap<>();
        summaryDetail.put("branch_org_id", effectiveBranchOrgId);
        auditLogService.log(userId, "EXPORT_SUMMARY", "bill_batch", batchId, summaryDetail);
        String filename = java.net.URLEncoder.encode(
                "分行费用分摊汇总_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @GetMapping("/export/detail")
    public ResponseEntity<byte[]> exportDetail(
            @RequestParam Long batchId,
            @RequestParam(required = false) Long branchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        Long effectiveBranchOrgId = resolveEffectiveBranchOrgId(branchOrgId, userId);

        byte[] data = exportService.exportDetail(batchId, effectiveBranchOrgId);
        java.util.HashMap<String, Object> detailMap = new java.util.HashMap<>();
        detailMap.put("branch_org_id", effectiveBranchOrgId);
        auditLogService.log(userId, "EXPORT_DETAIL", "bill_batch", batchId, detailMap);
        String detailFilename = java.net.URLEncoder.encode(
                "分行费用分摊明细_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + detailFilename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    // ==================== 分行成本中心对照表导出 ====================

    @GetMapping("/export/cost-center-mapping")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportCostCenterMapping(
            @RequestParam Long batchId,
            @RequestParam(required = false) Long branchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        Long effectiveBranchOrgId = resolveEffectiveBranchOrgId(branchOrgId, userId);

        byte[] data = branchBillExportService.exportCostCenterMapping(batchId, effectiveBranchOrgId, userId);
        java.util.HashMap<String, Object> ccmDetail = new java.util.HashMap<>();
        ccmDetail.put("branch_org_id", effectiveBranchOrgId);
        auditLogService.log(userId, "EXPORT_COST_CENTER_MAPPING", "bill_batch", batchId, ccmDetail);
        String filename = java.net.URLEncoder.encode(
                "分行成本中心对照表_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    // ==================== 三级分摊导出 ====================

    /**
     * L1 分摊汇总数据（JSON，供前端表格展示）
     * 数据源：分摊号码归属（phone_ownership_entry），按 l1_branch 聚合
     */
    @GetMapping("/l1-summary-data")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getL1SummaryData(
            @RequestParam Long batchId,
            @RequestAttribute("userId") Long userId) {
        List<Map<String, Object>> data = branchBillExportService.getL1SummaryDataByOwnership(batchId);
        // 按数据范围过滤：分行管理员只看管辖分行
        if (!data.isEmpty()) {
            DataScope scope = dataScopeService.getDataScope(userId);
            if (!scope.isAllScope()) {
                Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
                if (visibleBranchNames != null) {
                    data = data.stream()
                            .filter(row -> {
                                Object l1Branch = row.get("l1_branch");
                                if (l1Branch == null) return true;
                                return visibleBranchNames.contains(l1Branch.toString());
                            })
                            .toList();
                }
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * L1 分摊明细数据（JSON，供前端分摊明细4个Tab展示）
     * 数据源：分摊号码归属，按号码匹配 bill_detail
     * sheetType: CALL / RECORDING / CRBT / FLASH_MSG
     */
    @GetMapping("/l1-detail")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getL1DetailData(
            @RequestParam Long batchId,
            @RequestParam String sheetType,
            @RequestAttribute("userId") Long userId) {
        List<Map<String, Object>> data = branchBillExportService.getL1DetailDataByOwnership(batchId, sheetType);
        if (!data.isEmpty()) {
            DataScope scope = dataScopeService.getDataScope(userId);
            if (!scope.isAllScope()) {
                Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
                if (visibleBranchNames != null) {
                    data = data.stream()
                            .filter(row -> {
                                Object l1Branch = row.get("l1_branch");
                                if (l1Branch == null || l1Branch.toString().isBlank()) return true;
                                return visibleBranchNames.contains(l1Branch.toString());
                            })
                            .toList();
                }
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * L2 一级分行汇总数据（JSON，按 l2_branch 聚合）
     * 数据源：分摊号码归属
     */
    @GetMapping("/l2-summary-data")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getL2SummaryData(
            @RequestParam Long batchId,
            @RequestParam String l1Branch,
            @RequestAttribute("userId") Long userId) {
        List<Map<String, Object>> data = branchBillExportService.getL2SummaryDataByOwnership(batchId, l1Branch);
        // 权限过滤：非全量用户只能看本分行
        if (!data.isEmpty()) {
            DataScope scope = dataScopeService.getDataScope(userId);
            if (!scope.isAllScope()) {
                Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
                if (visibleBranchNames != null && !visibleBranchNames.contains(l1Branch)) {
                    data = List.of();
                }
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * L2 一级分行分摊明细数据（JSON，供前端分摊明细4个Tab展示）
     * 数据源：分摊号码归属，按 l1_branch 过滤
     */
    @GetMapping("/l2-detail")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getL2DetailData(
            @RequestParam Long batchId,
            @RequestParam String l1Branch,
            @RequestParam String sheetType,
            @RequestAttribute("userId") Long userId) {
        // 权限校验：非全量用户只能看本分行
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isAllScope()) {
            Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
            if (visibleBranchNames != null && !visibleBranchNames.contains(l1Branch)) {
                return ResponseEntity.ok(ApiResponse.ok(List.of()));
            }
        }
        List<Map<String, Object>> data = branchBillExportService.getL2DetailDataByOwnership(batchId, l1Branch, sheetType);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * L3 二级分行汇总数据（JSON，按 alloc_dept 聚合）
     * 数据源：分摊号码归属
     */
    @GetMapping("/l3-summary-data")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getL3SummaryData(
            @RequestParam Long batchId,
            @RequestParam String l1Branch,
            @RequestParam String l2Branch,
            @RequestAttribute("userId") Long userId) {
        List<Map<String, Object>> data = branchBillExportService.getL3SummaryDataByOwnership(batchId, l1Branch, l2Branch);
        // 权限过滤
        if (!data.isEmpty()) {
            DataScope scope = dataScopeService.getDataScope(userId);
            if (!scope.isAllScope()) {
                Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
                if (visibleBranchNames != null && !visibleBranchNames.contains(l1Branch)) {
                    data = List.of();
                }
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * L3 二级分行分摊明细数据（JSON，供前端分摊明细4个Tab展示）
     * 数据源：分摊号码归属，按 l1_branch + l2_branch 过滤
     */
    @GetMapping("/l3-detail")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getL3DetailData(
            @RequestParam Long batchId,
            @RequestParam String l1Branch,
            @RequestParam String l2Branch,
            @RequestParam String sheetType,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isAllScope()) {
            Set<String> visibleBranchNames = resolveVisibleL1BranchNames(userId);
            if (visibleBranchNames != null && !visibleBranchNames.contains(l1Branch)) {
                return ResponseEntity.ok(ApiResponse.ok(List.of()));
            }
        }
        List<Map<String, Object>> data = branchBillExportService.getL3DetailDataByOwnership(batchId, l1Branch, l2Branch, sheetType);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * Resolve visible l1_branch names for non-admin users based on their orgId.
     * Returns null for admin/finance (all visible).
     */
    private Set<String> resolveVisibleL1BranchNames(Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (scope.isAllScope()) return null;
        // Find the user's orgId, then walk up to find type=2 (一级分行) name
        List<Long> visibleOrgIds = scope.getVisibleOrgIds();
        if (visibleOrgIds == null || visibleOrgIds.isEmpty()) return Set.of();
        Map<Long, SysOrganization> orgMap = orgRepository.findByDeletedAtIsNull().stream()
                .collect(java.util.stream.Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));
        Set<String> names = new java.util.HashSet<>();
        for (Long orgId : visibleOrgIds) {
            SysOrganization org = orgMap.get(orgId);
            if (org != null && org.getType() != null && org.getType() == 2) {
                names.add(org.getName());
            }
        }
        // Also resolve from singleOrgId or path prefix
        if (scope.getSingleOrgId() != null) {
            SysOrganization org = orgMap.get(scope.getSingleOrgId());
            if (org != null && org.getPath() != null) {
                String[] segments = org.getPath().split("/");
                for (int i = segments.length - 1; i >= 0; i--) {
                    if (segments[i].isEmpty()) continue;
                    Long segId = Long.parseLong(segments[i]);
                    SysOrganization ancestor = orgMap.get(segId);
                    if (ancestor != null && ancestor.getType() != null && ancestor.getType() == 2) {
                        names.add(ancestor.getName());
                        break;
                    }
                }
            }
        }
        return names;
    }

    /**
     * L1 分摊汇总：集团 → 一级分行
     * 每个一级分行一行，汇总其所有下属费用
     */
    @GetMapping("/export/l1-summary")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<byte[]> exportL1Summary(
            @RequestParam Long batchId,
            @RequestAttribute("userId") Long userId) throws Exception {
        byte[] data = branchBillExportService.exportLevel1Summary(batchId, userId);
        auditLogService.log(userId, "EXPORT_L1_SUMMARY", "bill_batch", batchId,
                Map.of("module", "L1_summary"));
        String filename = java.net.URLEncoder.encode(
                "集团分摊汇总_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    /**
     * L2 一级分行明细：一级分行 → 直属下级（二级分行+部门+支行）
     */
    @GetMapping("/export/l2-branch-detail")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<byte[]> exportL2BranchDetail(
            @RequestParam Long batchId,
            @RequestParam Long branchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        Long effectiveBranchOrgId = resolveEffectiveBranchOrgId(branchOrgId, userId);
        byte[] data = branchBillExportService.exportLevel2BranchDetail(batchId, effectiveBranchOrgId, userId);
        auditLogService.log(userId, "EXPORT_L2_BRANCH_DETAIL", "bill_batch", batchId,
                Map.of("branch_org_id", effectiveBranchOrgId));
        SysOrganization org = orgRepository.findByIdAndDeletedAtIsNull(effectiveBranchOrgId).orElse(null);
        String name = org != null ? org.getName() : "branch";
        String filename = java.net.URLEncoder.encode(
                name + "_分摊明细_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    /**
     * L3 二级分行明细：二级分行 → 下属部门+支行
     */
    @GetMapping("/export/l3-sub-branch-detail")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH', 'ROLE_DEPARTMENT')")
    public ResponseEntity<byte[]> exportL3SubBranchDetail(
            @RequestParam Long batchId,
            @RequestParam Long subBranchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        byte[] data = branchBillExportService.exportLevel3SubBranchDetail(batchId, subBranchOrgId, userId);
        auditLogService.log(userId, "EXPORT_L3_SUB_BRANCH_DETAIL", "bill_batch", batchId,
                Map.of("sub_branch_org_id", subBranchOrgId));
        SysOrganization org = orgRepository.findByIdAndDeletedAtIsNull(subBranchOrgId).orElse(null);
        String name = org != null ? org.getName() : "sub_branch";
        String filename = java.net.URLEncoder.encode(
                name + "_下属分摊_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    /** Resolve effective branch org ID based on user's data scope
     *  Always returns a 一级分行 (type=2) org ID for L2 page usage
     *  Optimized: uses orgMap cache instead of N+1 findById per path segment
     */
    private Long resolveEffectiveBranchOrgId(Long branchOrgId, Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (scope.isAllScope()) return branchOrgId;

        // Build orgMap once for this method call
        Map<Long, SysOrganization> orgMap = orgRepository.findByDeletedAtIsNull().stream()
                .collect(java.util.stream.Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        if (scope.getSingleOrgId() != null) {
            // 单组织范围：从该组织path向上查找一级分行
            SysOrganization org = orgMap.get(scope.getSingleOrgId());
            if (org != null && org.getPath() != null) {
                String[] segments = org.getPath().split("/");
                for (int i = segments.length - 1; i >= 0; i--) {
                    if (segments[i].isEmpty()) continue;
                    Long segId = Long.parseLong(segments[i]);
                    SysOrganization ancestor = orgMap.get(segId);
                    if (ancestor != null && ancestor.getType() == 2) {
                        return ancestor.getId();
                    }
                }
            }
            return scope.getSingleOrgId();
        }
        if (scope.getPathPrefix() != null) {
            // 子树范围：pathPrefix末尾组织可能是一级分行或二级分行
            String path = scope.getPathPrefix();
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int lastSlash = trimmed.lastIndexOf('/');
            Long lastOrgId = Long.parseLong(trimmed.substring(lastSlash + 1));
            SysOrganization org = orgMap.get(lastOrgId);
            if (org != null && org.getType() == 2) {
                return lastOrgId; // Already a 一级分行
            }
            // 不是一级分行，从path向上查找
            String[] segments = path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (segments[i].isEmpty()) continue;
                Long segId = Long.parseLong(segments[i]);
                SysOrganization ancestor = orgMap.get(segId);
                if (ancestor != null && ancestor.getType() == 2) {
                    return ancestor.getId();
                }
            }
            return lastOrgId;
        }
        return branchOrgId;
    }

    // ==================== 费用分析 ====================

    @GetMapping("/analysis/monthly-comparison")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> monthlyComparison(
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        return ResponseEntity.ok(ApiResponse.ok(feeAnalysisService.monthlyComparison(scope)));
    }

    @GetMapping("/analysis/l1-monthly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzeL1Monthly(
            @RequestParam Long orgId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isAllScope() && !scope.isOrgVisible(orgId)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("org_id", orgId, "org_name", "", "month_count", 0, "total_fee", BigDecimal.ZERO, "avg_monthly_fee", BigDecimal.ZERO, "rows", Collections.emptyList())));
        }
        return ResponseEntity.ok(ApiResponse.ok(feeAnalysisService.analyzeL1Monthly(orgId)));
    }

    @GetMapping("/analysis/l2-monthly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzeL2Monthly(
            @RequestParam Long orgId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isAllScope() && !scope.isOrgVisible(orgId)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("org_id", orgId, "org_name", "", "month_count", 0, "total_fee", BigDecimal.ZERO, "avg_monthly_fee", BigDecimal.ZERO, "rows", Collections.emptyList())));
        }
        return ResponseEntity.ok(ApiResponse.ok(feeAnalysisService.analyzeL2Monthly(orgId)));
    }

    @GetMapping("/analysis/dept-monthly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzeDeptMonthly(
            @RequestParam Long orgId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isAllScope() && !scope.isOrgVisible(orgId)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("org_id", orgId, "org_name", "", "month_count", 0, "total_fee", BigDecimal.ZERO, "avg_monthly_fee", BigDecimal.ZERO, "rows", Collections.emptyList())));
        }
        return ResponseEntity.ok(ApiResponse.ok(feeAnalysisService.analyzeDeptMonthly(orgId)));
    }

    @GetMapping("/analysis/phone-list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzePhoneList(
            @RequestParam(required = false) Long orgId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        return ResponseEntity.ok(ApiResponse.ok(feeAnalysisService.analyzePhoneList(orgId, scope)));
    }

    @GetMapping("/analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyze(
            @RequestParam Long batchId,
            @RequestParam String dimension,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String phoneNumber,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        Map<String, Object> data = switch (dimension) {
            case "ALL" -> feeAnalysisService.analyzeAll(batchId, scope);
            case "L1" -> {
                List<Map<String, Object>> l1Rows = feeAnalysisService.analyzeL1(batchId);
                if (!scope.isAllScope()) {
                    List<Long> visibleIds = scope.getVisibleOrgIds();
                    if (visibleIds != null) {
                        l1Rows = l1Rows.stream()
                                .filter(row -> {
                                    Object oid = row.get("org_id");
                                    if (oid == null) return true;
                                    return visibleIds.contains(((Number) oid).longValue());
                                })
                                .toList();
                    }
                }
                yield Map.of("rows", l1Rows);
            }
            case "L2" -> {
                // 校验 orgId 在可见范围内
                if (orgId != null && !scope.isAllScope() && !scope.isOrgVisible(orgId)) {
                    yield Map.of("rows", Collections.emptyList());
                }
                List<Map<String, Object>> l2Rows = feeAnalysisService.analyzeL2(batchId, orgId != null ? orgId : 0L);
                if (!scope.isAllScope()) {
                    List<Long> visibleIds = scope.getVisibleOrgIds();
                    if (visibleIds != null) {
                        l2Rows = l2Rows.stream()
                                .filter(row -> {
                                    Object oid = row.get("org_id");
                                    if (oid == null) return true;
                                    return visibleIds.contains(((Number) oid).longValue());
                                })
                                .toList();
                    }
                }
                yield Map.of("rows", l2Rows);
            }
            case "DEPARTMENT" -> {
                if (orgId != null && !scope.isAllScope() && !scope.isOrgVisible(orgId)) {
                    yield Map.of("rows", Collections.emptyList());
                }
                List<Map<String, Object>> deptRows = feeAnalysisService.analyzeDepartment(batchId, orgId != null ? orgId : 0L);
                if (!scope.isAllScope()) {
                    List<Long> visibleIds = scope.getVisibleOrgIds();
                    if (visibleIds != null) {
                        deptRows = deptRows.stream()
                                .filter(row -> {
                                    Object oid = row.get("org_id");
                                    if (oid == null) return true;
                                    return visibleIds.contains(((Number) oid).longValue());
                                })
                                .toList();
                    }
                }
                yield Map.of("rows", deptRows);
            }
            case "PHONE" -> (phoneNumber != null && !phoneNumber.isEmpty())
                    ? feeAnalysisService.analyzePhone(phoneNumber, scope)
                    : Map.of("rows", Collections.emptyList(), "phone_number", phoneNumber != null ? phoneNumber : "", "month_count", 0, "total_fee", BigDecimal.ZERO, "avg_monthly_fee", BigDecimal.ZERO);
            default -> throw new IllegalArgumentException("不支持的分析维度: " + dimension);
        };
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}
