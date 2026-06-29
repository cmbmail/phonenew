package com.phonecost.service;

import com.phonecost.domain.BackupRecord;
import com.phonecost.repository.BackupRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据备份服务
 * - 全量备份：mysqldump 整个数据库
 * - 增量备份：按 updated_at / created_at 过滤导出变更行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final BackupRecordRepository backupRecordRepository;
    private final DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private static final String BACKUP_DIR = "/data/apps/phonecost/backups";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DB_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 执行全量备份
     */
    public BackupRecord performFullBackup(String triggerType) {
        String timestamp = LocalDateTime.now().format(TS_FMT);
        String filename = "full_" + timestamp + ".sql.gz";
        String filepath = BACKUP_DIR + "/" + filename;

        BackupRecord record = BackupRecord.builder()
                .backupType("FULL")
                .filePath(filepath)
                .fileSize(0L)
                .status("IN_PROGRESS")
                .tableCount(0)
                .rowCount(0L)
                .triggerType(triggerType)
                .build();
        record = backupRecordRepository.save(record);

        try {
            ensureBackupDir();
            String[] connInfo = parseDbUrl();
            String host = connInfo[0], port = connInfo[1], dbName = connInfo[2];

            ProcessBuilder pb = new ProcessBuilder(
                    "mysqldump",
                    "-h", host, "-P", port,
                    "-u", dbUser,
                    "-p" + dbPassword,
                    "--single-transaction",
                    "--routines",
                    "--triggers",
                    "--set-gtid-purged=OFF",
                    "--no-tablespaces",
                    dbName
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (InputStream dumpOut = process.getInputStream();
                 OutputStream fileOut = Files.newOutputStream(Paths.get(filepath));
                 OutputStream gzipOut = new java.util.zip.GZIPOutputStream(fileOut)) {
                dumpOut.transferTo(gzipOut);
            }

            String stderr = readStream(process.getErrorStream());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("mysqldump failed (exit=" + exitCode + "): " + stderr);
            }

            long fileSize = Files.size(Paths.get(filepath));
            record.setFileSize(fileSize);
            record.setStatus("SUCCESS");
            log.info("Full backup completed: {} ({} bytes)", filename, fileSize);

        } catch (Exception e) {
            log.error("Full backup failed", e);
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");
        }

        return backupRecordRepository.save(record);
    }

    /**
     * 执行增量备份
     * 策略：按表是否有 updated_at 列分别用不同 WHERE 条件导出
     * - 有 updated_at 的表：WHERE updated_at > '上次备份时间'
     * - 仅有 created_at 的表：WHERE created_at > '上次备份时间'
     */
    public BackupRecord performIncrementalBackup(String triggerType) {
        BackupRecord lastBackup = backupRecordRepository
                .findTopByStatusOrderByCreatedAtDesc("SUCCESS")
                .orElse(null);

        LocalDateTime sinceTime = lastBackup != null ? lastBackup.getCreatedAt() : LocalDateTime.now().minusDays(1);
        Long baseBackupId = null;
        if (lastBackup != null) {
            if ("FULL".equals(lastBackup.getBackupType())) {
                baseBackupId = lastBackup.getId();
            } else {
                baseBackupId = lastBackup.getBaseBackupId();
            }
        }

        String timestamp = LocalDateTime.now().format(TS_FMT);
        String filename = "incr_" + timestamp + ".sql.gz";
        String filepath = BACKUP_DIR + "/" + filename;

        BackupRecord record = BackupRecord.builder()
                .backupType("INCREMENTAL")
                .filePath(filepath)
                .fileSize(0L)
                .status("IN_PROGRESS")
                .tableCount(0)
                .rowCount(0L)
                .triggerType(triggerType)
                .baseBackupId(baseBackupId)
                .build();
        record = backupRecordRepository.save(record);

        try {
            ensureBackupDir();
            String[] connInfo = parseDbUrl();
            String host = connInfo[0], port = connInfo[1], dbName = connInfo[2];
            String sinceStr = sinceTime.format(DB_TS_FMT);

            // Query information_schema for table column info
            List<String> tablesWithUpdatedAt = new ArrayList<>();
            List<String> tablesWithCreatedOnly = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT t.TABLE_NAME, " +
                     "  MAX(CASE WHEN c.COLUMN_NAME='updated_at' THEN 1 ELSE 0 END) AS has_updated, " +
                     "  MAX(CASE WHEN c.COLUMN_NAME='created_at' THEN 1 ELSE 0 END) AS has_created " +
                     "FROM information_schema.TABLES t " +
                     "JOIN information_schema.COLUMNS c ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME " +
                     "WHERE t.TABLE_SCHEMA='" + dbName + "' AND t.TABLE_TYPE='BASE TABLE' " +
                     "AND t.TABLE_NAME NOT IN ('flyway_schema_history','backup_record') " +
                     "GROUP BY t.TABLE_NAME")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    boolean hasUpdated = rs.getInt("has_updated") == 1;
                    boolean hasCreated = rs.getInt("has_created") == 1;
                    if (hasUpdated) {
                        tablesWithUpdatedAt.add(table);
                    } else if (hasCreated) {
                        tablesWithCreatedOnly.add(table);
                    }
                }
            }

            log.info("Incremental backup: {} tables with updated_at, {} tables with created_at only",
                    tablesWithUpdatedAt.size(), tablesWithCreatedOnly.size());

            // Write both dumps to same gzip file
            try (OutputStream fileOut = Files.newOutputStream(Paths.get(filepath));
                 OutputStream gzipOut = new java.util.zip.GZIPOutputStream(fileOut)) {

                // Dump tables with updated_at
                if (!tablesWithUpdatedAt.isEmpty()) {
                    runMysqldump(host, port, dbName, tablesWithUpdatedAt,
                            "updated_at > '" + sinceStr + "'", gzipOut);
                }

                // Dump tables with created_at only (e.g. audit_log)
                if (!tablesWithCreatedOnly.isEmpty()) {
                    runMysqldump(host, port, dbName, tablesWithCreatedOnly,
                            "created_at > '" + sinceStr + "'", gzipOut);
                }
            }

            long fileSize = Files.size(Paths.get(filepath));
            record.setFileSize(fileSize);
            int tableCount = tablesWithUpdatedAt.size() + tablesWithCreatedOnly.size();
            record.setTableCount(tableCount);
            record.setStatus("SUCCESS");
            log.info("Incremental backup completed: {} ({} bytes, {} tables, since {})",
                    filename, fileSize, tableCount, sinceStr);

        } catch (Exception e) {
            log.error("Incremental backup failed", e);
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");
        }

        return backupRecordRepository.save(record);
    }

    /**
     * 从备份恢复
     */
    public BackupRecord restoreBackup(Long backupId) {
        BackupRecord record = backupRecordRepository.findById(backupId)
                .orElseThrow(() -> new RuntimeException("备份记录不存在: " + backupId));

        if (!"SUCCESS".equals(record.getStatus())) {
            throw new RuntimeException("该备份状态不可恢复: " + record.getStatus());
        }

        String filepath = record.getFilePath();
        if (!Files.exists(Paths.get(filepath))) {
            throw new RuntimeException("备份文件不存在: " + filepath);
        }

        try {
            String[] connInfo = parseDbUrl();
            String host = connInfo[0], port = connInfo[1], dbName = connInfo[2];

            ProcessBuilder gunzipPb = new ProcessBuilder("gunzip", "-c", filepath);
            ProcessBuilder mysqlPb = new ProcessBuilder(
                    "mysql",
                    "-h", host, "-P", port,
                    "-u", dbUser,
                    "-p" + dbPassword,
                    "--no-tablespaces",
                    dbName
            );

            Process gunzipProc = gunzipPb.start();
            Process mysqlProc = mysqlPb.start();

            try (InputStream gunzipOut = gunzipProc.getInputStream();
                 OutputStream mysqlIn = mysqlProc.getOutputStream()) {
                gunzipOut.transferTo(mysqlIn);
                mysqlIn.flush();
            }
            mysqlProc.getOutputStream().close();

            String gunzipErr = readStream(gunzipProc.getErrorStream());
            String mysqlErr = readStream(mysqlProc.getErrorStream());
            int gunzipExit = gunzipProc.waitFor();
            int mysqlExit = mysqlProc.waitFor();

            if (gunzipExit != 0) {
                throw new RuntimeException("gunzip failed: " + gunzipErr);
            }
            if (mysqlExit != 0) {
                throw new RuntimeException("mysql restore failed: " + mysqlErr);
            }

            log.info("Restore completed from: {}", filepath);
            return record;

        } catch (Exception e) {
            log.error("Restore failed", e);
            throw new RuntimeException("恢复失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除备份（软删除记录 + 删除文件）
     */
    public void deleteBackup(Long backupId) {
        BackupRecord record = backupRecordRepository.findById(backupId)
                .orElseThrow(() -> new RuntimeException("备份记录不存在: " + backupId));

        try {
            if (Files.exists(Paths.get(record.getFilePath()))) {
                Files.delete(Paths.get(record.getFilePath()));
            }
        } catch (IOException e) {
            log.warn("Failed to delete backup file: {}", record.getFilePath(), e);
        }

        record.setDeletedAt(LocalDateTime.now());
        backupRecordRepository.save(record);
    }

    /**
     * 查询备份列表
     */
    public Page<BackupRecord> listBackups(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return backupRecordRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    // === Helper methods ===

    /**
     * 运行 mysqldump 导出指定表，输出写入 outputStream
     */
    private void runMysqldump(String host, String port, String dbName,
                               List<String> tables, String whereClause,
                               OutputStream outputStream) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("mysqldump");
        cmd.add("-h"); cmd.add(host);
        cmd.add("-P"); cmd.add(port);
        cmd.add("-u"); cmd.add(dbUser);
        cmd.add("-p" + dbPassword);
        cmd.add("--single-transaction");
        cmd.add("--no-create-info");
        cmd.add("--replace");
        cmd.add("--set-gtid-purged=OFF");
        cmd.add("--no-tablespaces");
        cmd.add("--where=" + whereClause);
        cmd.add(dbName);
        cmd.addAll(tables);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Pipe stdout to outputStream
        try (InputStream dumpOut = process.getInputStream()) {
            dumpOut.transferTo(outputStream);
        }

        String stderr = readStream(process.getErrorStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("mysqldump failed for tables " + tables + " (exit=" + exitCode + "): " + stderr);
        }
    }

    private void ensureBackupDir() throws IOException {
        Path dir = Paths.get(BACKUP_DIR);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private String[] parseDbUrl() {
        Pattern pattern = Pattern.compile("jdbc:mysql://([^:/]+):(\\d+)/([^?]+)");
        Matcher matcher = pattern.matcher(dbUrl);
        if (!matcher.find()) {
            pattern = Pattern.compile("jdbc:mysql://([^:/]+)/([^?]+)");
            matcher = pattern.matcher(dbUrl);
            if (matcher.find()) {
                return new String[]{matcher.group(1), "3306", matcher.group(2)};
            }
            throw new RuntimeException("Cannot parse DB URL: " + dbUrl);
        }
        return new String[]{matcher.group(1), matcher.group(2), matcher.group(3)};
    }

    private String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toString();
    }
}
