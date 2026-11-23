package com.chaos.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link TwoLevelCache}、{@link TwoLevelCacheManager} 与 {@link CacheAsideTemplate} 单元测试。
 */
class TwoLevelCacheAndTemplateTest {

    @Test
    void shouldHitL1FallbackL2AndPropagateWritesAndEvictions() {
        ConcurrentMapCacheManager l2Manager = new ConcurrentMapCacheManager("orders");
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                l2Manager, 100, Duration.ofMinutes(5), true, true);
        Cache twoLevel = manager.getCache("orders");
        assertThat(twoLevel).isNotNull();
        assertThat(manager.getCacheNames()).containsExactly("orders");
        assertThat(manager.getCache(" ")).isNull();

        Cache l2 = l2Manager.getCache("orders");
        l2.put("k1", "from-l2");

        // L1 未命中时从 L2 回源并回填 L1
        assertThat(twoLevel.get("k1", String.class)).isEqualTo("from-l2");
        l2.evict("k1");
        assertThat(twoLevel.get("k1", String.class)).isEqualTo("from-l2");

        // 写、putIfAbsent、evictIfPresent 与 clear 同步作用于两级
        twoLevel.put("k2", "v2");
        assertThat(l2.get("k2", String.class)).isEqualTo("v2");
        assertThat(twoLevel.putIfAbsent("k2", "other").get()).isEqualTo("v2");
        assertThat(twoLevel.putIfAbsent("k3", "v3")).isNull();
        assertThat(l2.get("k3", String.class)).isEqualTo("v3");

        assertThat(twoLevel.evictIfPresent("k3")).isTrue();
        assertThat(twoLevel.evictIfPresent("k3")).isFalse();
        assertThat(l2.get("k3")).isNull();

