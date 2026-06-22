package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bill_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillBatch extends BaseEntity {

    @Column(name = "batch_no", nullable = false, unique = true)
    private String batchNo;

    @Column(name = "billing_month", nullable = false)
    private String billingMonth;

    @Column(name = "file_name")
    @ColumnDefault("''")
    private String fileName;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "status")
    @ColumnDefault("0")
    private Byte status;

    @Column(name = "total_amount", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal totalAmount;

    @Column(name = "total_count")
    @ColumnDefault("0")
    private Integer totalCount;

    @Column(name = "import_status")
    @ColumnDefault("0")
    private Byte importStatus;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "imported_by", nullable = false)
    private Long importedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;
}
