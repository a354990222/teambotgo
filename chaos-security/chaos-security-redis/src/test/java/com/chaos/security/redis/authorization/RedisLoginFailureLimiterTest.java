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
 * {@link RedisLoginFailureLimiter} 单元测试。
 */
class RedisLoginFailureLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldCheckLockRecordFailureAndReset() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        RedisLoginFailureLimiter limiter = new RedisLoginFailureLimiter(
                redisTemplate, "chaos:login-fail", 3, Duration.ofMinutes(15));

        when(valueOps.get("chaos:login-fail:tenant:alice")).thenReturn(null, "2", "3", "corrupted");
        assertThat(limiter.isLocked("tenant:alice")).isFalse();
        assertThat(limiter.isLocked("tenant:alice")).isFalse();
        assertThat(limiter.isLocked("tenant:alice")).isTrue();
        assertThat(limiter.isLocked("tenant:alice")).isFalse();

        limiter.recordFailure("tenant:alice");
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("chaos:login-fail:tenant:alice")),
                eq("900000"));

        limiter.reset("tenant:alice");
        verify(redisTemplate).delete("chaos:login-fail:tenant:alice");
    }
}
