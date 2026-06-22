package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;

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
