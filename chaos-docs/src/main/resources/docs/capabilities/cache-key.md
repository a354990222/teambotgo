# 缓存 key 策略与两级缓存（chaos-domain + chaos-redis）

> 仓库中不存在独立的 `chaos-cache` 模块。缓存 key 策略接口位于 `chaos-domain`（包 `com.chaos.domain.cache`），带前缀实现、Caffeine L1 + Redis L2 两级缓存以及 `CacheAsideTemplate` 均位于 `chaos-redis`（包 `com.chaos.redis.key`、`com.chaos.redis.cache`）。

## 组成

| 类型 | 位置 | 说明 |
| --- | --- | --- |
| `CacheKeyStrategy` | chaos-domain | 生成缓存 key 的抽象 |
| `DefaultCacheKeyStrategy` | chaos-domain | `namespace:key` |
| `PrefixedCacheKeyStrategy` | chaos-redis | 在委托策略结果前加 `chaos.redis.key-prefix` |
| `TwoLevelCacheManager` / `TwoLevelCache` | chaos-redis | Caffeine（L1）+ Redis（L2）两级 `CacheManager`，支持空值防穿透、按 key 单飞防击穿及 Redis Pub/Sub 跨实例 L1 失效广播 |
| `RedisTwoLevelCacheInvalidationBridge` | chaos-redis | 基于 `StringRedisTemplate` 与 `MessageListener` 的跨实例 L1 失效广播与自回声过滤 |
| `CacheAsideTemplate` | chaos-redis | 显式 Cache-Aside 回源加载、空值保护与写后删缓存模板 |

## 配置

引入 `chaos-redis-starter` 后默认注册 `PrefixedCacheKeyStrategy(DefaultCacheKeyStrategy, chaos.redis.key-prefix)` 并通过 `chaosRedisCachePrefixCustomizer` 对齐 Spring Cache `RedisCacheManager` 前缀；
容器内存在 `CacheManager` 时由 `ChaosRedisCacheAsideAutoConfiguration` 自动注册 `CacheAsideTemplate`；若同时开启两级缓存（需 classpath 存在 `com.github.ben-manes.caffeine:caffeine`），配置如下：

```yaml
chaos:
  redis:
    key-prefix: ${spring.application.name}
    two-level-cache:
      enabled: true
      l1-max-size: 10000
      l1-ttl: 5m
      l2-ttl: 30m
      cache-null-values: true
      single-flight: true
      broadcast-invalidation: true
      invalidation-topic: "chaos:cache:invalidation"
```

## 示例

```java
String key = cacheKeyStrategy.build("order", orderId); // order-service:order:1001
OrderSummary summary = cacheAsideTemplate.getOrLoad("orders", orderId, () -> loadFromDb(orderId));
```

## 扩展点

- 注册自定义 `CacheKeyStrategy` Bean 覆盖默认实现，例如追加租户维度。
- 注册自定义 `CacheManager` Bean 时，`TwoLevelCacheManager` 自动让位。
