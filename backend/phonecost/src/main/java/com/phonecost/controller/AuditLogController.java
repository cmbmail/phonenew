package com.phonecost.controller;

import com.phonecost.domain.AuditLog;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        LocalDateTime startDt = null;
        LocalDateTime endDt = null;
        if (startDate != null && !startDate.isEmpty()) {
            startDt = java.time.LocalDate.parse(startDate).atStartOfDay();
        }
        if (endDate != null && !endDate.isEmpty()) {
            endDt = java.time.LocalDate.parse(endDate).plusDays(1).atStartOfDay();
        }
        Page<AuditLog> result = auditLogService.listPaged(page, size, action, username, startDt, endDt);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "content", result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", result.getNumber(),
                "size", result.getSize()
        )));
    }
}