        twoLevel.clear();
        assertThat(twoLevel.get("k2")).isNull();
        assertThat(l2.get("k2")).isNull();
    }

    @Test
    void shouldPreventCachePenetrationAndStampede() throws Exception {
        ConcurrentMapCacheManager l2Manager = new ConcurrentMapCacheManager("users");
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                l2Manager, 100, Duration.ofMinutes(5), true, true);
        CacheAsideTemplate template = new CacheAsideTemplate(manager);

        AtomicInteger missingLoads = new AtomicInteger();
        assertThat((String) template.getOrLoad("users", "missing-id", () -> {
            missingLoads.incrementAndGet();
            return null;
        })).isNull();
        assertThat((String) template.getOrLoad("users", "missing-id", () -> {
            missingLoads.incrementAndGet();
            return null;
        })).isNull();
        assertThat(missingLoads.get()).isEqualTo(1);

        AtomicInteger stampedeLoads = new AtomicInteger();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            Callable<String> task = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return template.getOrLoad("users", "hot-key", () -> {
                    Thread.sleep(30);
                    stampedeLoads.incrementAndGet();
                    return "hot-value";
                });
            };
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("hot-value");
            }
            assertThat(stampedeLoads.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        template.evict("users", "hot-key");
        assertThat(manager.getCache("users").get("hot-key")).isNull();
        template.clear("users");
        assertThatThrownBy(() -> template.getOrLoad(" ", "k", () -> "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportNullValuesDisabledAndWrapLoaderExceptions() {
        ConcurrentMapCacheManager l2Manager = new ConcurrentMapCacheManager("items");
        TwoLevelCacheManager noNullManager = new TwoLevelCacheManager(
                l2Manager, 10, Duration.ofMinutes(1), false, false);
        Cache cache = noNullManager.getCache("items");

        AtomicInteger loads = new AtomicInteger();
        Callable<String> absentLoader = () -> {
            loads.incrementAndGet();
            return null;
        };
        assertThat(cache.get("absent", absentLoader)).isNull();
        assertThat(cache.get("absent", absentLoader)).isNull();
        assertThat(loads.get()).isEqualTo(2);

        cache.put("num", 123);
        assertThatThrownBy(() -> cache.get("num", String.class)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.get("boom", () -> {
            throw new IllegalStateException("db error");
        })).isInstanceOf(Cache.ValueRetrievalException.class);
    }

    @Test
    void shouldBroadcastAndApplyCrossInstanceL1InvalidationsAndSkipSelfEcho() {
        ConcurrentMapCacheManager sharedL2 = new ConcurrentMapCacheManager("orders");
        List<TwoLevelCacheInvalidationMessage> bus = new ArrayList<>();

        TwoLevelCacheManager nodeA = new TwoLevelCacheManager(
                sharedL2, 100, Duration.ofMinutes(5), true, true, "node-a", bus::add);
        TwoLevelCacheManager nodeB = new TwoLevelCacheManager(
                sharedL2, 100, Duration.ofMinutes(5), true, true, "node-b", TwoLevelCacheInvalidator.noop());

        Cache cacheA = nodeA.getCache("orders");
        Cache cacheB = nodeB.getCache("orders");

        // 两个节点都预热 L1（包含普通 key 与自定义对象 key）
        record CustomKey(String code) {
        }
        CustomKey customKey = new CustomKey("k-custom");
        sharedL2.getCache("orders").put("k1", "v1-old");
        sharedL2.getCache("orders").put(100L, "long-old");
        sharedL2.getCache("orders").put(customKey, "custom-old");
        assertThat(cacheA.get("k1", String.class)).isEqualTo("v1-old");
        assertThat(cacheB.get("k1", String.class)).isEqualTo("v1-old");
        assertThat(cacheB.get(100L, String.class)).isEqualTo("long-old");
        assertThat(cacheB.get(customKey, String.class)).isEqualTo("custom-old");

        // nodeA 写入新值 -> L2 已更新，且 nodeA 自身回声被忽略，nodeB 收到广播后淘汰 L1 并从 L2 读到新值
        cacheA.put("k1", "v1-new");
        cacheA.put(100L, "long-new");
        cacheA.put(customKey, "custom-new");
        assertThat(bus).hasSize(3);
        bus.forEach(msg -> {
            String raw = msg.encode();
            TwoLevelCacheInvalidationMessage decoded = TwoLevelCacheInvalidationMessage.decode(raw).orElseThrow();
            nodeA.handleInvalidation(decoded);
            nodeB.handleInvalidation(decoded);
        });
        assertThat(cacheA.get("k1", String.class)).isEqualTo("v1-new");
        assertThat(cacheB.get("k1", String.class)).isEqualTo("v1-new");
        assertThat(cacheB.get(100L, String.class)).isEqualTo("long-new");
        assertThat(cacheB.get(customKey, String.class)).isEqualTo("custom-new");

        // clear 广播清空其他节点整个 cache 的 L1
        bus.clear();
        cacheA.clear();
        assertThat(bus).hasSize(1);
        TwoLevelCacheInvalidationMessage clearMsg =
                TwoLevelCacheInvalidationMessage.decode(bus.get(0).encode()).orElseThrow();
        nodeB.handleInvalidation(clearMsg);
        assertThat(((TwoLevelCache) cacheB).getNativeCache()).isNotNull();
        assertThat(cacheB.get("k1")).isNull();
    }

    @Test
    void shouldEncodeDecodeAllKeyTypesAndBridgeRedisPubSubMessages() {
        UUID uuid = UUID.randomUUID();
        List<Object> keys = List.of("str|pipe", 42L, 7, true, uuid);
        for (Object key : keys) {
            String wire = TwoLevelCacheInvalidationMessage.evict("origin-1", "users|ns", key).encode();
            TwoLevelCacheInvalidationMessage decoded = TwoLevelCacheInvalidationMessage.decode(wire).orElseThrow();
            assertThat(decoded.originId()).isEqualTo("origin-1");
            assertThat(decoded.cacheName()).isEqualTo("users|ns");
            assertThat(decoded.action()).isEqualTo(TwoLevelCacheInvalidationMessage.Action.EVICT);
            assertThat(decoded.key()).isEqualTo(key);
        }
        assertThat(TwoLevelCacheInvalidationMessage.decode(
                TwoLevelCacheInvalidationMessage.evict("o", "c", null).encode()).orElseThrow().key()).isNull();
        assertThat(TwoLevelCacheInvalidationMessage.decode("")).isEmpty();
        assertThat(TwoLevelCacheInvalidationMessage.decode("v2|o|E|Yw|S|Yw")).isEmpty();
        assertThat(TwoLevelCacheInvalidationMessage.decode("v1|o|Z|Yw|S|Yw")).isEmpty();
        assertThat(TwoLevelCacheInvalidationMessage.decode("v1|o|E|!!!|S|Yw")).isEmpty();

        List<String> sentTopics = new ArrayList<>();
        List<Object> sentPayloads = new ArrayList<>();
        AtomicInteger failOnSend = new AtomicInteger();
        StringRedisTemplate redisTemplate = new StringRedisTemplate() {
            @Override
            public Long convertAndSend(String channel, Object message) {
                if (failOnSend.get() > 0) {
                    throw new IllegalStateException("redis down");
                }
                sentTopics.add(channel);
                sentPayloads.add(message);
                return 1L;
            }
        };
        List<TwoLevelCacheInvalidationMessage> received = new ArrayList<>();
        RedisTwoLevelCacheInvalidationBridge bridge = new RedisTwoLevelCacheInvalidationBridge(
                redisTemplate, "order:chaos:cache:invalidation", received::add);
        assertThat(bridge.getTopic()).isEqualTo("order:chaos:cache:invalidation");

        TwoLevelCacheInvalidationMessage sample = TwoLevelCacheInvalidationMessage.evict("n1", "orders", 99L);
        bridge.publish(null);
        bridge.publish(sample);
        assertThat(sentTopics).containsExactly("order:chaos:cache:invalidation");
        assertThat(sentPayloads).containsExactly(sample.encode());

        byte[] bytes = sample.encode().getBytes(StandardCharsets.UTF_8);
        bridge.onMessage(new DefaultMessage("order:chaos:cache:invalidation".getBytes(StandardCharsets.UTF_8), bytes), null);
        bridge.onMessage(new DefaultMessage("t".getBytes(StandardCharsets.UTF_8), "bad".getBytes(StandardCharsets.UTF_8)), null);
        bridge.onMessage(null, null);
        assertThat(received).containsExactly(sample);

        // 发布异常与消费异常均不应外泄中断主流程
        failOnSend.set(1);
        bridge.publish(sample);

        RedisTwoLevelCacheInvalidationBridge failingConsumerBridge = new RedisTwoLevelCacheInvalidationBridge(
                redisTemplate, "topic-x", msg -> {
                    throw new IllegalStateException("consumer failed");
                });
        failingConsumerBridge.onMessage(new DefaultMessage("topic-x".getBytes(StandardCharsets.UTF_8), bytes), null);
    }
}
