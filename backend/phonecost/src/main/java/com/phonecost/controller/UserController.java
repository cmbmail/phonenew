package com.phonecost.controller;

import com.phonecost.domain.SysOrganization;
import com.phonecost.domain.SysUser;
import com.phonecost.dto.ApiResponse;
import com.phonecost.repository.SysOrganizationRepository;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final DataScopeService dataScopeService;
    private final SysOrganizationRepository orgRepository;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SysUser>>> list(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) Long org_id) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<SysUser> allUsers = userService.list();
        List<SysUser> activeUsers = allUsers.stream().filter(u -> u.getDeletedAt() == null).toList();

        if (org_id != null) {
            // Must be within caller's data scope
            if (!scope.isOrgVisible(org_id)) {
                return ResponseEntity.ok(ApiResponse.ok(List.of()));
            }
            // Get subtree org IDs for the requested org
            SysOrganization targetOrg = orgRepository.findById(org_id).orElse(null);
            if (targetOrg == null) {
                return ResponseEntity.ok(ApiResponse.ok(List.of()));
            }
            List<SysOrganization> descendants = orgRepository.findByPathStartingWithAndDeletedAtIsNull(targetOrg.getPath());
            Set<Long> subtreeIds = descendants.stream().map(SysOrganization::getId).collect(Collectors.toSet());

            List<SysUser> filtered = activeUsers.stream()
                    .filter(u -> u.getOrgId() != null && subtreeIds.contains(u.getOrgId()))
                    .toList();
            return ResponseEntity.ok(ApiResponse.ok(filtered));
        }

        List<SysUser> filtered = scope.filterByOrgId(activeUsers, SysUser::getOrgId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SysUser>> getById(
            @PathVariable Long id,
            @RequestAttribute("userId") Long currentUserId) {
        SysUser target = userService.getById(id);
        DataScope scope = dataScopeService.getDataScope(currentUserId);
        if (!scope.isOrgVisible(target.getOrgId())) {
            throw new IllegalArgumentException("无权访问该用户数据");
        }
        return ResponseEntity.ok(ApiResponse.ok(target));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysUser>> create(
            @Valid @RequestBody CreateUserRequest req,
            @RequestAttribute("userId") Long userId) {
        SysUser user = SysUser.builder()
                .username(req.getUsername())
                .password(req.getPassword())
                .realName(req.getRealName())
                .role(req.getRole())
                .orgId(req.getOrgId())
                .status(req.getStatus())
                .build();
        SysUser created = userService.create(user);
        auditLogService.log(userId, "USER_CREATE", "sys_user", created.getId(),
                Map.of("username", created.getUsername(), "role", req.getRole() != null ? req.getRole() : 0, "org_id", req.getOrgId() != null ? req.getOrgId() : 0));
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysUser>> update(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest req,
            @RequestAttribute("userId") Long userId) {
        SysUser updates = new SysUser();
        updates.setRealName(req.getRealName());
        updates.setRole(req.getRole());
        updates.setOrgId(req.getOrgId());
        updates.setStatus(req.getStatus());
        SysUser updated = userService.update(id, updates);
        auditLogService.log(userId, "USER_UPDATE", "sys_user", id,
                Map.of("role", req.getRole() != null ? req.getRole() : 0,
                        "org_id", req.getOrgId() != null ? req.getOrgId() : 0,
                        "status", req.getStatus() != null ? req.getStatus() : 0));
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        SysUser target = userService.getById(id);
        auditLogService.log(userId, "USER_DELETE", "sys_user", id,
                Map.of("username", target.getUsername()));
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PutMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId) {
        String newPassword = body.get("new_password");
        if (newPassword == null || newPassword.isEmpty()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        SysUser target = userService.getById(id);
        auditLogService.log(userId, "USER_RESET_PASSWORD", "sys_user", id,
                Map.of("target_username", target.getUsername()));
        userService.resetPassword(id, newPassword);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank private String password;
        private String realName;
        private Byte role;
        private Long orgId;
        private Byte status;
    }

    @Data
    public static class UpdateUserRequest {
        private String realName;
        private Byte role;
        private Long orgId;
        private Byte status;
    }
}
