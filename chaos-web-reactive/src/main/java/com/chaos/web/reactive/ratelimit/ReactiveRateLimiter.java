package com.chaos.web.reactive.ratelimit;

import reactor.core.publisher.Mono;

/**
 * 非阻塞响应式限流端口。
 */
@FunctionalInterface
public interface ReactiveRateLimiter {

    /**
     * 尝试申请一次请求许可。
     *
     * @param key 限流键
     * @param permitsPerSecond 每秒允许通过的请求数
     * @return {@code true} 表示放行，{@code false} 表示触发限流
     */
    Mono<Boolean> tryAcquire(String key, int permitsPerSecond);
}
