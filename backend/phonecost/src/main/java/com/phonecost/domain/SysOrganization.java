package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sys_organization")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysOrganization extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type", nullable = false)
    @ColumnDefault("0")
    private Byte type;

    @Column(name = "code")
    private String code;

    @Column(name = "cost_center")
    private String costCenter;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort_order")
    @ColumnDefault("0")
    private Integer sortOrder;

    @Column(name = "path", nullable = false)
    @ColumnDefault("''")
    private String path;

    @Column(name = "is_active")
    @ColumnDefault("1")
    private Byte isActive;
}
