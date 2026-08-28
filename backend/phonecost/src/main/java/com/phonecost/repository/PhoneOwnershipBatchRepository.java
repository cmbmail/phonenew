package com.phonecost.repository;

import com.phonecost.domain.PhoneOwnershipBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PhoneOwnershipBatchRepository extends JpaRepository<PhoneOwnershipBatch, Long> {
    List<PhoneOwnershipBatch> findByDeletedAtIsNull();
    Optional<PhoneOwnershipBatch> findByIdAndDeletedAtIsNull(Long id);
    Optional<PhoneOwnershipBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);

    List<PhoneOwnershipBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);
    List<PhoneOwnershipBatch> findByDeletedAtIsNullOrderByBillingMonthAsc();
    List<PhoneOwnershipBatch> findByDeletedAtIsNullOrderByIdDesc();

    @Query("SELECT DISTINCT b.billingMonth FROM PhoneOwnershipBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL ORDER BY b.billingMonth")
    List<String> findDistinctBillingMonths();
}
