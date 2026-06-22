package com.phonecost.service;

import java.util.List;

/**
 * 数据范围定义
 * 根据用户角色决定可见的数据范围：
 * - ADMIN(1)/FINANCE(4): 全部数据
 * - BRANCH(2): 本分行及下级子树
 * - DEPARTMENT(3): 仅本部门
 */
public class DataScope {

    private final boolean allScope;
    private final String pathPrefix;      // BRANCH: org.path，用于 LIKE path%
    private final Long singleOrgId;       // DEPARTMENT: 仅自己的 orgId
    private final List<Long> visibleOrgIds; // 预计算的可见组织ID列表

    private DataScope(boolean allScope, String pathPrefix, Long singleOrgId, List<Long> visibleOrgIds) {
        this.allScope = allScope;
        this.pathPrefix = pathPrefix;
        this.singleOrgId = singleOrgId;
        this.visibleOrgIds = visibleOrgIds;
    }

    public static DataScope allScope() {
        return new DataScope(true, null, null, null);
    }

    public static DataScope subtreeScope(String pathPrefix, List<Long> visibleOrgIds) {
        return new DataScope(false, pathPrefix, null, visibleOrgIds);
    }

    public static DataScope singleOrgScope(Long orgId) {
        return new DataScope(false, null, orgId, List.of(orgId));
    }

    public boolean isAllScope() {
        return allScope;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public Long getSingleOrgId() {
        return singleOrgId;
    }

    /**
     * 获取可见的组织ID列表
     * @return null表示不限制（全量），否则为允许的组织ID列表
     */
    public List<Long> getVisibleOrgIds() {
        if (allScope) return null;
        return visibleOrgIds;
    }

    /**
     * 检查指定orgId是否在可见范围内
     */
    public boolean isOrgVisible(Long orgId) {
        if (allScope) return true;
        if (orgId == null) return false;
        return visibleOrgIds != null && visibleOrgIds.contains(orgId);
    }

    /**
     * 对组织ID列表进行过滤
     * @return 过滤后的列表（包含 sentinel orgId=-1 的未归属项）
     */
    public <T> List<T> filterByOrgId(List<T> items, java.util.function.Function<T, Long> orgIdExtractor) {
        if (allScope) return items;
        return items.stream()
                .filter(item -> {
                    Long orgId = orgIdExtractor.apply(item);
                    // 未归属的 sentinel orgId=-1 总是可见
                    if (orgId != null && orgId == -1L) return true;
                    return isOrgVisible(orgId);
                })
                .toList();
    }
}
