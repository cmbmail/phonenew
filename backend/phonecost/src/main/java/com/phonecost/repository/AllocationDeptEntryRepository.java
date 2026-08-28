package com.phonecost.repository;

import com.phonecost.domain.AllocationDeptEntry;
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
public interface AllocationDeptEntryRepository extends JpaRepository<AllocationDeptEntry, Long> {
    List<AllocationDeptEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);

    // DB-level pagination
    Page<AllocationDeptEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);
    long countByBatchIdAndDeletedAtIsNull(Long batchId);

    // All active entries (for P2 matching in ownership match service)
    @Query("SELECT e FROM AllocationDeptEntry e WHERE e.deletedAt IS NULL ORDER BY e.id DESC")
    List<AllocationDeptEntry> findAllActiveEntries();

    // Entries by billing_month (joined with batch table)
    @Query("SELECT e FROM AllocationDeptEntry e JOIN AllocationDeptBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "ORDER BY e.id")
    Page<AllocationDeptEntry> findByBillingMonth(@Param("billingMonth") String billingMonth, Pageable pageable);

    @Query("SELECT e FROM AllocationDeptEntry e JOIN AllocationDeptBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "AND (e.phoneNumber LIKE %:keyword% OR e.branch LIKE %:keyword% " +
            "OR e.deptName LIKE %:keyword% " +
            "OR e.fullPath LIKE %:keyword% OR e.orgCode LIKE %:keyword% OR e.costCenter LIKE %:keyword%) " +
            "ORDER BY e.id")
    Page<AllocationDeptEntry> searchByBillingMonthAndKeyword(@Param("billingMonth") String billingMonth,
                                                             @Param("keyword") String keyword,
                                                             Pageable pageable);

    // All entries by billing month (no pagination, for export)
    @Query("SELECT e FROM AllocationDeptEntry e JOIN AllocationDeptBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "ORDER BY e.id")
    List<AllocationDeptEntry> findAllByBillingMonth(@Param("billingMonth") String billingMonth);

    // All active entries — paginated (for all-entries API)
    @Query("SELECT e FROM AllocationDeptEntry e WHERE e.deletedAt IS NULL ORDER BY e.id DESC")
    Page<AllocationDeptEntry> findAllActiveEntriesPaged(Pageable pageable);

    // All active entries — keyword search paginated (for all-entries API)
    @Query("SELECT e FROM AllocationDeptEntry e WHERE e.deletedAt IS NULL " +
            "AND (e.phoneNumber LIKE %:keyword% OR e.branch LIKE %:keyword% " +
            "OR e.deptName LIKE %:keyword% " +
            "OR e.fullPath LIKE %:keyword% OR e.orgCode LIKE %:keyword% OR e.costCenter LIKE %:keyword%) " +
            "ORDER BY e.id DESC")
    Page<AllocationDeptEntry> searchAllActiveEntries(@Param("keyword") String keyword, Pageable pageable);

    @Modifying
    @Query("UPDATE AllocationDeptEntry e SET e.deletedAt = :now WHERE e.batchId = :batchId AND e.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId, @Param("now") LocalDateTime now);
}
