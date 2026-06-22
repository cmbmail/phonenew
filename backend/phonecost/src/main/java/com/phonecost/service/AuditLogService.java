package com.phonecost.service;

import com.phonecost.domain.AuditLog;
import com.phonecost.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public void log(Long userId, String username, String action, String resourceType, Long resourceId, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .userId(userId)
                .username(username != null ? username : "")
                .action(action)
                .entityType(resourceType != null ? resourceType : "")
                .entityId(resourceId)
                .detail(detail)
                .ipAddress("")
                .build();
        auditLogRepository.save(auditLog);
        log.debug("AuditLog: user={}, action={}, resource={}/{}", username, action, resourceType, resourceId);
    }

    public List<AuditLog> list() {
        return auditLogRepository.findAll()
                .stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }
}
