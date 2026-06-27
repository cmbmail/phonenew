package com.phonecost.repository;

import com.phonecost.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
    Page<AuditLog> findByUsernameContainingOrderByCreatedAtDesc(String username, Pageable pageable);
    Page<AuditLog> findByActionAndUsernameContainingOrderByCreatedAtDesc(String action, String username, Pageable pageable);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // With date range
    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);
    Page<AuditLog> findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(String action, LocalDateTime start, LocalDateTime end, Pageable pageable);
    Page<AuditLog> findByUsernameContainingAndCreatedAtBetweenOrderByCreatedAtDesc(String username, LocalDateTime start, LocalDateTime end, Pageable pageable);
    Page<AuditLog> findByActionAndUsernameContainingAndCreatedAtBetweenOrderByCreatedAtDesc(String action, String username, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
