package com.chaos.security.redis.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RedisCaptchaStore} 单元测试。
 */
class RedisCaptchaStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldSaveWithNormalizedPrefixAndConsumeAtomically() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        RedisCaptchaStore store = new RedisCaptchaStore(redisTemplate, "chaos:captcha");
        store.save("cap-1", "digest-1", Duration.ofMinutes(2));
        verify(valueOps).set("chaos:captcha:cap-1", "digest-1", Duration.ofMinutes(2));

        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("chaos:captcha:cap-1")), eq("digest-1")))
                .thenReturn(1L);
        assertThat(store.consume("cap-1", "digest-1")).isTrue();

        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("chaos:captcha:cap-1")), eq("wrong")))
                .thenReturn(-1L);
        assertThat(store.consume("cap-1", "wrong")).isFalse();

        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("chaos:captcha:missing")), eq("digest-1")))
                .thenReturn(null);
        assertThat(store.consume("missing", "digest-1")).isFalse();
    }
}
