package com.phonecost.repository;

import com.phonecost.domain.PhoneOwnershipEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PhoneOwnershipEntryRepository extends JpaRepository<PhoneOwnershipEntry, Long> {
    Optional<PhoneOwnershipEntry> findByIdAndDeletedAtIsNull(Long id);
    List<PhoneOwnershipEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);
    // DB-level pagination (performance optimization)
    Page<PhoneOwnershipEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);

    long countByBatchIdAndDeletedAtIsNull(Long batchId);

    // Entries by billing_month (joined with batch table)
    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "ORDER BY e.id")
    Page<PhoneOwnershipEntry> findByBillingMonth(@Param("billingMonth") String billingMonth, Pageable pageable);

    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "AND (e.phoneNumber LIKE %:keyword% OR e.description LIKE %:keyword% " +
            "OR e.extension LIKE %:keyword% OR e.fullPath LIKE %:keyword%) " +
            "ORDER BY e.id")
    Page<PhoneOwnershipEntry> searchByBillingMonthAndKeyword(@Param("billingMonth") String billingMonth, @Param("keyword") String keyword, Pageable pageable);

    // Entries by billing_month with org scope
    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "AND e.orgId IN :orgIds ORDER BY e.id")
    Page<PhoneOwnershipEntry> findByBillingMonthAndOrgIdIn(@Param("billingMonth") String billingMonth, @Param("orgIds") List<Long> orgIds, Pageable pageable);

    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "AND e.orgId IN :orgIds AND (e.phoneNumber LIKE %:keyword% OR e.description LIKE %:keyword% " +
            "OR e.extension LIKE %:keyword% OR e.fullPath LIKE %:keyword%) " +
            "ORDER BY e.id")
    Page<PhoneOwnershipEntry> searchByBillingMonthAndKeywordAndOrgIdIn(@Param("billingMonth") String billingMonth, @Param("keyword") String keyword, @Param("orgIds") List<Long> orgIds, Pageable pageable);

    @Modifying
    @Query("UPDATE PhoneOwnershipEntry e SET e.deletedAt = :now WHERE e.batchId = :batchId AND e.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId, @Param("now") LocalDateTime now);

    // Exception entries by billing_month (is_exception=1)
    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.isException = 1 AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "ORDER BY e.id")
    Page<PhoneOwnershipEntry> findExceptionsByBillingMonth(@Param("billingMonth") String billingMonth, Pageable pageable);

    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.isException = 1 AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "AND (e.phoneNumber LIKE %:keyword% OR e.description LIKE %:keyword% " +
            "OR e.extension LIKE %:keyword% OR e.fullPath LIKE %:keyword%) " +
            "ORDER BY e.id")
    Page<PhoneOwnershipEntry> searchExceptionsByBillingMonthAndKeyword(@Param("billingMonth") String billingMonth, @Param("keyword") String keyword, Pageable pageable);

    // All exception entries by billing_month (no pagination)
    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
            "WHERE b.billingMonth = :billingMonth AND e.isException = 1 AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
            "ORDER BY e.id")
    List<PhoneOwnershipEntry> findAllExceptionsByBillingMonth(@Param("billingMonth") String billingMonth);

    // Search by batchId with keyword
    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.batchId = :batchId AND e.deletedAt IS NULL " +
            "AND (e.phoneNumber LIKE %:keyword% OR e.description LIKE %:keyword% " +
            "OR e.extension LIKE %:keyword% OR e.fullPath LIKE %:keyword%) " +
            "ORDER BY e.id")
    Page<PhoneOwnershipEntry> searchByBatchIdAndKeyword(@Param("batchId") Long batchId, @Param("keyword") String keyword, Pageable pageable);

    // All entries by batchId (no pagination, for exception cross-check)
    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.batchId = :batchId AND e.isException = 1 AND e.deletedAt IS NULL ORDER BY e.id")
    List<PhoneOwnershipEntry> findExceptionsByBatchId(@Param("batchId") Long batchId);

    // Count exception entries by batch_id
    long countByBatchIdAndIsExceptionAndDeletedAtIsNull(Long batchId, Byte isException);

    // ==================== All-entries queries (cross-batch, deduplicated) ====================

    // All non-exception entries (is_exception=0), newest first
    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.isException = 0 AND e.deletedAt IS NULL ORDER BY e.id DESC")
    Page<PhoneOwnershipEntry> findAllNonExceptionEntries(Pageable pageable);

    // All non-exception entries for export (no pagination)
    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.isException = 0 AND e.deletedAt IS NULL ORDER BY e.id DESC")
    List<PhoneOwnershipEntry> findAllNonExceptionEntriesForExport();

    // All entries by billing_month (no pagination, for export)
    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "ORDER BY e.id")
    List<PhoneOwnershipEntry> findAllByBillingMonth(@Param("billingMonth") String billingMonth);

    // All entries by billing_month + org scope (no pagination, for statistics)
    @Query("SELECT e FROM PhoneOwnershipEntry e JOIN PhoneOwnershipBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "AND e.orgId IN :orgIds ORDER BY e.id")
    List<PhoneOwnershipEntry> findAllByBillingMonthAndOrgIdIn(@Param("billingMonth") String billingMonth, @Param("orgIds") List<Long> orgIds);

    // Count non-exception entries
    // ==================== Deduplicated queries (one row per phone_number, latest record) ====================

    // Find IDs of the latest non-exception entry per phone_number (native SQL for GROUP BY)
    @Query(value = "SELECT MAX(e.id) FROM phone_ownership_entry e " +
            "WHERE e.is_exception = 0 AND e.deleted_at IS NULL GROUP BY e.phone_number", nativeQuery = true)
    List<Long> findLatestEntryIdsPerPhoneNumber();

    @Query(value = "SELECT MAX(e.id) FROM phone_ownership_entry e " +
            "WHERE e.is_exception = 0 AND e.deleted_at IS NULL " +
            "AND (e.phone_number LIKE %:keyword% OR e.extension LIKE %:keyword% " +
            "OR e.full_path LIKE %:keyword% OR e.l1_branch LIKE %:keyword% OR e.l2_branch LIKE %:keyword%) " +
            "GROUP BY e.phone_number", nativeQuery = true)
    List<Long> searchLatestEntryIdsPerPhoneNumber(@Param("keyword") String keyword);

    // Count distinct phone_numbers among non-exception entries
    @Query(value = "SELECT COUNT(DISTINCT e.phone_number) FROM phone_ownership_entry e " +
            "WHERE e.is_exception = 0 AND e.deleted_at IS NULL", nativeQuery = true)
    long countDistinctNonExceptionPhoneNumbers();

    @Query(value = "SELECT COUNT(DISTINCT e.phone_number) FROM phone_ownership_entry e " +
            "WHERE e.is_exception = 0 AND e.deleted_at IS NULL " +
            "AND (e.phone_number LIKE %:keyword% OR e.extension LIKE %:keyword% " +
            "OR e.full_path LIKE %:keyword% OR e.l1_branch LIKE %:keyword% OR e.l2_branch LIKE %:keyword%)", nativeQuery = true)
    long searchDistinctNonExceptionPhoneNumbers(@Param("keyword") String keyword);

    // ==================== All-exceptions queries (cross-batch) ====================

    // All exception entries (is_exception=1), newest first
    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.isException = 1 AND e.deletedAt IS NULL ORDER BY e.id DESC")
    Page<PhoneOwnershipEntry> findAllExceptionEntries(Pageable pageable);

    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.isException = 1 AND e.deletedAt IS NULL " +
            "AND (e.phoneNumber LIKE %:keyword% OR e.description LIKE %:keyword% " +
            "OR e.extension LIKE %:keyword% OR e.fullPath LIKE %:keyword%) " +
            "ORDER BY e.id DESC")
    Page<PhoneOwnershipEntry> searchAllExceptionEntries(@Param("keyword") String keyword, Pageable pageable);

    // All exception entries for export (no pagination)
    @Query("SELECT e FROM PhoneOwnershipEntry e WHERE e.isException = 1 AND e.deletedAt IS NULL ORDER BY e.id DESC")
    List<PhoneOwnershipEntry> findAllExceptionEntriesForExport();
}
