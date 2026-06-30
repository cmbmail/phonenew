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
    public Map<String, Object> analyzeAll(Long batchId, DataScope scope) {
        List<AllocationResult> allResults = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        // Apply DataScope filtering
        List<AllocationResult> results = scope.filterByOrgId(allResults, AllocationResult::getOrgId);

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
     * 号码列表：返回所有号码的累计费用汇总，按总费用降序，支持按一级分行过滤
     */
    public Map<String, Object> analyzePhoneList(Long l1OrgId, DataScope scope) {
        List<BillDetail> allDetails = billDetailRepository.findAll();

        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        // If l1OrgId provided, build descendant org ID set for filtering
        Set<Long> filterOrgIds = null;
        if (l1OrgId != null) {
            SysOrganization l1 = orgMap.get(l1OrgId);
            if (l1 != null && l1.getPath() != null) {
                String l1Path = l1.getPath();
                filterOrgIds = new HashSet<>();
                for (SysOrganization o : orgMap.values()) {
                    if (o.getPath() != null && o.getPath().startsWith(l1Path)) {
                        filterOrgIds.add(o.getId());
                    }
                }
            }
        }

        // Apply DataScope: intersect with scope's visibleOrgIds
        if (!scope.isAllScope()) {
            List<Long> scopeOrgIds = scope.getVisibleOrgIds();
            if (scopeOrgIds != null) {
                Set<Long> scopeSet = new HashSet<>(scopeOrgIds);
                if (filterOrgIds != null) {
                    filterOrgIds.retainAll(scopeSet);
                } else {
                    filterOrgIds = scopeSet;
                }
            }
        }

        // Group by phone_number
        Map<String, List<BillDetail>> byPhone = new LinkedHashMap<>();
        for (BillDetail d : allDetails) {
            if (filterOrgIds != null) {
                if (d.getOrgId() == null || !filterOrgIds.contains(d.getOrgId())) continue;
            }
            byPhone.computeIfAbsent(d.getPhoneNumber(), k -> new ArrayList<>()).add(d);
        }

        // Get batch map for billing_month resolution
        Map<Long, BillBatch> batchMap = new HashMap<>();
        for (BillBatch b : billBatchRepository.findAll()) {
            batchMap.put(b.getId(), b);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<BillDetail>> entry : byPhone.entrySet()) {
            String phone = entry.getKey();
            List<BillDetail> details = entry.getValue();

            BigDecimal totalRent = BigDecimal.ZERO, totalCall = BigDecimal.ZERO, totalRecording = BigDecimal.ZERO;
            BigDecimal totalCrbt = BigDecimal.ZERO, totalFlash = BigDecimal.ZERO, totalFee = BigDecimal.ZERO;

            Set<Long> batchIds = new HashSet<>();
            for (BillDetail d : details) {
                totalRent = totalRent.add(d.getMonthlyRent() != null ? d.getMonthlyRent() : BigDecimal.ZERO);
                totalCall = totalCall.add(d.getCallFee() != null ? d.getCallFee() : BigDecimal.ZERO);
                totalRecording = totalRecording.add(d.getRecordingFee() != null ? d.getRecordingFee() : BigDecimal.ZERO);
                totalCrbt = totalCrbt.add(d.getCrbtFee() != null ? d.getCrbtFee() : BigDecimal.ZERO);
                totalFlash = totalFlash.add(d.getFlashMsgFee() != null ? d.getFlashMsgFee() : BigDecimal.ZERO);
                totalFee = totalFee.add(d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO);
                batchIds.add(d.getBatchId());
            }

            // Get org info from the latest batch's first detail
            Long latestBatchId = batchIds.stream().max(Long::compareTo).orElse(null);
            BillDetail latestDetail = details.stream()
                    .filter(d -> d.getBatchId().equals(latestBatchId))
                    .findFirst().orElse(details.get(0));
            SysOrganization org = latestDetail.getOrgId() != null ? orgMap.get(latestDetail.getOrgId()) : null;
            String orgName = org != null ? org.getName() : "";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phone_number", phone);
            row.put("org_name", orgName);
            row.put("ownership_source", latestDetail.getOwnershipSource() != null ? latestDetail.getOwnershipSource() : "");
            row.put("total_fee", totalFee);
            row.put("monthly_rent", totalRent);
            row.put("call_fee", totalCall);
            row.put("recording_fee", totalRecording);
            row.put("crbt_fee", totalCrbt);
            row.put("flash_msg_fee", totalFlash);
            row.put("month_count", batchIds.size());
            row.put("detail_count", details.size());
            rows.add(row);
        }

        // Sort by total_fee DESC
        rows.sort((a, b) -> ((BigDecimal) b.getOrDefault("total_fee", BigDecimal.ZERO))
                .compareTo((BigDecimal) a.getOrDefault("total_fee", BigDecimal.ZERO)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_count", rows.size());
        result.put("rows", rows);
        return result;
    }

    /**
     * 单个号码维度：查询指定号码近一年的月度费用清单
     */
    public Map<String, Object> analyzePhone(String phoneNumber, DataScope scope) {
        List<BillDetail> details = billDetailRepository.findByPhoneNumberAndDeletedAtIsNull(phoneNumber);
        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        // Apply DataScope: only include details belonging to visible orgs
        if (!scope.isAllScope()) {
            List<Long> visibleIds = scope.getVisibleOrgIds();
            if (visibleIds != null) {
                Set<Long> visibleSet = new HashSet<>(visibleIds);
                details = details.stream()
                        .filter(d -> d.getOrgId() == null || visibleSet.contains(d.getOrgId()))
                        .toList();
            }
        }

        // Group by batch_id (month)
        Map<Long, List<BillDetail>> byBatch = details.stream()
                .collect(Collectors.groupingBy(BillDetail::getBatchId));

        List<Map<String, Object>> rows = new ArrayList<>();
        String latestOrgName = "";
        String latestSource = "";

        for (Map.Entry<Long, List<BillDetail>> entry : byBatch.entrySet()) {
            List<BillDetail> batchDetails = entry.getValue();

            BigDecimal totalRent = BigDecimal.ZERO, totalCall = BigDecimal.ZERO, totalRecording = BigDecimal.ZERO;
            BigDecimal totalCrbt = BigDecimal.ZERO, totalFlash = BigDecimal.ZERO, total = BigDecimal.ZERO;

            for (BillDetail d : batchDetails) {
                totalRent = totalRent.add(d.getMonthlyRent() != null ? d.getMonthlyRent() : BigDecimal.ZERO);
                totalCall = totalCall.add(d.getCallFee() != null ? d.getCallFee() : BigDecimal.ZERO);
                totalRecording = totalRecording.add(d.getRecordingFee() != null ? d.getRecordingFee() : BigDecimal.ZERO);
                totalCrbt = totalCrbt.add(d.getCrbtFee() != null ? d.getCrbtFee() : BigDecimal.ZERO);
                totalFlash = totalFlash.add(d.getFlashMsgFee() != null ? d.getFlashMsgFee() : BigDecimal.ZERO);
                total = total.add(d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO);
            }

            BillBatch batch = billBatchRepository.findById(entry.getKey()).orElse(null);
            BillDetail first = batchDetails.get(0);
            SysOrganization org = first.getOrgId() != null ? orgMap.get(first.getOrgId()) : null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("billing_month", batch != null ? batch.getBillingMonth() : "");
            row.put("phone_number", phoneNumber);
            row.put("org_id", first.getOrgId());
            row.put("org_name", org != null ? org.getName() : "");
            row.put("ownership_source", first.getOwnershipSource());
            row.put("total_fee", total);
            row.put("monthly_rent", totalRent);
            row.put("call_fee", totalCall);
            row.put("recording_fee", totalRecording);
            row.put("crbt_fee", totalCrbt);
            row.put("flash_msg_fee", totalFlash);
            row.put("detail_count", batchDetails.size());
            rows.add(row);

            latestOrgName = org != null ? org.getName() : "";
            latestSource = first.getOwnershipSource() != null ? first.getOwnershipSource() : "";
        }

        // Sort by billing_month ascending for charts
        rows.sort((a, b) -> String.valueOf(a.getOrDefault("billing_month", ""))
                .compareTo(String.valueOf(b.getOrDefault("billing_month", ""))));

        // Summary stats
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            grandTotal = grandTotal.add((BigDecimal) r.get("total_fee"));
        }
        BigDecimal avgMonthly = rows.size() > 0 ? grandTotal.divide(new BigDecimal(rows.size()), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO;

        // MoM change (latest vs previous)
        String momChange = null;
        if (rows.size() >= 2) {
            BigDecimal prev = (BigDecimal) rows.get(rows.size() - 2).get("total_fee");
            BigDecimal cur = (BigDecimal) rows.get(rows.size() - 1).get("total_fee");
            if (prev.compareTo(BigDecimal.ZERO) > 0) {
                momChange = cur.subtract(prev).multiply(new BigDecimal("100")).divide(prev, 1, BigDecimal.ROUND_HALF_UP).toPlainString();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phone_number", phoneNumber);
        result.put("org_name", latestOrgName);
        result.put("ownership_source", latestSource);
        result.put("month_count", rows.size());
        result.put("total_fee", grandTotal);
        result.put("avg_monthly_fee", avgMonthly);
        result.put("mom_change", momChange);
        result.put("rows", rows);

        return result;
    }

    /**
     * 月度总费用对比：返回近12个月（或所有有数据的月份）的费用汇总
     */
    public List<Map<String, Object>> monthlyComparison(DataScope scope) {
        List<BillBatch> batches = billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (BillBatch batch : batches) {
            List<AllocationResult> allResults = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batch.getId());
            // Apply DataScope filtering
            List<AllocationResult> results = scope.filterByOrgId(allResults, AllocationResult::getOrgId);

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

    /**
     * 一级分行月度费用：指定L1分行在各月的费用汇总（含同比数据）
     */
    public Map<String, Object> analyzeL1Monthly(Long orgId) {
        return analyzeOrgMonthly(orgId);
    }

    /**
     * 二级分行月度费用：指定L2分行在各月的费用汇总（含同比数据）
     */
    public Map<String, Object> analyzeL2Monthly(Long orgId) {
        return analyzeOrgMonthly(orgId);
    }

    /**
     * 部门月度费用：指定部门在各月的费用汇总（含同比数据）
     */
    public Map<String, Object> analyzeDeptMonthly(Long orgId) {
        return analyzeOrgMonthly(orgId);
    }

    /**
     * 通用组织月度费用分析：指定组织在各月的费用汇总（含同比数据）
     */
    private Map<String, Object> analyzeOrgMonthly(Long orgId) {
        List<BillBatch> allBatches = billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        Map<Long, SysOrganization> orgMap = orgRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));

        SysOrganization targetOrg = orgMap.get(orgId);
        String orgName = targetOrg != null ? targetOrg.getName() : "";

        // Collect all descendant org IDs under this L1
        Set<Long> descendantIds = new HashSet<>();
        if (targetOrg != null) {
            String targetPath = targetOrg.getPath();
            for (SysOrganization o : orgMap.values()) {
                if (o.getPath() != null && o.getPath().startsWith(targetPath)) {
                    descendantIds.add(o.getId());
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillBatch batch : allBatches) {
            List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batch.getId());

            BigDecimal totalRent = BigDecimal.ZERO, totalCall = BigDecimal.ZERO, totalRecording = BigDecimal.ZERO;
            BigDecimal totalCrbt = BigDecimal.ZERO, totalFlash = BigDecimal.ZERO, totalFee = BigDecimal.ZERO;
            int phoneCount = 0, subOrgCount = 0;

            for (AllocationResult r : results) {
                if (r.getOrgId() == null || !descendantIds.contains(r.getOrgId())) continue;
                totalRent = totalRent.add(r.getMonthlyRent() != null ? r.getMonthlyRent() : BigDecimal.ZERO);
                totalCall = totalCall.add(r.getCallFee() != null ? r.getCallFee() : BigDecimal.ZERO);
                totalRecording = totalRecording.add(r.getRecordingFee() != null ? r.getRecordingFee() : BigDecimal.ZERO);
                totalCrbt = totalCrbt.add(r.getCrbtFee() != null ? r.getCrbtFee() : BigDecimal.ZERO);
                totalFlash = totalFlash.add(r.getFlashMsgFee() != null ? r.getFlashMsgFee() : BigDecimal.ZERO);
                totalFee = totalFee.add(r.getTotalFee() != null ? r.getTotalFee() : BigDecimal.ZERO);
                phoneCount += r.getPhoneCount() != null ? r.getPhoneCount() : 0;
                subOrgCount++;
            }

            if (totalFee.compareTo(BigDecimal.ZERO) == 0) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("billing_month", batch.getBillingMonth());
            row.put("total_fee", totalFee);
            row.put("monthly_rent", totalRent);
            row.put("call_fee", totalCall);
            row.put("recording_fee", totalRecording);
            row.put("crbt_fee", totalCrbt);
            row.put("flash_msg_fee", totalFlash);
            row.put("phone_count", phoneCount);
            row.put("sub_org_count", subOrgCount);
            rows.add(row);
        }

        // Build YoY map: billing_month -> {this_year, last_year}
        // Group by month number (e.g. "01", "02") across years
        Map<String, Map<String, BigDecimal>> yoyMap = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String month = (String) r.get("billing_month");
            String monthNum = month.substring(5);  // "01", "02", etc.
            yoyMap.computeIfAbsent(monthNum, k -> new LinkedHashMap<>());
            // Determine year from billing_month
            String year = month.substring(0, 4);
            yoyMap.get(monthNum).put(year, (BigDecimal) r.get("total_fee"));
        }

        // Build YoY comparison rows
        List<Map<String, Object>> yoyRows = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String month = (String) r.get("billing_month");
            String monthNum = month.substring(5);
            String year = month.substring(0, 4);
            String prevYear = String.valueOf(Integer.parseInt(year) - 1);

            Map<String, BigDecimal> yearMap = yoyMap.get(monthNum);
            BigDecimal lastYearFee = yearMap != null ? yearMap.getOrDefault(prevYear, null) : null;

            Map<String, Object> yoyRow = new LinkedHashMap<>(r);
            yoyRow.put("last_year_fee", lastYearFee);
            yoyRow.put("last_year_month", lastYearFee != null ? prevYear + "-" + monthNum : null);

            // YoY change
            if (lastYearFee != null && lastYearFee.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentFee = (BigDecimal) r.get("total_fee");
                yoyRow.put("yoy_change", currentFee.subtract(lastYearFee)
                        .multiply(new BigDecimal("100"))
                        .divide(lastYearFee, 1, BigDecimal.ROUND_HALF_UP)
                        .toPlainString());
            } else {
                yoyRow.put("yoy_change", null);
            }
            yoyRows.add(yoyRow);
        }

        // Summary
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            grandTotal = grandTotal.add((BigDecimal) r.get("total_fee"));
        }
        BigDecimal avgMonthly = rows.size() > 0 ? grandTotal.divide(new BigDecimal(rows.size()), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("org_id", orgId);
        result.put("org_name", orgName);
        result.put("month_count", rows.size());
        result.put("total_fee", grandTotal);
        result.put("avg_monthly_fee", avgMonthly);
        result.put("rows", yoyRows);

        return result;
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
