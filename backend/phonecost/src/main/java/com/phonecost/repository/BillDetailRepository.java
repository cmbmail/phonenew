package com.phonecost.repository;

import com.phonecost.domain.BillDetail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface BillDetailRepository extends JpaRepository<BillDetail, Long> {
    List<BillDetail> findByBatchIdAndDeletedAtIsNull(Long batchId);
    List<BillDetail> findByPhoneNumberAndBatchIdAndDeletedAtIsNull(String phoneNumber, Long batchId);
    List<BillDetail> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    /**
     * Paginated query for bill details by batch (avoids loading ALL into memory)
     */
    Page<BillDetail> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);

    /**
     * Paginated query for bill details by batch + org IDs (scoped, DB-level pagination)
     */
    Page<BillDetail> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Pageable pageable);

    /**
     * Paginated query for bill details by batch + sheet type
     */
    Page<BillDetail> findByBatchIdAndSheetTypeAndDeletedAtIsNull(Long batchId, String sheetType, Pageable pageable);

    /**
     * Paginated query for bill details by batch + sheet type + org IDs
     */
    Page<BillDetail> findByBatchIdAndSheetTypeAndOrgIdInAndDeletedAtIsNull(Long batchId, String sheetType, List<Long> orgIds, Pageable pageable);

    /**
     * Search bill details by batch + keyword (phone_number OR extension), case-insensitive
     */
    @Query("SELECT bd FROM BillDetail bd WHERE bd.batchId = :batchId AND bd.deletedAt IS NULL " +
           "AND (LOWER(bd.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(bd.extension) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<BillDetail> searchByBatchIdAndKeyword(@Param("batchId") Long batchId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * Search bill details by batch + sheet type + keyword (phone_number OR extension), case-insensitive
     */
    @Query("SELECT bd FROM BillDetail bd WHERE bd.batchId = :batchId AND bd.sheetType = :sheetType AND bd.deletedAt IS NULL " +
           "AND (LOWER(bd.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(bd.extension) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<BillDetail> searchByBatchIdAndSheetTypeAndKeyword(@Param("batchId") Long batchId, @Param("sheetType") String sheetType, @Param("keyword") String keyword, Pageable pageable);

    /**
     * Search bill details by batch + org IDs + keyword (phone_number OR extension), case-insensitive (scoped)
     */
    @Query("SELECT bd FROM BillDetail bd WHERE bd.batchId = :batchId AND bd.orgId IN :orgIds AND bd.deletedAt IS NULL " +
           "AND (LOWER(bd.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(bd.extension) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<BillDetail> searchByBatchIdAndOrgIdsAndKeyword(@Param("batchId") Long batchId, @Param("orgIds") List<Long> orgIds, @Param("keyword") String keyword, Pageable pageable);

    /**
     * Search bill details by batch + sheet type + org IDs + keyword (phone_number OR extension), case-insensitive (scoped)
     */
    @Query("SELECT bd FROM BillDetail bd WHERE bd.batchId = :batchId AND bd.sheetType = :sheetType AND bd.orgId IN :orgIds AND bd.deletedAt IS NULL " +
           "AND (LOWER(bd.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(bd.extension) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<BillDetail> searchByBatchIdAndSheetTypeAndOrgIdsAndKeyword(@Param("batchId") Long batchId, @Param("sheetType") String sheetType, @Param("orgIds") List<Long> orgIds, @Param("keyword") String keyword, Pageable pageable);

        /**
     * Soft-delete all details for a given batch
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BillDetail bd SET bd.deletedAt = CURRENT_TIMESTAMP WHERE bd.batchId = :batchId AND bd.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId);

    /** Phone list aggregation: sum by phone_number (admin) — avoids loading ALL entities */
    @Query("SELECT bd.phoneNumber, " +
           "COALESCE(SUM(bd.monthlyRent), 0), COALESCE(SUM(bd.callFee), 0), " +
           "COALESCE(SUM(bd.recordingFee), 0), COALESCE(SUM(bd.crbtFee), 0), COALESCE(SUM(bd.flashMsgFee), 0), " +
           "COALESCE(SUM(bd.totalFee), 0), COUNT(DISTINCT bd.batchId), COUNT(bd) " +
           "FROM BillDetail bd WHERE bd.deletedAt IS NULL " +
           "GROUP BY bd.phoneNumber")
    List<Object[]> aggregateAllGroupByPhoneNumber();

    /** Phone list aggregation: sum by phone_number for given org IDs (scoped) */
    @Query("SELECT bd.phoneNumber, " +
           "COALESCE(SUM(bd.monthlyRent), 0), COALESCE(SUM(bd.callFee), 0), " +
           "COALESCE(SUM(bd.recordingFee), 0), COALESCE(SUM(bd.crbtFee), 0), COALESCE(SUM(bd.flashMsgFee), 0), " +
           "COALESCE(SUM(bd.totalFee), 0), COUNT(DISTINCT bd.batchId), COUNT(bd) " +
           "FROM BillDetail bd WHERE bd.orgId IN :orgIds AND bd.deletedAt IS NULL " +
           "GROUP BY bd.phoneNumber")
    List<Object[]> aggregateByOrgIdsGroupByPhoneNumber(@Param("orgIds") Collection<Long> orgIds);

    /** Latest org info per phone (admin): uses ROW_NUMBER to get the most recent batch detail */
    @Query(value = "SELECT t.phone_number, t.org_id, t.ownership_source FROM (" +
           "SELECT phone_number, org_id, ownership_source, " +
           "ROW_NUMBER() OVER (PARTITION BY phone_number ORDER BY batch_id DESC) as rn " +
           "FROM bill_detail WHERE deleted_at IS NULL" +
           ") t WHERE t.rn = 1", nativeQuery = true)
    List<Object[]> findLatestDetailPerPhoneNative();

    /** Latest org info per phone with org filter: uses ROW_NUMBER to get the most recent batch detail */
    @Query(value = "SELECT t.phone_number, t.org_id, t.ownership_source FROM (" +
           "SELECT phone_number, org_id, ownership_source, " +
           "ROW_NUMBER() OVER (PARTITION BY phone_number ORDER BY batch_id DESC) as rn " +
           "FROM bill_detail WHERE deleted_at IS NULL AND org_id IN (:orgIds)" +
           ") t WHERE t.rn = 1", nativeQuery = true)
    List<Object[]> findLatestDetailPerPhoneByOrgIdsNative(@Param("orgIds") Collection<Long> orgIds);

    /** Fallback aggregation: sum by batch_id (all orgs) — used when allocation_result is empty */
    @Query("SELECT bd.batchId, " +
           "COALESCE(SUM(bd.monthlyRent), 0), COALESCE(SUM(bd.callFee), 0), " +
           "COALESCE(SUM(bd.recordingFee), 0), COALESCE(SUM(bd.crbtFee), 0), COALESCE(SUM(bd.flashMsgFee), 0), " +
           "COALESCE(SUM(bd.totalFee), 0), COUNT(DISTINCT bd.phoneNumber), COUNT(DISTINCT bd.orgId) " +
           "FROM BillDetail bd WHERE bd.deletedAt IS NULL " +
           "GROUP BY bd.batchId ORDER BY bd.batchId")
    List<Object[]> aggregateAllGroupByBatchId();

    /** Fallback aggregation: sum by batch_id for given org IDs — used when allocation_result is empty */
    @Query("SELECT bd.batchId, " +
           "COALESCE(SUM(bd.monthlyRent), 0), COALESCE(SUM(bd.callFee), 0), " +
           "COALESCE(SUM(bd.recordingFee), 0), COALESCE(SUM(bd.crbtFee), 0), COALESCE(SUM(bd.flashMsgFee), 0), " +
           "COALESCE(SUM(bd.totalFee), 0), COUNT(DISTINCT bd.phoneNumber), COUNT(DISTINCT bd.orgId) " +
           "FROM BillDetail bd WHERE bd.orgId IN :orgIds AND bd.deletedAt IS NULL " +
           "GROUP BY bd.batchId ORDER BY bd.batchId")
    List<Object[]> aggregateByOrgIdsGroupByBatchId(@Param("orgIds") Collection<Long> orgIds);

    /** Fallback aggregation: sum by org_id for a given batch — used when allocation_result is empty */
    @Query("SELECT bd.orgId, " +
           "COALESCE(SUM(bd.monthlyRent), 0), COALESCE(SUM(bd.callFee), 0), " +
           "COALESCE(SUM(bd.recordingFee), 0), COALESCE(SUM(bd.crbtFee), 0), COALESCE(SUM(bd.flashMsgFee), 0), " +
           "COALESCE(SUM(bd.totalFee), 0), COUNT(DISTINCT bd.phoneNumber) " +
           "FROM BillDetail bd WHERE bd.batchId = :batchId AND bd.deletedAt IS NULL AND bd.orgId IS NOT NULL " +
           "GROUP BY bd.orgId")
    List<Object[]> aggregateByBatchIdGroupByOrgId(@Param("batchId") Long batchId);
}
