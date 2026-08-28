package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "org_code_mapping")
@Getter @Setter @NoArgsConstructor
public class OrgCodeMapping extends BaseEntity {

    @Column(name = "l1_branch", nullable = false)
    @ColumnDefault("''")
    private String l1Branch;

    @Column(name = "org_code", nullable = false)
    @ColumnDefault("''")
    private String orgCode;

    @Column(name = "org_name", nullable = false)
    @ColumnDefault("''")
    private String orgName;

    @Column(name = "cost_center_code", nullable = false)
    @ColumnDefault("''")
    private String costCenterCode;

    @Column(name = "remark")
    @ColumnDefault("''")
    private String remark;
}