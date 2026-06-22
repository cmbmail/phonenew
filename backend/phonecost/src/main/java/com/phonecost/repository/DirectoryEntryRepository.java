package com.phonecost.repository;

import com.phonecost.domain.DirectoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DirectoryEntryRepository extends JpaRepository<DirectoryEntry, Long> {
    List<DirectoryEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);
    List<DirectoryEntry> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);
    List<DirectoryEntry> findByIsSecondedAndDeletedAtIsNull(Byte isSeconded);
}
