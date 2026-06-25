package com.phonecost.controller;

import com.phonecost.domain.*;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.*;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
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

        // ========== 基础统计 ==========
        long orgCount = scope.isAllScope() ? orgRepository.count()
                : scope.getVisibleOrgIds() != null ? scope.getVisibleOrgIds().size() : 0;

        long userCount = scope.isAllScope() ? userRepository.count()
                : scope.getVisibleOrgIds() != null
                        ? userRepository.findByOrgIdInAndDeletedAtIsNull(scope.getVisibleOrgIds()).size() : 0;

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
        var scopedResults = scope.filterByOrgId(allResults, AllocationResult::getOrgId);
        long allocationResultCount = scopedResults.size();
        long confirmedCount = scopedResults.stream()
                .filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == (byte) 1).count();
        long pendingCount = scopedResults.stream()
                .filter(r -> r.getConfirmStatus() != null && r.getConfirmStatus() == (byte) 0).count();

        // 分行数
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

        // ========== 月度趋势 ==========
        List<Map<String, Object>> monthlyTrend = billBatchRepository.findAll().stream()
                .filter(b -> b.getDeletedAt() == null && b.getBillingMonth() != null && !b.getBillingMonth().isEmpty())
                .sorted(Comparator.comparing(BillBatch::getBillingMonth))
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("month", b.getBillingMonth());
                    m.put("amount", b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO);
                    m.put("count", b.getTotalCount() != null ? b.getTotalCount() : 0);
                    m.put("batch_id", b.getId());
                    return m;
                })
                .collect(Collectors.toList());

        // ========== 最新批次分行排行 ==========
        List<Map<String, Object>> branchSummary = List.of();
        Map<String, Object> latestBatch = null;
        if (!monthlyTrend.isEmpty()) {
            var lastEntry = monthlyTrend.get(monthlyTrend.size() - 1);
            Long latestBatchId = (Long) lastEntry.get("batch_id");

            // 获取最新批次的分摊结果
            var latestResults = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(latestBatchId);

            // 构建 orgId -> type 映射
            Map<Long, Byte> orgTypeMap = new HashMap<>();
            for (SysOrganization org : orgRepository.findAll()) {
                if (org.getDeletedAt() == null && org.getType() != null) {
                    orgTypeMap.put(org.getId(), org.getType());
                }
            }

            // 过滤出一级行(type=2)的结果
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

            // 最新批次信息
            latestBatch = new LinkedHashMap<>();
            latestBatch.put("batch_id", latestBatchId);
            latestBatch.put("month", lastEntry.get("month"));
            latestBatch.put("amount", lastEntry.get("amount"));
            latestBatch.put("count", lastEntry.get("count"));
        }

        // ========== 费用类型分布 (最新批次) ==========
        List<Map<String, Object>> feeBreakdown = List.of();
        if (!monthlyTrend.isEmpty()) {
            Long latestBatchId = (Long) monthlyTrend.get(monthlyTrend.size() - 1).get("batch_id");
            var latestResults = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(latestBatchId);

            BigDecimal platformFee = BigDecimal.ZERO;
            BigDecimal callFee = BigDecimal.ZERO;
            BigDecimal recordingFee = BigDecimal.ZERO;
            BigDecimal crbtFee = BigDecimal.ZERO;
            BigDecimal flashFee = BigDecimal.ZERO;

            for (AllocationResult r : latestResults) {
                platformFee = platformFee.add(r.getMonthlyRent() != null ? r.getMonthlyRent() : BigDecimal.ZERO);
                callFee = callFee.add(r.getCallFee() != null ? r.getCallFee() : BigDecimal.ZERO);
                recordingFee = recordingFee.add(r.getRecordingFee() != null ? r.getRecordingFee() : BigDecimal.ZERO);
                crbtFee = crbtFee.add(r.getCrbtFee() != null ? r.getCrbtFee() : BigDecimal.ZERO);
                flashFee = flashFee.add(r.getFlashMsgFee() != null ? r.getFlashMsgFee() : BigDecimal.ZERO);
            }

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
