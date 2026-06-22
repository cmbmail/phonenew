package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "bill_detail")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillDetail extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "extension")
    @ColumnDefault("''")
    private String extension;

    @Column(name = "sheet_type")
    @ColumnDefault("'CALL'")
    private String sheetType;

    @Column(name = "monthly_rent", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal monthlyRent;

    @Column(name = "call_fee", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal callFee;

    @Column(name = "recording_fee", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal recordingFee;

    @Column(name = "crbt_fee", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal crbtFee;

    @Column(name = "flash_msg_fee", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal flashMsgFee;

    @Column(name = "total_fee", precision = 12, scale = 2)
    @ColumnDefault("0.00")
    private BigDecimal totalFee;

    @Column(name = "ownership_source")
    @ColumnDefault("''")
    private String ownershipSource;

    @Column(name = "is_exception")
    @ColumnDefault("0")
    private Byte isException;

    @Column(name = "is_seconded")
    @ColumnDefault("0")
    private Byte isSeconded;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "flash_month")
    @ColumnDefault("''")
    private String flashMonth;

    @Column(name = "raw_data", columnDefinition = "JSON")
    private String rawData;
}
