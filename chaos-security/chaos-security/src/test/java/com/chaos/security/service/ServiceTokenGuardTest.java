package com.chaos.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.security.oauth2.LoginUserJwtAuthenticationConverter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 服务 JWT 的身份、作用域与拒绝边界。 */
class ServiceTokenGuardTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsCollectionScopes() {
        authenticate(claims());
        assertThat(ServiceTokenGuard.require("inventory:read")).isEqualTo("worker");
    }

    @Test
    void acceptsSpaceSeparatedScopesThroughFrameworkConverter() {
        var claims = claims();
        claims.put("scope", "inventory:write inventory:read");
        SecurityContextHolder.getContext().setAuthentication(
                new LoginUserJwtAuthenticationConverter().convert(jwt(claims)));
        assertThat(ServiceTokenGuard.require("inventory:read")).isEqualTo("worker");
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsInvalidServiceIdentityOrScopes(Map<String, Object> claims) {
        authenticate(claims);
        assertThatThrownBy(() -> ServiceTokenGuard.require("inventory:read"))
                .isInstanceOf(AccessDeniedException.class);
    }

    static Stream<Map<String, Object>> invalidClaims() {
        return Stream.of(
                changed("grantType", null), changed("grantType", "password"),
                changed("grantType", "refresh_token"), changed("grantType", 123),
                changed("clientId", null), changed("clientId", " "), changed("clientId", 123),
                changed("scope", null), changed("scope", List.of()),
                changed("scope", "inventory:write"), changed("scope", "inventory:*"),
                changed("scope", "inventory:read,inventory:write"), changed("scope", 123),
                changed("scope", List.of("inventory:read", 123)),
                changed("scope", List.of("inventory:read", "bad scope")));
    }

    @Test
    void rejectsUnauthenticatedJwt() {
        authenticate(claims());
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertThatThrownBy(() -> ServiceTokenGuard.require("inventory:read"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsMissingOrNonJwtAuthentication() {
        assertThatThrownBy(() -> ServiceTokenGuard.require("inventory:read"))
                .isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("worker", null, List.of()));
        assertThatThrownBy(() -> ServiceTokenGuard.require("inventory:read"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "inventory:read inventory:write", "bad\tscope", "bad\"scope", "bad\\scope"})
    void rejectsInvalidRequiredScope(String scope) {
        authenticate(claims());
        assertThatThrownBy(() -> ServiceTokenGuard.require(scope))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> claims() {
        return new HashMap<>(Map.of("grantType", "client_credentials", "clientId", "worker",
                "scope", List.of("inventory:read", "inventory:write")));
    }

    private static Map<String, Object> changed(String name, Object value) {
        var claims = claims();
        if (value == null) {
            claims.remove(name);
        } else {
            claims.put(name, value);
        }
        return claims;
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject("worker").claims(values -> values.putAll(claims)).build();
    }

    private static void authenticate(Map<String, Object> claims) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(claims), List.of()));
    }
}
