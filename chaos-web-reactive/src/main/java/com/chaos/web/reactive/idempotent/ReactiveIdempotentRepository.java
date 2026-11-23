package com.chaos.web.reactive.idempotent;

import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * 非阻塞响应式幂等防重仓储端口。
 */
public interface ReactiveIdempotentRepository {

    /**
     * 若幂等键尚未存在则原子写入并设置过期时间。
     *
     * @param key 幂等键
     * @param ttl 过期时长
     * @return {@code true} 表示首次占位成功；{@code false} 表示重复请求
     */
    Mono<Boolean> saveIfAbsent(String key, Duration ttl);

    /**
     * 释放指定幂等键（通常用于业务执行失败后允许重试）。
     */
    Mono<Void> remove(String key);
}
