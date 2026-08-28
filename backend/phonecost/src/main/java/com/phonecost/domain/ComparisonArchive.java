package com.phonecost.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 数据对比归档
 */
@Entity
@Table(name = "comparison_archive")
@Getter
@Setter
@NoArgsConstructor
public class ComparisonArchive extends BaseEntity {

    /** 对比类型: exception=与例外对比, month=跨月对比 */
    @Column(name = "compare_type", nullable = false, length = 20)
    private String compareType;

    /** 月份A (跨月对比) */
    @Column(name = "month1", length = 10)
    private String month1;

    /** 月份B (跨月对比) */
    @Column(name = "month2", length = 10)
    private String month2;

    /** 最新月份 (例外对比) */
    @Column(name = "latest_month", length = 10)
    private String latestMonth;

    @Column(name = "added_count")
    private Integer addedCount = 0;

    @Column(name = "removed_count")
    private Integer removedCount = 0;

    @Column(name = "changed_count")
    private Integer changedCount = 0;

    @Column(name = "unchanged_count")
    private Integer unchangedCount = 0;

    @Column(name = "total_count")
    private Integer totalCount = 0;

    @Column(name = "archived_by")
    private Long archivedBy;

    @Column(name = "remark", length = 500)
    private String remark;

    /** 对比结果快照(JSON), 归档时由后端重算全量对比结果存储, 查看时直接读取避免实时重算导致数据漂移 (BUG-3) */
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;
}
