package com.chaos.authorization.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.chaos.audit.AuditAction;
import com.chaos.audit.AuditEvent;
import com.chaos.authorization.grant.ChaosAuthorizationGrantTypes;
import com.chaos.authorization.kickout.AuthorizationSessionRegistry;
import com.chaos.security.api.token.JwtRevocationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * {@link ChaosTokenRevocationAuthenticationProvider} 单元测试。
 */
class ChaosTokenRevocationAuthenticationProviderTest {

    @Test
    void shouldRevokeTokenRemoveSessionAndPublishAudit() {
        RegisteredClient client = RegisteredClient.withId("client-1")
                .clientId("chaos-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("{noop}secret")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-1")
                .principalName("1001")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .accessToken(accessToken)
                .build();
        InMemoryOAuth2AuthorizationService authService = new InMemoryOAuth2AuthorizationService(authorization);
        AuthorizationSessionRegistry sessionRegistry = mock(AuthorizationSessionRegistry.class);
        JwtRevocationService revocationService = mock(JwtRevocationService.class);
        List<AuditEvent> events = new ArrayList<>();

        ChaosTokenRevocationAuthenticationProvider provider = new ChaosTokenRevocationAuthenticationProvider(
                authService, sessionRegistry, revocationService, events::add);
        assertThat(provider.supports(OAuth2TokenRevocationAuthenticationToken.class)).isTrue();

        OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "{noop}secret");
        OAuth2TokenRevocationAuthenticationToken request = new OAuth2TokenRevocationAuthenticationToken(
                "access-token-value", clientPrincipal, "access_token");

        Authentication result = provider.authenticate(request);
        assertThat(result.isAuthenticated()).isTrue();
        verify(sessionRegistry).remove(authorization);
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.action()).isEqualTo(AuditAction.AUTH_TOKEN_REVOKE));
    }
}
