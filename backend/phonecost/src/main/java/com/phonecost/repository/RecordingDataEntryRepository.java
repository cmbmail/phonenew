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
}
