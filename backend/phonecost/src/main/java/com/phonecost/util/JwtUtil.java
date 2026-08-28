package com.phonecost.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String secret;

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            log.error("FATAL: jwt.secret is empty or not configured. Set JWT_SECRET environment variable.");
            throw new IllegalStateException("jwt.secret must be configured via JWT_SECRET environment variable");
        }
        if (secret.length() < 32) {
            log.warn("WARNING: jwt.secret is shorter than 32 characters, which may be insecure for HMAC-SHA256.");
        }
        log.info("JWT secret validated successfully (length={})", secret.length());
    }

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long userId, String username, Byte role, Long orgId) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
            .claim("role", role)
            .claim("orgId", orgId != null ? orgId : 0)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
            .signWith(getSigningKey())
            .compact();
    }

    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiration))
            .signWith(getSigningKey())
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public Byte getRole(String token) {
        return parseToken(token).get("role", Byte.class);
    }

    public Long getOrgId(String token) {
        return parseToken(token).get("orgId", Long.class);
    }

    public String mapRoleToName(Byte role) {
        return switch (role.intValue()) {
            case 1 -> "ROLE_ADMIN";
            case 2 -> "ROLE_BRANCH";
            case 3 -> "ROLE_DEPARTMENT";
            case 4 -> "ROLE_FINANCE";
            default -> "ROLE_USER";
        };
    }
}
