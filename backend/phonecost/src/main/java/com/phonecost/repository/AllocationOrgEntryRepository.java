package com.phonecost.repository;

import com.phonecost.domain.AllocationOrgEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationOrgEntryRepository extends JpaRepository<AllocationOrgEntry, Long> {

    List<AllocationOrgEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);

    Optional<AllocationOrgEntry> findByIdAndDeletedAtIsNull(Long id);

    Page<AllocationOrgEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);

    long countByBatchIdAndDeletedAtIsNull(Long batchId);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "ORDER BY e.id")
    Page<AllocationOrgEntry> findByBillingMonth(@Param("billingMonth") String billingMonth, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "AND (e.phoneNumber LIKE %:keyword% OR e.l1Branch LIKE %:keyword% " +
           "OR e.allocDept LIKE %:keyword% OR e.orgCode LIKE %:keyword% OR e.remark LIKE %:keyword%) " +
           "ORDER BY e.id")
    Page<AllocationOrgEntry> searchByBillingMonthAndKeyword(@Param("billingMonth") String billingMonth, @Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "ORDER BY e.id")
    List<AllocationOrgEntry> findAllByBillingMonth(@Param("billingMonth") String billingMonth);

    @Modifying
    @Transactional
    @Query("UPDATE AllocationOrgEntry e SET e.deletedAt = :now WHERE e.batchId = :batchId AND e.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId, @Param("now") LocalDateTime now);

    // ==================== Branch-scoped queries (条目级数据隔离：按 e.branchOrgId 过滤) ====================

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "ORDER BY e.id")
    Page<AllocationOrgEntry> findByBillingMonthAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "AND (e.phoneNumber LIKE %:keyword% OR e.l1Branch LIKE %:keyword% " +
           "OR e.allocDept LIKE %:keyword% OR e.orgCode LIKE %:keyword% OR e.remark LIKE %:keyword%) " +
           "ORDER BY e.id")
    Page<AllocationOrgEntry> searchByBillingMonthAndKeywordAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId, @Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL " +
           "ORDER BY e.id")
    List<AllocationOrgEntry> findAllByBillingMonthAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId);

    // ==================== Source-scoped queries (按来源过滤：import=号码分摊机构 Tab 含导入/COMP-/BRN-，exception=例外号码清单 Tab) ====================

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' " +
           "ORDER BY CASE WHEN e.changeType = 'unmatched' THEN 0 ELSE 1 END, e.id")
    Page<AllocationOrgEntry> findByBillingMonthAndSourceImport(@Param("billingMonth") String billingMonth, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' " +
           "ORDER BY CASE WHEN e.changeType = 'unmatched' THEN 0 ELSE 1 END, e.id")
    List<AllocationOrgEntry> findAllByBillingMonthAndSourceImport(@Param("billingMonth") String billingMonth);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' " +
           "ORDER BY CASE WHEN e.changeType = 'unmatched' THEN 0 ELSE 1 END, e.id")
    Page<AllocationOrgEntry> findByBillingMonthAndSourceImportAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' " +
           "ORDER BY CASE WHEN e.changeType = 'unmatched' THEN 0 ELSE 1 END, e.id")
    List<AllocationOrgEntry> findAllByBillingMonthAndSourceImportAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId);

    // ==================== Exception-scoped queries (例外号码清单/差异数据: 推送 PUSH-EXC- + 导入 EXC-IMP- 批次) ====================

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND (b.batchNo LIKE 'PUSH-EXC-%' OR b.batchNo LIKE 'EXC-IMP-%') " +
           "ORDER BY e.id")
    List<AllocationOrgEntry> findAllByBillingMonthAndSourceException(@Param("billingMonth") String billingMonth);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND (b.batchNo LIKE 'PUSH-EXC-%' OR b.batchNo LIKE 'EXC-IMP-%') " +
           "ORDER BY e.id")
    List<AllocationOrgEntry> findAllByBillingMonthAndSourceExceptionAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' " +
           "AND (e.phoneNumber LIKE %:keyword% OR e.l1Branch LIKE %:keyword% OR e.allocDept LIKE %:keyword% OR e.orgCode LIKE %:keyword% OR e.remark LIKE %:keyword%) " +
           "ORDER BY CASE WHEN e.changeType = 'unmatched' THEN 0 ELSE 1 END, e.id")
    Page<AllocationOrgEntry> searchByBillingMonthAndSourceImport(@Param("billingMonth") String billingMonth, @Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE b.billingMonth = :billingMonth AND e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL AND b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' " +
           "AND (e.phoneNumber LIKE %:keyword% OR e.l1Branch LIKE %:keyword% OR e.allocDept LIKE %:keyword% OR e.orgCode LIKE %:keyword% OR e.remark LIKE %:keyword%) " +
           "ORDER BY CASE WHEN e.changeType = 'unmatched' THEN 0 ELSE 1 END, e.id")
    Page<AllocationOrgEntry> searchByBillingMonthAndSourceImportAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId, @Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT e FROM AllocationOrgEntry e JOIN AllocationOrgBatch b ON e.batchId = b.id " +
           "WHERE e.deletedAt IS NULL AND b.deletedAt IS NULL ORDER BY b.billingMonth DESC, e.id DESC")
    List<AllocationOrgEntry> findAllActiveOrderedByMonthDesc();
}