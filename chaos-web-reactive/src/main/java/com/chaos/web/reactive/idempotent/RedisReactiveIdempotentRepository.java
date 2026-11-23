package com.chaos.web.reactive.idempotent;

import java.time.Duration;
import java.util.Objects;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * 基于 {@link ReactiveStringRedisTemplate} 的分布式非阻塞响应式幂等仓储实现。
 */
public class RedisReactiveIdempotentRepository implements ReactiveIdempotentRepository {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String keyPrefix;

    /**
     * 创建 Redis 响应式幂等仓储。
     */
    public RedisReactiveIdempotentRepository(ReactiveStringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.keyPrefix = keyPrefix == null ? "chaos:web-reactive:idempotent:" : keyPrefix;
    }

    @Override
    public Mono<Boolean> saveIfAbsent(String key, Duration ttl) {
        return redisTemplate.opsForValue()
                .setIfAbsent(keyPrefix + key, "1", ttl)
                .map(Boolean.TRUE::equals);
    }

    @Override
    public Mono<Void> remove(String key) {
        return redisTemplate.delete(keyPrefix + key).then();
    }
}
