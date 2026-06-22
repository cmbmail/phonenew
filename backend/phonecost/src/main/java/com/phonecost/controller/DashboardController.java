package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.*;
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

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        long orgCount = orgRepository.count();
        long userCount = userRepository.count();
        long billBatchCount = billBatchRepository.count();
        long billDetailCount = billDetailRepository.count();

        BigDecimal totalAmount = billBatchRepository.findAll().stream()
                .filter(b -> b.getDeletedAt() == null)
                .map(b -> b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var allResults = allocationResultRepository.findAll().stream()
                .filter(r -> r.getDeletedAt() == null)
                .toList();
        long allocationResultCount = allResults.size();
        long confirmedCount = allResults.stream().filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == (byte) 1).count();
        long pendingCount = allResults.stream().filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == (byte) 0).count();

        long branchCount = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null && o.getType() != null && o.getType() == (byte) 2)
                .count();

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
