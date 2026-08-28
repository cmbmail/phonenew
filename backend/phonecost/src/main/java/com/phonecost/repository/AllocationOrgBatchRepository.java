package com.phonecost.repository;

import com.phonecost.domain.AllocationOrgBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationOrgBatchRepository extends JpaRepository<AllocationOrgBatch, Long> {

    List<AllocationOrgBatch> findByDeletedAtIsNull();

    Optional<AllocationOrgBatch> findByIdAndDeletedAtIsNull(Long id);

    Optional<AllocationOrgBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);

    List<AllocationOrgBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonths();

    // ==================== Branch-scoped queries (条目级数据隔离：按 entry.branchOrgId 过滤) ====================

    List<AllocationOrgBatch> findByBranchOrgIdAndDeletedAtIsNull(Long branchOrgId);

    List<AllocationOrgBatch> findByBillingMonthAndBranchOrgIdAndDeletedAtIsNull(String billingMonth, Long branchOrgId);

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL AND b.id IN (SELECT e.batchId FROM AllocationOrgEntry e WHERE e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL) ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonthsByBranchOrgId(@Param("branchOrgId") Long branchOrgId);

    // ==================== Source-scoped queries (按来源过滤：push=推送, import=导入) ====================

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL AND (b.batchNo LIKE 'PUSH-%' OR b.batchNo LIKE 'BRN-%') ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonthsBySourcePush();

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL AND b.batchNo NOT LIKE 'PUSH-%' AND b.batchNo NOT LIKE 'BRN-%' ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonthsBySourceImport();

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL AND (b.batchNo LIKE 'PUSH-%' OR b.batchNo LIKE 'BRN-%') AND b.id IN (SELECT e.batchId FROM AllocationOrgEntry e WHERE e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL) ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonthsBySourcePushAndBranchOrgId(@Param("branchOrgId") Long branchOrgId);

    @Query("SELECT DISTINCT b.billingMonth FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth IS NOT NULL AND b.batchNo NOT LIKE 'PUSH-%' AND b.batchNo NOT LIKE 'BRN-%' AND b.id IN (SELECT e.batchId FROM AllocationOrgEntry e WHERE e.branchOrgId = :branchOrgId AND e.deletedAt IS NULL) ORDER BY b.billingMonth DESC")
    List<String> findDistinctBillingMonthsBySourceImportAndBranchOrgId(@Param("branchOrgId") Long branchOrgId);

    // ==================== Source-scoped batch lists (按来源过滤批次列表) ====================

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.batchNo NOT LIKE 'PUSH-%' AND b.batchNo NOT LIKE 'BRN-%' ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findBySourceImport();

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth = :billingMonth AND b.batchNo NOT LIKE 'PUSH-%' AND b.batchNo NOT LIKE 'BRN-%' ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findByBillingMonthAndSourceImport(@Param("billingMonth") String billingMonth);

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND (b.batchNo LIKE 'PUSH-%' OR b.batchNo LIKE 'BRN-%') ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findBySourcePush();

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth = :billingMonth AND (b.batchNo LIKE 'PUSH-%' OR b.batchNo LIKE 'BRN-%') ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findByBillingMonthAndSourcePush(@Param("billingMonth") String billingMonth);

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.branchOrgId = :branchOrgId AND b.batchNo NOT LIKE 'PUSH-%' AND b.batchNo NOT LIKE 'BRN-%' ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findBySourceImportAndBranchOrgId(@Param("branchOrgId") Long branchOrgId);

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth = :billingMonth AND b.branchOrgId = :branchOrgId AND b.batchNo NOT LIKE 'PUSH-%' AND b.batchNo NOT LIKE 'BRN-%' ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findByBillingMonthAndSourceImportAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId);

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.branchOrgId = :branchOrgId AND (b.batchNo LIKE 'PUSH-%' OR b.batchNo LIKE 'BRN-%') ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findBySourcePushAndBranchOrgId(@Param("branchOrgId") Long branchOrgId);

    @Query("SELECT b FROM AllocationOrgBatch b WHERE b.deletedAt IS NULL AND b.billingMonth = :billingMonth AND b.branchOrgId = :branchOrgId AND (b.batchNo LIKE 'PUSH-%' OR b.batchNo LIKE 'BRN-%') ORDER BY b.createdAt DESC")
    List<AllocationOrgBatch> findByBillingMonthAndSourcePushAndBranchOrgId(@Param("billingMonth") String billingMonth, @Param("branchOrgId") Long branchOrgId);
}