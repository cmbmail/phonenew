package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import com.phonecost.domain.SysUser;
import com.phonecost.repository.SysUserRepository;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.LoginRateLimitService;
import com.phonecost.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuditLogService auditLogService;
    private final LoginRateLimitService rateLimitService;
    private final RedisTemplate<String, Object> redisTemplate;

    public AuthController(SysUserRepository userRepository, PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil, AuditLogService auditLogService,
                          LoginRateLimitService rateLimitService,
                          RedisTemplate<String, Object> redisTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.auditLogService = auditLogService;
        this.rateLimitService = rateLimitService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest req,
                                                                    HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);

        // Rate limit check
        LoginRateLimitService.RateLimitResult limitResult = rateLimitService.checkAllowed(req.getUsername(), clientIp);
        if (!limitResult.isAllowed()) {
            throw new IllegalArgumentException(limitResult.getReason());
        }

        var user = userRepository.findByUsernameAndDeletedAtIsNull(req.getUsername())
            .orElseThrow(() -> {
                rateLimitService.recordFailure(req.getUsername(), clientIp);
                return new IllegalArgumentException("用户名或密码错误");
            });

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            rateLimitService.recordFailure(req.getUsername(), clientIp);
            auditLogService.log(user.getId(), "AUTH_LOGIN_FAILED", "sys_user", user.getId(),
                    Map.of("username", req.getUsername()));
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!Byte.valueOf((byte)1).equals(user.getStatus())) {
            auditLogService.log(user.getId(), "AUTH_LOGIN_DISABLED", "sys_user", user.getId(),
                    Map.of("username", req.getUsername()));
            throw new IllegalArgumentException("账号已被停用");
        }

        rateLimitService.recordSuccess(req.getUsername(), clientIp);

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole(), user.getOrgId());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        auditLogService.log(user.getId(), "AUTH_LOGIN", "sys_user", user.getId(),
                Map.of("username", user.getUsername(), "role", user.getRole()));

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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@RequestAttribute("userId") Long userId) {
        var user = userRepository.findByIdAndDeletedAtIsNull(userId)
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
    public ResponseEntity<ApiResponse<Map<String, String>>> refresh(@Valid @RequestBody RefreshTokenRequest body) {
        String refreshToken = body.getRefreshToken();
        if (refreshToken == null || !jwtUtil.validateToken(refreshToken)) {
            throw new IllegalArgumentException("无效的refresh token");
        }
        // H-S04: Check if refresh token is revoked
        String revokeKey = "revoked_refresh:" + refreshToken.hashCode();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(revokeKey))) {
            throw new IllegalArgumentException("refresh token已撤销，请重新登录");
        }
        Long userId = jwtUtil.getUserId(refreshToken);
        var user = userRepository.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole(), user.getOrgId());
        // Rotate refresh token
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());
        // H-S04: Revoke old refresh token
        long remainingMs = jwtUtil.parseToken(refreshToken).getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisTemplate.opsForValue().set(revokeKey, "1", remainingMs, TimeUnit.MILLISECONDS);
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "access_token", newAccessToken,
                "refresh_token", newRefreshToken
        )));
    }

    /** H-S04: Logout — revoke refresh token */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestAttribute("userId") Long userId,
                                                     @RequestBody(required = false) Map<String, String> body) {
        if (body != null && body.containsKey("refresh_token")) {
            String refreshToken = body.get("refresh_token");
            if (refreshToken != null && jwtUtil.validateToken(refreshToken)) {
                String revokeKey = "revoked_refresh:" + refreshToken.hashCode();
                long remainingMs = jwtUtil.parseToken(refreshToken).getExpiration().getTime() - System.currentTimeMillis();
                if (remainingMs > 0) {
                    redisTemplate.opsForValue().set(revokeKey, "1", remainingMs, TimeUnit.MILLISECONDS);
                }
            }
        }
        auditLogService.log(userId, "AUTH_LOGOUT", "sys_user", userId, Map.of());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                             @RequestAttribute("userId") Long userId) {
        var user = userRepository.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }
        // Validate password complexity
        validatePasswordComplexity(req.getNewPassword());
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setMustChangePwd((byte) 0);
        userRepository.save(user);
        auditLogService.log(userId, "AUTH_CHANGE_PASSWORD", "sys_user", userId,
                Map.of("username", user.getUsername()));
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // === Helpers ===

    /** Extract client IP, considering X-Forwarded-For header
     *  H-S05: Only trust XFF from trusted proxy (nginx on localhost) */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String remoteAddr = request.getRemoteAddr();
        if (xff != null && !xff.isBlank()) {
            // Only trust XFF if the request comes from a trusted proxy (localhost / 127.0.0.1)
            if (remoteAddr.equals("127.0.0.1") || remoteAddr.equals("0:0:0:0:0:0:0:1") || remoteAddr.equals("::1")) {
                return xff.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    /** Validate password meets complexity requirements */
    private void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码长度至少8个字符");
        }
        if (password.length() > 128) {
            throw new IllegalArgumentException("密码长度不能超过128个字符");
        }
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(c -> "!@#$%^&*()_+-=[]{}|;:',.<>?/`~".indexOf(c) >= 0);
        int categories = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        if (categories < 3) {
            throw new IllegalArgumentException("密码必须包含大写字母、小写字母、数字、特殊字符中至少3种");
        }
    }

    @Data
    public static class LoginRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank private String oldPassword;
        @NotBlank
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{8,}$",
                message = "密码必须至少8位，包含大小写字母、数字和特殊字符")
        private String newPassword;
    }

    // M-12 fix: typed DTO for refresh token request
    @Data
    public static class RefreshTokenRequest {
        @NotBlank private String refreshToken;
    }
}
