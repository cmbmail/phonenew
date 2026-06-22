package com.phonecost.repository;

import com.phonecost.domain.AllocationAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllocationAdjustmentRepository extends JpaRepository<AllocationAdjustment, Long> {
    List<AllocationAdjustment> findByBatchIdAndDeletedAtIsNull(Long batchId);
}
