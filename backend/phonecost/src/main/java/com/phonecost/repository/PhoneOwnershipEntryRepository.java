package com.phonecost.repository;

import com.phonecost.domain.PhoneOwnershipEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PhoneOwnershipEntryRepository extends JpaRepository<PhoneOwnershipEntry, Long> {
    List<PhoneOwnershipEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);
    List<PhoneOwnershipEntry> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);
    List<PhoneOwnershipEntry> findByIsExceptionAndDeletedAtIsNull(Byte isException);

    // DB-level pagination (performance optimization)
    Page<PhoneOwnershipEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);
    Page<PhoneOwnershipEntry> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Pageable pageable);
    long countByBatchIdAndDeletedAtIsNull(Long batchId);
    long countByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds);
}
