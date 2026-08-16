package com.example.fullstack.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTests {
    private final SecurityConfig security = new SecurityConfig();

    @Test
    void configuredOidcRoleBecomesAdminAuthorityAndUsesConfiguredPrincipal() {
        var converter = security.jwtAuthenticationConverter(
                "groups", "tableflow-operator", "preferred_username");
        var authentication = converter.convert(jwt(
                List.of("tableflow-operator", "other"), "operator-1"));

        assertEquals("operator-1", authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void unrelatedOidcRoleDoesNotReceiveAdminAuthority() {
        var converter = security.jwtAuthenticationConverter(
                "groups", "tableflow-operator", "preferred_username");
        var authentication = converter.convert(jwt(List.of("viewer"), "viewer-1"));

        assertFalse(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")));
    }

    private static Jwt jwt(List<String> roles, String username) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("groups", roles)
                .claim("preferred_username", username)
                .issuedAt(Instant.parse("2026-08-16T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-16T01:00:00Z"))
                .build();
    }
}
