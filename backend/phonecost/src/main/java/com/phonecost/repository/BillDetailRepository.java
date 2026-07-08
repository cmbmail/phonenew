package com.phonecost.repository;

import com.phonecost.domain.BillDetail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Find all bill details with org_id in a given set (avoids loading ALL details into memory)
     */
    @Query("SELECT bd FROM BillDetail bd WHERE bd.orgId IN :orgIds AND bd.deletedAt IS NULL")
    List<BillDetail> findByOrgIdInAndDeletedAtIsNull(@Param("orgIds") List<Long> orgIds);

    /**
     * Find distinct phone numbers across all batches (lightweight query for phone list)
     */
    @Query("SELECT DISTINCT bd.phoneNumber FROM BillDetail bd WHERE bd.deletedAt IS NULL")
    List<String> findDistinctPhoneNumbers();

    /**
     * Count bill details by batch and org IDs without loading entities
     */
    long countByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds);

    /**
     * Soft-delete all details for a given batch
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BillDetail bd SET bd.deletedAt = CURRENT_TIMESTAMP WHERE bd.batchId = :batchId AND bd.deletedAt IS NULL")
    void softDeleteByBatchId(@Param("batchId") Long batchId);
}
