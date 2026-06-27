package com.phonecost.service;

import com.phonecost.domain.AllocationResult;
import com.phonecost.domain.AllocationAdjustment;
import com.phonecost.repository.AllocationResultRepository;
import com.phonecost.repository.AllocationAdjustmentRepository;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 确认/撤回服务
 * - 确认: 标记当前组织的分摊结果为已确认(1)
 * - 撤回: 标记为已撤回(2)，需提供原因，级联撤回所有下级
 * - 乐观锁(@Version)防止并发冲突
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationConfirmService {

    private final AllocationResultRepository resultRepository;
    private final AllocationAdjustmentRepository adjustmentRepository;
    private final SysOrganizationRepository orgRepository;
    private final AuditLogService auditLogService;

    /**
     * 确认指定组织的分摊结果
     */
    @Transactional
    public AllocationResult confirm(Long batchId, Long orgId, Long userId) {
        AllocationResult result = resultRepository
                .findByBatchIdAndOrgIdAndDeletedAtIsNull(batchId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("分摊结果不存在"));

        if (result.getConfirmStatus() == (byte) 1) {
            throw new IllegalArgumentException("已经确认，无需重复操作");
        }
        if (result.getConfirmStatus() == (byte) 2) {
            throw new IllegalArgumentException("已撤回状态，请重新确认");
        }

        result.setConfirmStatus((byte) 1);
        result.setConfirmedAt(LocalDateTime.now());
        result.setConfirmedBy(userId);

        try {
            result = resultRepository.save(result);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new IllegalArgumentException("数据已被修改，请刷新后重试");
        }

        log.info("Allocation confirmed: batch={}, org={}, by={}", batchId, orgId, userId);
        auditLogService.log(userId, "user", "ALLOCATION_CONFIRM", "allocation_result", result.getId(),
                "{\"batch_id\":" + batchId + ",\"org_id\":" + orgId + "}");
        return result;
    }

    /**
     * 撤回指定组织的分摊结果（级联撤回所有下级）
     */
    @Transactional
    public List<AllocationResult> withdraw(Long batchId, Long orgId, Long userId, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("撤回原因不能为空");
        }

        AllocationResult result = resultRepository
                .findByBatchIdAndOrgIdAndDeletedAtIsNull(batchId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("分摊结果不存在"));

        if (result.getConfirmStatus() != (byte) 1) {
            throw new IllegalArgumentException("只有已确认状态才能撤回");
        }

        // Withdraw this org
        result.setConfirmStatus((byte) 2);
        result.setWithdrawnAt(LocalDateTime.now());
        result.setWithdrawnBy(userId);
        result.setWithdrawReason(reason);

        try {
            resultRepository.save(result);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new IllegalArgumentException("数据已被修改，请刷新后重试");
        }

        // Cascade withdraw: find all descendant orgs and withdraw them too
        var org = orgRepository.findById(orgId).orElse(null);
        int cascadeCount = 0;
        if (org != null && org.getPath() != null) {
            List<AllocationResult> allResults = resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
            for (AllocationResult r : allResults) {
                if (r.getId().equals(result.getId())) continue;
                if (r.getConfirmStatus() != (byte) 1) continue;

                var rOrg = orgRepository.findById(r.getOrgId()).orElse(null);
                if (rOrg != null && rOrg.getPath() != null
                        && rOrg.getPath().startsWith(org.getPath())
                        && !rOrg.getPath().equals(org.getPath())) {
                    r.setConfirmStatus((byte) 2);
                    r.setWithdrawnAt(LocalDateTime.now());
                    r.setWithdrawnBy(userId);
                    r.setWithdrawReason("上级撤回: " + reason);
                    resultRepository.save(r);
                    cascadeCount++;
                }
            }
        }

        log.info("Allocation withdrawn: batch={}, org={}, by={}, cascade={}", batchId, orgId, userId, cascadeCount);
        auditLogService.log(userId, "user", "ALLOCATION_WITHDRAW", "allocation_result", result.getId(),
                "{\"batch_id\":" + batchId + ",\"org_id\":" + orgId + ",\"cascade\":" + cascadeCount + "}");

        return resultRepository.findByBatchIdAndDeletedAtIsNull(batchId);
    }

    /**
     * Batch confirm all results for a batch
     */
    @Transactional
    public int confirmAll(Long batchId, Long userId) {
        List<AllocationResult> results = resultRepository.findByBatchIdAndConfirmStatusAndDeletedAtIsNull(
                batchId, (byte) 0);

        int count = 0;
        for (AllocationResult r : results) {
            r.setConfirmStatus((byte) 1);
            r.setConfirmedAt(LocalDateTime.now());
            r.setConfirmedBy(userId);
            try {
                resultRepository.save(r);
                count++;
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict on confirmAll, skipping result id={}", r.getId());
            }
        }

        log.info("Batch confirm: batch={}, count={}, by={}", batchId, count, userId);
        return count;
    }

    /**
     * Batch confirm results within a data scope
     * 分行管理员只确认自己范围内的待确认结果
     */
    @Transactional
    public int confirmAllInScope(Long batchId, Long userId, DataScope scope) {
        if (scope.isAllScope()) {
            return confirmAll(batchId, userId);
        }

        List<Long> visibleOrgIds = scope.getVisibleOrgIds();
        if (visibleOrgIds == null || visibleOrgIds.isEmpty()) {
            return 0;
        }

        List<AllocationResult> results = resultRepository.findByBatchIdAndConfirmStatusAndDeletedAtIsNull(
                batchId, (byte) 0);

        int count = 0;
        for (AllocationResult r : results) {
            // 只确认可见范围内的组织（sentinel orgId=-1 不自动确认）
            if (visibleOrgIds.contains(r.getOrgId())) {
                r.setConfirmStatus((byte) 1);
                r.setConfirmedAt(LocalDateTime.now());
                r.setConfirmedBy(userId);
                try {
                    resultRepository.save(r);
                    count++;
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.warn("Optimistic lock conflict on confirmAllInScope, skipping result id={}", r.getId());
                }
            }
        }

        log.info("Scoped batch confirm: batch={}, count={}, by={}", batchId, count, userId);
        return count;
    }
}
