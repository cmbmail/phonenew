package com.phonecost.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username")
    @ColumnDefault("''")
    private String username;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entity_type")
    @ColumnDefault("''")
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "detail", columnDefinition = "JSON")
    private String detail;

    @Column(name = "ip_address")
    @ColumnDefault("''")
    private String ipAddress;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
