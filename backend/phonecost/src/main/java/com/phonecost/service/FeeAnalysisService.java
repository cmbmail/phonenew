package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** Cached org map — built once per request cycle. ThreadLocal ensures request isolation. */
    private final ThreadLocal<Map<Long, SysOrganization>> orgMapCache = new ThreadLocal<>();

    /** Build or return cached org map. H-B05: each public method MUST call clearOrgMapCache() in finally block. */
    private Map<Long, SysOrganization> buildOrgMap() {
        Map<Long, SysOrganization> cached = orgMapCache.get();
        if (cached != null) return cached;
        cached = orgRepository.findByDeletedAtIsNull().stream()
                .collect(Collectors.toMap(SysOrganization::getId, o -> o, (a, b) -> a));
        orgMapCache.set(cached);
        return cached;
    }

    private void clearOrgMapCache() {
        orgMapCache.remove();
    }

    /**
     * 全部维度：返回总体费用汇总
     * Fallback: when allocation_result has no data for the batch, falls back to bill_detail aggregation
     */
    public Map<String, Object> analyzeAll(Long batchId, DataScope scope) {
        clearOrgMapCache();
        try {  // H-B05: ThreadLocal leak fix
            List<AllocationResult> allResults = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
            // Apply DataScope filtering
            List<AllocationResult> results = scope.filterByOrgId(allResults, AllocationResult::getOrgId);

            // Fallback: if allocation_result is empty, aggregate from bill_detail
            if (results.isEmpty()) {
                log.info("analyzeAll: allocation_result empty for batchId={}, falling back to bill_detail", batchId);
                return analyzeAllFromBillDetail(batchId, scope);
            }

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
            data.put("data_source", "allocation_result");

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
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 一级分行维度：按一级分行汇总
     * Fallback: when allocation_result has no data, falls back to bill_detail aggregation
     */
    public List<Map<String, Object>> analyzeL1(Long batchId) {
        clearOrgMapCache();
        try {  // H-B05: ThreadLocal leak fix
            List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
            List<SysOrganization> l1Orgs = orgRepository.findByTypeAndDeletedAtIsNull((byte) 2);

            // Fallback: if allocation_result is empty, aggregate from bill_detail
            if (results.isEmpty()) {
                log.info("analyzeL1: allocation_result empty for batchId={}, falling back to bill_detail", batchId);
                return analyzeL1FromBillDetail(batchId, l1Orgs);
            }

            Map<Long, SysOrganization> orgMap = buildOrgMap();

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
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 二级分行维度：按指定一级分行下的二级分行汇总
     * Fallback: when allocation_result has no data, falls back to bill_detail aggregation
     */
    public List<Map<String, Object>> analyzeL2(Long batchId, Long l1OrgId) {
        clearOrgMapCache();
        try {  // H-B05: ThreadLocal leak fix
            List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
            Map<Long, SysOrganization> orgMap = buildOrgMap();

            // Find all L2 orgs under the given L1
            SysOrganization l1 = orgMap.get(l1OrgId);
            if (l1 == null) return Collections.emptyList();
            String l1Path = l1.getPath();
            List<SysOrganization> l2Orgs = orgRepository.findAllDescendants(l1Path).stream()
                    .filter(o -> o.getType() != null && o.getType() == 3)
                    .toList();

            // Fallback: if allocation_result is empty, aggregate from bill_detail
            if (results.isEmpty()) {
                log.info("analyzeL2: allocation_result empty for batchId={}, falling back to bill_detail", batchId);
                return analyzeOrgLevelFromBillDetail(batchId, l2Orgs, orgMap);
            }

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
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 部门维度：按指定组织下的直属部门汇总
     * Fallback: when allocation_result has no data, falls back to bill_detail aggregation
     */
    public List<Map<String, Object>> analyzeDepartment(Long batchId, Long parentOrgId) {
        clearOrgMapCache();
        try {  // H-B05: ThreadLocal leak fix
            List<AllocationResult> results = allocationResultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
            Map<Long, SysOrganization> orgMap = buildOrgMap();

            // Get direct children orgs
            List<SysOrganization> children = orgRepository.findByParentIdAndDeletedAtIsNull(parentOrgId);

            // Fallback: if allocation_result is empty, aggregate from bill_detail
            if (results.isEmpty()) {
                log.info("analyzeDepartment: allocation_result empty for batchId={}, falling back to bill_detail", batchId);
                return analyzeOrgLevelFromBillDetail(batchId, children, orgMap);
            }
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
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 号码列表：返回所有号码的累计费用汇总，按总费用降序，支持按一级分行过滤
     * Optimized: SQL aggregation instead of loading ALL BillDetail entities
     */
    public Map<String, Object> analyzePhoneList(Long l1OrgId, DataScope scope) {
        clearOrgMapCache();
        try {  // H-B05: ThreadLocal leak fix
            Map<Long, SysOrganization> orgMap = buildOrgMap();

            // Build filter org ID set
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

            // SQL aggregation — avoids loading ALL BillDetail entities
            List<Object[]> aggResults;
            Map<String, Object[]> latestInfoMap;

            if (filterOrgIds != null && !filterOrgIds.isEmpty()) {
                aggResults = billDetailRepository.aggregateByOrgIdsGroupByPhoneNumber(filterOrgIds);
                latestInfoMap = buildLatestInfoMap(billDetailRepository.findLatestDetailPerPhoneByOrgIdsNative(filterOrgIds));
            } else {
                aggResults = billDetailRepository.aggregateAllGroupByPhoneNumber();
                latestInfoMap = buildLatestInfoMap(billDetailRepository.findLatestDetailPerPhoneNative());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object[] agg : aggResults) {
                String phone = (String) agg[0];
                Object[] latest = latestInfoMap.get(phone);

                Long latestOrgId = latest != null && latest[1] != null ? ((Number) latest[1]).longValue() : null;
                String ownershipSource = latest != null && latest[2] != null ? (String) latest[2] : "";
                SysOrganization org = latestOrgId != null ? orgMap.get(latestOrgId) : null;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("phone_number", phone);
                row.put("org_name", org != null ? org.getName() : "");
                row.put("ownership_source", ownershipSource);
                row.put("total_fee", agg[6]);   // SUM(total_fee)
                row.put("monthly_rent", agg[1]); // SUM(monthly_rent)
                row.put("call_fee", agg[2]);     // SUM(call_fee)
                row.put("recording_fee", agg[3]); // SUM(recording_fee)
                row.put("crbt_fee", agg[4]);      // SUM(crbt_fee)
                row.put("flash_msg_fee", agg[5]);  // SUM(flash_msg_fee)
                row.put("month_count", agg[7]);    // COUNT(DISTINCT batch_id)
                row.put("detail_count", agg[8]);   // COUNT(*)
                rows.add(row);
            }

            // Sort by total_fee DESC
            rows.sort((a, b) -> {
                BigDecimal fa = toBigDecimal(a.get("total_fee"));
                BigDecimal fb = toBigDecimal(b.get("total_fee"));
                return fb.compareTo(fa);
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_count", rows.size());
            result.put("rows", rows);
            return result;
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 单个号码维度：查询指定号码近一年的月度费用清单
     */
    public Map<String, Object> analyzePhone(String phoneNumber, DataScope scope) {
        clearOrgMapCache();
        try {  // H-B05: ThreadLocal leak fix
            List<BillDetail> details = billDetailRepository.findByPhoneNumberAndDeletedAtIsNull(phoneNumber);
            Map<Long, SysOrganization> orgMap = buildOrgMap();

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

                BillBatch batch = billBatchRepository.findByIdAndDeletedAtIsNull(entry.getKey()).orElse(null);
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
            BigDecimal avgMonthly = rows.size() > 0 ? grandTotal.divide(new BigDecimal(rows.size()), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            // MoM change (latest vs previous)
            String momChange = null;
            if (rows.size() >= 2) {
                BigDecimal prev = (BigDecimal) rows.get(rows.size() - 2).get("total_fee");
                BigDecimal cur = (BigDecimal) rows.get(rows.size() - 1).get("total_fee");
                if (prev.compareTo(BigDecimal.ZERO) > 0) {
                    momChange = cur.subtract(prev).multiply(new BigDecimal("100")).divide(prev, 1, RoundingMode.HALF_UP).toPlainString();
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
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 月度总费用对比：返回近12个月（或所有有数据的月份）的费用汇总
     * Optimized: single SQL aggregation instead of N+1 queries per batch
     * Fallback: when allocation_result has no data, falls back to bill_detail aggregation
     */
    public List<Map<String, Object>> monthlyComparison(DataScope scope) {
        // Load batch info (lightweight — typically 3-12 rows)
        List<BillBatch> batches = billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        Map<Long, BillBatch> batchMap = new LinkedHashMap<>();
        for (BillBatch b : batches) {
            batchMap.put(b.getId(), b);
        }

        // Try allocation_result first
        List<Object[]> aggResults;
        if (scope.isAllScope()) {
            aggResults = allocationResultRepository.aggregateAllGroupByBatchId();
        } else {
            List<Long> visibleOrgIds = scope.getVisibleOrgIds();
            if (visibleOrgIds == null || visibleOrgIds.isEmpty()) {
                return Collections.emptyList();
            }
            aggResults = allocationResultRepository.aggregateByOrgIdsGroupByBatchId(visibleOrgIds);
        }

        // Fallback: if allocation_result is empty, use bill_detail aggregation
        if (aggResults.isEmpty()) {
            log.info("monthlyComparison: allocation_result empty, falling back to bill_detail");
            if (scope.isAllScope()) {
                aggResults = billDetailRepository.aggregateAllGroupByBatchId();
            } else {
                List<Long> visibleOrgIds = scope.getVisibleOrgIds();
                if (visibleOrgIds != null && !visibleOrgIds.isEmpty()) {
                    aggResults = billDetailRepository.aggregateByOrgIdsGroupByBatchId(visibleOrgIds);
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] agg : aggResults) {
            Long batchId = ((Number) agg[0]).longValue();
            BillBatch batch = batchMap.get(batchId);
            if (batch == null) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", batchId);
            row.put("billing_month", batch.getBillingMonth());
            row.put("monthly_rent", agg[1]);
            row.put("call_fee", agg[2]);
            row.put("recording_fee", agg[3]);
            row.put("crbt_fee", agg[4]);
            row.put("flash_msg_fee", agg[5]);
            row.put("total_fee", agg[6]);
            row.put("phone_count", agg[7]);
            row.put("org_count", agg[8]);
            rows.add(row);
        }

        return rows;
    }

    /**
     * 一级分行月度费用：指定L1分行在各月的费用汇总（含同比数据）
     */
    public Map<String, Object> analyzeL1Monthly(Long orgId) {
        try {
            return analyzeOrgMonthly(orgId);
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 二级分行月度费用：指定L2分行在各月的费用汇总（含同比数据）
     */
    public Map<String, Object> analyzeL2Monthly(Long orgId) {
        try {
            return analyzeOrgMonthly(orgId);
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 部门月度费用：指定部门在各月的费用汇总（含同比数据）
     */
    public Map<String, Object> analyzeDeptMonthly(Long orgId) {
        try {
            return analyzeOrgMonthly(orgId);
        } finally {
            clearOrgMapCache();  // H-B05: ensure cleanup
        }
    }

    /**
     * 通用组织月度费用分析：指定组织在各月的费用汇总（含同比数据）
     * Optimized: single SQL aggregation instead of N queries per batch + in-memory filter
     * Fallback: when allocation_result has no data, falls back to bill_detail aggregation
     */
    private Map<String, Object> analyzeOrgMonthly(Long orgId) {
        clearOrgMapCache();
        List<BillBatch> allBatches = billBatchRepository.findByDeletedAtIsNullOrderByBillingMonthAsc();
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        SysOrganization targetOrg = orgMap.get(orgId);
        String orgName = targetOrg != null ? targetOrg.getName() : "";

        // Collect all descendant org IDs under this org
        Set<Long> descendantIds = new HashSet<>();
        if (targetOrg != null) {
            String targetPath = targetOrg.getPath();
            for (SysOrganization o : orgMap.values()) {
                if (o.getPath() != null && o.getPath().startsWith(targetPath)) {
                    descendantIds.add(o.getId());
                }
            }
        }

        // Try allocation_result first
        List<Object[]> aggResults = Collections.emptyList();
        boolean usedFallback = false;
        if (!descendantIds.isEmpty()) {
            aggResults = allocationResultRepository.aggregateByOrgIdsGroupByBatchId(descendantIds);
        }

        // Fallback: if allocation_result is empty, use bill_detail aggregation
        if (aggResults.isEmpty() && !descendantIds.isEmpty()) {
            log.info("analyzeOrgMonthly: allocation_result empty for orgId={}, falling back to bill_detail", orgId);
            aggResults = billDetailRepository.aggregateByOrgIdsGroupByBatchId(descendantIds);
            usedFallback = true;
        }

        // Build batch map for billing_month resolution
        Map<Long, BillBatch> batchMap = new LinkedHashMap<>();
        for (BillBatch b : allBatches) {
            batchMap.put(b.getId(), b);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] agg : aggResults) {
            Long batchId = ((Number) agg[0]).longValue();
            BillBatch batch = batchMap.get(batchId);
            if (batch == null) continue;

            BigDecimal totalFee = toBigDecimal(agg[6]);
            if (totalFee.compareTo(BigDecimal.ZERO) == 0) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("billing_month", batch.getBillingMonth());
            row.put("total_fee", totalFee);
            row.put("monthly_rent", agg[1]);
            row.put("call_fee", agg[2]);
            row.put("recording_fee", agg[3]);
            row.put("crbt_fee", agg[4]);
            row.put("flash_msg_fee", agg[5]);
            row.put("phone_count", agg[7]);
            row.put("sub_org_count", agg[8]);
            rows.add(row);
        }

        // Build YoY map: billing_month -> {this_year, last_year}
        // Group by month number (e.g. "01", "02") across years
        Map<String, Map<String, BigDecimal>> yoyMap = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String month = (String) r.get("billing_month");
            String monthNum = month.length() > 5 ? month.substring(5) : month;  // "01", "02" etc. — H-B07: safe substring
            yoyMap.computeIfAbsent(monthNum, k -> new LinkedHashMap<>());
            // Determine year from billing_month
            String year = month.length() >= 4 ? month.substring(0, 4) : "";  // H-B07: safe substring
            yoyMap.get(monthNum).put(year, toBigDecimal(r.get("total_fee")));
        }

        // Build YoY comparison rows
        List<Map<String, Object>> yoyRows = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String month = (String) r.get("billing_month");
            String monthNum = month.length() > 5 ? month.substring(5) : month;  // H-B07: safe substring
            String year = month.length() >= 4 ? month.substring(0, 4) : "";  // H-B07: safe substring
            String prevYear = String.valueOf(Integer.parseInt(year) - 1);

            Map<String, BigDecimal> yearMap = yoyMap.get(monthNum);
            BigDecimal lastYearFee = yearMap != null ? yearMap.getOrDefault(prevYear, null) : null;

            Map<String, Object> yoyRow = new LinkedHashMap<>(r);
            yoyRow.put("last_year_fee", lastYearFee);
            yoyRow.put("last_year_month", lastYearFee != null ? prevYear + "-" + monthNum : null);

            // YoY change
            if (lastYearFee != null && lastYearFee.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentFee = toBigDecimal(r.get("total_fee"));
                yoyRow.put("yoy_change", currentFee.subtract(lastYearFee)
                        .multiply(new BigDecimal("100"))
                        .divide(lastYearFee, 1, RoundingMode.HALF_UP)
                        .toPlainString());
            } else {
                yoyRow.put("yoy_change", null);
            }
            yoyRows.add(yoyRow);
        }

        // Summary
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            grandTotal = grandTotal.add(toBigDecimal(r.get("total_fee")));
        }
        BigDecimal avgMonthly = rows.size() > 0 ? grandTotal.divide(new BigDecimal(rows.size()), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

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

    /** Convert aggregation result value to BigDecimal (handles both BigDecimal and Number types from JPQL/native queries) */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    /** Build a map of phone_number -> latest detail info from native query results */
    private Map<String, Object[]> buildLatestInfoMap(List<Object[]> nativeResults) {
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Object[] row : nativeResults) {
            String phone = (String) row[0];
            map.put(phone, row);
        }
        return map;
    }

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
            item.put("percent", value.multiply(new BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP) + "%");
        } else {
            item.put("percent", "0%");
        }
        list.add(item);
    }

    // === Fallback methods: aggregate from bill_detail when allocation_result is empty ===

    /**
     * Fallback for analyzeAll: aggregate all fees from bill_detail for a given batch
     */
    private Map<String, Object> analyzeAllFromBillDetail(Long batchId, DataScope scope) {
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // Aggregate by org_id from bill_detail
        List<Object[]> orgAgg = billDetailRepository.aggregateByBatchIdGroupByOrgId(batchId);

        // Apply DataScope: filter org IDs
        Set<Long> visibleOrgIds = null;
        if (!scope.isAllScope()) {
            List<Long> scopeIds = scope.getVisibleOrgIds();
            if (scopeIds != null) visibleOrgIds = new HashSet<>(scopeIds);
        }

        BigDecimal totalRent = BigDecimal.ZERO, totalCall = BigDecimal.ZERO, totalRecording = BigDecimal.ZERO;
        BigDecimal totalCrbt = BigDecimal.ZERO, totalFlash = BigDecimal.ZERO, totalFee = BigDecimal.ZERO;
        int totalPhones = 0, orgCount = 0;

        // Collect per-org data for top-orgs ranking
        List<Map<String, Object>> orgRows = new ArrayList<>();

        for (Object[] agg : orgAgg) {
            Long orgId = agg[0] != null ? ((Number) agg[0]).longValue() : null;
            BigDecimal rent = toBigDecimal(agg[1]);
            BigDecimal call = toBigDecimal(agg[2]);
            BigDecimal recording = toBigDecimal(agg[3]);
            BigDecimal crbt = toBigDecimal(agg[4]);
            BigDecimal flash = toBigDecimal(agg[5]);
            BigDecimal fee = toBigDecimal(agg[6]);
            int phones = agg[7] != null ? ((Number) agg[7]).intValue() : 0;

            // DataScope filter
            if (visibleOrgIds != null && (orgId == null || !visibleOrgIds.contains(orgId))) continue;

            totalRent = totalRent.add(rent);
            totalCall = totalCall.add(call);
            totalRecording = totalRecording.add(recording);
            totalCrbt = totalCrbt.add(crbt);
            totalFlash = totalFlash.add(flash);
            totalFee = totalFee.add(fee);
            totalPhones += phones;
            orgCount++;

            SysOrganization org = orgId != null ? orgMap.get(orgId) : null;
            Map<String, Object> orgRow = new LinkedHashMap<>();
            orgRow.put("org_id", orgId);
            orgRow.put("org_name", org != null ? org.getName() : "");
            orgRow.put("total_fee", fee);
            orgRow.put("phone_count", phones);
            orgRows.add(orgRow);
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
        data.put("unassigned_fee", BigDecimal.ZERO);
        data.put("unassigned_phones", 0);
        data.put("data_source", "bill_detail");

        // Fee type breakdown for pie chart
        List<Map<String, Object>> feeBreakdown = new ArrayList<>();
        addBreakdownItem(feeBreakdown, "月租费", totalRent, totalFee);
        addBreakdownItem(feeBreakdown, "通话费", totalCall, totalFee);
        addBreakdownItem(feeBreakdown, "录音费", totalRecording, totalFee);
        addBreakdownItem(feeBreakdown, "彩铃费", totalCrbt, totalFee);
        addBreakdownItem(feeBreakdown, "闪信费", totalFlash, totalFee);
        data.put("fee_breakdown", feeBreakdown);

        // Top 10 orgs by total_fee
        orgRows.sort((a, b) -> toBigDecimal(b.get("total_fee")).compareTo(toBigDecimal(a.get("total_fee"))));
        data.put("top_orgs", orgRows.stream().limit(10).toList());

        clearOrgMapCache();
        return data;
    }

    /**
     * Fallback for analyzeL1: aggregate fees by L1 branch from bill_detail
     */
    private List<Map<String, Object>> analyzeL1FromBillDetail(Long batchId, List<SysOrganization> l1Orgs) {
        Map<Long, SysOrganization> orgMap = buildOrgMap();

        // Aggregate by org_id from bill_detail
        List<Object[]> orgAgg = billDetailRepository.aggregateByBatchIdGroupByOrgId(batchId);

        // Map each org's allocation to its L1 ancestor
        Map<Long, List<Object[]>> l1Groups = new LinkedHashMap<>();
        for (SysOrganization l1 : l1Orgs) {
            l1Groups.put(l1.getId(), new ArrayList<>());
        }

        for (Object[] agg : orgAgg) {
            Long orgId = agg[0] != null ? ((Number) agg[0]).longValue() : null;
            if (orgId == null) continue;
            Long l1Id = findAncestorByType(orgMap, orgId, (byte) 2);
            if (l1Id != null && l1Groups.containsKey(l1Id)) {
                l1Groups.get(l1Id).add(agg);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysOrganization l1 : l1Orgs) {
            List<Object[]> group = l1Groups.getOrDefault(l1.getId(), Collections.emptyList());
            if (group.isEmpty()) continue;

            BigDecimal rent = BigDecimal.ZERO, call = BigDecimal.ZERO, recording = BigDecimal.ZERO;
            BigDecimal crbt = BigDecimal.ZERO, flash = BigDecimal.ZERO, fee = BigDecimal.ZERO;
            int phones = 0;
            for (Object[] agg : group) {
                rent = rent.add(toBigDecimal(agg[1]));
                call = call.add(toBigDecimal(agg[2]));
                recording = recording.add(toBigDecimal(agg[3]));
                crbt = crbt.add(toBigDecimal(agg[4]));
                flash = flash.add(toBigDecimal(agg[5]));
                fee = fee.add(toBigDecimal(agg[6]));
                phones += agg[7] != null ? ((Number) agg[7]).intValue() : 0;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", l1.getId());
            row.put("org_name", l1.getName());
            row.put("code", l1.getCode());
            row.put("cost_center", l1.getCostCenter());
            row.put("monthly_rent", rent);
            row.put("call_fee", call);
            row.put("recording_fee", recording);
            row.put("crbt_fee", crbt);
            row.put("flash_msg_fee", flash);
            row.put("total_fee", fee);
            row.put("phone_count", phones);
            row.put("sub_org_count", group.size());
            row.put("data_source", "bill_detail");
            rows.add(row);
        }

        rows.sort((a, b) -> toBigDecimal(b.getOrDefault("total_fee", BigDecimal.ZERO))
                .compareTo(toBigDecimal(a.getOrDefault("total_fee", BigDecimal.ZERO))));

        clearOrgMapCache();
        return rows;
    }

    /**
     * Fallback for analyzeL2/Department: aggregate fees by org level from bill_detail
     * Works for both L2 branches and departments by passing the appropriate org list.
     */
    private List<Map<String, Object>> analyzeOrgLevelFromBillDetail(Long batchId, List<SysOrganization> targetOrgs, Map<Long, SysOrganization> orgMap) {
        // Aggregate by org_id from bill_detail
        List<Object[]> orgAgg = billDetailRepository.aggregateByBatchIdGroupByOrgId(batchId);

        // Build descendant sets for each target org
        Map<Long, Set<Long>> targetDescendants = new LinkedHashMap<>();
        for (SysOrganization target : targetOrgs) {
            Set<Long> descIds = new HashSet<>();
            String targetPath = target.getPath();
            for (SysOrganization o : orgMap.values()) {
                if (o.getPath() != null && o.getPath().startsWith(targetPath)) {
                    descIds.add(o.getId());
                }
            }
            targetDescendants.put(target.getId(), descIds);
        }

        // Map each org's allocation to the target org it belongs to
        Map<Long, List<Object[]>> targetGroups = new LinkedHashMap<>();
        for (SysOrganization target : targetOrgs) {
            targetGroups.put(target.getId(), new ArrayList<>());
        }

        for (Object[] agg : orgAgg) {
            Long orgId = agg[0] != null ? ((Number) agg[0]).longValue() : null;
            if (orgId == null) continue;
            // Find which target org this orgId belongs to
            for (Map.Entry<Long, Set<Long>> entry : targetDescendants.entrySet()) {
                if (entry.getValue().contains(orgId)) {
                    targetGroups.get(entry.getKey()).add(agg);
                    break;
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysOrganization target : targetOrgs) {
            List<Object[]> group = targetGroups.getOrDefault(target.getId(), Collections.emptyList());
            if (group.isEmpty()) continue;

            BigDecimal rent = BigDecimal.ZERO, call = BigDecimal.ZERO, recording = BigDecimal.ZERO;
            BigDecimal crbt = BigDecimal.ZERO, flash = BigDecimal.ZERO, fee = BigDecimal.ZERO;
            int phones = 0;
            for (Object[] agg : group) {
                rent = rent.add(toBigDecimal(agg[1]));
                call = call.add(toBigDecimal(agg[2]));
                recording = recording.add(toBigDecimal(agg[3]));
                crbt = crbt.add(toBigDecimal(agg[4]));
                flash = flash.add(toBigDecimal(agg[5]));
                fee = fee.add(toBigDecimal(agg[6]));
                phones += agg[7] != null ? ((Number) agg[7]).intValue() : 0;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("org_id", target.getId());
            row.put("org_name", target.getName());
            row.put("org_type", target.getType());
            row.put("code", target.getCode());
            row.put("cost_center", target.getCostCenter());
            row.put("monthly_rent", rent);
            row.put("call_fee", call);
            row.put("recording_fee", recording);
            row.put("crbt_fee", crbt);
            row.put("flash_msg_fee", flash);
            row.put("total_fee", fee);
            row.put("phone_count", phones);
            row.put("sub_org_count", group.size());
            row.put("data_source", "bill_detail");
            rows.add(row);
        }

        rows.sort((a, b) -> toBigDecimal(b.getOrDefault("total_fee", BigDecimal.ZERO))
                .compareTo(toBigDecimal(a.getOrDefault("total_fee", BigDecimal.ZERO))));

        clearOrgMapCache();
        return rows;
    }
}
