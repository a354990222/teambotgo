package com.chaos.security.redis.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.authorization.core.ChaosAuthorizationProperties;
import com.chaos.test.redis.InMemoryRedisTemplates;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;

/**
 * {@link RedisOAuth2AuthorizationConsentService} 单元测试。
 */
class RedisOAuth2AuthorizationConsentServiceTest {

    @Test
    void shouldSaveFindAndRemoveConsent() {
        ChaosAuthorizationProperties properties = new ChaosAuthorizationProperties();
        RedisOAuth2AuthorizationConsentService service = new RedisOAuth2AuthorizationConsentService(
                InMemoryRedisTemplates.create(), properties);

        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent.withId("client-1", "alice")
                .authority(new SimpleGrantedAuthority("SCOPE_read"))
                .build();

        service.save(consent);
        OAuth2AuthorizationConsent found = service.findById("client-1", "alice");
        assertThat(found).isNotNull();
        assertThat(found.getScopes()).containsExactly("read");

        service.remove(consent);
        assertThat(service.findById("client-1", "alice")).isNull();
        service.remove(null);
    }
}
