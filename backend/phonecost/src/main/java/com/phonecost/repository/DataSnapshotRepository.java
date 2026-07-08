package com.phonecost.repository;

import com.phonecost.domain.DataSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {

    Optional<DataSnapshot> findByBillBatchIdAndDeletedAtIsNull(Long billBatchId);

    List<DataSnapshot> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    /** Soft-delete snapshot for a given bill batch */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DataSnapshot s SET s.deletedAt = CURRENT_TIMESTAMP WHERE s.billBatchId = :billBatchId AND s.deletedAt IS NULL")
    void softDeleteByBillBatchId(@Param("billBatchId") Long billBatchId);
}
