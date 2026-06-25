package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 费用分摊计算服务
 * 1. 按org_id汇总bill_detail的费用（6个字段+号码数）
 * 2. 向上级组织级联汇总（部门→分行→集团）
 * 3. 处理org_id=null的未归属费用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationService {

    private final BillBatchRepository billBatchRepository;
    private final BillDetailRepository billDetailRepository;
    private final AllocationResultRepository allocationResultRepository;
    private final SysOrganizationRepository orgRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public List<AllocationResult> calculateAllocation(Long billBatchId) {
        BillBatch batch = billBatchRepository.findById(billBatchId)
                .orElseThrow(() -> new IllegalArgumentException("账单批次不存在: " + billBatchId));

        // Check batch status
        if (batch.getStatus() == (byte) 2 || batch.getStatus() == (byte) 3) {
            throw new IllegalArgumentException("账单已确认或已锁定，不能重新分摊");
        }

        // Hard-delete existing allocation results for this batch (if recalculating)
        // Must use hard delete because unique constraint uk_batch_org(batch_id, org_id) 
        // does not include deleted_at, so soft-deleted rows would block new inserts
        allocationResultRepository.hardDeleteByBatchId(billBatchId);
        log.info("Hard-deleted existing allocation results for batch {}", billBatchId);

        // Get all bill details
        List<BillDetail> details = billDetailRepository.findByBatchIdAndDeletedAtIsNull(billBatchId);

        // Phase 1: Aggregate by org_id at leaf level (direct assignment)
        // Key: orgId (null means unassigned), Value: aggregated fees
        Map<Long, FeeAggregator> orgFees = new LinkedHashMap<>();
        FeeAggregator unassigned = new FeeAggregator();

        for (BillDetail detail : details) {
            Long orgId = detail.getOrgId();
            if (orgId == null) {
                unassigned.add(detail);
            } else {
                orgFees.computeIfAbsent(orgId, k -> new FeeAggregator()).add(detail);
            }
        }

        // Phase 2: Build org tree and cascade up
        // Load all organizations for tree building
        List<SysOrganization> allOrgs = orgRepository.findAll();
        Map<Long, SysOrganization> orgMap = new HashMap<>();
        Map<Long, List<Long>> childrenMap = new HashMap<>(); // parentId -> childIds

        for (SysOrganization org : allOrgs) {
            if (org.getDeletedAt() != null) continue;
            orgMap.put(org.getId(), org);
            if (org.getParentId() != null) {
                childrenMap.computeIfAbsent(org.getParentId(), k -> new ArrayList<>()).add(org.getId());
            }
        }

        // Create allocation results for each leaf org
        List<AllocationResult> results = new ArrayList<>();
        for (Map.Entry<Long, FeeAggregator> entry : orgFees.entrySet()) {
            Long orgId = entry.getKey();
            FeeAggregator fees = entry.getValue();
            SysOrganization org = orgMap.get(orgId);

            AllocationResult result = AllocationResult.builder()
                    .batchId(billBatchId)
                    .orgId(orgId)
                    .orgName(org != null ? org.getName() : "未知组织")
                    .costCenter(org != null ? org.getCostCenter() : null)
                    .monthlyRent(fees.monthlyRent)
                    .callFee(fees.callFee)
                    .recordingFee(fees.recordingFee)
                    .crbtFee(fees.crbtFee)
                    .flashMsgFee(fees.flashMsgFee)
                    .totalFee(fees.totalFee)
                    .phoneCount(fees.phoneCount)
                    .confirmStatus((byte) 0) // PENDING
                    .build();
            results.add(result);
        }

        // Add unassigned (P3) fees as a special result with orgId=-1
        if (unassigned.phoneCount > 0) {
            AllocationResult unassignedResult = AllocationResult.builder()
                    .batchId(billBatchId)
                    .orgId(-1L) // sentinel for unassigned
                    .orgName("未归属号码")
                    .costCenter(null)
                    .monthlyRent(unassigned.monthlyRent)
                    .callFee(unassigned.callFee)
                    .recordingFee(unassigned.recordingFee)
                    .crbtFee(unassigned.crbtFee)
                    .flashMsgFee(unassigned.flashMsgFee)
                    .totalFee(unassigned.totalFee)
                    .phoneCount(unassigned.phoneCount)
                    .confirmStatus((byte) 0)
                    .build();
            results.add(unassignedResult);
        }

        // Phase 3: Cascade up - for each parent org, sum all descendant fees
        // We need allocation results for parent orgs too (they show aggregated totals)
        Set<Long> processedOrgs = new HashSet<>(orgFees.keySet());
        for (Long leafOrgId : orgFees.keySet()) {
            cascadeUp(leafOrgId, orgMap, billBatchId, results, processedOrgs);
        }

        // Save all results (with defaults for nullable fields)
        results.forEach(this::fillDefaults);
        allocationResultRepository.saveAll(results);

        // Update batch status to ALLOCATED
        batch.setStatus((byte) 1);
        billBatchRepository.save(batch);

        log.info("Allocation calculated: batch={}, orgs={}, unassigned_phones={}, total_fee={}",
                billBatchId, results.size(), unassigned.phoneCount,
                results.stream().map(AllocationResult::getTotalFee).reduce(BigDecimal.ZERO, BigDecimal::add));

        auditLogService.log(0L, "system", "ALLOCATION_CALCULATE", "bill_batch", billBatchId,
                "{\"org_count\":" + results.size() + "}");

        return results;
    }

    /**
     * Cascade fees up the org tree.
     * For each ancestor org that's not yet in results, create a parent allocation result
     * with aggregated fees from all its descendants.
     */
    private void cascadeUp(Long orgId, Map<Long, SysOrganization> orgMap,
                           Long batchId, List<AllocationResult> results, Set<Long> processedOrgs) {
        SysOrganization org = orgMap.get(orgId);
        if (org == null || org.getParentId() == null) return;

        Long parentId = org.getParentId();
        if (!processedOrgs.contains(parentId)) {
            // Find all descendant results for this parent
            SysOrganization parent = orgMap.get(parentId);
            if (parent == null) return;

            FeeAggregator parentFees = new FeeAggregator();
            int totalPhoneCount = 0;

            // Sum all results whose orgId is a descendant of parentId
            String parentPath = parent.getPath();
            for (AllocationResult r : results) {
                SysOrganization rOrg = orgMap.get(r.getOrgId());
                if (rOrg != null && rOrg.getPath() != null && rOrg.getPath().startsWith(parentPath)) {
                    parentFees.monthlyRent = parentFees.monthlyRent.add(r.getMonthlyRent());
                    parentFees.callFee = parentFees.callFee.add(r.getCallFee());
                    parentFees.recordingFee = parentFees.recordingFee.add(r.getRecordingFee());
                    parentFees.crbtFee = parentFees.crbtFee.add(r.getCrbtFee());
                    parentFees.flashMsgFee = parentFees.flashMsgFee.add(r.getFlashMsgFee());
                    parentFees.totalFee = parentFees.totalFee.add(r.getTotalFee());
                    totalPhoneCount += r.getPhoneCount();
                }
            }

            AllocationResult parentResult = AllocationResult.builder()
                    .batchId(batchId)
                    .orgId(parentId)
                    .orgName(parent.getName())
                    .costCenter(parent.getCostCenter())
                    .monthlyRent(parentFees.monthlyRent)
                    .callFee(parentFees.callFee)
                    .recordingFee(parentFees.recordingFee)
                    .crbtFee(parentFees.crbtFee)
                    .flashMsgFee(parentFees.flashMsgFee)
                    .totalFee(parentFees.totalFee)
                    .phoneCount(totalPhoneCount)
                    .confirmStatus((byte) 0)
                    .build();
            results.add(parentResult);
            processedOrgs.add(parentId);
        }

        // Recurse up
        cascadeUp(parentId, orgMap, batchId, results, processedOrgs);
    }

    /**
     * Fee aggregator helper
     */
    static class FeeAggregator {
        BigDecimal monthlyRent = BigDecimal.ZERO;
        BigDecimal callFee = BigDecimal.ZERO;
        BigDecimal recordingFee = BigDecimal.ZERO;
        BigDecimal crbtFee = BigDecimal.ZERO;
        BigDecimal flashMsgFee = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int phoneCount = 0;

        // Track unique phone numbers for phone count
        Set<String> uniquePhones = new HashSet<>();

        void add(BillDetail detail) {
            this.monthlyRent = this.monthlyRent.add(safe(detail.getMonthlyRent()));
            this.callFee = this.callFee.add(safe(detail.getCallFee()));
            this.recordingFee = this.recordingFee.add(safe(detail.getRecordingFee()));
            this.crbtFee = this.crbtFee.add(safe(detail.getCrbtFee()));
            this.flashMsgFee = this.flashMsgFee.add(safe(detail.getFlashMsgFee()));
            this.totalFee = this.totalFee.add(safe(detail.getTotalFee()));
            // Only count unique phone numbers from CALL sheet
            if ("CALL".equals(detail.getSheetType()) && detail.getPhoneNumber() != null) {
                if (uniquePhones.add(detail.getPhoneNumber())) {
                    this.phoneCount++;
                }
            }
        }

        private BigDecimal safe(BigDecimal v) {
            return v != null ? v : BigDecimal.ZERO;
        }
    }

    private void fillDefaults(AllocationResult r) {
        if (r.getOrgName() == null) r.setOrgName("");
        if (r.getMonthlyRent() == null) r.setMonthlyRent(BigDecimal.ZERO);
        if (r.getCallFee() == null) r.setCallFee(BigDecimal.ZERO);
        if (r.getRecordingFee() == null) r.setRecordingFee(BigDecimal.ZERO);
        if (r.getCrbtFee() == null) r.setCrbtFee(BigDecimal.ZERO);
        if (r.getFlashMsgFee() == null) r.setFlashMsgFee(BigDecimal.ZERO);
        if (r.getTotalFee() == null) r.setTotalFee(BigDecimal.ZERO);
        if (r.getPhoneCount() == null) r.setPhoneCount(0);
        if (r.getConfirmStatus() == null) r.setConfirmStatus((byte) 0);
        if (r.getWithdrawReason() == null) r.setWithdrawReason("");
        if (r.getVersion() == null) r.setVersion(0);
    }
}
