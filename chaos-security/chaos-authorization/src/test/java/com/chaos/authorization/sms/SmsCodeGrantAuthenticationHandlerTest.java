package com.chaos.authorization.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.authorization.grant.ChaosAuthorizationGrantTypes;
import com.chaos.security.api.auth.LoginUser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

/**
 * {@link SmsCodeGrantAuthenticationHandler} 单元测试。
 */
class SmsCodeGrantAuthenticationHandlerTest {

    private final LoginUser expectedUser = new LoginUser("1001", "alice", "tenant-a", Set.of("user"), Set.of());

    private final SmsCodeGrantAuthenticationHandler handler = new SmsCodeGrantAuthenticationHandler(
            new com.chaos.authorization.core.ChaosAuthorizationUserService() {
                @Override
                public LoginUser authenticateByUsername(String username, String password) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public LoginUser loadByMobile(String mobile) {
                    return expectedUser;
                }
            },
            (mobile, code) -> "13800138000".equals(mobile) && "123456".equals(code));

    @Test
    void shouldValidateParametersVerifyCodeAndLoadUser() {
        assertThat(handler.grantType()).isEqualTo(ChaosAuthorizationGrantTypes.SMS_CODE);
        assertThat(handler.authenticate(Map.of("mobile", "13800138000", "code", "123456")))
                .isEqualTo(expectedUser);

        assertThatThrownBy(() -> handler.authenticate(Map.of("mobile", "13800138000", "code", "000000")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));

        assertThatThrownBy(() -> handler.authenticate(Map.of("mobile", " ")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST));
    }
}
