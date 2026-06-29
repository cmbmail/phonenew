package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 费用分析服务
 * 支持5个维度：全部、一级分行、二级分行、部门、单个号码
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeAnalysisService {

    private final AllocationResultRepository allocationResultRepository;
    private final BillDetailRepository billDetailRepository;
    private final SysOrganizationRepository orgRepository;
    private final BillBatchRepository billBatchRepository;

    /**
     * 全部维度：返回总体费用汇总
     */
    public Map<String, Object> analyzeAll(Long batchId) {
        List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);

        BigDecimal totalRent = BigDecimal.ZERO, totalCall = BigDecimal.ZERO, totalRecording = BigDecimal.ZERO;
        BigDecimal totalCrbt = BigDecimal.ZERO, totalFlash = BigDecimal.ZERO, totalFee = BigDecimal.ZERO;
        int totalPhones = 0, orgCount = 0, unassignedPhones = 0;
        BigDecimal unassignedFee = BigDecimal.ZERO;

        for (AllocationResult r : results) {
            totalRent = totalRent.add(r.getMonthlyRent() != null ? r.getMonthlyRent() : BigDecimal.ZERO);
            totalCall = totalCall.add(r.getCallFee() != null ? r.getCallFee() : BigDecimal.ZERO);
            totalRecording = totalRecording.add(r.getRecordingFee() != null ? r.getRecordingFee() : BigDecimal.ZERO);
            totalCrbt = totalCrbt.add(r.getCrbtFee() != null ? r.getCrbtFee() : BigDecimal.ZERO);
            totalFlash = totalFlash.add(r.getFlashMsgFee() != null ? r.getFlashMsgFee() : BigDecimal.ZERO);
            totalFee = totalFee.add(r.getTotalFee() != null ? r.getTotalFee() : BigDecimal.ZERO);
            totalPhones += r.getPhoneCount() != null ? r.getPhoneCount() : 0;
            if (r.getOrgId() != null && r.getOrgId() == -1L) {
                unassignedPhones += r.getPhoneCount() != null ? r.getPhoneCount() : 0;
                unassignedFee = unassignedFee.add(r.getTotalFee() != null ? r.getTotalFee() : BigDecimal.ZERO);
            } else {
                orgCount++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total_fee", totalFee);
        data.put("monthly_rent", totalRent);
        data.put("call_fee", totalCall);
        data.put("recording_fee", totalRecording);
        data.put("crbt_fee", totalCrbt);
        data.put("flash_msg_fee", totalFlash);
        data.put("phone_count", totalPhones);
        data.put("org_count", orgCount);
        data.put("unassigned_fee", unassignedFee);
        data.put("unassigned_phones", unassignedPhones);

        // Fee type breakdown for pie chart
        List<Map<String, Object>> feeBreakdown = new ArrayList<>();
        addBreakdownItem(feeBreakdown, "月租费", totalRent, totalFee);
        addBreakdownItem(feeBreakdown, "通话费", totalCall, totalFee);
        addBreakdownItem(feeBreakdown, "录音费", totalRecording, totalFee);
        addBreakdownItem(feeBreakdown, "彩铃费", totalCrbt, totalFee);
        addBreakdownItem(feeBreakdown, "闪信费", totalFlash, totalFee);
        data.put("fee_breakdown", feeBreakdown);

        // Top 10 orgs by total_fee
        List<AllocationResult> sorted = results.stream()
                .filter(r -> r.getOrgId() != null && r.getOrgId() != -1L)
                .sorted((a, b) -> {
                    BigDecimal fa = a.getTotalFee() != null ? a.getTotalFee() : BigDecimal.ZERO;
                    BigDecimal fb = b.getTotalFee() != null ? b.getTotalFee() : BigDecimal.ZERO;
                    return fb.compareTo(fa);
                })
                .limit(10)
                .toList();
        List<Map<String, Object>> topOrgs = new ArrayList<>();
        for (AllocationResult r : sorted) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("org_id", r.getOrgId());
            item.put("org_name", r.getOrgName());
            item.put("total_fee", r.getTotalFee());
            item.put("phone_count", r.getPhoneCount());
            topOrgs.add(item);
        }
        data.put("top_orgs", topOrgs);

        return data;
    }

    /**
     * 一级分行维度：按一级分行汇总
     */
    public List<Map<String, Object>> analyzeL1(Long batchId) {
        List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        List<SysOrganization> l1Orgs = orgRepository.findByTypeAndDeletedAtIsNull((byte) 2);

        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        // Find L1 org for each allocation result
        Map<Long, List<AllocationResult>> l1Groups = new LinkedHashMap<>();
        for (SysOrganization l1 : l1Orgs) {
            l1Groups.put(l1.getId(), new ArrayList<>());
        }

        for (AllocationResult r : results) {
            if (r.getOrgId() == null || r.getOrgId() == -1L) continue;
            Long l1Id = findAncestorByType(orgMap, r.getOrgId(), (byte) 2);
            if (l1Id != null && l1Groups.containsKey(l1Id)) {
                l1Groups.get(l1Id).add(r);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysOrganization l1 : l1Orgs) {
            List<AllocationResult> group = l1Groups.getOrDefault(l1.getId(), Collections.emptyList());
            if (group.isEmpty()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", l1.getId());
            row.put("org_name", l1.getName());
            row.put("code", l1.getCode());
            row.put("cost_center", l1.getCostCenter());
            row.put("monthly_rent", sumField(group, AllocationResult::getMonthlyRent));
            row.put("call_fee", sumField(group, AllocationResult::getCallFee));
            row.put("recording_fee", sumField(group, AllocationResult::getRecordingFee));
            row.put("crbt_fee", sumField(group, AllocationResult::getCrbtFee));
            row.put("flash_msg_fee", sumField(group, AllocationResult::getFlashMsgFee));
            row.put("total_fee", sumField(group, AllocationResult::getTotalFee));
            row.put("phone_count", group.stream().mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum());
            row.put("sub_org_count", group.size());
            rows.add(row);
        }

        // Sort by total_fee desc
        rows.sort((a, b) -> ((BigDecimal) b.getOrDefault("total_fee", BigDecimal.ZERO))
                .compareTo((BigDecimal) a.getOrDefault("total_fee", BigDecimal.ZERO)));

        return rows;
    }

    /**
     * 二级分行维度：按指定一级分行下的二级分行汇总
     */
    public List<Map<String, Object>> analyzeL2(Long batchId, Long l1OrgId) {
        List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        // Find all L2 orgs under the given L1
        SysOrganization l1 = orgMap.get(l1OrgId);
        if (l1 == null) return Collections.emptyList();
        String l1Path = l1.getPath();
        List<SysOrganization> l2Orgs = orgRepository.findAllDescendants(l1Path).stream()
                .filter(o -> o.getType() != null && o.getType() == 3)
                .toList();

        Map<Long, List<AllocationResult>> l2Groups = new LinkedHashMap<>();
        for (SysOrganization l2 : l2Orgs) {
            l2Groups.put(l2.getId(), new ArrayList<>());
        }

        for (AllocationResult r : results) {
            if (r.getOrgId() == null || r.getOrgId() == -1L) continue;
            Long l2Id = findAncestorByType(orgMap, r.getOrgId(), (byte) 3);
            if (l2Id != null && l2Groups.containsKey(l2Id)) {
                l2Groups.get(l2Id).add(r);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysOrganization l2 : l2Orgs) {
            List<AllocationResult> group = l2Groups.getOrDefault(l2.getId(), Collections.emptyList());
            if (group.isEmpty()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", l2.getId());
            row.put("org_name", l2.getName());
            row.put("code", l2.getCode());
            row.put("cost_center", l2.getCostCenter());
            row.put("monthly_rent", sumField(group, AllocationResult::getMonthlyRent));
            row.put("call_fee", sumField(group, AllocationResult::getCallFee));
            row.put("recording_fee", sumField(group, AllocationResult::getRecordingFee));
            row.put("crbt_fee", sumField(group, AllocationResult::getCrbtFee));
            row.put("flash_msg_fee", sumField(group, AllocationResult::getFlashMsgFee));
            row.put("total_fee", sumField(group, AllocationResult::getTotalFee));
            row.put("phone_count", group.stream().mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum());
            row.put("sub_org_count", group.size());
            rows.add(row);
        }

        rows.sort((a, b) -> ((BigDecimal) b.getOrDefault("total_fee", BigDecimal.ZERO))
                .compareTo((BigDecimal) a.getOrDefault("total_fee", BigDecimal.ZERO)));

        return rows;
    }

    /**
     * 部门维度：按指定组织下的直属部门汇总
     */
    public List<Map<String, Object>> analyzeDepartment(Long batchId, Long parentOrgId) {
        List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        // Get direct children orgs
        List<SysOrganization> children = orgRepository.findByParentIdAndDeletedAtIsNull(parentOrgId);
        Map<Long, List<AllocationResult>> childGroups = new LinkedHashMap<>();
        for (SysOrganization child : children) {
            childGroups.put(child.getId(), new ArrayList<>());
        }

        // Also include the parent itself if it has direct allocation results (leaf node)
        for (AllocationResult r : results) {
            if (r.getOrgId() == null || r.getOrgId() == -1L) continue;
            // Find which child (or self) this org belongs to
            Long directParent = findDirectChildAncestor(orgMap, r.getOrgId(), childGroups.keySet());
            if (directParent != null) {
                childGroups.get(directParent).add(r);
            } else if (r.getOrgId().equals(parentOrgId)) {
                // The parent org itself
                childGroups.computeIfAbsent(parentOrgId, k -> new ArrayList<>()).add(r);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysOrganization child : children) {
            List<AllocationResult> group = childGroups.getOrDefault(child.getId(), Collections.emptyList());
            if (group.isEmpty()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", child.getId());
            row.put("org_name", child.getName());
            row.put("org_type", child.getType());
            row.put("code", child.getCode());
            row.put("cost_center", child.getCostCenter());
            row.put("monthly_rent", sumField(group, AllocationResult::getMonthlyRent));
            row.put("call_fee", sumField(group, AllocationResult::getCallFee));
            row.put("recording_fee", sumField(group, AllocationResult::getRecordingFee));
            row.put("crbt_fee", sumField(group, AllocationResult::getCrbtFee));
            row.put("flash_msg_fee", sumField(group, AllocationResult::getFlashMsgFee));
            row.put("total_fee", sumField(group, AllocationResult::getTotalFee));
            row.put("phone_count", group.stream().mapToInt(r -> r.getPhoneCount() != null ? r.getPhoneCount() : 0).sum());
            rows.add(row);
        }

        rows.sort((a, b) -> ((BigDecimal) b.getOrDefault("total_fee", BigDecimal.ZERO))
                .compareTo((BigDecimal) a.getOrDefault("total_fee", BigDecimal.ZERO)));

        return rows;
    }

    /**
     * 单个号码维度：查询指定号码的费用明细
     */
    public List<Map<String, Object>> analyzePhone(String phoneNumber) {
        List<BillDetail> details = billDetailRepository.findByPhoneNumberAndDeletedAtIsNull(phoneNumber);
        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        // Group by batch_id (month)
        Map<Long, List<BillDetail>> byBatch = details.stream()
                .collect(Collectors.groupingBy(BillDetail::getBatchId));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, List<BillDetail>> entry : byBatch.entrySet()) {
            List<BillDetail> batchDetails = entry.getValue();
            BillDetail first = batchDetails.get(0);

            Map<String, Object> row = new LinkedHashMap<>();
            // Resolve batch billing_month
            BillBatch batch = billBatchRepository.findById(entry.getKey()).orElse(null);
            row.put("billing_month", batch != null ? batch.getBillingMonth() : "");
            row.put("phone_number", phoneNumber);
            row.put("org_id", first.getOrgId());
            SysOrganization org = first.getOrgId() != null ? orgMap.get(first.getOrgId()) : null;
            row.put("org_name", org != null ? org.getName() : "");
            row.put("ownership_source", first.getOwnershipSource());

            BigDecimal total = BigDecimal.ZERO;
            for (BillDetail d : batchDetails) {
                total = total.add(d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO);
            }
            row.put("total_fee", total);
            row.put("detail_count", batchDetails.size());

            // Fee breakdown by sheet type
            Map<String, BigDecimal> bySheet = new LinkedHashMap<>();
            for (BillDetail d : batchDetails) {
                String st = d.getSheetType() != null ? d.getSheetType() : "UNKNOWN";
                bySheet.merge(st, d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO, BigDecimal::add);
            }
            row.put("sheet_breakdown", bySheet);

            rows.add(row);
        }

        rows.sort((a, b) -> String.valueOf(b.getOrDefault("billing_month", ""))
                .compareTo(String.valueOf(a.getOrDefault("billing_month", ""))));

        return rows;
    }

    /**
     * 月度总费用对比：返回近12个月（或所有有数据的月份）的费用汇总
     */
    public List<Map<String, Object>> monthlyComparison() {
        List<BillBatch> batches = billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (BillBatch batch : batches) {
            List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batch.getId());

            BigDecimal totalRent = BigDecimal.ZERO, totalCall = BigDecimal.ZERO, totalRecording = BigDecimal.ZERO;
            BigDecimal totalCrbt = BigDecimal.ZERO, totalFlash = BigDecimal.ZERO, totalFee = BigDecimal.ZERO;
            int phoneCount = 0;

            for (AllocationResult r : results) {
                totalRent = totalRent.add(r.getMonthlyRent() != null ? r.getMonthlyRent() : BigDecimal.ZERO);
                totalCall = totalCall.add(r.getCallFee() != null ? r.getCallFee() : BigDecimal.ZERO);
                totalRecording = totalRecording.add(r.getRecordingFee() != null ? r.getRecordingFee() : BigDecimal.ZERO);
                totalCrbt = totalCrbt.add(r.getCrbtFee() != null ? r.getCrbtFee() : BigDecimal.ZERO);
                totalFlash = totalFlash.add(r.getFlashMsgFee() != null ? r.getFlashMsgFee() : BigDecimal.ZERO);
                totalFee = totalFee.add(r.getTotalFee() != null ? r.getTotalFee() : BigDecimal.ZERO);
                phoneCount += r.getPhoneCount() != null ? r.getPhoneCount() : 0;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", batch.getId());
            row.put("billing_month", batch.getBillingMonth());
            row.put("total_fee", totalFee);
            row.put("monthly_rent", totalRent);
            row.put("call_fee", totalCall);
            row.put("recording_fee", totalRecording);
            row.put("crbt_fee", totalCrbt);
            row.put("flash_msg_fee", totalFlash);
            row.put("phone_count", phoneCount);
            row.put("org_count", (int) results.stream().filter(r -> r.getOrgId() != null && r.getOrgId() != -1L).count());
            rows.add(row);
        }

        return rows;
    }

    // === Helper methods ===

    private Long findAncestorByType(Map<Long, SysOrganization> orgMap, Long orgId, byte type) {
        Set<Long> visited = new HashSet<>();
        Long cur = orgId;
        while (cur != null && !visited.contains(cur)) {
            SysOrganization org = orgMap.get(cur);
            if (org == null) break;
            if (org.getType() != null && org.getType() == type) return cur;
            visited.add(cur);
            cur = org.getParentId();
        }
        return null;
    }

    private Long findDirectChildAncestor(Map<Long, SysOrganization> orgMap, Long orgId, Set<Long> childIds) {
        Set<Long> visited = new HashSet<>();
        Long cur = orgId;
        while (cur != null && !visited.contains(cur)) {
            if (childIds.contains(cur)) return cur;
            visited.add(cur);
            SysOrganization org = orgMap.get(cur);
            if (org == null) break;
            cur = org.getParentId();
        }
        return null;
    }

    private BigDecimal sumField(List<AllocationResult> results, java.util.function.Function<AllocationResult, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AllocationResult r : results) {
            BigDecimal v = getter.apply(r);
            if (v != null) sum = sum.add(v);
        }
        return sum;
    }

    private void addBreakdownItem(List<Map<String, Object>> list, String name, BigDecimal value, BigDecimal total) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            item.put("percent", value.multiply(new BigDecimal("100")).divide(total, 1, BigDecimal.ROUND_HALF_UP) + "%");
        } else {
            item.put("percent", "0%");
        }
        list.add(item);
    }
}
