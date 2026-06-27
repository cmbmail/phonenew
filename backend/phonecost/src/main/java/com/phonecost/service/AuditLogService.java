package com.phonecost.service;

import com.phonecost.domain.AuditLog;
import com.phonecost.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void log(Long userId, String username, String action, String resourceType, Long resourceId, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .userId(userId)
                .username(username != null ? username : "")
                .action(action)
                .entityType(resourceType != null ? resourceType : "")
                .entityId(resourceId)
                .detail(detail)
                .ipAddress(getClientIp())
                .build();
        auditLogRepository.save(auditLog);
        log.debug("AuditLog: user={}, action={}, resource={}/{}", username, action, resourceType, resourceId);
    }

    public void log(Long userId, String username, String action, String resourceType, Long resourceId, Map<String, Object> detail) {
        String detailJson = null;
        if (detail != null) {
            try {
                detailJson = objectMapper.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit detail: {}", e.getMessage());
                detailJson = "{}";
            }
        }
        log(userId, username, action, resourceType, resourceId, detailJson);
    }

    public Page<AuditLog> listPaged(int page, int size, String action, String username,
                                     LocalDateTime startDate, LocalDateTime endDate) {
        Pageable pageable = PageRequest.of(page, size);
        boolean hasAction = action != null && !action.isEmpty();
        boolean hasUsername = username != null && !username.isEmpty();
        boolean hasDateRange = startDate != null && endDate != null;

        if (hasAction && hasUsername && hasDateRange) {
            return auditLogRepository.findByActionAndUsernameContainingAndCreatedAtBetweenOrderByCreatedAtDesc(action, username, startDate, endDate, pageable);
        } else if (hasAction && hasDateRange) {
            return auditLogRepository.findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(action, startDate, endDate, pageable);
        } else if (hasUsername && hasDateRange) {
            return auditLogRepository.findByUsernameContainingAndCreatedAtBetweenOrderByCreatedAtDesc(username, startDate, endDate, pageable);
        } else if (hasDateRange) {
            return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate, pageable);
        } else if (hasAction && hasUsername) {
            return auditLogRepository.findByActionAndUsernameContainingOrderByCreatedAtDesc(action, username, pageable);
        } else if (hasAction) {
            return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
        } else if (hasUsername) {
            return auditLogRepository.findByUsernameContainingOrderByCreatedAtDesc(username, pageable);
        }
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    // Keep backward-compatible overload
    public Page<AuditLog> listPaged(int page, int size, String action, String username) {
        return listPaged(page, size, action, username, null, null);
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "";
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty()) ip = request.getHeader("X-Real-IP");
            if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
            return ip;
        } catch (Exception e) {
            return "";
        }
    }
}
