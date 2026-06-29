package com.phonecost.service;

import com.phonecost.domain.BackupRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 定时备份任务
 * - 每月1日凌晨2:00 执行全量备份
 * - 每日凌晨2:30 执行增量备份
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledBackupService {

    private final BackupService backupService;

    /**
     * 每月1日凌晨2:00 全量备份
     */
    @Scheduled(cron = "0 0 2 1 * ?")
    public void scheduledFullBackup() {
        log.info("=== Scheduled full backup started ===");
        try {
            BackupRecord result = backupService.performFullBackup("AUTO");
            log.info("=== Scheduled full backup finished: status={} ===", result.getStatus());
        } catch (Exception e) {
            log.error("=== Scheduled full backup failed ===", e);
        }
    }

    /**
     * 每日凌晨2:30 增量备份
     */
    @Scheduled(cron = "0 30 2 * * ?")
    public void scheduledIncrementalBackup() {
        log.info("=== Scheduled incremental backup started ===");
        try {
            BackupRecord result = backupService.performIncrementalBackup("AUTO");
            log.info("=== Scheduled incremental backup finished: status={} ===", result.getStatus());
        } catch (Exception e) {
            log.error("=== Scheduled incremental backup failed ===", e);
        }
    }
}
