package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "allocation_adjustment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllocationAdjustment extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "from_org_id", nullable = false)
    private Long fromOrgId;

    @Column(name = "to_org_id", nullable = false)
    private Long toOrgId;

    @Column(name = "from_org_name")
    @ColumnDefault("''")
    private String fromOrgName;

    @Column(name = "to_org_name")
    @ColumnDefault("''")
    private String toOrgName;

    @Column(name = "amount", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal amount;

    @Column(name = "fee_type")
    @ColumnDefault("'TOTAL'")
    private String feeType;

    @Column(name = "reason")
    @ColumnDefault("''")
    private String reason;

    @Column(name = "adjusted_by", nullable = false)
    private Long adjustedBy;

    @Column(name = "adjusted_name")
    @ColumnDefault("''")
    private String adjustedName;
}
