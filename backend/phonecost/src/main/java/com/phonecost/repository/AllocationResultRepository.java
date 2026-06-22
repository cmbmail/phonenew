package com.phonecost.repository;

import com.phonecost.domain.AllocationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationResultRepository extends JpaRepository<AllocationResult, Long> {
    List<AllocationResult> findByBatchIdAndDeletedAtIsNull(Long batchId);
    Optional<AllocationResult> findByBatchIdAndOrgIdAndDeletedAtIsNull(Long batchId, Long orgId);
    List<AllocationResult> findByBatchIdAndConfirmStatusAndDeletedAtIsNull(Long batchId, Byte confirmStatus);
    List<AllocationResult> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds);
}
