package com.phonecost.controller;

import com.phonecost.domain.*;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.*;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
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

        // ========== 基础统计 (M-07: 使用聚合查询替代findAll()) ==========
        long orgCount = scope.isAllScope() ? orgRepository.count()
                : scope.getVisibleOrgIds() != null ? scope.getVisibleOrgIds().size() : 0;

        long userCount = scope.isAllScope() ? userRepository.count()
                : scope.getVisibleOrgIds() != null
                        ? userRepository.countByOrgIdInAndDeletedAtIsNull(scope.getVisibleOrgIds()) : 0;

        long billBatchCount = billBatchRepository.count();
        long billDetailCount = billDetailRepository.count();

        // M-07: Use aggregate query instead of loading all BillBatch entities
        BigDecimal totalAmount = billBatchRepository.sumTotalAmount();

        // M-07: Use single aggregate query instead of N+1 loop over batches
        long allocationResultCount;
        long confirmedCount;
        long pendingCount;

        if (scope.isAllScope()) {
            allocationResultCount = allocationResultRepository.count();
            List<Object[]> statusCounts = allocationResultRepository.countByConfirmStatusGlobal();
            long tmpConfirmed = 0, tmpPending = 0;
            for (Object[] row : statusCounts) {
                Byte status = (Byte) row[0];
                Long cnt = (Long) row[1];
                if (status != null && status == 1) tmpConfirmed = cnt;
                else if (status != null && status == 0) tmpPending = cnt;
            }
            confirmedCount = tmpConfirmed;
            pendingCount = tmpPending;
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null && !visibleIds.isEmpty()) {
                List<Object[]> statusCounts = allocationResultRepository.countByConfirmStatusScoped(visibleIds);
                long tmpTotal = 0, tmpConfirmed = 0, tmpPending = 0;
                for (Object[] row : statusCounts) {
                    Byte status = (Byte) row[0];
                    Long cnt = (Long) row[1];
                    tmpTotal += cnt;
                    if (status != null && status == 1) tmpConfirmed = cnt;
                    else if (status != null && status == 0) tmpPending = cnt;
                }
                allocationResultCount = tmpTotal;
                confirmedCount = tmpConfirmed;
                pendingCount = tmpPending;
            } else {
                allocationResultCount = 0;
                confirmedCount = 0;
                pendingCount = 0;
            }
        }

        // 分行数 — M-07: Use countByType + org ID filtering without loading all entities
        long branchCount;
        if (scope.isAllScope()) {
            branchCount = orgRepository.countByTypeAndDeletedAtIsNull((byte) 2);
        } else {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null && !visibleIds.isEmpty()) {
                branchCount = orgRepository.countByTypeAndIdInAndDeletedAtIsNull((byte) 2, visibleIds);
            } else {
                branchCount = 0;
            }
        }

        // ========== 月度趋势 (M-07: Use projection query instead of findAll) ==========
        List<Object[]> trendData = billBatchRepository.findMonthlyTrendData();
        List<Map<String, Object>> monthlyTrend = new ArrayList<>();
        for (Object[] row : trendData) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", row[0]);
            m.put("amount", row[1] != null ? row[1] : BigDecimal.ZERO);
            m.put("count", row[2] != null ? row[2] : 0);
            m.put("batch_id", row[3]);
            monthlyTrend.add(m);
        }

        // ========== 最新批次分行排行 (M-07: Use projection for org-type map) ==========
        List<Map<String, Object>> branchSummary = List.of();
        Map<String, Object> latestBatch = null;
        if (!monthlyTrend.isEmpty()) {
            var lastEntry = monthlyTrend.get(monthlyTrend.size() - 1);
            Long latestBatchId = (Long) lastEntry.get("batch_id");

            var latestResults = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(latestBatchId);

            // M-07: Use projection query for org type map instead of findAll()
            Map<Long, Byte> orgTypeMap = new HashMap<>();
            List<Object[]> idTypePairs = orgRepository.findIdTypePairs();
            for (Object[] pair : idTypePairs) {
                orgTypeMap.put((Long) pair[0], (Byte) pair[1]);
            }

            branchSummary = latestResults.stream()
                    .filter(r -> {
                        Byte type = orgTypeMap.get(r.getOrgId());
                        return type != null && type == (byte) 2;
                    })
                    .sorted((a, b) -> {
                        BigDecimal aFee = a.getTotalFee() != null ? a.getTotalFee() : BigDecimal.ZERO;
                        BigDecimal bFee = b.getTotalFee() != null ? b.getTotalFee() : BigDecimal.ZERO;
                        return bFee.compareTo(aFee);
                    })
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("org_id", r.getOrgId());
                        m.put("name", r.getOrgName());
                        m.put("amount", r.getTotalFee() != null ? r.getTotalFee() : BigDecimal.ZERO);
                        m.put("phone_count", r.getPhoneCount() != null ? r.getPhoneCount() : 0);
                        m.put("confirm_status", r.getConfirmStatus() != null ? r.getConfirmStatus() : 0);
                        return m;
                    })
                    .collect(Collectors.toList());

            latestBatch = new LinkedHashMap<>();
            latestBatch.put("batch_id", latestBatchId);
            latestBatch.put("month", lastEntry.get("month"));
            latestBatch.put("amount", lastEntry.get("amount"));
            latestBatch.put("count", lastEntry.get("count"));
        }

        // ========== 费用类型分布 (M-07: Use aggregate query) ==========
        List<Map<String, Object>> feeBreakdown = List.of();
        if (!monthlyTrend.isEmpty()) {
            Long latestBatchId = (Long) monthlyTrend.get(monthlyTrend.size() - 1).get("batch_id");
            Object[] sums = allocationResultRepository.sumFeeBreakdownByBatchId(latestBatchId);

            BigDecimal platformFee = sums[0] != null ? (BigDecimal) sums[0] : BigDecimal.ZERO;
            BigDecimal callFee = sums[1] != null ? (BigDecimal) sums[1] : BigDecimal.ZERO;
            BigDecimal recordingFee = sums[2] != null ? (BigDecimal) sums[2] : BigDecimal.ZERO;
            BigDecimal crbtFee = sums[3] != null ? (BigDecimal) sums[3] : BigDecimal.ZERO;
            BigDecimal flashFee = sums[4] != null ? (BigDecimal) sums[4] : BigDecimal.ZERO;

            feeBreakdown = List.of(
                    Map.of("name", "通话费", "value", callFee, "color", "#8B9D9E"),
                    Map.of("name", "录音费", "value", recordingFee, "color", "#B8A99A"),
                    Map.of("name", "彩铃费", "value", crbtFee, "color", "#7B8FA1"),
                    Map.of("name", "闪信费", "value", flashFee, "color", "#A89B8C"),
                    Map.of("name", "月租费", "value", platformFee, "color", "#9B8B9E")
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("org_count", orgCount);
        result.put("user_count", userCount);
        result.put("bill_batch_count", billBatchCount);
        result.put("bill_detail_count", billDetailCount);
        result.put("total_amount", totalAmount);
        result.put("allocation_result_count", allocationResultCount);
        result.put("confirmed_count", confirmedCount);
        result.put("pending_count", pendingCount);
        result.put("branch_count", branchCount);
        result.put("monthly_trend", monthlyTrend);
        result.put("branch_summary", branchSummary);
        result.put("latest_batch", latestBatch);
        result.put("fee_breakdown", feeBreakdown);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
