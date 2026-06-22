package com.phonecost.repository;

import com.phonecost.domain.DirectoryBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DirectoryBatchRepository extends JpaRepository<DirectoryBatch, Long> {
    Optional<DirectoryBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
}
