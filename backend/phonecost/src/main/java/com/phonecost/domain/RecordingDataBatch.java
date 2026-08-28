package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "recording_data_batch")
@Getter @Setter @NoArgsConstructor
public class RecordingDataBatch extends BaseEntity {
    @Column(name = "batch_no", nullable = false)
    private String batchNo;
    @Column(name = "billing_month") @ColumnDefault("''")
    private String billingMonth;
    @Column(name = "file_name") @ColumnDefault("''")
    private String fileName;
    @Column(name = "total_count") @ColumnDefault("0")
    private Integer totalCount;
    @Column(name = "import_status") @ColumnDefault("0")
    private Byte importStatus;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "imported_by", nullable = false)
    private Long importedBy;
}
