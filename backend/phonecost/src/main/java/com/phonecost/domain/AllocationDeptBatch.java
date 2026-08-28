package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "allocation_dept_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllocationDeptBatch extends BaseEntity {

    @Column(name = "batch_no", nullable = false)
    private String batchNo;

    @Column(name = "file_name")
    @ColumnDefault("''")
    private String fileName;

    @Column(name = "total_count")
    @ColumnDefault("0")
    private Integer totalCount;

    @Column(name = "billing_month", length = 7)
    private String billingMonth;

    @Column(name = "import_status")
    @ColumnDefault("0")
    private Byte importStatus;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "imported_by", nullable = false)
    private Long importedBy;
}
