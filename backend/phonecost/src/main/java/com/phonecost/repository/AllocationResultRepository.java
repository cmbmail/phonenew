package com.phonecost.repository;

import com.phonecost.domain.AllocationResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationResultRepository extends JpaRepository<AllocationResult, Long> {
    List<AllocationResult> findByBatchIdAndDeletedAtIsNull(Long batchId);
    Optional<AllocationResult> findByBatchIdAndOrgIdAndDeletedAtIsNull(Long batchId, Long orgId);
    List<AllocationResult> findByBatchIdAndConfirmStatusAndDeletedAtIsNull(Long batchId, Byte confirmStatus);
    List<AllocationResult> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds);

    /** Find by batch, org IDs, confirm status — used by confirmAllInScope scoped query */
    List<AllocationResult> findByBatchIdAndOrgIdInAndConfirmStatusAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Byte confirmStatus);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM allocation_result WHERE batch_id = :batchId", nativeQuery = true)
    void hardDeleteByBatchId(Long batchId);

    /** Soft-delete all allocation results for a given batch */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AllocationResult r SET r.deletedAt = CURRENT_TIMESTAMP WHERE r.batchId = :batchId AND r.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId);

    /** M-07: Count by confirm status for a given batch (no full entity load) */
    long countByBatchIdAndConfirmStatusAndDeletedAtIsNull(Long batchId, Byte confirmStatus);
    long countByBatchIdAndDeletedAtIsNull(Long batchId);

    /** M-07: Count by confirm status for multiple org IDs (scoped) */
    long countByBatchIdAndOrgIdInAndConfirmStatusAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Byte confirmStatus);
    long countByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds);

    /** M-40: Paginated query by batch and org IDs */
    Page<AllocationResult> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Pageable pageable);

    /** M-40: Paginated query by batch (all orgs) */
    Page<AllocationResult> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);

    /** M-07: Sum fee breakdown for a batch (no full entity load) */
    @Query("SELECT COALESCE(SUM(r.monthlyRent), 0), COALESCE(SUM(r.callFee), 0), COALESCE(SUM(r.recordingFee), 0), COALESCE(SUM(r.crbtFee), 0), COALESCE(SUM(r.flashMsgFee), 0) FROM AllocationResult r WHERE r.batchId = :batchId AND r.deletedAt IS NULL")
    List<Object[]> sumFeeBreakdownByBatchId(@Param("batchId") Long batchId);

    /** Global confirm status counts — single query instead of N+1 loop */
    @Query("SELECT r.confirmStatus, COUNT(r) FROM AllocationResult r WHERE r.deletedAt IS NULL GROUP BY r.confirmStatus")
    List<Object[]> countByConfirmStatusGlobal();

    /** Scoped confirm status counts for visible org IDs — single query */
    @Query("SELECT r.confirmStatus, COUNT(r) FROM AllocationResult r WHERE r.deletedAt IS NULL AND r.orgId IN :orgIds GROUP BY r.confirmStatus")
    List<Object[]> countByConfirmStatusScoped(@Param("orgIds") List<Long> orgIds);

    /** Fee aggregation: sum all by batch_id (used by monthlyComparison admin) — avoids N+1 + full load */
    @Query("SELECT r.batchId, " +
           "COALESCE(SUM(r.monthlyRent), 0), COALESCE(SUM(r.callFee), 0), " +
           "COALESCE(SUM(r.recordingFee), 0), COALESCE(SUM(r.crbtFee), 0), COALESCE(SUM(r.flashMsgFee), 0), " +
           "COALESCE(SUM(r.totalFee), 0), COALESCE(SUM(r.phoneCount), 0), " +
           "SUM(CASE WHEN r.orgId IS NOT NULL AND r.orgId != -1 THEN 1 ELSE 0 END) " +
           "FROM AllocationResult r WHERE r.deletedAt IS NULL " +
           "GROUP BY r.batchId ORDER BY r.batchId")
    List<Object[]> aggregateAllGroupByBatchId();

    /** Fee aggregation: sum by batch_id for given org IDs (used by monthlyComparison scoped & analyzeOrgMonthly) */
    @Query("SELECT r.batchId, " +
           "COALESCE(SUM(r.monthlyRent), 0), COALESCE(SUM(r.callFee), 0), " +
           "COALESCE(SUM(r.recordingFee), 0), COALESCE(SUM(r.crbtFee), 0), COALESCE(SUM(r.flashMsgFee), 0), " +
           "COALESCE(SUM(r.totalFee), 0), COALESCE(SUM(r.phoneCount), 0), COUNT(r) " +
           "FROM AllocationResult r WHERE r.orgId IN :orgIds AND r.deletedAt IS NULL " +
           "GROUP BY r.batchId ORDER BY r.batchId")
    List<Object[]> aggregateByOrgIdsGroupByBatchId(@Param("orgIds") Collection<Long> orgIds);
}
