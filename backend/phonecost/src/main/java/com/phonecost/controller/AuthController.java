package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import com.phonecost.domain.SysUser;
import com.phonecost.repository.SysUserRepository;
import com.phonecost.util.JwtUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(SysUserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest req) {
        var user = userRepository.findByUsernameAndDeletedAtIsNull(req.getUsername())
            .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (user.getStatus() != 1) {
            throw new IllegalArgumentException("账号已被停用");
        }

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "access_token", accessToken,
            "refresh_token", refreshToken,
            "must_change_pwd", user.getMustChangePwd(),
            "role", user.getRole(),
            "username", user.getUsername(),
            "real_name", user.getRealName(),
            "org_id", user.getOrgId() != null ? user.getOrgId() : 0
        )));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@RequestAttribute("userId") Long userId) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "id", user.getId(),
            "username", user.getUsername(),
            "real_name", user.getRealName(),
            "role", user.getRole(),
            "org_id", user.getOrgId() != null ? user.getOrgId() : 0,
            "status", user.getStatus(),
            "must_change_pwd", user.getMustChangePwd()
        )));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, String>>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refresh_token");
        if (refreshToken == null || !jwtUtil.validateToken(refreshToken)) {
            throw new IllegalArgumentException("无效的refresh token");
        }
        Long userId = jwtUtil.getUserId(refreshToken);
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("access_token", newAccessToken)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                             @RequestAttribute("userId") Long userId) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setMustChangePwd((byte) 0);
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Data
    public static class LoginRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank private String oldPassword;
        @NotBlank private String newPassword;
    }
}
