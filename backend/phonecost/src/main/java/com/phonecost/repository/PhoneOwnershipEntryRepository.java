package com.phonecost.repository;

import com.phonecost.domain.PhoneOwnershipEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PhoneOwnershipEntryRepository extends JpaRepository<PhoneOwnershipEntry, Long> {
    List<PhoneOwnershipEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);
    List<PhoneOwnershipEntry> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);
    List<PhoneOwnershipEntry> findByIsExceptionAndDeletedAtIsNull(Byte isException);
}
