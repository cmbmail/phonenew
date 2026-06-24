package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "matched_count")
    @ColumnDefault("0")
    private Integer matchedCount;
}
