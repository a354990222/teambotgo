package com.chaos.web.reactive.ratelimit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

/**
 * 基于 {@link ReactiveStringRedisTemplate} 与 Lua 脚本的集群非阻塞响应式限流实现。
 */
public class RedisReactiveRateLimiter implements ReactiveRateLimiter {

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], 2)
            end
            return current
            """,
            Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String keyPrefix;

    /**
     * 创建 Redis 响应式限流器。
     */
    public RedisReactiveRateLimiter(ReactiveStringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.keyPrefix = keyPrefix == null ? "chaos:web-reactive:rate-limit:" : keyPrefix;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int permitsPerSecond) {
        if (permitsPerSecond < 1) {
            return Mono.just(false);
        }
        long epochSecond = Instant.now().getEpochSecond();
        String redisKey = keyPrefix + key + ":" + epochSecond;
        return redisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(redisKey))
                .next()
                .map(count -> count <= permitsPerSecond)
                .defaultIfEmpty(true);
    }
}
