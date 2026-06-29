package com.phonecost.repository;

import com.phonecost.domain.BackupRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {

    Page<BackupRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<BackupRecord> findTopByBackupTypeAndStatusOrderByCreatedAtDesc(String backupType, String status);

    Optional<BackupRecord> findTopByStatusOrderByCreatedAtDesc(String status);
}
