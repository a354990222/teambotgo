package com.chaos.redis.cache;

import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * 显式 Cache-Aside 模板：封装缓存读取、回源加载、空值防穿透、单飞防击穿及写后删缓存操作。
 */
public class CacheAsideTemplate {

    private final CacheManager cacheManager;

    /**
     * 创建 Cache-Aside 模板。
     *
     * @param cacheManager 缓存管理器（可为 {@link TwoLevelCacheManager} 或任意 Spring {@link CacheManager}）
     */
    public CacheAsideTemplate(CacheManager cacheManager) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager must not be null");
    }

    /**
     * 读取缓存；未命中时调用 {@code loader} 回源并写入缓存。
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     * @param loader 回源加载逻辑
     * @param <T> 返回值类型
     * @return 缓存或回源结果
     */
    public <T> T getOrLoad(String cacheName, Object key, Callable<T> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        Cache cache = requireCache(cacheName);
        return cache.get(key, loader);
    }

    /**
     * 删除指定缓存条目。
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     */
    public void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    /**
     * 清空指定缓存命名空间。
     *
     * @param cacheName 缓存名称
     */
    public void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private Cache requireCache(String cacheName) {
        if (cacheName == null || cacheName.isBlank()) {
            throw new IllegalArgumentException("cacheName must not be blank");
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new IllegalArgumentException("Cache not found: " + cacheName);
        }
        return cache;
    }
}
