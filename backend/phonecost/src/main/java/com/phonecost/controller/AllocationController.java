package com.phonecost.controller;

import com.phonecost.domain.AllocationResult;
import com.phonecost.domain.BillBatch;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.AllocationResultRepository;
import com.phonecost.repository.BillBatchRepository;
import com.phonecost.repository.SysUserRepository;
import com.phonecost.service.AllocationConfirmService;
import com.phonecost.service.AllocationExportService;
import com.phonecost.service.AllocationService;
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
    private final AllocationResultRepository resultRepository;
    private final BillBatchRepository billBatchRepository;
    private final DataScopeService dataScopeService;
    private final SysUserRepository userRepository;

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

    // ==================== 导出 ====================

    @GetMapping("/export/summary")
    public ResponseEntity<byte[]> exportSummary(
            @RequestParam Long batchId,
            @RequestParam(required = false) Long branchOrgId,
            @RequestAttribute("userId") Long userId) throws Exception {
        DataScope scope = dataScopeService.getDataScope(userId);

        // 非管理员强制使用自己的组织范围
        Long effectiveBranchOrgId = branchOrgId;
        if (!scope.isAllScope()) {
            // 分行/部门用户：忽略客户端传入的 branchOrgId，使用自己的
            if (scope.getSingleOrgId() != null) {
                effectiveBranchOrgId = scope.getSingleOrgId();
            } else if (scope.getPathPrefix() != null) {
                // BRANCH: pathPrefix 对应的 orgId 需要从 path 推算
                // 取 pathPrefix 中最后一个 ID
                String path = scope.getPathPrefix();
                String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                int lastSlash = trimmed.lastIndexOf('/');
                effectiveBranchOrgId = Long.parseLong(trimmed.substring(lastSlash + 1));
            }
        }

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
        DataScope scope = dataScopeService.getDataScope(userId);

        Long effectiveBranchOrgId = branchOrgId;
        if (!scope.isAllScope()) {
            if (scope.getSingleOrgId() != null) {
                effectiveBranchOrgId = scope.getSingleOrgId();
            } else if (scope.getPathPrefix() != null) {
                String path = scope.getPathPrefix();
                String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                int lastSlash = trimmed.lastIndexOf('/');
                effectiveBranchOrgId = Long.parseLong(trimmed.substring(lastSlash + 1));
            }
        }

        byte[] data = exportService.exportDetail(batchId, effectiveBranchOrgId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"分行费用分摊明细.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.valueOf(val.toString());
    }
}
