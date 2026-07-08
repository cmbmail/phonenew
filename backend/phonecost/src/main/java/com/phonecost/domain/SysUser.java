package com.phonecost.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Where(clause = "deleted_at IS NULL")
@Entity
@Table(name = "sys_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysUser extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password", nullable = false)
    @JsonIgnore
    private String password;

    @Column(name = "real_name")
    @ColumnDefault("''")
    private String realName;

    @Column(name = "role")
    @ColumnDefault("4")
    private Byte role;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "status")
    @ColumnDefault("1")
    private Byte status;

    @Column(name = "must_change_pwd")
    @ColumnDefault("0")
    private Byte mustChangePwd;
}
