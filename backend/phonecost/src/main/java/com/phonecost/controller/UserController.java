package com.phonecost.controller;

import com.phonecost.domain.SysUser;
import com.phonecost.dto.ApiResponse;
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

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SysUser>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(userService.list()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SysUser>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysUser>> create(@Valid @RequestBody CreateUserRequest req) {
        SysUser user = SysUser.builder()
                .username(req.getUsername())
                .password(req.getPassword())
                .realName(req.getRealName())
                .role(req.getRole())
                .orgId(req.getOrgId())
                .status(req.getStatus())
                .build();
        return ResponseEntity.ok(ApiResponse.ok(userService.create(user)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysUser>> update(@PathVariable Long id,
                                                        @RequestBody UpdateUserRequest req) {
        SysUser updates = new SysUser();
        updates.setRealName(req.getRealName());
        updates.setRole(req.getRole());
        updates.setOrgId(req.getOrgId());
        updates.setStatus(req.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(userService.update(id, updates)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PutMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body) {
        String newPassword = body.get("new_password");
        if (newPassword == null || newPassword.isEmpty()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
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
