package com.chaos.web.reactive.idempotent;

import com.chaos.core.idempotent.support.InMemoryIdempotentRepository;
import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * 基于内存缓存的响应式幂等仓储默认实现（仅适用于单实例或开发测试）。
 */
public class InMemoryReactiveIdempotentRepository implements ReactiveIdempotentRepository {

    private final InMemoryIdempotentRepository delegate;

    /**
     * 创建内存响应式幂等仓储。
     */
    public InMemoryReactiveIdempotentRepository(int maxLocalKeys) {
        this.delegate = new InMemoryIdempotentRepository(maxLocalKeys);
    }

    @Override
    public Mono<Boolean> saveIfAbsent(String key, Duration ttl) {
        return Mono.fromSupplier(() -> delegate.saveIfAbsent(key, ttl));
    }

    @Override
    public Mono<Void> remove(String key) {
        return Mono.fromRunnable(() -> delegate.remove(key));
    }
}
