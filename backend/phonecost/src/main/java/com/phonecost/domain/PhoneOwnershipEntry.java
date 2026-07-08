package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "phone_ownership_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneOwnershipEntry extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

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
}
