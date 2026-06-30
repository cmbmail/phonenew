package com.phonecost.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phonecost.domain.BackupRecord;
import com.phonecost.domain.SystemVersion;
import com.phonecost.domain.VersionUpgradePackage;
import com.phonecost.repository.BackupRecordRepository;
import com.phonecost.repository.SystemVersionRepository;
import com.phonecost.repository.VersionUpgradePackageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 版本升级服务
 *
 * 升级包结构（ZIP）:
 *   manifest.json  - { "version": "1.1.0", "description": "描述" }
 *   upgrade.sql    - SQL迁移脚本（逐句执行）
 *
 * 流程：上传ZIP → 解压验证 → 自动备份 → 执行SQL → 更新版本号 → 记录历史
 * 回滚：恢复升级前备份 → 回退版本号
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionUpgradeService {

    private final VersionUpgradePackageRepository packageRepository;
    private final SystemVersionRepository versionRepository;
    private final BackupRecordRepository backupRecordRepository;
    private final BackupService backupService;
    private final DataSource dataSource;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private static final String PACKAGE_DIR = "/data/apps/phonecost/upgrade_packages";
    private static final String STAGING_DIR = "/data/apps/phonecost/upgrade_staging";

    /**
     * 上传升级包
     */
    public VersionUpgradePackage uploadPackage(MultipartFile file, Long userId) throws Exception {
        ensureDir(PACKAGE_DIR);
        ensureDir(STAGING_DIR);

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".zip")) {
            throw new IllegalArgumentException("升级包必须是ZIP格式");
        }

        // Save zip file
        String storedName = "pkg_" + System.currentTimeMillis() + "_" + originalName;
        Path zipPath = Paths.get(PACKAGE_DIR, storedName);
        Files.createDirectories(zipPath.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        long fileSize = Files.size(zipPath);

        // Stage and validate: extract manifest.json to read version + description
        AtomicReference<String> targetVersionRef = new AtomicReference<>(null);
        AtomicReference<String> descriptionRef = new AtomicReference<>(null);
        Path stagingDir = null;
        try {
            stagingDir = Files.createTempDirectory(Paths.get(STAGING_DIR), "pkg_");
            Map<String, String> extracted = extractZip(zipPath, stagingDir);
            String manifestPath = extracted.get("manifest.json");
            if (manifestPath == null) {
                Files.deleteIfExists(zipPath);
                throw new IllegalArgumentException("升级包缺少 manifest.json");
            }

            // Parse manifest
            String manifestContent = Files.readString(Paths.get(manifestPath));
            JsonNode manifest = objectMapper.readTree(manifestContent);
            targetVersionRef.set(manifest.path("version").asText(null));
            descriptionRef.set(manifest.path("description").asText(null));

            if (targetVersionRef.get() == null || targetVersionRef.get().isBlank()) {
                Files.deleteIfExists(zipPath);
                throw new IllegalArgumentException("manifest.json 缺少 version 字段");
            }

            // Check for duplicate
            packageRepository.findByTargetVersionAndDeletedAtIsNull(targetVersionRef.get()).ifPresent(existing -> {
                throw new RuntimeException("目标版本 " + targetVersionRef.get() + " 的升级包已存在（ID=" + existing.getId() + "）");
            });

        } finally {
            // Clean staging
            if (stagingDir != null) {
                deleteRecursive(stagingDir);
            }
        }

        String targetVersion = targetVersionRef.get();
        String description = descriptionRef.get();

        VersionUpgradePackage pkg = VersionUpgradePackage.builder()
                .packageName(originalName)
                .targetVersion(targetVersion)
                .description(description)
                .filePath(zipPath.toString())
                .fileSize(fileSize)
                .status("UPLOADED")
                .createdBy(userId)
                .build();

        pkg = packageRepository.save(pkg);
        log.info("Upgrade package uploaded: {} -> v{}", storedName, targetVersion);

        auditLogService.log(userId, String.valueOf(userId), "UPGRADE_PACKAGE_UPLOAD", "version_upgrade_package", pkg.getId(),
                Map.of("package_name", originalName, "target_version", targetVersion));

        return pkg;
    }

    /**
     * 应用升级：备份 → 解压 → 执行SQL → 更新版本号
     */
    @Transactional
    public Map<String, Object> applyUpgrade(Long packageId, Long userId) {
        VersionUpgradePackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new RuntimeException("升级包不存在: " + packageId));

        if (!"UPLOADED".equals(pkg.getStatus()) && !"FAILED".equals(pkg.getStatus())) {
            throw new RuntimeException("该升级包状态不可应用: " + pkg.getStatus());
        }

        // Get current version
        SystemVersion currentVersion = versionRepository.findByIsCurrentTrueAndDeletedAtIsNull().orElse(null);
        String previousVersion = currentVersion != null ? currentVersion.getVersion() : "0.0.0";

        // Step 1: Auto backup before upgrade
        log.info("Auto-backup before upgrade to v{}...", pkg.getTargetVersion());
        try {
            ensureDir(STAGING_DIR);
        } catch (IOException e) {
            throw new RuntimeException("创建临时目录失败: " + e.getMessage(), e);
        }
        BackupRecord backupRecord = backupService.performFullBackup("MANUAL");
        if (!"SUCCESS".equals(backupRecord.getStatus())) {
            pkg.setStatus("FAILED");
            pkg.setErrorMessage("升级前自动备份失败: " + backupRecord.getErrorMessage());
            packageRepository.save(pkg);
            throw new RuntimeException("升级前自动备份失败，升级中止");
        }

        // Step 2: Extract and execute SQL
        Path stagingDir = null;
        List<String> executedStatements = new ArrayList<>();

        try {
            stagingDir = Files.createTempDirectory(Paths.get(STAGING_DIR), "apply_");
            Map<String, String> extracted = extractZip(Paths.get(pkg.getFilePath()), stagingDir);
            String sqlPath = extracted.get("upgrade.sql");
            if (sqlPath == null) {
                throw new IllegalArgumentException("升级包缺少 upgrade.sql");
            }

            // Read and execute SQL
            String sqlContent = Files.readString(Paths.get(sqlPath));
            executedStatements = executeSqlScript(sqlContent);
            log.info("Upgrade SQL executed: {} statements", executedStatements.size());

        } catch (Exception e) {
            log.error("Upgrade failed for v{}", pkg.getTargetVersion(), e);
            pkg.setStatus("FAILED");
            String errMsg = e.getMessage();
            pkg.setErrorMessage(errMsg != null ? errMsg.substring(0, Math.min(errMsg.length(), 2000)) : "Unknown error");
            packageRepository.save(pkg);

            auditLogService.log(userId, String.valueOf(userId), "UPGRADE_FAILED", "version_upgrade_package", pkg.getId(),
                    Map.of("target_version", pkg.getTargetVersion(), "error", errMsg));

            throw new RuntimeException("升级失败: " + e.getMessage(), e);
        } finally {
            if (stagingDir != null) {
                deleteRecursive(stagingDir);
            }
        }

        // Step 3: Update package status + version records (only after SQL success)
        pkg.setStatus("APPLIED");
        pkg.setAppliedAt(LocalDateTime.now());
        if (currentVersion != null) {
            currentVersion.setIsCurrent(false);
            versionRepository.save(currentVersion);
        }

        SystemVersion newVersion = SystemVersion.builder()
                .version(pkg.getTargetVersion())
                .description(pkg.getDescription())
                .isCurrent(true)
                .backupId(backupRecord.getId())
                .build();
        versionRepository.save(newVersion);

        pkg = packageRepository.save(pkg);

        auditLogService.log(userId, String.valueOf(userId), "UPGRADE_APPLIED", "version_upgrade_package", pkg.getId(),
                Map.of("previous_version", previousVersion, "target_version", pkg.getTargetVersion(),
                        "backup_id", backupRecord.getId(), "sql_statements", executedStatements.size()));

        return Map.of(
                "previous_version", previousVersion,
                "target_version", pkg.getTargetVersion(),
                "backup_id", backupRecord.getId(),
                "sql_statements", executedStatements.size()
        );
    }

    /**
     * 回滚升级：恢复升级前备份 → 回退版本号
     * 注意：不使用 @Transactional，因为 restoreBackup() 通过外部 mysql 进程执行，
     * 需要 MySQL 连接池释放后才能获得独占访问，否则会 metadata lock 死锁。
     */
    public Map<String, Object> rollbackUpgrade(Long versionId, Long userId) {
        // Pre-read target version info BEFORE restore (DB will be overwritten)
        SystemVersion targetVersion = versionRepository.findById(versionId)
                .orElseThrow(() -> new RuntimeException("版本记录不存在: " + versionId));

        if (!targetVersion.getIsCurrent()) {
            throw new RuntimeException("该版本不是当前版本，无需回滚");
        }

        if (targetVersion.getBackupId() == null) {
            throw new RuntimeException("该版本没有关联的备份记录，无法回滚");
        }

        BackupRecord backup = backupRecordRepository.findById(targetVersion.getBackupId())
                .orElseThrow(() -> new RuntimeException("关联的备份记录不存在: " + targetVersion.getBackupId()));

        if (!"SUCCESS".equals(backup.getStatus())) {
            throw new RuntimeException("关联的备份状态不可用于恢复: " + backup.getStatus());
        }

        String rolledBackFromVersion = targetVersion.getVersion();
        Long backupId = backup.getId();

        // Step 1: Restore backup — DB is now reverted to pre-upgrade state
        log.info("Rolling back from v{} using backup #{}", rolledBackFromVersion, backupId);
        backupService.restoreBackup(backupId);

        // Step 1.5: Evict stale connections from pool
        // restoreBackup uses external `mysql` process which causes MySQL to drop all existing connections.
        // HikariCP pool still holds dead connections → evict them to force fresh connections on next use.
        evictStaleConnections();

        // Wait briefly for MySQL to settle after full restore
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Step 2: DB is restored — refresh JPA state from the now-restored database
        // The pre-restore entities are stale; we must re-read from DB
        SystemVersion previousVersion = versionRepository.findByIsCurrentTrueAndDeletedAtIsNull().orElse(null);
        if (previousVersion != null) {
            // The restored DB already has is_current=1 on the correct version, no action needed
            log.info("Current version after rollback: v{}", previousVersion.getVersion());
        }

        String rolledBackTo = previousVersion != null ? previousVersion.getVersion() : "unknown";

        auditLogService.log(userId, String.valueOf(userId), "UPGRADE_ROLLBACK", "system_version", versionId,
                Map.of("rolled_back_from", rolledBackFromVersion, "rolled_back_to", rolledBackTo,
                        "backup_id", backupId));

        return Map.of(
                "rolled_back_from", rolledBackFromVersion,
                "rolled_back_to", rolledBackTo,
                "backup_id", backupId
        );
    }

    /**
     * 获取当前版本
     */
    public Map<String, Object> getCurrentVersion() {
        SystemVersion current = versionRepository.findByIsCurrentTrueAndDeletedAtIsNull().orElse(null);
        if (current == null) {
            // Initialize if no version record exists (handle concurrent creation)
            try {
                SystemVersion initial = SystemVersion.builder()
                        .version("1.0.0")
                        .description("初始版本")
                        .isCurrent(true)
                        .build();
                initial = versionRepository.save(initial);
                return versionToMap(initial);
            } catch (Exception e) {
                // Concurrent creation — just query again
                current = versionRepository.findByIsCurrentTrueAndDeletedAtIsNull().orElse(null);
                if (current != null) return versionToMap(current);
                throw new RuntimeException("初始化系统版本失败: " + e.getMessage(), e);
            }
        }
        return versionToMap(current);
    }

    /**
     * 获取版本历史
     */
    public List<Map<String, Object>> getVersionHistory() {
        List<SystemVersion> versions = versionRepository.findAll();
        versions.sort(Comparator.comparing(SystemVersion::getCreatedAt).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (SystemVersion v : versions) {
            result.add(versionToMap(v));
        }
        return result;
    }

    /**
     * 获取升级包列表
     */
    public List<VersionUpgradePackage> listPackages() {
        return packageRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 50)
        ).getContent();
    }

    /**
     * 删除升级包
     */
    public void deletePackage(Long packageId, Long userId) {
        VersionUpgradePackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new RuntimeException("升级包不存在: " + packageId));

        if ("APPLIED".equals(pkg.getStatus())) {
            throw new RuntimeException("已应用的升级包不可删除");
        }

        // Delete physical file
        try {
            if (pkg.getFilePath() != null) {
                Files.deleteIfExists(Paths.get(pkg.getFilePath()));
            }
        } catch (IOException e) {
            log.warn("Failed to delete package file: {}", pkg.getFilePath(), e);
        }

        pkg.setDeletedAt(LocalDateTime.now());
        packageRepository.save(pkg);
    }

    // === Private helpers ===

    /**
     * 清除HikariCP连接池中的失效连接
     * restoreBackup通过外部mysql进程恢复数据，会导致MySQL断开所有现有连接，
     * 但HikariCP连接池不知道连接已断开，后续使用时会报Broken pipe。
     */
    private void evictStaleConnections() {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                hikari.getHikariPoolMXBean().softEvictConnections();
                log.info("Evicted stale connections from HikariCP pool");
            } else {
                // Fallback: close one connection to trigger pool refresh
                try (Connection conn = dataSource.getConnection()) {
                    // Just open and close to validate
                }
            }
        } catch (Exception e) {
            log.warn("Failed to evict stale connections (non-fatal)", e);
        }
    }

    private Map<String, String> extractZip(Path zipPath, Path stagingDir) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path outFile = stagingDir.resolve(entry.getName());
                // Security: prevent path traversal
                if (!outFile.normalize().startsWith(stagingDir.normalize())) {
                    continue;
                }
                Files.createDirectories(outFile.getParent());
                try (OutputStream out = Files.newOutputStream(outFile)) {
                    zis.transferTo(out);
                }
                result.put(entry.getName(), outFile.toString());
            }
        }
        return result;
    }

    private List<String> executeSqlScript(String sqlContent) throws Exception {
        List<String> statements = new ArrayList<>();
        // Strip block comments /* ... */ and line comments -- ...\n
        String cleaned = sqlContent.replaceAll("/\\*.*?\\*/", "").replaceAll("--[^\\n]*", "");
        // Split by semicolons, ignore empty fragments
        String[] parts = cleaned.split(";");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    stmt.execute(trimmed);
                    statements.add(trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed);
                } catch (Exception e) {
                    throw new RuntimeException("SQL执行失败: " + trimmed.substring(0, Math.min(trimmed.length(), 200)) + "\n原因: " + e.getMessage(), e);
                }
            }
        }
        return statements;
    }

    private Map<String, Object> versionToMap(SystemVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("version", v.getVersion());
        m.put("description", v.getDescription());
        m.put("is_current", v.getIsCurrent());
        m.put("backup_id", v.getBackupId());
        m.put("created_at", v.getCreatedAt() != null ? v.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        return m;
    }

    private void ensureDir(String dir) throws IOException {
        Path p = Paths.get(dir);
        if (!Files.exists(p)) {
            Files.createDirectories(p);
        }
    }

    private void deleteRecursive(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete staging dir: {}", dir, e);
        }
    }
}
