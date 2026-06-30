package com.phonecost.controller;

import com.phonecost.domain.BackupRecord;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/backups")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BackupRecord> result = backupService.listBackups(page, size);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "content", result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", result.getNumber(),
                "size", result.getSize()
        )));
    }

    @PostMapping("/full")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BackupRecord>> triggerFullBackup(
            @RequestAttribute("userId") Long userId) {
        BackupRecord record = backupService.performFullBackup("MANUAL");
        auditLogService.log(userId, "BACKUP_FULL", "backup_record", record.getId(),
                Map.of("type", "full", "file_path", record.getFilePath() != null ? record.getFilePath() : ""));
        return ResponseEntity.ok(ApiResponse.ok(record));
    }

    @PostMapping("/incremental")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BackupRecord>> triggerIncrementalBackup(
            @RequestAttribute("userId") Long userId) {
        BackupRecord record = backupService.performIncrementalBackup("MANUAL");
        auditLogService.log(userId, "BACKUP_INCREMENTAL", "backup_record", record.getId(),
                Map.of("type", "incremental", "file_path", record.getFilePath() != null ? record.getFilePath() : ""));
        return ResponseEntity.ok(ApiResponse.ok(record));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restore(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        auditLogService.log(userId, "BACKUP_RESTORE", "backup_record", id,
                Map.of("restored_by", userId));
        Map<String, Object> result = backupService.restoreBackup(id);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        auditLogService.log(userId, "BACKUP_DELETE", "backup_record", id, (Map<String, Object>) null);
        backupService.deleteBackup(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
