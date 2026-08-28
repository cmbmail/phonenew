package com.phonecost.repository;

import com.phonecost.domain.DirectoryBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DirectoryBatchRepository extends JpaRepository<DirectoryBatch, Long> {
    Optional<DirectoryBatch> findByIdAndDeletedAtIsNull(Long id);

    List<DirectoryBatch> findByDeletedAtIsNull();
    List<DirectoryBatch> findByBillingMonthAndDeletedAtIsNull(String billingMonth);
    List<DirectoryBatch> findByDeletedAtIsNullOrderByCreatedAtDesc();
    Optional<DirectoryBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
    Optional<DirectoryBatch> findTopByDeletedAtIsNullOrderByCreatedAtDesc();

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT d.billingMonth FROM DirectoryBatch d WHERE d.deletedAt IS NULL AND d.billingMonth IS NOT NULL ORDER BY d.billingMonth DESC")
    List<String> findDistinctMonths();

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT d.billingMonth FROM DirectoryBatch d WHERE d.deletedAt IS NULL AND d.billingMonth IS NOT NULL AND d.batchNo LIKE 'EXC-%' ORDER BY d.billingMonth DESC")
    List<String> findExceptionDistinctMonths();
}
