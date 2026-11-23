package com.chaos.security.redis.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chaos.audit.AuditAction;
import com.chaos.audit.AuditEvent;
import com.chaos.audit.AuditEventPublisher;
import com.chaos.authorization.core.ChaosAuthorizationProperties;
import com.chaos.authorization.session.AuthorizationLoginContext;
import com.chaos.security.api.auth.LoginUser;
import com.chaos.security.api.token.JwtRevocationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * {@link RedisAuthorizationKickoutService} 单元测试。
 */
class RedisAuthorizationKickoutServiceTest {

    private final LoginUser user = new LoginUser("1001", "alice", "tenant-a", Set.of("user"), Set.of());

    private final RegisteredClient client = RegisteredClient.withId("client-1")
            .clientId("chaos-client")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .build();

    @Test
    void shouldSkipWhenKickoutDisabled() {
        RedisOAuth2AuthorizationService authService = mock(RedisOAuth2AuthorizationService.class);
        ChaosAuthorizationProperties properties = new ChaosAuthorizationProperties();
        properties.getKickout().setEnabled(false);

        RedisAuthorizationKickoutService service = new RedisAuthorizationKickoutService(authService, properties);
        service.kickout(user, client, new AuthorizationLoginContext("password", "dev-1", "127.0.0.1", "ua"));

        verifyNoInteractions(authService);
    }

    @Test
    void shouldKickoutAcrossScopesAndCleanStaleIndices() {
        RedisOAuth2AuthorizationService authService = mock(RedisOAuth2AuthorizationService.class);
        JwtRevocationService revocationService = mock(JwtRevocationService.class);
        List<AuditEvent> events = new ArrayList<>();
        AuditEventPublisher auditPublisher = events::add;

        ChaosAuthorizationProperties properties = new ChaosAuthorizationProperties();
        properties.getKickout().setEnabled(true);
        properties.getKickout().setScope(ChaosAuthorizationProperties.Scope.GLOBAL);

        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-1")
                .principalName("1001")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "token-1",
                        Instant.now(),
                        Instant.now().plusSeconds(300)))
                .build();

        when(authService.findIdsByPrincipal("1001")).thenReturn(Set.of("auth-1", "stale-1"));
        when(authService.findById("auth-1")).thenReturn(authorization);
        when(authService.findById("stale-1")).thenReturn(null);

        RedisAuthorizationKickoutService service = new RedisAuthorizationKickoutService(
                authService, properties, revocationService, auditPublisher);
        service.kickout(user, client, null);

        verify(authService).remove(authorization);
        verify(authService).removePrincipalIndex("1001", null, "stale-1");
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.action()).isEqualTo(AuditAction.AUTH_SESSION_KICKOUT));

        properties.getKickout().setScope(ChaosAuthorizationProperties.Scope.DEVICE);
        when(authService.findIdsByPrincipalClientAndDevice("1001", "client-1", "dev-1")).thenReturn(Set.of());
        service.kickout(user, client, new AuthorizationLoginContext("password", "dev-1", "127.0.0.1", "ua"));
        verify(authService).findIdsByPrincipalClientAndDevice("1001", "client-1", "dev-1");
        verify(authService, never()).findIdsByPrincipalAndClient("1001", "client-1");

        properties.getKickout().setScope(ChaosAuthorizationProperties.Scope.CLIENT);
        when(authService.findIdsByPrincipalAndClient("1001", "client-1")).thenReturn(Set.of("stale-2"));
        when(authService.findById("stale-2")).thenReturn(null);
        service.kickout(user, client, new AuthorizationLoginContext("password", null, "127.0.0.1", "ua"));
        verify(authService).removePrincipalIndex("1001", "client-1", "stale-2");
    }
}
