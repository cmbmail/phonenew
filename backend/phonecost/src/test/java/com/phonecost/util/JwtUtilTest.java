package com.phonecost.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // Use a test secret that's at least 256 bits (32 chars)
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-for-jwt-util-unit-testing-2026!!");
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiration", 3600000L); // 1 hour
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpiration", 86400000L); // 24 hours
    }

    @Nested
    @DisplayName("generateAccessToken / parseToken")
    class AccessTokenTests {

        @Test
        @DisplayName("Generate and parse access token")
        void generateAndParse() {
            String token = jwtUtil.generateAccessToken(1L, "admin", (byte) 1, 5L);

            Claims claims = jwtUtil.parseToken(token);
            assertEquals("1", claims.getSubject());
            assertEquals("admin", claims.get("username", String.class));
            assertEquals((byte) 1, claims.get("role", Byte.class));
            assertEquals(5L, claims.get("orgId", Long.class));
        }

        @Test
        @DisplayName("Token with null orgId defaults to 0")
        void nullOrgId() {
            String token = jwtUtil.generateAccessToken(1L, "admin", (byte) 1, null);
            Claims claims = jwtUtil.parseToken(token);
            assertEquals(0L, claims.get("orgId", Long.class));
        }

        @Test
        @DisplayName("getUserId extracts user ID from token")
        void getUserId() {
            String token = jwtUtil.generateAccessToken(42L, "testuser", (byte) 2, 10L);
            assertEquals(42L, jwtUtil.getUserId(token));
        }

        @Test
        @DisplayName("getUsername extracts username from token")
        void getUsername() {
            String token = jwtUtil.generateAccessToken(1L, "bj_admin", (byte) 2, 6L);
            assertEquals("bj_admin", jwtUtil.getUsername(token));
        }

        @Test
        @DisplayName("getRole extracts role from token")
        void getRole() {
            String token = jwtUtil.generateAccessToken(1L, "user", (byte) 4, 5L);
            assertEquals((byte) 4, jwtUtil.getRole(token));
        }

        @Test
        @DisplayName("getOrgId extracts orgId from token")
        void getOrgId() {
            String token = jwtUtil.generateAccessToken(1L, "user", (byte) 2, 42L);
            assertEquals(42L, jwtUtil.getOrgId(token));
        }
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateTests {

        @Test
        @DisplayName("Valid token returns true")
        void validToken() {
            String token = jwtUtil.generateAccessToken(1L, "admin", (byte) 1, 5L);
            assertTrue(jwtUtil.validateToken(token));
        }

        @Test
        @DisplayName("Invalid token returns false")
        void invalidToken() {
            assertFalse(jwtUtil.validateToken("invalid.jwt.token"));
        }

        @Test
        @DisplayName("Empty string returns false")
        void emptyToken() {
            assertFalse(jwtUtil.validateToken(""));
        }

        @Test
        @DisplayName("Tampered token returns false")
        void tamperedToken() {
            String token = jwtUtil.generateAccessToken(1L, "admin", (byte) 1, 5L);
            String tampered = token.substring(0, token.length() - 5) + "XXXXX";
            assertFalse(jwtUtil.validateToken(tampered));
        }
    }

    @Nested
    @DisplayName("generateRefreshToken")
    class RefreshTokenTests {

        @Test
        @DisplayName("Generate and parse refresh token")
        void generateAndParse() {
            String token = jwtUtil.generateRefreshToken(1L);
            Claims claims = jwtUtil.parseToken(token);
            assertEquals("1", claims.getSubject());
        }
    }

    @Nested
    @DisplayName("mapRoleToName")
    class RoleMappingTests {

        @Test
        @DisplayName("Maps all roles correctly")
        void roleMapping() {
            assertEquals("ROLE_ADMIN", jwtUtil.mapRoleToName((byte) 1));
            assertEquals("ROLE_BRANCH", jwtUtil.mapRoleToName((byte) 2));
            assertEquals("ROLE_DEPARTMENT", jwtUtil.mapRoleToName((byte) 3));
            assertEquals("ROLE_FINANCE", jwtUtil.mapRoleToName((byte) 4));
        }

        @Test
        @DisplayName("Unknown role maps to ROLE_USER")
        void unknownRole() {
            assertEquals("ROLE_USER", jwtUtil.mapRoleToName((byte) 99));
        }
    }
}
