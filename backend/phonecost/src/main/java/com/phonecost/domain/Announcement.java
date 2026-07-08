package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Where;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 通知公告实体
 * status: 0=草稿 1=已发布 2=已归档
 * type: 0=通知 1=公告
 * priority: 0=普通 1=重要 2=紧急
 */
@Entity
@Table(name = "announcement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Where(clause = "deleted_at IS NULL")
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
public class Announcement extends BaseEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "type", nullable = false)
    @ColumnDefault("0")
    private Byte type;

    @Column(name = "priority", nullable = false)
    @ColumnDefault("0")
    private Byte priority;

    @Column(name = "status", nullable = false)
    @ColumnDefault("0")
    private Byte status;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "author_name", length = 100)
    @ColumnDefault("''")
    private String authorName;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "pinned")
    @ColumnDefault("0")
    private Byte pinned;
}
