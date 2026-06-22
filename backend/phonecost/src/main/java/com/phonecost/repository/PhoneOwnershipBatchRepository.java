package com.phonecost.repository;

import com.phonecost.domain.PhoneOwnershipBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PhoneOwnershipBatchRepository extends JpaRepository<PhoneOwnershipBatch, Long> {
    Optional<PhoneOwnershipBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
}
