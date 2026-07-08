package com.phonecost.repository;

import com.phonecost.domain.AllocationAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface AllocationAdjustmentRepository extends JpaRepository<AllocationAdjustment, Long> {
    List<AllocationAdjustment> findByBatchIdAndDeletedAtIsNull(Long batchId);

    /** Soft-delete all adjustments for a given batch */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AllocationAdjustment a SET a.deletedAt = CURRENT_TIMESTAMP WHERE a.batchId = :batchId AND a.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId);
}
