package com.chaos.autoconfigure.redis;

import com.chaos.autoconfigure.gateway.ChaosGatewayAutoConfiguration;
import com.chaos.autoconfigure.security.redis.ChaosSecurityRedisAutoConfiguration;
import com.chaos.autoconfigure.web.ChaosWebAutoConfiguration;
import com.chaos.domain.cache.CacheKeyStrategy;
import com.chaos.domain.cache.DefaultCacheKeyStrategy;
import com.chaos.core.idempotent.IdempotentRecordStore;
import com.chaos.core.idempotent.IdempotentRepository;
import com.chaos.core.lock.DistributedLock;
import com.chaos.core.ratelimit.RateLimiter;
import com.chaos.redis.bloom.BloomFilterTemplate;
import com.chaos.redis.cache.RedisTwoLevelCacheInvalidationBridge;
import com.chaos.redis.cache.TwoLevelCacheManager;
import com.chaos.redis.delay.DelayQueueTemplate;
import com.chaos.redis.idempotent.RedissonIdempotentRecordStore;
import com.chaos.redis.idempotent.RedissonIdempotentRepository;
import com.chaos.redis.key.PrefixedCacheKeyStrategy;
import com.chaos.redis.key.RedisKeyPrefix;
import com.chaos.redis.lock.RedissonDistributedLock;
import com.chaos.redis.lua.LuaScriptExecutor;
import com.chaos.redis.ratelimit.RedissonRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis starter 自动装配。
 *
 * <p>必须先于 Web 与 Gateway 自动装配执行：否则它们的 {@code @ConditionalOnMissingBean} 会先注册内存限流器和内存幂等存储。
 * JWT 黑名单等安全领域的 Redis 实现不在这里装配，见 {@link ChaosSecurityRedisAutoConfiguration}。</p>
 */
@AutoConfiguration(
        afterName = {
                "org.redisson.spring.starter.RedissonAutoConfigurationV2",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        },
        beforeName = {
                "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration",
                "com.chaos.autoconfigure.web.ChaosWebAutoConfiguration",
                "com.chaos.autoconfigure.gateway.ChaosGatewayAutoConfiguration"
        })
@ConditionalOnClass(value = RedissonClient.class, name = "com.chaos.redis.lock.RedissonDistributedLock")
@EnableConfigurationProperties(ChaosRedisProperties.class)

public class ChaosRedisAutoConfiguration {

    /**
     * 注册 Redis key 前缀。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisKeyPrefix redisKeyPrefix(ChaosRedisProperties properties) {
        return RedisKeyPrefix.of(properties.getKeyPrefix());
    }

    /**
     * 注册默认缓存 key 策略，自动带上 key 前缀。
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheKeyStrategy cacheKeyStrategy(RedisKeyPrefix redisKeyPrefix) {
        return new PrefixedCacheKeyStrategy(new DefaultCacheKeyStrategy(), redisKeyPrefix);
    }

    /**
     * 统一普通 RedisTemplate 的 key/value 序列化策略。
     */
    @Bean(name = "chaosRedisTemplateSerializationPostProcessor")
    @ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
    @ConditionalOnMissingBean(name = "chaosRedisTemplateSerializationPostProcessor")
    public BeanPostProcessor chaosRedisTemplateSerializationPostProcessor() {
        return RedisSerializationSupport.postProcessor();
    }

