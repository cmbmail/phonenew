package com.phonecost.repository;

import com.phonecost.domain.BillBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillBatchRepository extends JpaRepository<BillBatch, Long> {
    Optional<BillBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
    Optional<BillBatch> findByIdAndDeletedAtIsNull(Long id);
    List<BillBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);
    List<BillBatch> findByDeletedAtIsNullOrderByBillingMonthAsc();

    /** M-07: Aggregate total amount without loading all entities */
    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM BillBatch b WHERE b.deletedAt IS NULL")
    BigDecimal sumTotalAmount();

    /** M-07: Aggregate total amount by billing month (for monthly trend, no full entity load) */
    @Query("SELECT b.billingMonth, b.totalAmount, b.totalCount, b.id FROM BillBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL ORDER BY b.billingMonth")
    List<Object[]> findMonthlyTrendData();

    /** Distinct billing months for month filter dropdown */
    @Query("SELECT DISTINCT b.billingMonth FROM BillBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL AND b.billingMonth <> 'unknown' ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonths();
}
