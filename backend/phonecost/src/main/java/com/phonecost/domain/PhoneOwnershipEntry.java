package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "phone_ownership_entry")
@Getter
@Setter
@NoArgsConstructor
public class PhoneOwnershipEntry extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "extension")
    @ColumnDefault("''")
    private String extension;

    @Column(name = "full_path")
    @ColumnDefault("''")
    private String fullPath;

    @Column(name = "description")
    @ColumnDefault("''")
    private String description;

    @Column(name = "is_exception")
    @ColumnDefault("0")
    private Byte isException;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "match_level")
    @ColumnDefault("''")
    private String matchLevel;

    @Column(name = "l1_branch")
    @ColumnDefault("''")
    private String l1Branch;

    @Column(name = "l2_branch")
    @ColumnDefault("''")
    private String l2Branch;

    @Column(name = "status")
    @ColumnDefault("0")
    private Byte status;

    @Column(name = "alloc_dept")
    @ColumnDefault("''")
    private String allocDept;

    @Column(name = "org_code")
    @ColumnDefault("''")
    private String orgCode;

    @Column(name = "cost_center")
    @ColumnDefault("''")
    private String costCenter;
}
