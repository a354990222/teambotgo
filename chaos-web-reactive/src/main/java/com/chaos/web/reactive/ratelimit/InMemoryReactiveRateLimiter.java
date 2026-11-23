package com.chaos.web.reactive.ratelimit;

import com.chaos.core.ratelimit.RateLimitContext;
import com.chaos.core.ratelimit.support.InMemoryRateLimiter;
import reactor.core.publisher.Mono;

/**
 * 基于内存计数器的响应式限流默认实现（仅适用于单实例或开发测试）。
 */
public class InMemoryReactiveRateLimiter implements ReactiveRateLimiter {

    private final InMemoryRateLimiter delegate;

    /**
     * 创建内存响应式限流器。
     */
    public InMemoryReactiveRateLimiter(int maxLocalKeys) {
        this.delegate = new InMemoryRateLimiter(maxLocalKeys);
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int permitsPerSecond) {
        return Mono.fromSupplier(() -> delegate.tryAcquire(new RateLimitContext(key, permitsPerSecond)));
    }
}
