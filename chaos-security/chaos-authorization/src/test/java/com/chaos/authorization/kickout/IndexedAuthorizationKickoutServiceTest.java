package com.chaos.authorization.kickout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chaos.audit.AuditAction;
import com.chaos.audit.AuditEvent;
import com.chaos.audit.AuditEventPublisher;
import com.chaos.authorization.core.ChaosAuthorizationProperties;
import com.chaos.authorization.grant.ChaosAuthorizationGrantTypes;
import com.chaos.authorization.session.AuthorizationLoginContext;
import com.chaos.security.api.auth.LoginUser;
import com.chaos.security.api.token.JwtRevocationService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * {@link IndexedAuthorizationKickoutService} 单元测试。
 */
class IndexedAuthorizationKickoutServiceTest {

    private final LoginUser user = new LoginUser("1001", "alice", "tenant-a", Set.of("user"), Set.of());

    private final RegisteredClient client = RegisteredClient.withId("client-1")
            .clientId("chaos-client")
            .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
            .build();

    @Test
    void shouldSkipWhenDisabled() {
        OAuth2AuthorizationService authService = mock(OAuth2AuthorizationService.class);
        AuthorizationSessionRegistry registry = mock(AuthorizationSessionRegistry.class);
        ChaosAuthorizationProperties properties = new ChaosAuthorizationProperties();
        properties.getKickout().setEnabled(false);

        IndexedAuthorizationKickoutService service = new IndexedAuthorizationKickoutService(
                authService, registry, mock(JwtRevocationService.class), properties, event -> { });
        service.kickout(user, client, AuthorizationLoginContext.empty());
        service.record(user, client, mock(OAuth2Authorization.class), AuthorizationLoginContext.empty());

        verifyNoInteractions(authService, registry);
    }

    @Test
    void shouldKickoutAcrossScopesAndCleanStaleSessions() {
        OAuth2AuthorizationService authService = mock(OAuth2AuthorizationService.class);
        AuthorizationSessionRegistry registry = mock(AuthorizationSessionRegistry.class);
        JwtRevocationService revocationService = mock(JwtRevocationService.class);
        List<AuditEvent> events = new ArrayList<>();
        AuditEventPublisher publisher = events::add;

        ChaosAuthorizationProperties properties = new ChaosAuthorizationProperties();
        properties.getKickout().setEnabled(true);
        properties.getKickout().setScope(ChaosAuthorizationProperties.Scope.GLOBAL);

        AuthorizationSession active = new AuthorizationSession(
                "auth-1", "1001", "client-1", "jti-1", Instant.now().plusSeconds(120),
                "password", "dev-1", "10.0.0.1", "ua");
        AuthorizationSession stale = new AuthorizationSession(
                "stale-1", "1001", "client-1", "jti-2", Instant.now().plusSeconds(120),
                "password", "dev-2", "10.0.0.2", "ua");

        OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-1")
                .principalName("1001")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .build();

        when(registry.findByPrincipal("1001")).thenReturn(Set.of(active, stale));
        when(authService.findById("auth-1")).thenReturn(auth);
        when(authService.findById("stale-1")).thenReturn(null);

        IndexedAuthorizationKickoutService service = new IndexedAuthorizationKickoutService(
                authService, registry, revocationService, properties, publisher);
        service.kickout(user, client, null);
        service.record(user, client, auth, null);

        verify(authService).remove(auth);
        verify(registry).remove(auth);
        verify(registry).remove(stale);
        verify(registry).save(auth);
        verify(revocationService).revoke(eq("jti-2"), any(Duration.class));
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.action()).isEqualTo(AuditAction.AUTH_SESSION_KICKOUT));

        properties.getKickout().setScope(ChaosAuthorizationProperties.Scope.DEVICE);
        service.kickout(user, client, new AuthorizationLoginContext("password", "dev-1", "10.0.0.1", "ua"));
        verify(registry).findByPrincipalClientAndDevice("1001", "client-1", "dev-1");

        properties.getKickout().setScope(ChaosAuthorizationProperties.Scope.CLIENT);
        service.kickout(user, client, new AuthorizationLoginContext("password", "", "10.0.0.1", "ua"));
        verify(registry).findByPrincipalAndClient("1001", "client-1");
    }
}
