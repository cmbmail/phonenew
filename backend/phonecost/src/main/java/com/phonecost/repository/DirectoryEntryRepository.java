package com.phonecost.repository;

import com.phonecost.domain.DirectoryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DirectoryEntryRepository extends JpaRepository<DirectoryEntry, Long> {
    List<DirectoryEntry> findByDeletedAtIsNull();
    Optional<DirectoryEntry> findByIdAndDeletedAtIsNull(Long id);
    List<DirectoryEntry> findByBatchIdAndDeletedAtIsNull(Long batchId);
    List<DirectoryEntry> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);
    List<DirectoryEntry> findByIsSecondedAndDeletedAtIsNull(Byte isSeconded);

    /** Batch query: load all entries for multiple batch IDs in one query (eliminates N+1) */
    List<DirectoryEntry> findByBatchIdInAndDeletedAtIsNull(List<Long> batchIds);

    /** M-08: Paginated query for entries by batch (avoids OOM with large datasets) */
    Page<DirectoryEntry> findByBatchIdAndDeletedAtIsNull(Long batchId, Pageable pageable);

    /** M-08: Paginated scoped query for entries by batch + org IDs */
    Page<DirectoryEntry> findByBatchIdAndOrgIdInAndDeletedAtIsNull(Long batchId, List<Long> orgIds, Pageable pageable);
}
