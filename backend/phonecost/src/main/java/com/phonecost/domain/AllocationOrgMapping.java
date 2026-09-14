package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "allocation_org_mapping")
@Getter @Setter @NoArgsConstructor
public class AllocationOrgMapping extends BaseEntity {

    @Column(name = "l1_branch", nullable = false)
    @ColumnDefault("''")
    private String l1Branch;

    /** 机构名称 */
    @Column(name = "org_name", nullable = false)
    @ColumnDefault("''")
    private String orgName;

    /** 机构代码（不同机构可共用） */
    @Column(name = "org_code", nullable = false)
    @ColumnDefault("''")
    private String orgCode;

    /** 成本中心代码（可跨分行；空存 NULL） */
    @Column(name = "cost_center_code")
    private String costCenterCode;

    /** 部门全路径（独占一行；同一一级分行下唯一） */
    @Column(name = "dept_full_path")
    private String deptFullPath;

    @Column(name = "remark")
    @ColumnDefault("''")
    private String remark;
}
