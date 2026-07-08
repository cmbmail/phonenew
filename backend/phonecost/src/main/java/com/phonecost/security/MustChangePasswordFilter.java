package com.phonecost.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces password change for users with must_change_pwd=1.
 * Only allows access to auth endpoints (/auth/**) and health check.
 * All other API requests return 403 with MUST_CHANGE_PASSWORD code.
 *
 * M-35: Cache must_change_pwd status in memory (1 minute TTL) to avoid DB query on every request.
 */
@Component
@Order(2)
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MustChangePasswordFilter.class);
    private static final Set<String> ALLOWED_PREFIXES = Set.of("/auth/", "/health");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    private final MustChangePwdProvider provider;

    /** M-35: In-memory cache: userId -> (mustChangePwd, timestamp) */
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final boolean mustChangePwd;
        final long timestamp;

        CacheEntry(boolean mustChangePwd) {
            this.mustChangePwd = mustChangePwd;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    /**
     * Abstraction for checking must_change_pwd status, to keep filter testable
     */
    public interface MustChangePwdProvider {
        Boolean getMustChangePwd(Long userId);
    }

    public MustChangePasswordFilter(MustChangePwdProvider provider) {
        this.provider = provider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Long userId = (Long) request.getAttribute("userId");

        // Only check authenticated requests
        if (userId != null) {
            String path = request.getServletPath();

            // Allow auth and health endpoints unconditionally
            boolean isAllowed = ALLOWED_PREFIXES.stream().anyMatch(path::startsWith);

            if (!isAllowed) {
                boolean mustChange = checkMustChangePwd(userId);
                if (mustChange) {
                    log.debug("User {} must change password, blocking access to {}", userId, path);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    Map<String, Object> body = Map.of(
                        "code", 403,
                        "message", "请先修改初始密码",
                        "data", "MUST_CHANGE_PASSWORD"
                    );
                    response.getWriter().write(objectMapper.writeValueAsString(body));
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * M-35: Check must_change_pwd with 1-minute cache to avoid DB query per request.
     */
    private boolean checkMustChangePwd(Long userId) {
        CacheEntry entry = cache.get(userId);
        if (entry != null && !entry.isExpired()) {
            return entry.mustChangePwd;
        }
        // Cache miss or expired — check DB
        Boolean mustChange = provider.getMustChangePwd(userId);
        boolean result = mustChange != null && mustChange;
        cache.put(userId, new CacheEntry(result));
        // Opportunistic cleanup of expired entries
        if (cache.size() > 1000) {
            cache.entrySet().removeIf(e -> e.getValue().isExpired());
        }
        return result;
    }

    /**
     * Invalidate cache entry for a user (called after password change)
     */
    public void invalidateCache(Long userId) {
        cache.remove(userId);
    }
}
