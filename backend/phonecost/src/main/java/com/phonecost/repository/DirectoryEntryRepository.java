package com.phonecost.repository;

import com.phonecost.domain.DirectoryEntry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DirectoryEntryRepository extends JpaRepository<DirectoryEntry, Long> {
    Optional<DirectoryEntry> findByIdAndDeletedAtIsNull(Long id);
    List<DirectoryEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);

    /** Batch query: load all entries for multiple batch IDs in one query (eliminates N+1) */
    List<DirectoryEntry> findByBatchIdInAndDeletedAtIsNull(List<Long> batchIds);

    /** M-08: Paginated query for entries by batch (avoids OOM with large datasets) */
    Page<DirectoryEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);

    /** M-08: Paginated scoped query for entries by batch + org IDs */
    Page<DirectoryEntry> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Pageable pageable);

    @Modifying
    @Query("UPDATE DirectoryEntry e SET e.deletedAt = :now WHERE e.batchId = :batchId AND e.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId, @Param("now") LocalDateTime now);

    /** Projection query: only phone_number + extension (avoids loading full entities for backfill) */
    @Query("SELECT e.phoneNumber, e.extension FROM DirectoryEntry e " +
           "WHERE e.deletedAt IS NULL AND e.phoneNumber IS NOT NULL AND e.extension IS NOT NULL AND e.extension <> ''")
    List<Object[]> findPhoneAndExtension();

    // ==================== All-entries (cross-batch) queries ====================

    /** Paginated query for all active entries (cross-batch) */
    @Query("SELECT e FROM DirectoryEntry e WHERE e.deletedAt IS NULL")
    Page<DirectoryEntry> findAllActiveEntriesPaged(Pageable pageable);

    /** Search all active entries by keyword (cross-batch) */
    @Query("SELECT e FROM DirectoryEntry e WHERE e.deletedAt IS NULL AND " +
           "(LOWER(e.deptPath) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.extension) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.allocDept) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.orgCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.costCenter) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.remark) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<DirectoryEntry> searchAllActiveEntries(@Param("keyword") String keyword, Pageable pageable);

    /** Fetch all active entries (no pagination, for export) */
    @Query("SELECT e FROM DirectoryEntry e WHERE e.deletedAt IS NULL")
    List<DirectoryEntry> findAllActiveEntries();

    /** Count active entries by batch (for batch total update) */
    long countByBatchIdAndDeletedAtIsNull(Long batchId);

    // ==================== Cross-month comparison queries ====================

    /** Find all active entries by billing month (via batch join) */
    @Query("SELECT e FROM DirectoryEntry e JOIN DirectoryBatch b ON e.batchId = b.id " +
           "WHERE e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.billingMonth = :billingMonth")
    List<DirectoryEntry> findByBillingMonth(@Param("billingMonth") String billingMonth);

    /** Lightweight projection: phone_number + extension + dept_path by billing month (for ownership enrichment) */
    @Query("SELECT e.phoneNumber, e.extension, e.deptPath FROM DirectoryEntry e JOIN DirectoryBatch b ON e.batchId = b.id " +
           "WHERE e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.billingMonth = :billingMonth " +
           "AND e.phoneNumber IS NOT NULL AND e.phoneNumber <> ''")
    List<Object[]> findPhoneExtAndDeptByMonth(@Param("billingMonth") String billingMonth);

    // ==================== Exception entries queries ====================

    /** Find exception entries (is_seconded = 1) with pagination */
    @Query("SELECT e FROM DirectoryEntry e WHERE e.deletedAt IS NULL AND e.isSeconded = 1")
    Page<DirectoryEntry> findExceptionEntries(Pageable pageable);

    /** Search exception entries by keyword */
    @Query("SELECT e FROM DirectoryEntry e WHERE e.deletedAt IS NULL AND e.isSeconded = 1 AND " +
           "(LOWER(e.deptPath) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.extension) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(e.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<DirectoryEntry> searchExceptionEntries(@Param("keyword") String keyword, Pageable pageable);

    /** Find all exception entries without pagination (for comparison) */
    @Query("SELECT e FROM DirectoryEntry e WHERE e.deletedAt IS NULL AND e.isSeconded = 1")
    List<DirectoryEntry> findExceptionEntriesAll();

    /** Find exception entries by billing month (via batch join, for month-based export) */
    @Query("SELECT e FROM DirectoryEntry e JOIN DirectoryBatch b ON e.batchId = b.id " +
           "WHERE e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.billingMonth = :billingMonth AND e.isSeconded = 1")
    List<DirectoryEntry> findExceptionEntriesByMonth(@Param("billingMonth") String billingMonth);
}
