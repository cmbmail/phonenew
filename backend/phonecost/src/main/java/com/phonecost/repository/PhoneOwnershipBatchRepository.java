package com.phonecost.repository;

import com.phonecost.domain.PhoneOwnershipBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PhoneOwnershipBatchRepository extends JpaRepository<PhoneOwnershipBatch, Long> {
    List<PhoneOwnershipBatch> findByDeletedAtIsNull();
    Optional<PhoneOwnershipBatch> findByBatchNoAndDeletedAtIsNull(String batchNo);
}
