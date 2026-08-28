package com.phonecost.controller;

import com.phonecost.domain.SysOrganization;
import com.phonecost.domain.SysUser;
import com.phonecost.dto.ApiResponse;
import com.phonecost.dto.UserResponse;
import com.phonecost.repository.SysOrganizationRepository;
import com.phonecost.repository.SysUserRepository;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final SysUserRepository userRepository;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) Long org_id,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String realName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // M-04 fix: limit page size
        size = Math.min(size, 200);
        DataScope scope = dataScopeService.getDataScope(userId);

        // Normalize search params
        String usernameParam = (username != null && !username.isEmpty()) ? username : null;
        String realNameParam = (realName != null && !realName.isEmpty()) ? realName : null;

        // Determine org IDs to filter by
        List<Long> orgIdFilter = null;
        if (org_id != null) {
            if (!scope.isOrgVisible(org_id)) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of("content", List.of(), "total", 0, "page", page, "size", size)));
            }
            SysOrganization targetOrg = orgRepository.findByIdAndDeletedAtIsNull(org_id).orElse(null);
            if (targetOrg == null) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of("content", List.of(), "total", 0, "page", page, "size", size)));
            }
            List<SysOrganization> descendants = orgRepository.findByPathStartingWithAndDeletedAtIsNull(targetOrg.getPath());
            orgIdFilter = descendants.stream().map(SysOrganization::getId).collect(Collectors.toList());
        } else if (!scope.isAllScope()) {
            var visibleIds = scope.getVisibleOrgIds();
            if (visibleIds == null || visibleIds.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of("content", List.of(), "total", 0, "page", page, "size", size)));
            }
            orgIdFilter = visibleIds;
        }

        // Use dynamic search query
        Page<SysUser> paged = userRepository.searchPaged(
                usernameParam, realNameParam, orgIdFilter, PageRequest.of(page, size));
        List<UserResponse> userResponses = paged.getContent().stream()
                .map(UserResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "content", userResponses,
                "total", paged.getTotalElements(),
                "page", paged.getNumber(),
                "size", paged.getSize())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> getById(
            @PathVariable Long id,
            @RequestAttribute("userId") Long currentUserId) {
        SysUser target = userService.getById(id);
        DataScope scope = dataScopeService.getDataScope(currentUserId);
        if (!scope.isOrgVisible(target.getOrgId())) {
            throw new IllegalArgumentException("无权访问该用户数据");
        }
        return ResponseEntity.ok(ApiResponse.ok(UserResponse.from(target)));
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
            @Valid @RequestBody UpdateUserRequest req,
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
            @Valid @RequestBody ResetPasswordRequest req,
            @RequestAttribute("userId") Long userId) {
        SysUser target = userService.getById(id);
        auditLogService.log(userId, "USER_RESET_PASSWORD", "sys_user", id,
                Map.of("target_username", target.getUsername()));
        userService.resetPassword(id, req.getNewPassword());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{8,}$",
                message = "密码必须至少8位，包含大小写字母、数字和特殊字符")
        private String password;
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

    @Data
    public static class ResetPasswordRequest {
        @NotBlank
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{8,}$",
                message = "密码必须至少8位，包含大小写字母、数字和特殊字符")
        private String newPassword;
    }
}
