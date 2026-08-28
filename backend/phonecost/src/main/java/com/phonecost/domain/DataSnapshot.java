package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "data_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSnapshot extends BaseEntity {

    @Column(name = "bill_batch_id", nullable = false)
    private Long billBatchId;

    @Column(name = "ownership_batch_id")
    private Long ownershipBatchId;

    @Column(name = "directory_batch_id")
    private Long directoryBatchId;

    @Column(name = "allocation_dept_batch_id")
    private Long allocationDeptBatchId;

    @Column(name = "matched_count")
    @ColumnDefault("0")
    private Integer matchedCount;
}
