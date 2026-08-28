package com.phonecost.domain;

import com.phonecost.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "allocation_org_entry")
@Getter @Setter @NoArgsConstructor
public class AllocationOrgEntry extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "phone_number") @ColumnDefault("''")
    private String phoneNumber;

    @Column(name = "username")
    private String username;

    @Column(name = "l1_branch") @ColumnDefault("''")
    private String l1Branch;

    @Column(name = "branch_org_id")
    private Long branchOrgId;

    @Column(name = "alloc_dept") @ColumnDefault("''")
    private String allocDept;

    @Column(name = "dept_path")
    private String deptPath;

    @Column(name = "extension")
    private String extension;

    @Column(name = "change_type")
    private String changeType;

    @Column(name = "changed_columns")
    private String changedColumns;

    @Column(name = "org_code") @ColumnDefault("''")
    private String orgCode;

    @Column(name = "cost_center") @ColumnDefault("''")
    private String costCenter;

    @Column(name = "remark") @ColumnDefault("''")
    private String remark;

    @Column(name = "verified") @ColumnDefault("0")
    private Boolean verified;

    @Column(name = "verified_at")
    private java.time.LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private Long verifiedBy;
}
