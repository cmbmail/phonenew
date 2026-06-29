package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class RoleController {

    private final JdbcTemplate jdbcTemplate;

    private static final List<Map<String, Object>> ROLES = List.of(
            Map.of("id", 1, "code", "ADMIN", "name", "集团管理员", "description", "拥有系统全部权限，管理所有组织和用户"),
            Map.of("id", 2, "code", "BRANCH", "name", "分行管理员", "description", "管理所属分行及下级组织的数据导入和费用分摊"),
            Map.of("id", 3, "code", "DEPARTMENT", "name", "部门管理员", "description", "查看所属部门的费用分摊数据"),
            Map.of("id", 4, "code", "FINANCE", "name", "财务人员", "description", "查看和导出费用分摊汇总及明细报表")
    );

    private static final List<Map<String, Object>> PERMISSIONS = List.of(
            Map.of("module", "系统看板", "permissions", List.of(
                    Map.of("key", "dashboard_view", "label", "查看看板"),
                    Map.of("key", "dashboard_all", "label", "查看全量数据")
            )),
            Map.of("module", "费用分摊", "permissions", List.of(
                    Map.of("key", "bill_view", "label", "查看账单"),
                    Map.of("key", "bill_import", "label", "导入账单"),
                    Map.of("key", "allocation_view", "label", "查看分摊"),
                    Map.of("key", "allocation_calculate", "label", "计算分摊"),
                    Map.of("key", "allocation_confirm", "label", "确认/撤回"),
                    Map.of("key", "allocation_export", "label", "导出报表"),
                    Map.of("key", "allocation_analysis", "label", "费用分析")
            )),
            Map.of("module", "基础数据", "permissions", List.of(
                    Map.of("key", "base_view", "label", "查看数据"),
                    Map.of("key", "base_import", "label", "导入数据"),
                    Map.of("key", "org_manage", "label", "组织管理")
            )),
            Map.of("module", "系统管理", "permissions", List.of(
                    Map.of("key", "user_manage", "label", "人员管理"),
                    Map.of("key", "template_manage", "label", "模板管理"),
                    Map.of("key", "audit_view", "label", "操作日志"),
                    Map.of("key", "backup_manage", "label", "数据维护"),
                    Map.of("key", "role_manage", "label", "角色管理")
            ))
    );

    // 权限矩阵: role_id -> set of permission keys
    private static final Map<Integer, Set<String>> ROLE_PERMISSIONS = Map.of(
            1, Set.of("dashboard_view", "dashboard_all", "bill_view", "bill_import",
                    "allocation_view", "allocation_calculate", "allocation_confirm", "allocation_export", "allocation_analysis",
                    "base_view", "base_import", "org_manage",
                    "user_manage", "template_manage", "audit_view", "backup_manage", "role_manage"),
            2, Set.of("dashboard_view", "bill_view", "bill_import",
                    "allocation_view", "allocation_confirm",
                    "base_view", "base_import",
                    "allocation_analysis"),
            3, Set.of("dashboard_view", "allocation_view", "base_view", "allocation_analysis"),
            4, Set.of("dashboard_view", "allocation_view", "allocation_export", "base_view", "allocation_analysis")
    );

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> role : ROLES) {
            Map<String, Object> item = new LinkedHashMap<>(role);
            int roleId = ((Number) role.get("id")).intValue();
            // Count users with this role
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE role = ? AND deleted_at IS NULL", Integer.class, roleId);
            item.put("user_count", count != null ? count : 0);
            item.put("permissions", ROLE_PERMISSIONS.getOrDefault(roleId, Set.of()));
            result.add(item);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/permissions")
    public ApiResponse<Map<String, Object>> permissions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roles", ROLES);
        result.put("modules", PERMISSIONS);
        result.put("matrix", ROLE_PERMISSIONS);
        return ApiResponse.ok(result);
    }
}
