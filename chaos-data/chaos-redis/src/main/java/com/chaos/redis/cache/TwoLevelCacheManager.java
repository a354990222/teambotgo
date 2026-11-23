package com.chaos.redis.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * 基于 Caffeine（L1）与共享 {@link CacheManager}（L2，通常为 {@code RedisCacheManager}）的两级缓存管理器。
 */
public class TwoLevelCacheManager implements CacheManager {

    private final CacheManager l2CacheManager;
    private final long l1MaxSize;
    private final Duration l1Ttl;
    private final boolean cacheNullValues;
    private final boolean singleFlight;
    private final String instanceId;
    private final ConcurrentMap<String, TwoLevelCache> caches = new ConcurrentHashMap<>();
    private volatile TwoLevelCacheInvalidator invalidator;

    /**
     * 创建两级缓存管理器。
     *
     * @param l2CacheManager 共享 L2 缓存管理器
     * @param l1MaxSize 每个 cache 的本地 L1 最大条目数
     * @param l1Ttl 本地 L1 过期时间
     * @param cacheNullValues 是否缓存空值防止穿透
     * @param singleFlight 是否开启按 key 单飞加载防止击穿
     */
    public TwoLevelCacheManager(
            CacheManager l2CacheManager,
            long l1MaxSize,
            Duration l1Ttl,
            boolean cacheNullValues,
            boolean singleFlight) {
        this(l2CacheManager, l1MaxSize, l1Ttl, cacheNullValues, singleFlight,
                UUID.randomUUID().toString(), TwoLevelCacheInvalidator.noop());
    }

    /**
     * 创建支持跨实例 L1 广播失效的两级缓存管理器。
     */
    public TwoLevelCacheManager(
            CacheManager l2CacheManager,
            long l1MaxSize,
            Duration l1Ttl,
            boolean cacheNullValues,
            boolean singleFlight,
            String instanceId,
            TwoLevelCacheInvalidator invalidator) {
        this.l2CacheManager = Objects.requireNonNull(l2CacheManager, "l2CacheManager must not be null");
        this.l1MaxSize = l1MaxSize;
        this.l1Ttl = l1Ttl;
        this.cacheNullValues = cacheNullValues;
        this.singleFlight = singleFlight;
        this.instanceId = instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
        this.invalidator = invalidator == null ? TwoLevelCacheInvalidator.noop() : invalidator;
    }

    /**
     * 返回当前实例的唯一标识，用于识别广播消息是否来自本机。
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 绑定或替换跨实例失效广播发布器。
     */
    public void setInvalidator(TwoLevelCacheInvalidator invalidator) {
        this.invalidator = invalidator == null ? TwoLevelCacheInvalidator.noop() : invalidator;
    }

    /**
     * 处理来自其他实例的 L1 失效广播消息；本实例发出的消息会被自动忽略。
     */
    public void handleInvalidation(TwoLevelCacheInvalidationMessage message) {
        if (message == null || instanceId.equals(message.originId())) {
            return;
        }
        TwoLevelCache local = caches.get(message.cacheName());
        if (local == null) {
            return;
        }
        if (message.action() == TwoLevelCacheInvalidationMessage.Action.CLEAR) {
            local.clearLocal();
        } else {
            local.evictLocal(message.key());
        }
    }

    @Override
    public Cache getCache(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        TwoLevelCache existing = caches.get(name);
        if (existing != null) {
            return existing;
        }
        Cache l2Cache = l2CacheManager.getCache(name);
        if (l2Cache == null) {
            return null;
        }
        return caches.computeIfAbsent(
                name,
                cacheName -> new TwoLevelCache(
                        cacheName,
                        l2Cache,
                        l1MaxSize,
                        l1Ttl,
                        cacheNullValues,
                        singleFlight,
                        instanceId,
                        () -> invalidator));
    }

    @Override
    public Collection<String> getCacheNames() {
        return l2CacheManager.getCacheNames();
    }
}
