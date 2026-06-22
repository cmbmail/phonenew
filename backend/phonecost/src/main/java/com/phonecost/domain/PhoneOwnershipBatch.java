package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "phone_ownership_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneOwnershipBatch extends BaseEntity {

    @Column(name = "batch_no", nullable = false, unique = true)
    private String batchNo;

    @Column(name = "file_name")
    @ColumnDefault("''")
    private String fileName;

    @Column(name = "total_count")
    @ColumnDefault("0")
    private Integer totalCount;

    @Column(name = "exception_count")
    @ColumnDefault("0")
    private Integer exceptionCount;

    @Column(name = "import_status")
    @ColumnDefault("0")
    private Byte importStatus;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "imported_by", nullable = false)
    private Long importedBy;
}
