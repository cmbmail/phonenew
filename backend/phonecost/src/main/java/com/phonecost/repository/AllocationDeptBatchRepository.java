package com.phonecost.repository;

import com.phonecost.domain.AllocationDeptBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationDeptBatchRepository extends JpaRepository<AllocationDeptBatch, Long> {
    List<AllocationDeptBatch> findByDeletedAtIsNull();
    Optional<AllocationDeptBatch> findByIdAndDeletedAtIsNull(Long id);
    Optional<AllocationDeptBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);

    List<AllocationDeptBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);
    List<AllocationDeptBatch> findByDeletedAtIsNullOrderByBillingMonthAsc();

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationDeptBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL ORDER BY b.billingMonth")
    List<String> findDistinctBillingMonths();
}
