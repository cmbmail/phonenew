package com.phonecost.controller;

import com.phonecost.domain.BackupRecord;
import com.phonecost.dto.ApiResponse;
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
    public ResponseEntity<ApiResponse<BackupRecord>> triggerFullBackup() {
        BackupRecord record = backupService.performFullBackup("MANUAL");
        return ResponseEntity.ok(ApiResponse.ok(record));
    }

    @PostMapping("/incremental")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BackupRecord>> triggerIncrementalBackup() {
        BackupRecord record = backupService.performIncrementalBackup("MANUAL");
        return ResponseEntity.ok(ApiResponse.ok(record));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BackupRecord>> restore(@PathVariable Long id) {
        BackupRecord record = backupService.restoreBackup(id);
        return ResponseEntity.ok(ApiResponse.ok(record));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        backupService.deleteBackup(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
