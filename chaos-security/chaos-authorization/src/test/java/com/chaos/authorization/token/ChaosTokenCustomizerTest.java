package com.chaos.authorization.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.authorization.grant.ChaosAuthorizationGrantTypes;
import com.chaos.authorization.grant.LoginUserAuthenticationToken;
import com.chaos.authorization.session.AuthorizationLoginContext;
import com.chaos.authorization.session.AuthorizationSessionAttributes;
import com.chaos.security.api.auth.ChaosJwtClaims;
import com.chaos.security.api.auth.LoginUser;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsSet;

/**
 * {@link ChaosTokenCustomizer} 单元测试。
 */
class ChaosTokenCustomizerTest {

    private final ChaosTokenCustomizer customizer = new ChaosTokenCustomizer();

    private final RegisteredClient client = RegisteredClient.withId("client-1")
            .clientId("chaos-client")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
            .scope("read")
            .build();

    private final LoginUser user = new LoginUser(
            "1001", "alice", "tenant-a", Set.of("admin"), Set.of("order:create"));

    @Test
    void shouldPopulateJwtClaimsForAccessTokenAndSkipNonAccessToken() {
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-1")
                .principalName("1001")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .attribute(
                        AuthorizationSessionAttributes.LOGIN_CONTEXT,
                        new AuthorizationLoginContext("password", "dev-1", "10.0.0.1", "ua"))
                .build();

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder();
        JwtEncodingContext accessTokenContext = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256), claimsBuilder)
                .registeredClient(client)
                .principal(new LoginUserAuthenticationToken(user))
                .authorization(authorization)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .build();

        customizer.customize(accessTokenContext);
        JwtClaimsSet claims = claimsBuilder.build();

        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getSubject()).isEqualTo("1001");
        assertThat(claims.getClaimAsString(ChaosJwtClaims.CLIENT_ID)).isEqualTo("chaos-client");
        assertThat(claims.getClaimAsString(ChaosJwtClaims.GRANT_TYPE)).isEqualTo("password");
        assertThat(claims.getClaimAsString(ChaosJwtClaims.DEVICE_ID)).isEqualTo("dev-1");
        assertThat(claims.getClaimAsString(ChaosJwtClaims.TENANT_ID)).isEqualTo("tenant-a");
        assertThat(claims.getClaimAsString(ChaosJwtClaims.USERNAME)).isEqualTo("alice");
        assertThat(claims.getClaimAsStringList(ChaosJwtClaims.ROLES)).containsExactly("admin");
        assertThat(claims.getClaimAsStringList(ChaosJwtClaims.PERMISSIONS)).containsExactly("order:create");

        JwtClaimsSet.Builder refreshClaims = JwtClaimsSet.builder().subject("1001");
        JwtEncodingContext refreshContext = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256), refreshClaims)
                .registeredClient(client)
                .principal(new LoginUserAuthenticationToken(user))
                .tokenType(OAuth2TokenType.REFRESH_TOKEN)
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .build();
        customizer.customize(refreshContext);
        assertThat(refreshClaims.build().getClaims()).containsOnlyKeys("sub");
    }

    @Test
    void shouldFallbackToAuthorizationPrincipalAndPreviousAccessClaims() {
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "tok", Instant.now(), Instant.now().plusSeconds(60));
        OAuth2Authorization withTokenClaims = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-2")
                .principalName("1002")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .token(token, metadata -> metadata.put(
                        OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                        Map.of(
                                JwtClaimNames.SUB, "1002",
                                ChaosJwtClaims.USER_ID, "1002",
                                ChaosJwtClaims.USERNAME, "bob",
                                ChaosJwtClaims.PREFERRED_USERNAME, "bob",
                                ChaosJwtClaims.TENANT_ID, "tenant-b",
                                ChaosJwtClaims.ROLES, Set.of("user"),
                                ChaosJwtClaims.PERMISSIONS, Set.of("order:read"))))
                .build();

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder();
        JwtEncodingContext context = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256), claimsBuilder)
                .registeredClient(client)
                .principal(new org.springframework.security.authentication.TestingAuthenticationToken("1002", ""))
                .authorization(withTokenClaims)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .build();

        customizer.customize(context);
        assertThat(claimsBuilder.build().getClaimAsString(ChaosJwtClaims.TENANT_ID)).isEqualTo("tenant-b");

        OAuth2Authorization withPrincipal = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-3")
                .principalName("1001")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .attribute(Principal.class.getName(), new LoginUserAuthenticationToken(user))
                .attribute(
                        AuthorizationSessionAttributes.LOGIN_CONTEXT,
                        new AuthorizationLoginContext("password", "dev-2", "10.0.0.2", "ua"))
                .build();

        OAuth2TokenClaimsSet.Builder opaqueClaims = OAuth2TokenClaimsSet.builder();
        OAuth2TokenClaimsContext opaqueContext = OAuth2TokenClaimsContext.with(opaqueClaims)
                .registeredClient(client)
                .principal(new org.springframework.security.authentication.TestingAuthenticationToken("1001", ""))
                .authorization(withPrincipal)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .build();

        customizer.customizeOpaque(opaqueContext);
        OAuth2TokenClaimsSet builtOpaque = opaqueClaims.build();
        assertThat(builtOpaque.getClaimAsString(ChaosJwtClaims.TENANT_ID)).isEqualTo("tenant-a");
        assertThat(builtOpaque.getClaimAsString(ChaosJwtClaims.DEVICE_ID)).isEqualTo("dev-2");
    }
}
