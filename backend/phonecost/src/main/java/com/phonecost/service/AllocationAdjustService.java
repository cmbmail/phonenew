package com.phonecost.service;

import com.phonecost.domain.AllocationAdjustment;
import com.phonecost.domain.AllocationResult;
import com.phonecost.domain.BillDetail;
import com.phonecost.domain.SysOrganization;
import com.phonecost.repository.AllocationAdjustmentRepository;
import com.phonecost.repository.AllocationResultRepository;
import com.phonecost.repository.BillDetailRepository;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 费用调整服务
 * 将指定号码的费用从一个组织调到另一个组织
 * 1. 更新 bill_detail 的 org_id
 * 2. 更新 from/to 的 AllocationResult 金额
 * 3. 级联更新上级组织的汇总
 * 4. 记录调整日志
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationAdjustService {

    private final BillDetailRepository billDetailRepository;
    private final AllocationResultRepository resultRepository;
    private final AllocationAdjustmentRepository adjustmentRepository;
    private final SysOrganizationRepository orgRepository;

    /**
     * 调整指定号码的费用归属
     *
     * @param batchId     账单批次ID
     * @param phoneNumber 要调整的号码
     * @param fromOrgId   原始组织ID（必须匹配当前归属）
     * @param toOrgId     目标组织ID
     * @param reason      调整原因（必填）
     * @param userId      操作人ID
     * @return 调整记录
     */
    @Transactional
    public AllocationAdjustment adjust(Long batchId, String phoneNumber,
                                       Long fromOrgId, Long toOrgId,
                                       String reason, Long userId) {
        // --- 参数校验 ---
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("号码不能为空");
        }
        if (fromOrgId == null || toOrgId == null) {
            throw new IllegalArgumentException("原始组织和目标组织不能为空");
        }
        if (fromOrgId.equals(toOrgId)) {
            throw new IllegalArgumentException("原始组织和目标组织不能相同");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("调整原因不能为空");
        }

        // --- 查找该号码在该批次的所有账单明细 ---
        List<BillDetail> details = billDetailRepository
                .findByPhoneNumberAndBatchIdAndDeletedAtIsNull(phoneNumber, batchId);
        if (details.isEmpty()) {
            throw new IllegalArgumentException("未找到号码 " + phoneNumber + " 的账单明细");
        }

        // 校验所有明细当前归属是 fromOrgId
        for (BillDetail d : details) {
            if (!fromOrgId.equals(d.getOrgId())) {
                throw new IllegalArgumentException("号码 " + phoneNumber + " 当前不属于指定组织");
            }
        }

        // --- 计算该号码的费用合计 ---
        BigDecimal totalAmount = details.stream()
                .map(d -> d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("该号码费用为0，无需调整");
        }

        // --- 获取组织信息 ---
        SysOrganization fromOrg = orgRepository.findById(fromOrgId)
                .orElseThrow(() -> new IllegalArgumentException("原始组织不存在: " + fromOrgId));
        SysOrganization toOrg = orgRepository.findById(toOrgId)
                .orElseThrow(() -> new IllegalArgumentException("目标组织不存在: " + toOrgId));

        // --- Step 1: 更新 bill_detail 的 org_id ---
        for (BillDetail d : details) {
            d.setOrgId(toOrgId);
            d.setOwnershipSource("ADJUSTED");
        }
        billDetailRepository.saveAll(details);

        // --- Step 2: 更新 fromOrg 的 AllocationResult（减费用） ---
        AllocationResult fromResult = resultRepository
                .findByBatchIdAndOrgIdAndDeletedAtIsNull(batchId, fromOrgId)
                .orElse(null);

        if (fromResult != null) {
            fromResult.setMonthlyRent(safeSub(fromResult.getMonthlyRent(), sumField(details, "monthlyRent")));
            fromResult.setCallFee(safeSub(fromResult.getCallFee(), sumField(details, "callFee")));
            fromResult.setRecordingFee(safeSub(fromResult.getRecordingFee(), sumField(details, "recordingFee")));
            fromResult.setCrbtFee(safeSub(fromResult.getCrbtFee(), sumField(details, "crbtFee")));
            fromResult.setFlashMsgFee(safeSub(fromResult.getFlashMsgFee(), sumField(details, "flashMsgFee")));
            fromResult.setTotalFee(safeSub(fromResult.getTotalFee(), totalAmount));
            fromResult.setPhoneCount(Math.max(0, fromResult.getPhoneCount() - countCallPhones(details)));
            try {
                resultRepository.save(fromResult);
            } catch (ObjectOptimisticLockingFailureException e) {
                throw new IllegalArgumentException("数据已被修改，请刷新后重试");
            }
        }

        // --- Step 3: 更新 toOrg 的 AllocationResult（加费用） ---
        AllocationResult toResult = resultRepository
                .findByBatchIdAndOrgIdAndDeletedAtIsNull(batchId, toOrgId)
                .orElse(null);

        if (toResult == null) {
            // 目标组织没有分摊结果，创建新的
            toResult = AllocationResult.builder()
                    .batchId(batchId)
                    .orgId(toOrgId)
                    .orgName(toOrg.getName())
                    .monthlyRent(sumField(details, "monthlyRent"))
                    .callFee(sumField(details, "callFee"))
                    .recordingFee(sumField(details, "recordingFee"))
                    .crbtFee(sumField(details, "crbtFee"))
                    .flashMsgFee(sumField(details, "flashMsgFee"))
                    .totalFee(totalAmount)
                    .phoneCount(countCallPhones(details))
                    .confirmStatus((byte) 0)
                    .withdrawReason("")
                    .version(0)
                    .build();
        } else {
            toResult.setMonthlyRent(safeAdd(toResult.getMonthlyRent(), sumField(details, "monthlyRent")));
            toResult.setCallFee(safeAdd(toResult.getCallFee(), sumField(details, "callFee")));
            toResult.setRecordingFee(safeAdd(toResult.getRecordingFee(), sumField(details, "recordingFee")));
            toResult.setCrbtFee(safeAdd(toResult.getCrbtFee(), sumField(details, "crbtFee")));
            toResult.setFlashMsgFee(safeAdd(toResult.getFlashMsgFee(), sumField(details, "flashMsgFee")));
            toResult.setTotalFee(safeAdd(toResult.getTotalFee(), totalAmount));
            toResult.setPhoneCount(toResult.getPhoneCount() + countCallPhones(details));
        }
        try {
            resultRepository.save(toResult);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new IllegalArgumentException("数据已被修改，请刷新后重试");
        }

        // --- Step 4: 级联更新上级组织汇总 ---
        cascadeUpdateParentResults(batchId, fromOrgId, fromOrg, details, true);
        cascadeUpdateParentResults(batchId, toOrgId, toOrg, details, false);

        // --- Step 5: 记录调整日志 ---
        AllocationAdjustment adjustment = AllocationAdjustment.builder()
                .batchId(batchId)
                .phoneNumber(phoneNumber)
                .fromOrgId(fromOrgId)
                .toOrgId(toOrgId)
                .fromOrgName(fromOrg.getName())
                .toOrgName(toOrg.getName())
                .amount(totalAmount)
                .feeType("TOTAL")
                .reason(reason)
                .adjustedBy(userId)
                .adjustedName("")
                .build();
        adjustmentRepository.save(adjustment);

        log.info("Allocation adjusted: batch={}, phone={}, from={}, to={}, amount={}, by={}",
                batchId, phoneNumber, fromOrgId, toOrgId, totalAmount, userId);

        return adjustment;
    }

    /**
     * 查询指定批次的调整记录
     */
    public List<AllocationAdjustment> listAdjustments(Long batchId) {
        return adjustmentRepository.findByBatchIdAndDeletedAtIsNull(batchId);
    }

    // ==================== 辅助方法 ====================

    private BigDecimal sumField(List<BillDetail> details, String field) {
        return details.stream()
                .map(d -> switch (field) {
                    case "monthlyRent" -> d.getMonthlyRent();
                    case "callFee" -> d.getCallFee();
                    case "recordingFee" -> d.getRecordingFee();
                    case "crbtFee" -> d.getCrbtFee();
                    case "flashMsgFee" -> d.getFlashMsgFee();
                    default -> BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b != null ? b : BigDecimal.ZERO));
    }

    private BigDecimal safeSub(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).subtract(b != null ? b : BigDecimal.ZERO);
    }

    private BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).add(b != null ? b : BigDecimal.ZERO);
    }

    private int countCallPhones(List<BillDetail> details) {
        return (int) details.stream()
                .filter(d -> "CALL".equals(d.getSheetType()))
                .count();
    }

    /**
     * 级联更新上级组织的汇总金额（含分项）
     * 当 fromOrg 减少了费用，上级也要减；toOrg 增加了费用，上级也要加
     */
    private void cascadeUpdateParentResults(Long batchId, Long leafOrgId,
                                             SysOrganization leafOrg,
                                             List<BillDetail> details,
                                             boolean isSubtract) {
        if (leafOrg == null || leafOrg.getPath() == null || leafOrg.getParentId() == null) return;

        BigDecimal amount = details.stream()
                .map(d -> d.getTotalFee() != null ? d.getTotalFee() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal monthlyRentDelta = details.stream().map(d -> safe(d.getMonthlyRent())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal callFeeDelta = details.stream().map(d -> safe(d.getCallFee())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recordingFeeDelta = details.stream().map(d -> safe(d.getRecordingFee())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal crbtFeeDelta = details.stream().map(d -> safe(d.getCrbtFee())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal flashMsgFeeDelta = details.stream().map(d -> safe(d.getFlashMsgFee())).reduce(BigDecimal.ZERO, BigDecimal::add);
        int phoneCountDelta = countCallPhones(details);

        // Walk up the org tree
        Long currentParentId = leafOrg.getParentId();
        while (currentParentId != null) {
            AllocationResult parentResult = resultRepository
                    .findByBatchIdAndOrgIdAndDeletedAtIsNull(batchId, currentParentId)
                    .orElse(null);

            if (parentResult != null) {
                if (isSubtract) {
                    parentResult.setTotalFee(safeSub(parentResult.getTotalFee(), amount));
                    parentResult.setMonthlyRent(safeSub(parentResult.getMonthlyRent(), monthlyRentDelta));
                    parentResult.setCallFee(safeSub(parentResult.getCallFee(), callFeeDelta));
                    parentResult.setRecordingFee(safeSub(parentResult.getRecordingFee(), recordingFeeDelta));
                    parentResult.setCrbtFee(safeSub(parentResult.getCrbtFee(), crbtFeeDelta));
                    parentResult.setFlashMsgFee(safeSub(parentResult.getFlashMsgFee(), flashMsgFeeDelta));
                    parentResult.setPhoneCount(Math.max(0, parentResult.getPhoneCount() - phoneCountDelta));
                } else {
                    parentResult.setTotalFee(safeAdd(parentResult.getTotalFee(), amount));
                    parentResult.setMonthlyRent(safeAdd(parentResult.getMonthlyRent(), monthlyRentDelta));
                    parentResult.setCallFee(safeAdd(parentResult.getCallFee(), callFeeDelta));
                    parentResult.setRecordingFee(safeAdd(parentResult.getRecordingFee(), recordingFeeDelta));
                    parentResult.setCrbtFee(safeAdd(parentResult.getCrbtFee(), crbtFeeDelta));
                    parentResult.setFlashMsgFee(safeAdd(parentResult.getFlashMsgFee(), flashMsgFeeDelta));
                    parentResult.setPhoneCount(parentResult.getPhoneCount() + phoneCountDelta);
                }
                try {
                    resultRepository.save(parentResult);
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.warn("Optimistic lock failed on parent org {} during cascade, skipping", currentParentId);
                }
            }

            SysOrganization parent = orgRepository.findById(currentParentId).orElse(null);
            currentParentId = (parent != null) ? parent.getParentId() : null;
        }
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
