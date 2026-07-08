package com.phonecost.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 通用文件管理服务
 * - 普通文件上传/下载/列表
 * - 备份文件下载
 * - 系统配置查看
 * - 日志查看
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileManagementService {

    private final AuditLogService auditLogService;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    private static final String BACKUP_DIR = "/data/apps/phonecost/backups";
    private static final String UPLOAD_DIR = "/data/apps/phonecost/uploads";

    // 允许上传的文件扩展名（白名单）
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "xlsx", "xls", "csv", "json", "xml", "txt", "pdf", "doc", "docx",
            "zip", "tar", "gz", "sql", "md", "png", "jpg", "jpeg", "gif"
    );

    // 最大文件大小 1GB
    private static final long MAX_FILE_SIZE = 1024 * 1024 * 1024;

    /**
     * 上传普通文件
     */
    public Map<String, Object> uploadFile(MultipartFile file, Long userId) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 校验文件扩展名
        String ext = getFileExtension(originalName);
        if (ext.isEmpty() || !ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
            throw new IllegalArgumentException("不支持的文件类型：." + ext + "，允许的类型：" + String.join(", ", ALLOWED_EXTENSIONS));
        }

        // 校验文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过 1GB 限制");
        }

        // 保存文件
        ensureDir(UPLOAD_DIR);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String storedName = timestamp + "_" + originalName;
        Path uploadPath = Paths.get(UPLOAD_DIR, storedName);
        Files.createDirectories(uploadPath.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, uploadPath, StandardCopyOption.REPLACE_EXISTING);
        }

        long fileSize = Files.size(uploadPath);
        log.info("File uploaded: {}, size: {} bytes, by user: {}", originalName, fileSize, userId);

        auditLogService.log(userId, "FILE_UPLOADED", "file_management", null,
                Map.of("file_name", originalName, "stored_name", storedName, "size", fileSize));

        return Map.of(
                "file_name", originalName,
                "stored_name", storedName,
                "file_size", fileSize,
                "upload_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
    }

    /**
     * 获取已上传文件列表
     */
    public List<Map<String, Object>> listUploadedFiles() {
        return listFilesInDir(UPLOAD_DIR, null);
    }

    /**
     * 删除已上传文件
     */
    public void deleteUploadedFile(String fileName, Long userId) throws IOException {
        Path requested = Paths.get(UPLOAD_DIR, fileName).normalize();
        Path uploadDir = Paths.get(UPLOAD_DIR).normalize();
        if (!requested.startsWith(uploadDir)) {
            throw new IllegalArgumentException("非法的文件路径");
        }
        if (!Files.exists(requested)) {
            throw new FileNotFoundException("文件不存在: " + fileName);
        }
        Files.delete(requested);
        log.info("File deleted: {}, by user: {}", fileName, userId);
        auditLogService.log(userId, "FILE_DELETED", "file_management", null,
                Map.of("file_name", fileName));
    }

    /**
     * 下载已上传文件
     */
    public ResponseEntity<Resource> downloadUploadedFile(String fileName, Long userId) throws IOException {
        Path requested = Paths.get(UPLOAD_DIR, fileName).normalize();
        Path uploadDir = Paths.get(UPLOAD_DIR).normalize();
        if (!requested.startsWith(uploadDir)) {
            throw new IllegalArgumentException("非法的文件路径");
        }
        if (!Files.exists(requested)) {
            throw new FileNotFoundException("文件不存在: " + fileName);
        }

        Resource resource = new FileSystemResource(requested);

        auditLogService.log(userId, "FILE_DOWNLOADED", "file_management", null,
                Map.of("file_name", fileName));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(requested))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    /**
     * 获取备份文件列表
     */
    public List<Map<String, Object>> listBackupFiles() {
        return listFilesInDir(BACKUP_DIR, p ->
                p.toString().endsWith(".sql.gz") || p.toString().endsWith(".sql"));
    }

    /**
     * 下载备份文件（仅接受备份目录下的文件名，不接受完整路径）
     */
    public ResponseEntity<Resource> downloadBackupFile(String fileName, Long userId) throws IOException {
        // 只取文件名部分，防止路径穿越
        String safeName = Paths.get(fileName).getFileName().toString();
        Path requested = Paths.get(BACKUP_DIR, safeName).normalize();
        Path backupDir = Paths.get(BACKUP_DIR).normalize();
        if (!requested.startsWith(backupDir)) {
            throw new IllegalArgumentException("非法的文件路径");
        }
        if (!Files.exists(requested)) {
            throw new FileNotFoundException("文件不存在: " + safeName);
        }

        Resource resource = new FileSystemResource(requested);
        String actualFileName = requested.getFileName().toString();

        auditLogService.log(userId, "BACKUP_FILE_DOWNLOADED", "file_management", null,
                Map.of("file_name", actualFileName));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(requested))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(actualFileName, java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    /**
     * 获取系统配置信息（脱敏）
     */
    public Map<String, Object> getSystemConfig() {
        Map<String, Object> config = new LinkedHashMap<>();

        config.put("database", Map.of(
                "url", maskUrl(dbUrl)
        ));

        Runtime runtime = Runtime.getRuntime();
        config.put("runtime", Map.of(
                "java_version", System.getProperty("java.version"),
                "os_name", System.getProperty("os.name"),
                "max_memory", formatBytes(runtime.maxMemory()),
                "total_memory", formatBytes(runtime.totalMemory()),
                "free_memory", formatBytes(runtime.freeMemory()),
                "used_memory", formatBytes(runtime.totalMemory() - runtime.freeMemory())
        ));

        // 备份目录信息
        Path backupDir = Paths.get(BACKUP_DIR);
        long backupSize = 0;
        int backupCount = 0;
        if (Files.exists(backupDir)) {
            try (var stream = Files.walk(backupDir, 1)) {
                var files = stream.filter(p -> !Files.isDirectory(p)).toList();
                backupCount = files.size();
                backupSize = files.stream().mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException e) { return 0; }
                }).sum();
            } catch (IOException ignored) {}
        }
        config.put("backups", Map.of(
                "directory", BACKUP_DIR,
                "count", backupCount,
                "total_size", formatBytes(backupSize)
        ));

        // 上传目录信息
        Path uploadDir = Paths.get(UPLOAD_DIR);
        long uploadSize = 0;
        int uploadCount = 0;
        if (Files.exists(uploadDir)) {
            try (var stream = Files.walk(uploadDir, 1)) {
                var files = stream.filter(p -> !Files.isDirectory(p)).toList();
                uploadCount = files.size();
                uploadSize = files.stream().mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException e) { return 0; }
                }).sum();
            } catch (IOException ignored) {}
        }
        config.put("uploads", Map.of(
                "directory", UPLOAD_DIR,
                "count", uploadCount,
                "total_size", formatBytes(uploadSize)
        ));

        // 升级包目录
        Path pkgDir = Paths.get("/data/apps/phonecost/upgrade_packages");
        int pkgCount = 0;
        if (Files.exists(pkgDir)) {
            try (var stream = Files.list(pkgDir)) {
                pkgCount = (int) stream.count();
            } catch (IOException ignored) {}
        }
        config.put("upgrade_packages", Map.of("count", pkgCount));

        // 磁盘空间
        File rootFile = new File("/");
        config.put("disk", Map.of(
                "total_space", formatBytes(rootFile.getTotalSpace()),
                "free_space", formatBytes(rootFile.getFreeSpace()),
                "usable_space", formatBytes(rootFile.getUsableSpace())
        ));

        return config;
    }

    /**
     * 获取后端日志（最近 N 行）
     */
    public String getRecentLogs(int lines) {
        // M-26 fix: cap lines at 1000 in service layer too
        lines = Math.min(lines, 1000);
        ProcessBuilder pb;
        if (isSystemd()) {
            pb = new ProcessBuilder("journalctl", "-u", "phonecost-backend",
                    "-n", String.valueOf(lines), "--no-pager");
        } else {
            Path logFile = findLogFile();
            if (logFile != null && Files.exists(logFile)) {
                return tailFile(logFile, lines);
            }
            return "无法获取日志信息";
        }

        try {
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = readProcessOutput(proc);
            proc.waitFor();
            return output.isEmpty() ? "暂无日志" : output;
        } catch (Exception e) {
            log.warn("Failed to read logs", e);
            return "获取日志失败: " + e.getMessage();
        }
    }

    // === Private helpers ===

    private List<Map<String, Object>> listFilesInDir(String dirPath, java.util.function.Predicate<Path> filter) {
        List<Map<String, Object>> result = new ArrayList<>();
        Path dir = Paths.get(dirPath);

        if (!Files.exists(dir)) {
            return result;
        }

        try (var stream = Files.list(dir)) {
            var fileStream = stream.filter(p -> !Files.isDirectory(p));
            if (filter != null) {
                fileStream = fileStream.filter(filter);
            }
            List<Path> files = fileStream
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();
            for (Path p : files) {
                try {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("file_name", p.getFileName().toString());
                    item.put("file_size", Files.size(p));
                    result.add(item);
                } catch (IOException e) {
                    log.warn("Failed to read file info: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list directory: {}", dirPath, e);
        }

        return result;
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) return "";
        return fileName.substring(dotIndex + 1);
    }

    private String maskUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("(password=)[^&]*", "$1****")
                   .replaceAll("(://[^:]+:)[^@]+@", "$1***@");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private boolean isSystemd() {
        try {
            Process p = new ProcessBuilder("which", "systemctl").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Path findLogFile() {
        String[] candidates = {
                "/data/apps/phonecost/logs/phonecost.log",
                "/data/apps/phonecost/backend/phonecost/logs/application.log"
        };
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private String tailFile(Path file, int lines) {
        // M-27 fix: use RandomAccessFile to read from end instead of loading entire file
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) return "";

            // Read backwards in chunks to find the last N lines
            StringBuilder sb = new StringBuilder();
            long pos = fileLength - 1;
            int lineCount = 0;
            boolean foundNewline = false;

            while (pos >= 0 && lineCount < lines) {
                raf.seek(pos);
                int ch = raf.read();
                if (ch == '\n') {
                    lineCount++;
                    foundNewline = true;
                }
                if (lineCount < lines) {
                    pos--;
                }
            }

            // Start reading from the found position
            long startPos = foundNewline ? pos + 1 : 0;
            raf.seek(startPos);
            byte[] buffer = new byte[(int) (fileLength - startPos)];
            // M-27: limit to 1MB max for safety
            int bytesToRead = (int) Math.min(buffer.length, 1024 * 1024);
            raf.readFully(buffer, 0, bytesToRead);
            return new String(buffer, 0, bytesToRead, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    private String readProcessOutput(Process proc) throws IOException {
        try (InputStream is = proc.getInputStream()) {
            byte[] bytes = is.readAllBytes();
            return new String(bytes);
        }
    }

    private void ensureDir(String dir) throws IOException {
        Path p = Paths.get(dir);
        if (!Files.exists(p)) {
            Files.createDirectories(p);
        }
    }
}
