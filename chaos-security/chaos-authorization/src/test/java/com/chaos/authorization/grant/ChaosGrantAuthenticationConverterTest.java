package com.chaos.authorization.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.authorization.session.AuthorizationSessionParameterNames;
import com.chaos.security.api.auth.LoginUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * {@link ChaosGrantAuthenticationConverter} 单元测试。
 */
class ChaosGrantAuthenticationConverterTest {

    private final ChaosGrantAuthenticationConverter converter = new ChaosGrantAuthenticationConverter(List.of(
            new ChaosGrantAuthenticationHandler() {
                @Override
                public AuthorizationGrantType grantType() {
                    return ChaosAuthorizationGrantTypes.PASSWORD;
                }

                @Override
                public LoginUser authenticate(Map<String, Object> parameters) {
                    return new LoginUser("1", "u", "t", Set.of(), Set.of());
                }
            }));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnNullForUnsupportedGrantTypeAndRejectMissingClientPrincipal() {
        MockHttpServletRequest unknown = new MockHttpServletRequest();
        unknown.setParameter(OAuth2ParameterNames.GRANT_TYPE, "client_credentials");
        assertThat(converter.convert(unknown)).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE, "password");
        assertThatThrownBy(() -> converter.convert(request))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void shouldConvertParametersAndContextHeaders() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("client", "secret"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.GRANT_TYPE, "password");
        request.setParameter("username", "alice");
        request.setParameter("scope", "read", "write");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.2");
        request.addHeader("User-Agent", "ChaosClient/1.0");
        request.addHeader("X-Device-Id", "dev-01");

        ChaosGrantAuthenticationToken token = (ChaosGrantAuthenticationToken) converter.convert(request);
        assertThat(token).isNotNull();
        assertThat(token.getGrantType()).isEqualTo(ChaosAuthorizationGrantTypes.PASSWORD);
        assertThat(token.getAdditionalParameters())
                .containsEntry("username", "alice")
                .containsEntry("scope", List.of("read", "write"))
                .containsEntry("ip", "198.51.100.1")
                .containsEntry("user_agent", "ChaosClient/1.0")
                .containsEntry(AuthorizationSessionParameterNames.DEVICE_ID, "dev-01");
    }
}
