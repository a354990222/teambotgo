package com.chaos.authorization.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chaos.audit.AuditAction;
import com.chaos.audit.AuditEvent;
import com.chaos.authorization.kickout.AuthorizationKickoutService;
import com.chaos.authorization.session.AuthorizationLoginContext;
import com.chaos.security.api.auth.ChaosJwtClaims;
import com.chaos.security.api.auth.LoginUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

class ChaosGrantAuthenticationProviderTest {

    @AfterEach
    void resetContext() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void shouldUseOriginalAuthenticationFailureReasonForAudit() {
        BadCredentialsException cause = new BadCredentialsException("密码错误");
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_GRANT,
                        "username or password is invalid",
                        null
                ),
                cause
        );

        assertThat(ChaosGrantAuthenticationProvider.loginFailureReason(exception))
                .isEqualTo("密码错误");
    }

    @Test
    void shouldKeepDirectAuthenticationFailureReasonForAudit() {
        BadCredentialsException exception = new BadCredentialsException("客户端不允许使用密码登录");

        assertThat(ChaosGrantAuthenticationProvider.loginFailureReason(exception))
                .isEqualTo("客户端不允许使用密码登录");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAuthenticateGrantIssueTokensRecordKickoutAndAudit() {
        LoginUser loginUser = new LoginUser("1001", "alice", "tenant-a", Set.of("admin"), Set.of("order:create"));
        ChaosGrantAuthenticationHandler handler = new ChaosGrantAuthenticationHandler() {
            @Override
            public AuthorizationGrantType grantType() {
                return ChaosAuthorizationGrantTypes.PASSWORD;
            }

            @Override
            public LoginUser authenticate(Map<String, Object> parameters) {
                if ("bad".equals(parameters.get("password"))) {
                    throw new BadCredentialsException("密码错误");
                }
                return loginUser;
            }
        };

        InMemoryOAuth2AuthorizationService authService = new InMemoryOAuth2AuthorizationService();
        OAuth2TokenGenerator<OAuth2Token> tokenGenerator = mock(OAuth2TokenGenerator.class);
        when(tokenGenerator.generate(any(OAuth2TokenContext.class))).thenAnswer(invocation -> {
            OAuth2TokenContext ctx = invocation.getArgument(0);
            if (OAuth2TokenType.REFRESH_TOKEN.equals(ctx.getTokenType())) {
                return new OAuth2RefreshToken("refresh-1", Instant.now(), Instant.now().plusSeconds(3600));
            }
            return new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER, "access-1", Instant.now(), Instant.now().plusSeconds(300));
        });
        AuthorizationKickoutService kickoutService = mock(AuthorizationKickoutService.class);
        List<AuditEvent> events = new ArrayList<>();

        ChaosGrantAuthenticationProvider provider = new ChaosGrantAuthenticationProvider(
                List.of(handler), authService, tokenGenerator, kickoutService, events::add);
        assertThat(provider.supports(ChaosGrantAuthenticationToken.class)).isTrue();

        RegisteredClient client = RegisteredClient.withId("client-1")
                .clientId("chaos-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("{noop}secret")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("read")
                .build();
        OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "{noop}secret");

        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return "https://auth.example.com";
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return AuthorizationServerSettings.builder().build();
            }
        });

        ChaosGrantAuthenticationToken request = new ChaosGrantAuthenticationToken(
                ChaosAuthorizationGrantTypes.PASSWORD,
                clientPrincipal,
                Map.of("username", "alice", "password", "good", "scope", "read", "tenant_id", "tenant-a"));

        OAuth2AccessTokenAuthenticationToken response = (OAuth2AccessTokenAuthenticationToken) provider.authenticate(request);
        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("access-1");
        assertThat(response.getRefreshToken()).isNotNull();
        assertThat(response.getAdditionalParameters())
                .containsEntry(ChaosJwtClaims.USER_ID, "1001")
                .containsEntry(ChaosJwtClaims.TENANT_ID, "tenant-a");
        verify(kickoutService).kickout(any(), any(), any(AuthorizationLoginContext.class));
        verify(kickoutService).record(any(), any(), any(), any(AuthorizationLoginContext.class));
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.action()).isEqualTo(AuditAction.AUTH_LOGIN_SUCCESS));

        ChaosGrantAuthenticationToken failedRequest = new ChaosGrantAuthenticationToken(
                ChaosAuthorizationGrantTypes.PASSWORD,
                clientPrincipal,
                Map.of("username", "alice", "password", "bad", "tenant_id", "tenant-a"));
        assertThatThrownBy(() -> provider.authenticate(failedRequest))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).action()).isEqualTo(AuditAction.AUTH_LOGIN_FAILURE);
    }
}
