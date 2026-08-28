package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;

import java.time.LocalDateTime;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "recording_data_entry")
@Getter @Setter @NoArgsConstructor
public class RecordingDataEntry extends BaseEntity {
    @Column(name = "batch_id", nullable = false)
    private Long batchId;
    @Column(name = "extension", nullable = false) @ColumnDefault("''")
    private String extension;
    @Column(name = "phone_number", nullable = false) @ColumnDefault("''")
    private String phoneNumber;
    @Column(name = "dept_name") @ColumnDefault("''")
    private String deptName;
    @Column(name = "remark") @ColumnDefault("''")
    private String remark;
    @Column(name = "status") @ColumnDefault("0")
    private Byte status;  // H-DB09: INT→TINYINT
    @Column(name = "close_time")
    private LocalDateTime closeTime;
}
