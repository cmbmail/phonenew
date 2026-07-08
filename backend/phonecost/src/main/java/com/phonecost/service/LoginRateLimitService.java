package com.phonecost.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录速率限制服务
 * - 同一用户名：5次失败后锁定15分钟
 * - 同一IP：20次失败后锁定15分钟
 * - M-24: 过期条目自动清理，防止内存泄漏
 */
@Slf4j
@Service
public class LoginRateLimitService {

    private static final int MAX_USERNAME_ATTEMPTS = 5;
    private static final int MAX_IP_ATTEMPTS = 20;
    private static final int LOCKOUT_MINUTES = 15;
    /** M-24: Non-locked entries expire after this many minutes of inactivity */
    private static final int STALE_ENTRY_EXPIRY_MINUTES = 60;

    private final Map<String, AttemptRecord> usernameAttempts = new ConcurrentHashMap<>();
    private final Map<String, AttemptRecord> ipAttempts = new ConcurrentHashMap<>();

    public static class RateLimitResult {
        private final boolean allowed;
        private final String reason;
        private final long remainingSeconds;

        public RateLimitResult(boolean allowed, String reason, long remainingSeconds) {
            this.allowed = allowed;
            this.reason = reason;
            this.remainingSeconds = remainingSeconds;
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
        public long getRemainingSeconds() { return remainingSeconds; }
    }

    private static class AttemptRecord {
        int count;
        LocalDateTime lockedUntil;
        LocalDateTime lastAccess;

        AttemptRecord() {
            this.count = 0;
            this.lockedUntil = null;
            this.lastAccess = LocalDateTime.now();
        }
    }

    /**
     * Check if login is allowed for the given username + IP
     */
    public RateLimitResult checkAllowed(String username, String clientIp) {
        cleanup();

        // Check username lockout
        AttemptRecord usernameRecord = usernameAttempts.get(username);
        if (usernameRecord != null && usernameRecord.lockedUntil != null
                && usernameRecord.lockedUntil.isAfter(LocalDateTime.now())) {
            long remaining = java.time.Duration.between(LocalDateTime.now(), usernameRecord.lockedUntil).getSeconds();
            return new RateLimitResult(false, "账号已锁定，请" + (remaining / 60 + 1) + "分钟后再试", remaining);
        }

        // Check IP lockout
        AttemptRecord ipRecord = ipAttempts.get(clientIp);
        if (ipRecord != null && ipRecord.lockedUntil != null
                && ipRecord.lockedUntil.isAfter(LocalDateTime.now())) {
            long remaining = java.time.Duration.between(LocalDateTime.now(), ipRecord.lockedUntil).getSeconds();
            return new RateLimitResult(false, "登录尝试过于频繁，请" + (remaining / 60 + 1) + "分钟后再试", remaining);
        }

        return new RateLimitResult(true, null, 0);
    }

    /**
     * Record a failed login attempt
     */
    public void recordFailure(String username, String clientIp) {
        // Username-based track
        usernameAttempts.computeIfAbsent(username, k -> new AttemptRecord());
        AttemptRecord usernameRecord = usernameAttempts.get(username);
        usernameRecord.count++;
        usernameRecord.lastAccess = LocalDateTime.now();
        if (usernameRecord.count >= MAX_USERNAME_ATTEMPTS) {
            usernameRecord.lockedUntil = LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES);
            log.warn("Username {} locked out for {} minutes after {} failed attempts", username, LOCKOUT_MINUTES, MAX_USERNAME_ATTEMPTS);
        }

        // IP-based track
        ipAttempts.computeIfAbsent(clientIp, k -> new AttemptRecord());
        AttemptRecord ipRecord = ipAttempts.get(clientIp);
        ipRecord.count++;
        ipRecord.lastAccess = LocalDateTime.now();
        if (ipRecord.count >= MAX_IP_ATTEMPTS) {
            ipRecord.lockedUntil = LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES);
            log.warn("IP {} locked out for {} minutes after {} failed attempts", clientIp, LOCKOUT_MINUTES, MAX_IP_ATTEMPTS);
        }
    }

    /**
     * Record a successful login — clear counters
     */
    public void recordSuccess(String username, String clientIp) {
        usernameAttempts.remove(username);
        // Don't remove IP record — let it expire naturally
    }

    /**
     * M-24: Clean up expired and stale entries to prevent memory leak.
     * - Remove locked entries whose lockout has expired
     * - Remove non-locked entries that haven't been accessed recently
     */
    private void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minusMinutes(STALE_ENTRY_EXPIRY_MINUTES);

        usernameAttempts.entrySet().removeIf(e -> {
            AttemptRecord r = e.getValue();
            // Remove if lockout has expired
            if (r.lockedUntil != null && r.lockedUntil.isBefore(now)) {
                return true;
            }
            // Remove non-locked stale entries (no recent activity)
            if (r.lockedUntil == null && r.lastAccess != null && r.lastAccess.isBefore(staleThreshold)) {
                return true;
            }
            return false;
        });

        ipAttempts.entrySet().removeIf(e -> {
            AttemptRecord r = e.getValue();
            if (r.lockedUntil != null && r.lockedUntil.isBefore(now)) {
                return true;
            }
            if (r.lockedUntil == null && r.lastAccess != null && r.lastAccess.isBefore(staleThreshold)) {
                return true;
            }
            return false;
        });
    }
}
