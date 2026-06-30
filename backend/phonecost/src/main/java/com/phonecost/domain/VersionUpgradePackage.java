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
@Table(name = "version_upgrade_package")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Where(clause = "deleted_at IS NULL")
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
public class VersionUpgradePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "package_name", nullable = false, length = 200)
    private String packageName;

    @Column(name = "target_version", nullable = false, length = 50)
    private String targetVersion;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_size")
    @ColumnDefault("0")
    private Long fileSize = 0L;

    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'UPLOADED'")
    private String status = "UPLOADED"; // UPLOADED, APPLIED, FAILED, ROLLED_BACK

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by")
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
