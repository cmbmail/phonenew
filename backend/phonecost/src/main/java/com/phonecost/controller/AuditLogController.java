package com.phonecost.controller;

import com.phonecost.domain.AuditLog;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuditLog>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(auditLogService.list()));
    }
}
