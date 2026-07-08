package com.phonecost.repository;

import com.phonecost.domain.RecordingDataBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecordingDataBatchRepository extends JpaRepository<RecordingDataBatch, Long> {
    List<RecordingDataBatch> findByDeletedAtIsNull();
    Optional<RecordingDataBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
}