    /**
     * 注册 Redisson 分布式锁。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public DistributedLock distributedLock(RedissonClient redissonClient, RedisKeyPrefix redisKeyPrefix) {
        return new RedissonDistributedLock(redissonClient, redisKeyPrefix);
    }

    /**
     * 注册 Redis 幂等存储。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public IdempotentRepository idempotentRepository(RedissonClient redissonClient, RedisKeyPrefix redisKeyPrefix) {
        return new RedissonIdempotentRepository(redissonClient, redisKeyPrefix);
    }

    /**
     * 注册 Redis 幂等响应快照存储。
     *
     * <p>不按 {@code chaos.web.idempotent.replay.enabled} 做条件：Redis 实现不占本地资源，
     * 而且 chaos-redis 不应该知道 chaos-web 的配置项。是否真的读写快照由 Web 层的开关决定。</p>
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public IdempotentRecordStore idempotentRecordStore(RedissonClient redissonClient, RedisKeyPrefix redisKeyPrefix) {
        return new RedissonIdempotentRecordStore(redissonClient, redisKeyPrefix);
    }

    /**
     * 注册 Redis 集群限流器。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(RedissonClient redissonClient, RedisKeyPrefix redisKeyPrefix) {
        return new RedissonRateLimiter(redissonClient, redisKeyPrefix);
    }

    /**
     * 注册布隆过滤器模板。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public BloomFilterTemplate bloomFilterTemplate(RedissonClient redissonClient, RedisKeyPrefix redisKeyPrefix) {
        return new BloomFilterTemplate(redissonClient, redisKeyPrefix);
    }

    /**
     * 注册延迟队列模板。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public DelayQueueTemplate delayQueueTemplate(RedissonClient redissonClient, RedisKeyPrefix redisKeyPrefix) {
        return new DelayQueueTemplate(redissonClient, redisKeyPrefix);
    }

    /**
     * 注册 Lua 脚本执行器。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public LuaScriptExecutor luaScriptExecutor(RedissonClient redissonClient) {
        return new LuaScriptExecutor(redissonClient);
    }

    /**
     * 将 {@code chaos.redis.key-prefix} 同步应用到 Spring Cache {@link RedisCacheManager} 默认前缀。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({
            CacheManager.class,
            RedisCacheManager.class,
            RedisCacheManagerBuilderCustomizer.class,
            org.springframework.data.redis.cache.RedisCacheConfiguration.class
    })
    static class RedisCachePrefixConfiguration {

        @Bean(name = "chaosRedisCachePrefixCustomizer")
        @ConditionalOnMissingBean(name = "chaosRedisCachePrefixCustomizer")
        public RedisCacheManagerBuilderCustomizer chaosRedisCachePrefixCustomizer(RedisKeyPrefix redisKeyPrefix) {
            return builder -> {
                String prefix = redisKeyPrefix.value();
                if (prefix.isEmpty()) {
                    return;
                }
                org.springframework.data.redis.cache.RedisCacheConfiguration defaults = builder.cacheDefaults();
                if (defaults == null || !defaults.usePrefix()) {
                    return;
                }
                CacheKeyPrefix delegate = defaults.getKeyPrefix();
                builder.cacheDefaults(defaults.computePrefixWith(cacheName -> {
                    String base = delegate.compute(cacheName);
                    return base.startsWith(prefix) ? base : prefix + base;
                }));
            };
        }
    }

    /**
     * 启用 {@code chaos.redis.two-level-cache.enabled=true} 且存在 Caffeine 时装配两级缓存管理器。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({
            CacheManager.class,
            com.github.benmanes.caffeine.cache.Caffeine.class,
            RedisCacheManager.class,
            RedisConnectionFactory.class,
            org.springframework.data.redis.cache.RedisCacheConfiguration.class,
            TwoLevelCacheManager.class
    })
    @ConditionalOnProperty(prefix = "chaos.redis.two-level-cache", name = "enabled", havingValue = "true")
    static class TwoLevelCacheConfiguration {

        @Bean
        @ConditionalOnBean(RedisConnectionFactory.class)
        @ConditionalOnMissingBean(CacheManager.class)
        public TwoLevelCacheManager cacheManager(
                RedisConnectionFactory connectionFactory,
                RedisKeyPrefix redisKeyPrefix,
                ChaosRedisProperties properties) {
            ChaosRedisProperties.TwoLevelCache twoLevel = properties.getTwoLevelCache();
            org.springframework.data.redis.cache.RedisCacheConfiguration redisConfig =
                    org.springframework.data.redis.cache.RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(twoLevel.getL2Ttl());
            if (!twoLevel.isCacheNullValues()) {
                redisConfig = redisConfig.disableCachingNullValues();
            }
            String prefix = redisKeyPrefix.value();
            if (!prefix.isEmpty()) {
                CacheKeyPrefix base = redisConfig.getKeyPrefix();
                redisConfig = redisConfig.computePrefixWith(cacheName -> prefix + base.compute(cacheName));
            }
            RedisCacheManager l2 = RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(redisConfig)
                    .build();
            l2.initializeCaches();
            return new TwoLevelCacheManager(
                    l2,
                    twoLevel.getL1MaxSize(),
                    twoLevel.getL1Ttl(),
                    twoLevel.isCacheNullValues(),
                    twoLevel.isSingleFlight());
        }

        /**
         * 为两级缓存配置跨实例 Redis Pub/Sub 主动失效同步，降低多实例场景下的 L1 本地缓存陈旧窗口。
         */
        @Bean(name = "chaosTwoLevelCacheInvalidationListenerContainer")
        @ConditionalOnBean({TwoLevelCacheManager.class, RedisConnectionFactory.class})
        @ConditionalOnClass({StringRedisTemplate.class, RedisMessageListenerContainer.class, ChannelTopic.class})
        @ConditionalOnProperty(
                prefix = "chaos.redis.two-level-cache",
                name = "broadcast-invalidation",
                havingValue = "true",
                matchIfMissing = true)
        @ConditionalOnMissingBean(name = "chaosTwoLevelCacheInvalidationListenerContainer")
        public RedisMessageListenerContainer chaosTwoLevelCacheInvalidationListenerContainer(
                RedisConnectionFactory connectionFactory,
                ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                TwoLevelCacheManager twoLevelCacheManager,
                RedisKeyPrefix redisKeyPrefix,
                ChaosRedisProperties properties) {
            StringRedisTemplate template = stringRedisTemplateProvider.getIfAvailable(() -> {
                StringRedisTemplate created = new StringRedisTemplate(connectionFactory);
                created.afterPropertiesSet();
                return created;
            });
            String rawTopic = properties.getTwoLevelCache().getInvalidationTopic();
            String resolvedTopic = redisKeyPrefix.apply(rawTopic);
            RedisTwoLevelCacheInvalidationBridge bridge = new RedisTwoLevelCacheInvalidationBridge(
                    template, resolvedTopic, twoLevelCacheManager::handleInvalidation);
            twoLevelCacheManager.setInvalidator(bridge);
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(bridge, ChannelTopic.of(resolvedTopic));
            return container;
        }
    }
}
