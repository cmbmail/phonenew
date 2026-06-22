package com.phonecost.repository;

import com.phonecost.domain.BillBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillBatchRepository extends JpaRepository<BillBatch, Long> {
    Optional<BillBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
    List<BillBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);
}
