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
        // 审计日志保持全局可见（仅管理员使用，且记录全局操作）
        return ResponseEntity.ok(ApiResponse.ok(auditLogService.list()));
    }
}
