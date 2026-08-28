package com.phonecost.repository;

import com.phonecost.domain.RecordingDataBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecordingDataBatchRepository extends JpaRepository<RecordingDataBatch, Long> {
    List<RecordingDataBatch> findByDeletedAtIsNull();
    List<RecordingDataBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);
    Optional<RecordingDataBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
    Optional<RecordingDataBatch> findByIdAndDeletedAtIsNull(Long id);

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT b.billingMonth FROM RecordingDataBatch b WHERE b.deletedAt IS NULL AND b.billingMonth <> '' ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonths();
}
