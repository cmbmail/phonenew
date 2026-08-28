package com.phonecost.repository;

import com.phonecost.domain.RecordingDataEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecordingDataEntryRepository extends JpaRepository<RecordingDataEntry, Long> {
    List<RecordingDataEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);
    Page<RecordingDataEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);
    long countByBatchIdAndDeletedAtIsNull(Long batchId);

    @Modifying
    @Query("UPDATE RecordingDataEntry e SET e.deletedAt = :now WHERE e.batchId = :batchId AND e.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId, @Param("now") LocalDateTime now);

    // Search entries across batches for a given billing month
    @Query("SELECT e FROM RecordingDataEntry e JOIN RecordingDataBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "AND (e.extension LIKE %:keyword% OR e.phoneNumber LIKE %:keyword% OR e.deptName LIKE %:keyword% OR e.remark LIKE %:keyword%)")
    Page<RecordingDataEntry> searchByBillingMonthAndKeyword(@Param("billingMonth") String billingMonth, @Param("keyword") String keyword, Pageable pageable);

    // List all entries for a billing month (no keyword filter)
    @Query("SELECT e FROM RecordingDataEntry e JOIN RecordingDataBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL")
    Page<RecordingDataEntry> findByBillingMonth(@Param("billingMonth") String billingMonth, Pageable pageable);

    // Count all entries for a billing month
    @Query("SELECT COUNT(e) FROM RecordingDataEntry e JOIN RecordingDataBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL")
    long countByBillingMonth(@Param("billingMonth") String billingMonth);

    // All active entries for a given billing month (non-paginated, for export/service use)
    @Query("SELECT e FROM RecordingDataEntry e JOIN RecordingDataBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL ORDER BY e.id")
    List<RecordingDataEntry> findAllByBillingMonth(@Param("billingMonth") String billingMonth);

    // All active entries for export (non-deleted, across all batches)
    @Query("SELECT e FROM RecordingDataEntry e JOIN RecordingDataBatch b ON e.batchId = b.id " +
           "WHERE e.deletedAt IS NULL AND b.deletedAt IS NULL ORDER BY e.id")
    List<RecordingDataEntry> findAllActiveEntriesForExport();
}
