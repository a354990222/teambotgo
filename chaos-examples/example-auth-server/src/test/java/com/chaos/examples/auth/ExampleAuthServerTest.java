package com.chaos.examples.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.authorization.core.ChaosAuthorizationUserService;
import com.chaos.authorization.sms.SmsCodeVerifier;
import com.chaos.security.api.auth.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * {@code example-auth-server} 身份配置与核心端点测试。
 */
@SpringBootTest(
        classes = ExampleAuthServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.health.redis.enabled=false")
class ExampleAuthServerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldAuthenticateDemoUsersAndVerifySmsCodes() {
        ExampleAuthConfiguration config = new ExampleAuthConfiguration();
        ChaosAuthorizationUserService userService = config.chaosAuthorizationUserService();
        SmsCodeVerifier smsVerifier = config.smsCodeVerifier();

        LoginUser admin = userService.authenticateByUsername("admin", "123456");
        assertThat(admin.userId()).isEqualTo("10001");
        assertThat(admin.tenantId()).isEqualTo("tenant-a");
        assertThat(admin.roles()).containsExactly("admin");
        assertThat(admin.permissions()).contains("order:read", "order:create", "order:cancel");

        LoginUser operator = userService.loadByMobile("13900000000");
        assertThat(operator.userId()).isEqualTo("10002");
        assertThat(operator.tenantId()).isEqualTo("tenant-b");

        assertThatThrownBy(() -> userService.authenticateByUsername("missing", "123456"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> userService.authenticateByUsername("admin", "wrong"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> userService.loadByMobile("13700000000"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(smsVerifier.verify("13800000000", "123456")).isTrue();
        assertThat(smsVerifier.verify("13800000000", "000000")).isFalse();
    }

    @Test
    void shouldExposeHealthAndJwksEndpoints() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");

        ResponseEntity<String> jwks = restTemplate.getForEntity("/oauth2/jwks", String.class);
        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwks.getBody()).contains("example-auth-key");
    }
}
