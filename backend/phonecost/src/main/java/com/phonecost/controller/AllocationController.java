package com.phonecost.controller;

import com.phonecost.domain.AllocationAdjustment;
import com.phonecost.domain.AllocationResult;
import com.phonecost.domain.BillBatch;
import com.phonecost.domain.SysOrganization;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.AllocationResultRepository;
import com.phonecost.repository.BillBatchRepository;
import com.phonecost.repository.SysOrganizationRepository;
import com.phonecost.repository.SysUserRepository;
import com.phonecost.service.AllocationAdjustService;
import com.phonecost.service.AllocationConfirmService;
import com.phonecost.service.AllocationExportService;
import com.phonecost.service.AllocationService;
import com.phonecost.service.BranchBillExportService;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 费用分摊Controller
 * 分摊计算 + 确认/撤回 + 导出
 * 支持按角色数据范围过滤
 */
@RestController
@RequestMapping("/allocation")
@RequiredArgsConstructor
public class AllocationController {

    private final AllocationService allocationService;
    private final AllocationConfirmService confirmService;
    private final AllocationExportService exportService;
    private final BranchBillExportService branchBillExportService;
    private final AllocationAdjustService adjustService;
    private final AllocationResultRepository resultRepository;
    private final BillBatchRepository billBatchRepository;
    private final DataScopeService dataScopeService;
    private final SysUserRepository userRepository;
    private final SysOrganizationRepository orgRepository;

    // ==================== 分摊计算 ====================

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculate(
            @RequestBody Map<String, Long> body) {
        Long billBatchId = body.get("bill_batch_id");
        if (billBatchId == null) {
            throw new IllegalArgumentException("bill_batch_id 不能为空");
        }
        List<AllocationResult> results = allocationService.calculateAllocation(billBatchId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "bill_batch_id", billBatchId,
                "org_count", results.size()
        )));
    }

    // ==================== 查询结果 ====================

    @GetMapping("/results/{batchId}")
    public ResponseEntity<ApiResponse<List<AllocationResult>>> getResults(
            @PathVariable Long batchId,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<AllocationResult> all = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<AllocationResult> filtered = scope.filterByOrgId(all, AllocationResult::getOrgId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<BillBatch>>> listBatches(
            @RequestAttribute("userId") Long userId) {
        // 账单批次不按组织过滤（批次是全局的），但分行管理员只能看到相关批次
        DataScope scope = dataScopeService.getDataScope(userId);
        if (scope.isAllScope()) {
            return ResponseEntity.ok(ApiResponse.ok(billBatchRepository.findAll()));
        }
        // 非管理员：返回所有批次（批次本身不归属组织，但分摊结果按组织过滤）
        return ResponseEntity.ok(ApiResponse.ok(billBatchRepository.findAll()));
    }

    // ==================== 确认/撤回 ====================

    @PostMapping("/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = toLong(body.get("batch_id"));
        Long orgId = toLong(body.get("org_id"));
        if (batchId == null || orgId == null) {
            throw new IllegalArgumentException("batch_id 和 org_id 不能为空");
        }

        // 校验数据范围：分行管理员只能确认自己管辖范围内的组织
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(orgId)) {
            throw new IllegalArgumentException("无权操作该组织的分摊数据");
        }

        AllocationResult result = confirmService.confirm(batchId, orgId, userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_id", result.getOrgId(),
                "confirm_status", (int) result.getConfirmStatus()
        )));
    }

    @PostMapping("/confirm-all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmAll(
            @RequestBody Map<String, Long> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = body.get("batch_id");
        if (batchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }

        // 分行管理员只确认自己范围内的
        DataScope scope = dataScopeService.getDataScope(userId);
        int count = confirmService.confirmAllInScope(batchId, userId, scope);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("confirmed_count", count)));
    }

    @PostMapping("/withdraw")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_BRANCH')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> withdraw(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = toLong(body.get("batch_id"));
        Long orgId = toLong(body.get("org_id"));
        String reason = (String) body.get("reason");
        if (batchId == null || orgId == null) {
            throw new IllegalArgumentException("batch_id 和 org_id 不能为空");
        }

        // 校验数据范围：分行管理员只能撤回自己管辖范围内的组织
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(orgId)) {
            throw new IllegalArgumentException("无权操作该组织的分摊数据");
        }

        List<AllocationResult> results = confirmService.withdraw(batchId, orgId, userId, reason);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_id", orgId,
                "result_count", results.size()
        )));
    }

    // ==================== 费用调整 ====================

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE')")
    public ResponseEntity<ApiResponse<AllocationAdjustment>> adjust(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        Long batchId = toLong(body.get("batch_id"));
        String phoneNumber = (String) body.get("phone_number");
        Long fromOrgId = toLong(body.get("from_org_id"));
        Long toOrgId = toLong(body.get("to_org_id"));
        String reason = (String) body.get("reason");

        if (batchId == null || phoneNumber == null || fromOrgId == null || toOrgId == null) {
            throw new IllegalArgumentException("参数不完整：batch_id, phone_number, from_org_id, to_org_id 均为必填");
        }

        // 校验数据范围：from/to 组织至少一个在管辖范围内
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(fromOrgId) && !scope.isOrgVisible(toOrgId)) {
            throw new IllegalArgumentException("调整涉及的组织不在您的管辖范围内");
        }

        AllocationAdjustment adjustment = adjustService.adjust(
                batchId, phoneNumber, fromOrgId, toOrgId, reason, userId);
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
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"分行费用分摊汇总.xlsx\"")
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
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"分行费用分摊明细.xlsx\"")
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
     * L1 分摊汇总：集团 → 一级分行
     * 每个一级分行一行，汇总其所有下属费用
     */
    @GetMapping("/export/l1-summary")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportL1Summary(
            @RequestParam Long batchId,
            @RequestAttribute("userId") Long userId) throws Exception {
        byte[] data = branchBillExportService.exportLevel1Summary(batchId, userId);
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
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportL2BranchDetail(
            @RequestParam Long batchId,
            @RequestParam Long branchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        Long effectiveBranchOrgId = resolveEffectiveBranchOrgId(branchOrgId, userId);
        byte[] data = branchBillExportService.exportLevel2BranchDetail(batchId, effectiveBranchOrgId, userId);
        SysOrganization org = orgRepository.findById(effectiveBranchOrgId).orElse(null);
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
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_FINANCE', 'ROLE_BRANCH')")
    public ResponseEntity<byte[]> exportL3SubBranchDetail(
            @RequestParam Long batchId,
            @RequestParam Long subBranchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        byte[] data = branchBillExportService.exportLevel3SubBranchDetail(batchId, subBranchOrgId, userId);
        SysOrganization org = orgRepository.findById(subBranchOrgId).orElse(null);
        String name = org != null ? org.getName() : "sub_branch";
        String filename = java.net.URLEncoder.encode(
                name + "_下属分摊_" + batchId + ".xlsx", "UTF-8");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    /** Resolve effective branch org ID based on user's data scope */
    private Long resolveEffectiveBranchOrgId(Long branchOrgId, Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (scope.isAllScope()) return branchOrgId;
        if (scope.getSingleOrgId() != null) return scope.getSingleOrgId();
        if (scope.getPathPrefix() != null) {
            String path = scope.getPathPrefix();
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int lastSlash = trimmed.lastIndexOf('/');
            return Long.parseLong(trimmed.substring(lastSlash + 1));
        }
        return branchOrgId;
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.valueOf(val.toString());
    }
}
