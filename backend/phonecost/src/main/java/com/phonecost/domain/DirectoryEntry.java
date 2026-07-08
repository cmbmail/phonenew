package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "directory_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectoryEntry extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "dept_path")
    @ColumnDefault("''")
    private String deptPath;

    @Column(name = "username")
    @ColumnDefault("''")
    private String username;

    @Column(name = "extension")
    @ColumnDefault("''")
    private String extension;

    @Column(name = "phone_number")
    @ColumnDefault("''")
    private String phoneNumber;

    @Column(name = "alloc_dept")
    @ColumnDefault("''")
    private String allocDept;

    @Column(name = "org_code")
    @ColumnDefault("''")
    private String orgCode;

    @Column(name = "cost_center")
    @ColumnDefault("''")
    private String costCenter;

    @Column(name = "remark")
    @ColumnDefault("''")
    private String remark;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "is_seconded")
    @ColumnDefault("0")
    private Byte isSeconded;

    @Column(name = "actual_org_id")
    private Long actualOrgId;

    @Column(name = "seconded_keyword")
    @ColumnDefault("''")
    private String secondedKeyword;
}
