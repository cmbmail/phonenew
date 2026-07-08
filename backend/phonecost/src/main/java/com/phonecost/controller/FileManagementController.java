package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.FileManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 通用文件管理Controller
 * 管理员专属功能：文件上传/下载/列表、备份文件下载、系统配置查看、日志查看
 */
@RestController
@RequestMapping("/file-management")
@RequiredArgsConstructor
public class FileManagementController {

    private final FileManagementService fileManagementService;
    private final AuditLogService auditLogService;

    /** 上传普通文件 */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            Map<String, Object> result = fileManagementService.uploadFile(file, userId);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** 获取已上传文件列表 */
    @GetMapping("/uploads")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listUploadedFiles() {
        return ResponseEntity.ok(ApiResponse.ok(fileManagementService.listUploadedFiles()));
    }

    /** 下载已上传文件 */
    @GetMapping("/uploads/download")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Resource> downloadUploadedFile(
            @RequestParam("name") String fileName,
            @RequestAttribute("userId") Long userId) throws Exception {
        return fileManagementService.downloadUploadedFile(fileName, userId);
    }

    /** 删除已上传文件 */
    @DeleteMapping("/uploads/{fileName}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUploadedFile(
            @PathVariable String fileName,
            @RequestAttribute("userId") Long userId) throws Exception {
        fileManagementService.deleteUploadedFile(fileName, userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** 获取备份文件列表 */
    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listBackupFiles() {
        return ResponseEntity.ok(ApiResponse.ok(fileManagementService.listBackupFiles()));
    }

    /** 下载备份文件 */
    @GetMapping("/backups/download")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Resource> downloadBackupFile(
            @RequestParam("name") String fileName,
            @RequestAttribute("userId") Long userId) throws Exception {
        return fileManagementService.downloadBackupFile(fileName, userId);
    }

    /** 获取系统配置信息 */
    @GetMapping("/config")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSystemConfig() {
        return ResponseEntity.ok(ApiResponse.ok(fileManagementService.getSystemConfig()));
    }

    /** 获取后端日志（最近 N 行） */
    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<String>> getRecentLogs(
            @RequestParam(value = "lines", defaultValue = "100") int lines) {
        // M-10 fix: cap lines at 1000 to prevent excessive log output
        lines = Math.min(lines, 1000);
        return ResponseEntity.ok(ApiResponse.ok(fileManagementService.getRecentLogs(lines)));
    }
}
