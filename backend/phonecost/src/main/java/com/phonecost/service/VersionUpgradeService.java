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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;

/**
 * 版本升级服务
 *
 * 升级包结构（ZIP）:
 *   manifest.json    - { "version": "1.1.0", "description": "描述" }
 *   upgrade.sql      - SQL迁移脚本（逐句执行）
 *   frontend-dist/   - 前端构建产物（可选）
 *   backend.jar      - 后端JAR包（可选）
 *
 * 流程：上传ZIP → 解压验证 → 自动备份 → 执行SQL → 替换前端/后端 → 更新版本号 → 记录历史
 * 回滚：恢复升级前备份 → 回退版本号 → 恢复前端/后端文件
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
    private final TaskExecutor taskExecutor;

    /** M-28: Externalize directory paths via @Value */
    @Value("${app.upgrade.package-dir:/data/apps/phonecost/upgrade_packages}")
    private String packageDir;
    @Value("${app.upgrade.staging-dir:/data/apps/phonecost/upgrade_staging}")
    private String stagingDirPath;
    @Value("${app.upgrade.frontend-dist-dir:${APP_FRONTEND_DIR:/data/apps/phonecost/frontend}}")
    private String frontendDistDir;
    @Value("${app.upgrade.backend-jar-dir:/data/apps/phonecost/backend}")
    private String backendJarDir;
    @Value("${app.upgrade.backend-jar-direct:/data/apps/phonecost/backend/phonecost.jar}")
    private String backendJarDirect;
    @Value("${app.upgrade.backend-jar-target:/data/apps/phonecost/backend/phonecost/target/phonecost-0.1.0-SNAPSHOT.jar}")
    private String backendJarTarget;
    private static final String BACKEND_SERVICE_NAME = "phonecost-backend";

    /** 当前构建版本号，用于新建安装时初始化 system_version */
    public static final String BUILD_VERSION = "1.12.144";

    /**
     * 上传升级包
     */
    public VersionUpgradePackage uploadPackage(MultipartFile file, Long userId) throws Exception {
        ensureDir(packageDir);
        ensureDir(stagingDirPath);

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".zip")) {
            throw new IllegalArgumentException("升级包必须是ZIP格式");
        }

        // Save zip file — use UUID for unpredictable filename
        String storedName = "pkg_" + UUID.randomUUID().toString().substring(0, 8) + "_" + originalName;
        Path zipPath = Paths.get(packageDir, storedName);
        Files.createDirectories(zipPath.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        long fileSize = Files.size(zipPath);

        // Stage and validate: extract manifest.json to read version + description
        AtomicReference<String> targetVersionRef = new AtomicReference<>(null);
        AtomicReference<String> descriptionRef = new AtomicReference<>(null);
        Path stagingPath = null;
        try {
            stagingPath = Files.createTempDirectory(Paths.get(stagingDirPath), "pkg_");
            Map<String, String> extracted = extractZip(zipPath, stagingPath);
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

            // Normalize version: strip leading "v"/"V" prefix to avoid duplicate records like "v1.12.44" vs "1.12.44"
            String rawVersion = targetVersionRef.get();
            if (rawVersion.toLowerCase().startsWith("v")) {
                targetVersionRef.set(rawVersion.substring(1));
                log.info("Normalized version: '{}' -> '{}'", rawVersion, targetVersionRef.get());
            }

            // Check for duplicate
            packageRepository.findByTargetVersionAndDeletedAtIsNull(targetVersionRef.get()).ifPresent(existing -> {
                throw new RuntimeException("目标版本 " + targetVersionRef.get() + " 的升级包已存在（ID=" + existing.getId() + "）");
            });

        } finally {
            // Clean staging
            if (stagingPath != null) {
                deleteRecursive(stagingPath);
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

        auditLogService.log(userId, "UPGRADE_PACKAGE_UPLOAD", "version_upgrade_package", pkg.getId(),
                Map.of("package_name", originalName, "target_version", targetVersion));

        return pkg;
    }

    /**
     * 应用升级：备份 → 解压 → 执行SQL → 替换前端/后端 → 更新版本号
     * 不使用 @Transactional：升级涉及长时间文件I/O和外部进程，不适合事务包裹
     */
    public Map<String, Object> applyUpgrade(Long packageId, Long userId) {
        VersionUpgradePackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new RuntimeException("升级包不存在: " + packageId));

        if (!"UPLOADED".equals(pkg.getStatus()) && !"FAILED".equals(pkg.getStatus())) {
            throw new RuntimeException("该升级包状态不可应用: " + pkg.getStatus());
        }

        // Get current version
        SystemVersion currentVersion = versionRepository.findTopByIsCurrentTrueAndDeletedAtIsNullOrderByIdDesc().orElse(null);
        String previousVersion = currentVersion != null ? currentVersion.getVersion() : "0.0.0";

        // Step 1: Auto backup before upgrade
        log.info("Auto-backup before upgrade to v{}...", pkg.getTargetVersion());
        try {
            ensureDir(stagingDirPath);
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

        // Step 2: Extract ZIP and execute SQL + replace files
        Path stagingPath = null;
        List<String> executedStatements = new ArrayList<>();
        boolean frontendReplaced = false;
        boolean backendReplaced = false;

        try {
            stagingPath = Files.createTempDirectory(Paths.get(stagingDirPath), "apply_");
            Map<String, String> extracted = extractZip(Paths.get(pkg.getFilePath()), stagingPath);

            // 2a: Execute SQL (optional — skip if no upgrade.sql in package)
            String sqlPath = extracted.get("upgrade.sql");
            if (sqlPath != null) {
                String sqlContent = Files.readString(Paths.get(sqlPath));
                executedStatements = executeSqlScript(sqlContent);
                log.info("Upgrade SQL executed: {} statements", executedStatements.size());
            } else {
                log.info("No upgrade.sql in package, skipping SQL execution");
            }

            // 2b: Replace frontend dist (optional, if frontend-dist/ exists in ZIP)
            String frontendDir = findExtractedPrefix(extracted, "frontend-dist/");
            if (frontendDir != null) {
                log.info("Found frontend-dist/ in upgrade package, replacing...");
                String frontendBackupPath = backupFrontendDist();
                pkg.setFrontendBackupPath(frontendBackupPath);
                replaceFrontendDist(stagingPath.resolve("frontend-dist"));
                frontendReplaced = true;
                log.info("Frontend dist replaced successfully");
            } else {
                log.info("No frontend-dist/ in upgrade package, skipping frontend replacement");
            }

            // 2c: Replace backend JAR (optional, if backend.jar exists in ZIP)
            String backendJarPath = extracted.get("backend.jar");
            if (backendJarPath != null) {
                log.info("Found backend.jar in upgrade package, replacing...");
                String backendBackupPath = backupBackendJar();
                pkg.setBackendBackupPath(backendBackupPath);
                replaceBackendJar(Paths.get(backendJarPath));
                backendReplaced = true;
                log.info("Backend JAR replaced successfully");
            } else {
                log.info("No backend.jar in upgrade package, skipping backend replacement");
            }

        } catch (Exception e) {
            log.error("Upgrade failed for v{}", pkg.getTargetVersion(), e);
            pkg.setStatus("FAILED");
            String errMsg = e.getMessage();
            pkg.setErrorMessage(errMsg != null ? errMsg.substring(0, Math.min(errMsg.length(), 2000)) : "Unknown error");
            packageRepository.save(pkg);

            // Attempt to rollback file changes on failure
            // Note: replaceFrontendDist/replaceBackendJar are now atomic (temp+rename),
            // so if frontendReplaced/backendReplaced is true, the live files ARE the new version
            // and we need to restore from backup to get back to the old version.
            if (frontendReplaced && pkg.getFrontendBackupPath() != null) {
                try { restoreFrontendDist(pkg.getFrontendBackupPath()); } catch (Exception ex) {
                    log.warn("Failed to rollback frontend dist after upgrade failure", ex);
                }
            }
            if (backendReplaced && pkg.getBackendBackupPath() != null) {
                try { restoreBackendJar(pkg.getBackendBackupPath()); } catch (Exception ex) {
                    log.warn("Failed to rollback backend JAR after upgrade failure", ex);
                }
            }

            auditLogService.log(userId, "UPGRADE_FAILED", "version_upgrade_package", pkg.getId(),
                    Map.of("target_version", pkg.getTargetVersion(), "error", errMsg));

            throw new RuntimeException("升级失败: " + e.getMessage(), e);
        } finally {
            if (stagingPath != null) {
                deleteRecursive(stagingPath);
            }
        }

        // Step 3: Update package status + version records (only after SQL + file success)
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

        // Step 4: Restart backend service if JAR was replaced (async, after response)
        if (backendReplaced) {
            scheduleBackendRestart();
        }

        auditLogService.log(userId, "UPGRADE_APPLIED", "version_upgrade_package", pkg.getId(),
                Map.of("previous_version", previousVersion, "target_version", pkg.getTargetVersion(),
                        "backup_id", backupRecord.getId(), "sql_statements", executedStatements.size(),
                        "frontend_replaced", frontendReplaced, "backend_replaced", backendReplaced));

        return Map.of(
                "previous_version", previousVersion,
                "target_version", pkg.getTargetVersion(),
                "backup_id", backupRecord.getId(),
                "sql_statements", executedStatements.size(),
                "frontend_replaced", frontendReplaced,
                "backend_replaced", backendReplaced
        );
    }

    /**
     * 回滚升级：恢复升级前备份 → 回退版本号 → 恢复前端/后端文件
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

        // Collect file backup paths BEFORE DB restore (they're in the DB)
        // Find the upgrade package that was applied to this version
        String frontendBackupPath = null;
        String backendBackupPath = null;
        try {
            Optional<VersionUpgradePackage> appliedPkg = packageRepository
                    .findByTargetVersionAndStatusAndDeletedAtIsNull(targetVersion.getVersion(), "APPLIED");
            if (appliedPkg.isPresent()) {
                VersionUpgradePackage p = appliedPkg.get();
                frontendBackupPath = p.getFrontendBackupPath();
                backendBackupPath = p.getBackendBackupPath();
            }
        } catch (Exception e) {
            log.warn("Failed to lookup file backup paths for rollback", e);
        }

        // Step 1: Restore DB backup — DB is now reverted to pre-upgrade state
        log.info("Rolling back from v{} using backup #{}", rolledBackFromVersion, backupId);
        backupService.restoreBackup(backupId);

        // Step 1.5: Evict stale connections from pool
        evictStaleConnections();

        // Wait briefly for MySQL to settle after full restore
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Step 2: Restore frontend dist if backed up
        boolean frontendRestored = false;
        if (frontendBackupPath != null && !frontendBackupPath.isBlank() && Files.exists(Paths.get(frontendBackupPath))) {
            try {
                restoreFrontendDist(frontendBackupPath);
                frontendRestored = true;
                log.info("Frontend dist restored from backup: {}", frontendBackupPath);
            } catch (Exception e) {
                log.error("Failed to restore frontend dist during rollback", e);
            }
        }

        // Step 3: Restore backend JAR if backed up
        boolean backendRestored = false;
        if (backendBackupPath != null && !backendBackupPath.isBlank() && Files.exists(Paths.get(backendBackupPath))) {
            try {
                restoreBackendJar(backendBackupPath);
                backendRestored = true;
                log.info("Backend JAR restored from backup: {}", backendBackupPath);
            } catch (Exception e) {
                log.error("Failed to restore backend JAR during rollback", e);
            }
        }

        // Step 4: Restart backend service if JAR was restored
        if (backendRestored) {
            scheduleBackendRestart();
        }

        // Step 2 (continued): DB is restored — refresh JPA state from the now-restored database
        SystemVersion previousVersion = versionRepository.findTopByIsCurrentTrueAndDeletedAtIsNullOrderByIdDesc().orElse(null);
        if (previousVersion != null) {
            log.info("Current version after rollback: v{}", previousVersion.getVersion());
        }

        String rolledBackTo = previousVersion != null ? previousVersion.getVersion() : "unknown";

        auditLogService.log(userId, "UPGRADE_ROLLBACK", "system_version", versionId,
                Map.of("rolled_back_from", rolledBackFromVersion, "rolled_back_to", rolledBackTo,
                        "backup_id", backupId, "frontend_restored", frontendRestored,
                        "backend_restored", backendRestored));

        return Map.of(
                "rolled_back_from", rolledBackFromVersion,
                "rolled_back_to", rolledBackTo,
                "backup_id", backupId,
                "frontend_restored", frontendRestored,
                "backend_restored", backendRestored
        );
    }

    /**
     * 获取当前版本
     */
    public Map<String, Object> getCurrentVersion() {
        SystemVersion current = versionRepository.findTopByIsCurrentTrueAndDeletedAtIsNullOrderByIdDesc().orElse(null);
        if (current == null) {
            // Initialize if no version record exists (handle concurrent creation)
            try {
                SystemVersion initial = SystemVersion.builder()
                        .version(BUILD_VERSION)
                        .description("初始版本")
                        .isCurrent(true)
                        .build();
                initial = versionRepository.save(initial);
                return versionToMap(initial);
            } catch (Exception e) {
                // Concurrent creation — just query again
                current = versionRepository.findTopByIsCurrentTrueAndDeletedAtIsNullOrderByIdDesc().orElse(null);
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
        List<SystemVersion> versions = versionRepository.findByDeletedAtIsNull();
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
     * 检测实际后端 JAR 路径，优先 direct 模式，其次 target 模式
     */
    private String resolveBackendJarPath() {
        if (Files.exists(Paths.get(backendJarDirect))) {
            return backendJarDirect;
        }
        if (Files.exists(Paths.get(backendJarTarget))) {
            log.info("Using target-mode JAR path: {}", backendJarTarget);
            return backendJarTarget;
        }
        // 默认返回 direct 路径（新部署标准）
        log.warn("No JAR found at {} or {}, defaulting to {}", backendJarDirect, backendJarTarget, backendJarDirect);
        return backendJarDirect;
    }

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
        Path normalizedStaging = stagingDir.normalize().toAbsolutePath();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path outFile = normalizedStaging.resolve(entry.getName()).normalize();

                // Zip Slip prevention: ensure resolved path stays inside staging dir
                if (!outFile.startsWith(normalizedStaging)) {
                    log.warn("Zip Slip attempt blocked: entry '{}' resolves to '{}'", entry.getName(), outFile);
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

    /** Dangerous SQL keywords that must be blocked in upgrade scripts */
    private static final Set<String> BLOCKED_SQL_KEYWORDS = Set.of(
            "DROP", "TRUNCATE", "DELETE", "GRANT", "REVOKE",
            "SHUTDOWN", "KILL", "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE"
    );

    /** Allowed SQL statement prefixes for upgrade scripts (M-31: restrict to migration-safe operations only) */
    private static final Set<String> ALLOWED_SQL_PREFIXES = Set.of(
            "CREATE", "ALTER", "SET", "RENAME",
            "ADD", "MODIFY", "CHANGE", "DROP COLUMN", "DROP INDEX", "DROP TABLE IF EXISTS",
            "COMMENT",
            "INSERT INTO flyway", "INSERT  INTO flyway"
            // C-02: Removed UPDATE on sys_user, sys_organization — upgrade scripts should not modify auth/org data
            // Allowed UPDATE tables limited to allocation_result, bill_detail, bill_batch, directory_entry,
            // phone_ownership_entry, backup_record
    );

    /** Tables where UPDATE is allowed in upgrade scripts */
    private static final Set<String> ALLOWED_UPDATE_TABLES = Set.of(
            "allocation_result", "bill_detail", "bill_batch", "directory_entry",
            "phone_ownership_entry", "backup_record"
    );

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
                // Security: validate SQL statement
                validateSqlStatement(trimmed);
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

    private void validateSqlStatement(String sql) {
        String upper = sql.toUpperCase().trim();
        // Block dangerous keywords
        for (String blocked : BLOCKED_SQL_KEYWORDS) {
            if (upper.startsWith(blocked) || upper.contains(" " + blocked + " ") || upper.contains(" " + blocked + "(")) {
                // Allow "DROP TABLE IF EXISTS" and "DROP COLUMN" and "DROP INDEX" as they are common in migration
                if (blocked.equals("DROP") && (upper.startsWith("DROP TABLE IF EXISTS") || upper.startsWith("DROP COLUMN") || upper.startsWith("DROP INDEX"))) {
                    continue;
                }
                throw new IllegalArgumentException("升级脚本包含不允许的SQL语句: " + blocked + " (语句: " + sql.substring(0, Math.min(sql.length(), 100)) + ")");
            }
        }

        // C-01: Enforce whitelist — SQL must match an allowed prefix or an allowed UPDATE table
        boolean prefixAllowed = false;
        for (String prefix : ALLOWED_SQL_PREFIXES) {
            if (upper.startsWith(prefix)) {
                prefixAllowed = true;
                break;
            }
        }
        // Special handling for UPDATE: check table name against whitelist
        if (!prefixAllowed && upper.startsWith("UPDATE ")) {
            // Extract table name: "UPDATE table_name SET ..."
            String afterUpdate = upper.substring("UPDATE ".length()).trim();
            // Table name is the first word before SET
            int setIdx = afterUpdate.indexOf(" SET");
            if (setIdx > 0) {
                String tableName = afterUpdate.substring(0, setIdx).trim().toLowerCase();
                if (ALLOWED_UPDATE_TABLES.contains(tableName)) {
                    prefixAllowed = true;
                }
            }
        }
        // Special handling for INSERT INTO: allow flyway schema history only
        if (!prefixAllowed && (upper.startsWith("INSERT INTO ") || upper.startsWith("INSERT  INTO "))) {
            String afterInsert = upper.contains("INSERT  INTO ") ? upper.substring("INSERT  INTO ".length()) : upper.substring("INSERT INTO ".length());
            if (afterInsert.toLowerCase().startsWith("flyway")) {
                prefixAllowed = true;
            }
        }
        if (!prefixAllowed) {
            throw new IllegalArgumentException("升级脚本包含未授权的SQL操作: " + sql.substring(0, Math.min(sql.length(), 100)));
        }
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

    // === File replacement helpers ===

    /**
     * Find if the extracted ZIP contains files with a given prefix (e.g. "frontend-dist/")
     * Returns the first matching key, or null if none found
     */
    private String findExtractedPrefix(Map<String, String> extracted, String prefix) {
        for (String key : extracted.keySet()) {
            if (key.startsWith(prefix)) {
                return key;
            }
        }
        return null;
    }

    /**
     * Backup current frontend dist to a tar.gz file
     * @return backup file path
     */
    private String backupFrontendDist() throws Exception {
        Path distDir = Paths.get(frontendDistDir);
        if (!Files.exists(distDir)) {
            log.warn("Frontend dist directory does not exist: {}", frontendDistDir);
            return null;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String backupPath = stagingDirPath + "/frontend_backup_" + timestamp + ".tar.gz";
        ensureDir(stagingDirPath);

        ProcessBuilder pb = new ProcessBuilder("tar", "czf", backupPath,
                "-C", Paths.get(frontendDistDir).getParent().toString(),
                Paths.get(frontendDistDir).getFileName().toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = readProcessOutput(proc);
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("前端dist备份失败: " + output);
        }
        log.info("Frontend dist backed up to: {}", backupPath);
        return backupPath;
    }

    /**
     * Backup current backend JAR
     * @return backup file path
     */
    private String backupBackendJar() throws Exception {
        String resolvedPath = resolveBackendJarPath();
        Path jarPath = Paths.get(resolvedPath);
        if (!Files.exists(jarPath)) {
            log.warn("Backend JAR does not exist: {}", resolvedPath);
            return null;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String backupPath = stagingDirPath + "/backend_backup_" + timestamp + ".jar";
        ensureDir(stagingDirPath);
        Files.copy(jarPath, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING);
        log.info("Backend JAR backed up to: {}", backupPath);
        return backupPath;
    }

    /**
     * Replace frontend dist with new files from staging.
     * Atomic swap: write to a temp directory first, then rename-swap with the live directory.
     * If the copy fails, the live directory is left untouched.
     */
    private void replaceFrontendDist(Path newDistDir) throws Exception {
        Path targetDir = Paths.get(frontendDistDir);
        Path parentDir = targetDir.getParent();

        // Step 1: Write new files to a temporary directory next to the live one
        Path tempDir = parentDir.resolve("frontend_new_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(tempDir);
            // Copy new dist files to temp dir
            try (var stream = Files.walk(newDistDir)) {
                stream.forEach(source -> {
                    Path relative = newDistDir.relativize(source);
                    Path target = tempDir.resolve(relative);
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("复制前端文件失败: " + source, e);
                    }
                });
            }

            // Step 2: Rename old live dir out of the way (if it exists)
            Path oldDir = parentDir.resolve("frontend_old_" + UUID.randomUUID().toString().substring(0, 8));
            if (Files.exists(targetDir)) {
                Files.move(targetDir, oldDir);
            }

            // Step 3: Atomic rename: temp → live
            Files.move(tempDir, targetDir);
            log.info("Frontend dist replaced successfully (atomic swap)");

            // Step 4: Async cleanup of old directory (don't block; best-effort)
            Path oldDirRef = oldDir;
            taskExecutor.execute(() -> {
                try {
                    Thread.sleep(2000);
                    deleteRecursive(oldDirRef);
                    log.info("Old frontend directory cleaned up: {}", oldDirRef);
                } catch (Exception e) {
                    log.warn("Failed to cleanup old frontend directory: {}", oldDirRef, e);
                }
            });

        } catch (Exception e) {
            // If anything failed, try to clean up the temp directory
            try { deleteRecursive(tempDir); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * Replace backend JAR with new one.
     * Atomic swap: write to a temp file first, then rename to the live path.
     */
    private void replaceBackendJar(Path newJarPath) throws Exception {
        String resolvedPath = resolveBackendJarPath();
        Path targetPath = Paths.get(resolvedPath);
        Files.createDirectories(targetPath.getParent());

        // Write to temp file first
        Path tempPath = targetPath.resolveSibling("phonecost.jar.tmp_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.copy(newJarPath, tempPath, StandardCopyOption.REPLACE_EXISTING);
            // Atomic rename: temp → live
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backend JAR replaced successfully at: {}", resolvedPath);
        } catch (Exception e) {
            // Clean up temp file on failure
            try { Files.deleteIfExists(tempPath); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * Restore frontend dist from a tar.gz backup.
     * Atomic swap: extract to temp dir first, then rename-swap with live directory.
     */
    private void restoreFrontendDist(String backupPath) throws Exception {
        Path distParent = Paths.get(frontendDistDir).getParent();
        Path targetDir = Paths.get(frontendDistDir);

        // Step 1: Extract backup to a temporary directory
        Path tempDir = distParent.resolve("frontend_restore_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(tempDir);
            ProcessBuilder pb = new ProcessBuilder("tar", "xzf", backupPath,
                    "-C", tempDir.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = readProcessOutput(proc);
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("前端dist恢复解压失败: " + output);
            }

            // tar extracts with the "frontend" directory name, so find it
            // The backup was created as: tar czf ... -C <parent> frontend
            // So extracted structure is: tempDir/frontend/
            Path extractedFrontend = tempDir.resolve(Paths.get(frontendDistDir).getFileName());
            if (!Files.exists(extractedFrontend)) {
                // Fallback: the tar might have extracted contents directly
                extractedFrontend = tempDir;
            }

            // Step 2: Rename old live dir out of the way
            Path oldDir = distParent.resolve("frontend_old_restore_" + UUID.randomUUID().toString().substring(0, 8));
            if (Files.exists(targetDir)) {
                Files.move(targetDir, oldDir);
            }

            // Step 3: Atomic rename: extracted → live
            if (!extractedFrontend.equals(targetDir)) {
                Files.move(extractedFrontend, targetDir);
            } else {
                // extractedFrontend is tempDir itself (unlikely edge case)
                Files.move(tempDir, targetDir);
            }
            log.info("Frontend dist restored from: {}", backupPath);

            // Step 4: Async cleanup
            Path oldDirRef = oldDir;
            Path tempDirRef = tempDir;
            taskExecutor.execute(() -> {
                try {
                    Thread.sleep(2000);
                    deleteRecursive(oldDirRef);
                    deleteRecursive(tempDirRef);
                    log.info("Old directories cleaned up after restore");
                } catch (Exception e) {
                    log.warn("Failed to cleanup old directories after restore", e);
                }
            });

        } catch (Exception e) {
            try { deleteRecursive(tempDir); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * Restore backend JAR from backup.
     * Atomic swap: write to temp file first, then rename.
     */
    private void restoreBackendJar(String backupPath) throws Exception {
        String resolvedPath = resolveBackendJarPath();
        Path targetPath = Paths.get(resolvedPath);
        Path tempPath = targetPath.resolveSibling("phonecost.jar.restore_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.copy(Paths.get(backupPath), tempPath, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backend JAR restored from: {} to: {}", backupPath, resolvedPath);
        } catch (Exception e) {
            try { Files.deleteIfExists(tempPath); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * Schedule an async backend service restart after a short delay.
     * M-32: Use Spring TaskExecutor instead of raw Thread for better lifecycle management.
     * The delay allows the current API response to be sent before the process is killed.
     */
    private void scheduleBackendRestart() {
        taskExecutor.execute(() -> {
            try {
                log.info("Scheduling backend service restart in 3 seconds...");
                Thread.sleep(3000);
                ProcessBuilder pb = new ProcessBuilder("sudo", "systemctl", "restart", BACKEND_SERVICE_NAME);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = readProcessOutput(proc);
                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    log.info("Backend service restarted successfully");
                } else {
                    log.error("Backend service restart failed: {}", output);
                }
            } catch (Exception e) {
                log.error("Failed to restart backend service", e);
            }
        });
    }

    private String readProcessOutput(Process proc) throws IOException {
        try (InputStream is = proc.getInputStream()) {
            return new String(is.readAllBytes());
        }
    }
}
