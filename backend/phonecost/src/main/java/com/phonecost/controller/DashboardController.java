package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.*;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SysOrganizationRepository orgRepository;
    private final SysUserRepository userRepository;
    private final BillBatchRepository billBatchRepository;
    private final BillDetailRepository billDetailRepository;
    private final AllocationResultRepository allocationResultRepository;
    private final DataScopeService dataScopeService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats(
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);

        // 组织数：按范围过滤
        long orgCount;
        if (scope.isAllScope()) {
            orgCount = orgRepository.count();
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            orgCount = visibleIds != null ? visibleIds.size() : 0;
        }

        // 用户数：按范围过滤
        long userCount;
        if (scope.isAllScope()) {
            userCount = userRepository.count();
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            userCount = visibleIds != null
                    ? userRepository.findByOrgIdInAndDeletedAtIsNull(visibleIds).size()
                    : 0;
        }

        // 账单批次和明细（全局数据）
        long billBatchCount = billBatchRepository.count();
        long billDetailCount = billDetailRepository.count();

        BigDecimal totalAmount = billBatchRepository.findAll().stream()
                .filter(b -> b.getDeletedAt() == null)
                .map(b -> b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 分摊结果：按范围过滤
        var allResults = allocationResultRepository.findAll().stream()
                .filter(r -> r.getDeletedAt() == null)
                .toList();
        var scopedResults = scope.filterByOrgId(allResults, r -> r.getOrgId());
        long allocationResultCount = scopedResults.size();
        long confirmedCount = scopedResults.stream()
                .filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == (byte) 1).count();
        long pendingCount = scopedResults.stream()
                .filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == (byte) 0).count();

        // 分行数：按范围过滤
        long branchCount;
        if (scope.isAllScope()) {
            branchCount = orgRepository.findAll().stream()
                    .filter(o -> o.getDeletedAt() == null && o.getType() != null && o.getType() == (byte) 2)
                    .count();
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            branchCount = visibleIds != null
                    ? orgRepository.findAllById(visibleIds).stream()
                        .filter(o -> o.getDeletedAt() == null && o.getType() != null && o.getType() == (byte) 2)
                        .count()
                    : 0;
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "org_count", orgCount,
                "user_count", userCount,
                "bill_batch_count", billBatchCount,
                "bill_detail_count", billDetailCount,
                "total_amount", totalAmount,
                "allocation_result_count", allocationResultCount,
                "confirmed_count", confirmedCount,
                "pending_count", pendingCount,
                "branch_count", branchCount
        )));
    }
}
