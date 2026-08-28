package com.phonecost.service;

import com.phonecost.domain.SysOrganization;
import com.phonecost.domain.SysUser;
import com.phonecost.repository.SysOrganizationRepository;
import com.phonecost.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据范围解析服务
 * 根据用户角色和组织归属，解析可见数据范围
 *
 * 角色与范围映射：
 * - ADMIN(1): 全量数据
 * - BRANCH(2): 本分行及下级子树
 * - DEPARTMENT(3): 本部门及所有下级子组织
 * - FINANCE(4): 全量数据（财务需要看所有分行）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataScopeService {

    private final SysUserRepository userRepository;
    private final SysOrganizationRepository orgRepository;

    /**
     * 根据用户ID解析数据范围
     */
    public DataScope getDataScope(Long userId) {
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        Byte role = user.getRole();
        Long orgId = user.getOrgId();

        // Null role safety: treat null as no-permission
        if (role == null) {
            log.warn("DataScope: userId={} has null role, defaulting to empty scope", userId);
            return DataScope.singleOrgScope(-999L);
        }

        // ADMIN 和 FINANCE 拥有全量权限
        if (role == (byte)1 || role == (byte)4) {
            log.debug("DataScope: userId={}, role={}, scope=ALL", userId, role);
            return DataScope.allScope();
        }

        // 没有组织归属的非管理员用户，默认无权限看任何数据
        if (orgId == null || orgId == 0) {
            log.warn("DataScope: userId={}, role={} has no orgId, defaulting to empty scope", userId, role);
            return DataScope.singleOrgScope(-999L); // 不存在的orgId，等同于无数据
        }

        SysOrganization org = orgRepository.findByIdAndDeletedAtIsNull(orgId).orElse(null);
        if (org == null || org.getPath() == null) {
            log.warn("DataScope: userId={}, orgId={} not found or has no path", userId, orgId);
            return DataScope.singleOrgScope(-999L);
        }

        if (role == (byte)2) {
            // BRANCH: 本分行及所有下级
            List<SysOrganization> descendants = orgRepository
                    .findByPathStartingWithAndDeletedAtIsNull(org.getPath());
            List<Long> orgIds = descendants.stream().map(SysOrganization::getId).toList();
            log.debug("DataScope: userId={}, role=BRANCH, orgId={}, subtreeSize={}", userId, orgId, orgIds.size());
            return DataScope.subtreeScope(org.getPath(), orgIds);
        }

        if (role == (byte)3) {
            // DEPARTMENT: 本部门及所有下级子组织
            List<SysOrganization> descendants = orgRepository
                    .findByPathStartingWithAndDeletedAtIsNull(org.getPath());
            List<Long> orgIds = descendants.stream().map(SysOrganization::getId).toList();
            log.debug("DataScope: userId={}, role=DEPARTMENT, orgId={}, subtreeSize={}", userId, orgId, orgIds.size());
            return DataScope.subtreeScope(org.getPath(), orgIds);
        }

        // 未知角色，默认无权限
        log.warn("DataScope: userId={} has unknown role={}, defaulting to empty scope", userId, role);
        return DataScope.singleOrgScope(-999L);
    }

    /**
     * 解析用户所属一级分行（type=2）组织ID。
     * 从用户的 org_id 沿 parent 链向上查找第一个 type=2 的组织；
     * 用户自身即一级分行则直接返回；找不到返回 null（视为全量/未归属）。
     */
    public Long resolveBranchOrgId(Long userId) {
        try {
            SysUser user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
            if (user == null || user.getOrgId() == null) return null;
            java.util.Set<Long> visited = new java.util.HashSet<>();
            Long cur = user.getOrgId();
            while (cur != null && !visited.contains(cur)) {
                visited.add(cur);
                SysOrganization org = orgRepository.findByIdAndDeletedAtIsNull(cur).orElse(null);
                if (org == null) break;
                if (org.getType() != null && org.getType() == 2) return org.getId();
                if (org.getType() != null && org.getType() == 1) return null;
                if (org.getParentId() == null || org.getParentId() == 0L) break;
                cur = org.getParentId();
            }
        } catch (Exception e) {
            log.warn("resolveBranchOrgId failed for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    /**
     * 快捷方法：从请求属性(role, orgId)直接构建DataScope，不需要查库
     * 适用于已经从JWT中获取了role和orgId的场景
     */
    public DataScope getDataScopeFromContext(Byte role, Long orgId) {
        if (role == null) {
            return DataScope.singleOrgScope(-999L);
        }

        if (role == (byte)1 || role == (byte)4) {
            return DataScope.allScope();
        }

        if (orgId == null || orgId == 0) {
            return DataScope.singleOrgScope(-999L);
        }

        SysOrganization org = orgRepository.findByIdAndDeletedAtIsNull(orgId).orElse(null);
        if (org == null || org.getPath() == null) {
            return DataScope.singleOrgScope(-999L);
        }

        if (role == (byte)2) {
            List<SysOrganization> descendants = orgRepository
                    .findByPathStartingWithAndDeletedAtIsNull(org.getPath());
            List<Long> orgIds = descendants.stream().map(SysOrganization::getId).toList();
            return DataScope.subtreeScope(org.getPath(), orgIds);
        }

        if (role == (byte)3) {
            // DEPARTMENT: 本部门及所有下级子组织
            List<SysOrganization> descendants2 = orgRepository
                    .findByPathStartingWithAndDeletedAtIsNull(org.getPath());
            List<Long> orgIds2 = descendants2.stream().map(SysOrganization::getId).toList();
            return DataScope.subtreeScope(org.getPath(), orgIds2);
        }

        return DataScope.singleOrgScope(-999L);
    }
}
