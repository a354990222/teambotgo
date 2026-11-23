package com.chaos.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.security.api.auth.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

/**
 * {@link ServiceDelegationFilter} 单元测试。
 */
class ServiceDelegationFilterTest {

    private final ServiceDelegationFilter filter = new ServiceDelegationFilter(
            "internal.delegate",
            Map.of("gateway-client", new ServiceDelegationFilter.Caller("gateway", List.of("/internal/orders"))),
            "X-Delegated-Tenant",
            "X-Delegated-User",
            16);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRejectMissingOrUnscopedAuthenticationWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/internal/orders"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        BearerTokenAuthentication unscoped = bearerAuth("gateway-client", List.of("other.scope"));
        SecurityContextHolder.getContext().setAuthentication(unscoped);
        MockHttpServletResponse unscopedResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/internal/orders"), unscopedResponse, new MockFilterChain());
        assertThat(unscopedResponse.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void shouldRejectUntrustedCallerBadHeadersDisallowedPathAndLargeBody() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(bearerAuth("unknown-client", List.of("internal.delegate")));
        MockHttpServletResponse unknownCallerResp = new MockHttpServletResponse();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/orders");
        req.addHeader("X-Delegated-Tenant", "tenant-a");
        req.addHeader("X-Delegated-User", "u-1");
        filter.doFilter(req, unknownCallerResp, new MockFilterChain());
        assertThat(unknownCallerResp.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);

        SecurityContextHolder.getContext().setAuthentication(bearerAuth("gateway-client", List.of("internal.delegate")));
        MockHttpServletRequest wrongPath = new MockHttpServletRequest("GET", "/internal/admin");
        wrongPath.addHeader("X-Delegated-Tenant", "tenant-a");
        wrongPath.addHeader("X-Delegated-User", "u-1");
        MockHttpServletResponse wrongPathResp = new MockHttpServletResponse();
        filter.doFilter(wrongPath, wrongPathResp, new MockFilterChain());
        assertThat(wrongPathResp.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);

        MockHttpServletRequest largeBody = new MockHttpServletRequest("POST", "/internal/orders/create");
        largeBody.addHeader("X-Delegated-Tenant", "tenant-a");
        largeBody.addHeader("X-Delegated-User", "u-1");
        largeBody.setContent("01234567890123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse largeResp = new MockHttpServletResponse();
        filter.doFilter(largeBody, largeResp, new MockFilterChain());
        assertThat(largeResp.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    @Test
    void shouldDelegateLoginUserAndRestorePreviousAuthentication() throws Exception {
        BearerTokenAuthentication original = bearerAuth("gateway-client", List.of("internal.delegate"));
        SecurityContextHolder.getContext().setAuthentication(original);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/orders/create");
        request.addHeader("X-Delegated-Tenant", "tenant-a");
        request.addHeader("X-Delegated-User", "1001");
        request.setContent("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Authentication> insideChain = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) ->
                insideChain.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(insideChain.get()).isNotNull();
        assertThat(insideChain.get().getPrincipal()).isInstanceOfSatisfying(LoginUser.class, user -> {
            assertThat(user.userId()).isEqualTo("1001");
            assertThat(user.tenantId()).isEqualTo("tenant-a");
        });
        assertThat(insideChain.get().getDetails()).isEqualTo("gateway");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(original);

        assertThatThrownBy(() -> new ServiceDelegationFilter.Caller(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BearerTokenAuthentication bearerAuth(String clientId, List<String> scopes) {
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "svc-token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                SetOfScopes(scopes));
        OAuth2AuthenticatedPrincipal principal = new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
                clientId, Map.of("client_id", clientId), List.of());
        return new BearerTokenAuthentication(principal, token, List.of());
    }

    private static java.util.Set<String> SetOfScopes(List<String> scopes) {
        return java.util.Set.copyOf(scopes);
    }
}
