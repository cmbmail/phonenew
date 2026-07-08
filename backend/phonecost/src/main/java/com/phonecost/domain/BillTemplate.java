package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Where(clause = "deleted_at IS NULL")
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
