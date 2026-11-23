package com.chaos.redis.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

/**
 * Caffeine（L1）+ Redis（L2）两级缓存实现。
 *
 * <p>读路径优先命中本地 L1，未命中时回源 L2 并回填 L1；写、失效与清空会同步作用于 L2 与 L1。
 * 开启 {@code cacheNullValues} 时使用空值哨兵防止缓存穿透；开启 {@code singleFlight} 时按 key
 * 分段加锁保证热点 key 失效瞬间只有一个线程回源加载。</p>
 */
public class TwoLevelCache implements Cache {

    private static final int LOCK_STRIPES = 64;

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Cache;
    private final Cache l2Cache;
    private final boolean cacheNullValues;
    private final boolean singleFlight;
    private final String originId;
    private final Supplier<TwoLevelCacheInvalidator> invalidatorSupplier;
    private final ReentrantLock[] stripes;

    /**
     * 创建两级缓存。
     *
     * @param name 缓存名称
     * @param l2Cache L2 共享缓存（例如 {@code RedisCache}）
     * @param l1MaxSize 本地 L1 最大条目数
     * @param l1Ttl 本地 L1 写入过期时间
     * @param cacheNullValues 是否缓存空值以防止穿透
     * @param singleFlight 是否开启按 key 分段的单飞加载防止击穿
     */
    public TwoLevelCache(
            String name,
            Cache l2Cache,
            long l1MaxSize,
            Duration l1Ttl,
            boolean cacheNullValues,
            boolean singleFlight) {
        this(name, l2Cache, l1MaxSize, l1Ttl, cacheNullValues, singleFlight,
                UUID.randomUUID().toString(), TwoLevelCacheInvalidator::noop);
    }

    /**
     * 创建支持跨实例 L1 广播失效的两级缓存。
     */
    public TwoLevelCache(
            String name,
            Cache l2Cache,
            long l1MaxSize,
            Duration l1Ttl,
            boolean cacheNullValues,
            boolean singleFlight,
            String originId,
            Supplier<TwoLevelCacheInvalidator> invalidatorSupplier) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("cache name must not be blank");
        }
        if (originId == null || originId.isBlank()) {
            throw new IllegalArgumentException("originId must not be blank");
        }
        this.name = name;
        this.l2Cache = Objects.requireNonNull(l2Cache, "l2Cache must not be null");
        long maxSize = Math.max(l1MaxSize, 1);
        Duration ttl = l1Ttl == null || l1Ttl.isNegative() || l1Ttl.isZero() ? Duration.ofMinutes(5) : l1Ttl;
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
        this.cacheNullValues = cacheNullValues;
        this.singleFlight = singleFlight;
        this.originId = originId;
        this.invalidatorSupplier = invalidatorSupplier == null ? TwoLevelCacheInvalidator::noop : invalidatorSupplier;
        this.stripes = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1Cache;
    }

    @Override
    public ValueWrapper get(Object key) {
        Object cached = l1Cache.getIfPresent(key);
        if (cached != null) {
            return unwrapL1(cached);
        }
        ValueWrapper l2Wrapper = l2Cache.get(key);
        if (l2Wrapper == null) {
            return null;
        }
        Object l2Value = l2Wrapper.get();
        if (l2Value == null) {
            if (cacheNullValues) {
                l1Cache.put(key, NullValue.INSTANCE);
                return new SimpleValueWrapper(null);
            }
            return null;
        }
        l1Cache.put(key, l2Value);
        return new SimpleValueWrapper(l2Value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value == null) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value is not of required type [" + type.getName() + "]: " + value.getClass().getName());
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper existing = get(key);
        if (existing != null) {
            return (T) existing.get();
        }
        if (!singleFlight) {
            return loadAndPopulate(key, valueLoader);
        }
        ReentrantLock lock = stripeLock(key);
        lock.lock();
        try {
            ValueWrapper rechecked = get(key);
            if (rechecked != null) {
                return (T) rechecked.get();
            }
            return loadAndPopulate(key, valueLoader);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (value == null) {
            if (cacheNullValues) {
                l2Cache.put(key, null);
                l1Cache.put(key, NullValue.INSTANCE);
                broadcastEvict(key);
            } else {
                evict(key);
            }
            return;
        }
        l2Cache.put(key, value);
        l1Cache.put(key, value);
        broadcastEvict(key);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        ValueWrapper existing = l2Cache.putIfAbsent(key, value);
        if (existing == null) {
            if (value == null) {
                if (cacheNullValues) {
                    l1Cache.put(key, NullValue.INSTANCE);
                    broadcastEvict(key);
                }
            } else {
                l1Cache.put(key, value);
                broadcastEvict(key);
            }
            return null;
        }
        Object existingVal = existing.get();
        if (existingVal == null && cacheNullValues) {
            l1Cache.put(key, NullValue.INSTANCE);
        } else if (existingVal != null) {
            l1Cache.put(key, existingVal);
        }
        return existing;
    }

    @Override
    public void evict(Object key) {
        l2Cache.evict(key);
        l1Cache.invalidate(key);
        broadcastEvict(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean l2Evicted = l2Cache.evictIfPresent(key);
        boolean l1Present = l1Cache.asMap().remove(key) != null;
        if (l2Evicted || l1Present) {
            broadcastEvict(key);
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        l2Cache.clear();
        l1Cache.invalidateAll();
        broadcastClear();
    }

    /**
     * 仅淘汰本地 L1 缓存中的指定 key（供远端实例失效广播回调使用，不操作 L2 且不再广播）。
     */
    public void evictLocal(Object key) {
        if (key instanceof TwoLevelCacheInvalidationMessage.OpaqueKey opaque) {
            l1Cache.asMap().keySet().removeIf(candidate -> opaque.text().equals(String.valueOf(candidate)));
            return;
        }
        if (key != null) {
            l1Cache.invalidate(key);
        }
    }

    /**
     * 仅清空本地 L1 缓存（供远端实例清空广播回调使用，不操作 L2 且不再广播）。
     */
    public void clearLocal() {
        l1Cache.invalidateAll();
    }

    private void broadcastEvict(Object key) {
        TwoLevelCacheInvalidator invalidator = invalidatorSupplier.get();
        if (invalidator != null) {
            invalidator.publish(TwoLevelCacheInvalidationMessage.evict(originId, name, key));
        }
    }

    private void broadcastClear() {
        TwoLevelCacheInvalidator invalidator = invalidatorSupplier.get();
        if (invalidator != null) {
            invalidator.publish(TwoLevelCacheInvalidationMessage.clear(originId, name));
        }
    }

    private <T> T loadAndPopulate(Object key, Callable<T> valueLoader) {
        T loaded;
        try {
            loaded = valueLoader.call();
        } catch (Throwable ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
        if (loaded != null || cacheNullValues) {
            put(key, loaded);
        }
        return loaded;
    }

    private ValueWrapper unwrapL1(Object cached) {
        return cached == NullValue.INSTANCE ? new SimpleValueWrapper(null) : new SimpleValueWrapper(cached);
    }

    private ReentrantLock stripeLock(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        int spread = hash ^ (hash >>> 16);
        return stripes[spread & (LOCK_STRIPES - 1)];
    }

    /**
     * L1 Caffeine 不支持直接存入 {@code null}，用哨兵标记已缓存的空结果。
     */
    private static final class NullValue implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final NullValue INSTANCE = new NullValue();
    }
}
