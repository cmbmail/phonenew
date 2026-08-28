package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "allocation_dept_entry")
@Getter
@Setter
@NoArgsConstructor
public class AllocationDeptEntry extends BaseEntity {

    @Column(name = "phone_number")
    @ColumnDefault("''")
    private String phoneNumber;

    @Column(name = "extension")
    @ColumnDefault("''")
    private String extension;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "branch")
    @ColumnDefault("''")
    private String branch;

    @Column(name = "dept_name")
    @ColumnDefault("''")
    private String deptName;

    @Column(name = "full_path")
    @ColumnDefault("''")
    private String fullPath;

    @Column(name = "org_code")
    @ColumnDefault("''")
    private String orgCode;

    @Column(name = "cost_center")
    @ColumnDefault("''")
    private String costCenter;

    @Column(name = "is_exception")
    @ColumnDefault("0")
    private Byte isException;
}
