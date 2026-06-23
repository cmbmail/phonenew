package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bill_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillTemplate extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "operator")
    @ColumnDefault("'CHINA_TELECOM'")
    private String operator;

    @Column(name = "month_pattern")
    private String monthPattern;

    @Column(name = "sheet_configs", nullable = false, columnDefinition = "JSON")
    private String sheetConfigs;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active")
    @ColumnDefault("1")
    private Byte isActive;
}
