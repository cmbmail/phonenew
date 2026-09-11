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

    /** 机构名称（唯一） */
    @Column(name = "org_name", nullable = false)
    @ColumnDefault("''")
    private String orgName;

    /** 机构代码（唯一） */
    @Column(name = "org_code", nullable = false)
    @ColumnDefault("''")
    private String orgCode;

    /** 成本中心代码（唯一；空存 NULL，多条记录可同时无成本中心） */
    @Column(name = "cost_center_code")
    private String costCenterCode;

    /** 部门全路径（多个以、分隔；值在同一一级分行下唯一） */
    @Column(name = "dept_full_path", columnDefinition = "TEXT")
    private String deptFullPath;

    @Column(name = "remark")
    @ColumnDefault("''")
    private String remark;
}
