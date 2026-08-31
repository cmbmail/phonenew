package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;
import org.hibernate.annotations.ColumnDefault;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "allocation_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllocationResult extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "org_name")
    @ColumnDefault("''")
    private String orgName;

    @Column(name = "cost_center")
    private String costCenter;

    @Column(name = "monthly_rent", precision = 22, scale = 15)
    @ColumnDefault("0")
    private BigDecimal monthlyRent;

    @Column(name = "call_fee", precision = 22, scale = 15)
    @ColumnDefault("0")
    private BigDecimal callFee;

    @Column(name = "recording_fee", precision = 22, scale = 15)
    @ColumnDefault("0")
    private BigDecimal recordingFee;

    @Column(name = "crbt_fee", precision = 22, scale = 15)
    @ColumnDefault("0")
    private BigDecimal crbtFee;

    @Column(name = "flash_msg_fee", precision = 22, scale = 15)
    @ColumnDefault("0")
    private BigDecimal flashMsgFee;

    @Column(name = "total_fee", precision = 22, scale = 15)
    @ColumnDefault("0")
    private BigDecimal totalFee;

    @Column(name = "phone_count")
    @ColumnDefault("0")
    private Integer phoneCount;

    @Column(name = "confirm_status")
    @ColumnDefault("0")
    private Byte confirmStatus;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "withdrawn_by")
    private Long withdrawnBy;

    @Column(name = "withdraw_reason")
    @ColumnDefault("''")
    private String withdrawReason;

    @Version
    @Column(name = "version")
    @ColumnDefault("0")
    private Integer version;
}
