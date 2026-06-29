package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Where;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "backup_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Where(clause = "deleted_at IS NULL")
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
public class BackupRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backup_type", nullable = false, length = 20)
    private String backupType; // FULL, INCREMENTAL

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_size") @ColumnDefault("0")
    private Long fileSize = 0L;

    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'IN_PROGRESS'")
    private String status = "IN_PROGRESS"; // IN_PROGRESS, SUCCESS, FAILED

    @Column(name = "table_count") @ColumnDefault("0")
    private Integer tableCount = 0;

    @Column(name = "row_count") @ColumnDefault("0")
    private Long rowCount = 0L;

    @Column(name = "trigger_type", nullable = false, length = 20)
    @ColumnDefault("'AUTO'")
    private String triggerType; // AUTO, MANUAL

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "base_backup_id")
    private Long baseBackupId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
