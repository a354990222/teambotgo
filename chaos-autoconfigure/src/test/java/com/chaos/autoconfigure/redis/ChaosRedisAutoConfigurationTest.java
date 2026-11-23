package com.chaos.autoconfigure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chaos.autoconfigure.gateway.ChaosGatewayAutoConfiguration;
import com.chaos.autoconfigure.web.ChaosWebAutoConfiguration;
import com.chaos.domain.cache.CacheKeyStrategy;
import com.chaos.core.idempotent.IdempotentRepository;
import com.chaos.core.ratelimit.RateLimiter;
import com.chaos.redis.cache.CacheAsideTemplate;
import com.chaos.redis.cache.TwoLevelCacheManager;
import com.chaos.redis.idempotent.RedissonIdempotentRepository;
import com.chaos.redis.key.RedisKeyPrefix;
import com.chaos.redis.ratelimit.RedissonRateLimiter;
import com.chaos.test.redis.InMemoryRedisTemplates;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 自动装配测试。
 */
class ChaosRedisAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withConfiguration(AutoConfigurations.of(
                    ChaosRedisAutoConfiguration.class,
                    ChaosRedisCacheAsideAutoConfiguration.class,
                    ChaosWebAutoConfiguration.class
            ));

    /**
     * Redis 自动装配必须在 Redisson 之后、Web 默认实现之前执行。
     */
    @Test
    void shouldDeclareRequiredAutoConfigurationOrder() {
        AutoConfiguration annotation = ChaosRedisAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        assertThat(annotation.afterName())
                .contains("org.redisson.spring.starter.RedissonAutoConfigurationV2");
        assertThat(annotation.beforeName())
                .contains("com.chaos.autoconfigure.web.ChaosWebAutoConfiguration",
                        "com.chaos.autoconfigure.gateway.ChaosGatewayAutoConfiguration");
    }

    /**
     * key 前缀应绑定到所有 Redis 组件和缓存 key 策略。
     */
    @Test
    void shouldBindKeyPrefix() {
        contextRunner.withPropertyValues("chaos.redis.key-prefix=order-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RedisKeyPrefix.class).value()).isEqualTo("order-service:");
                    assertThat(context.getBean(CacheKeyStrategy.class).build("user", "1"))
                            .isEqualTo("order-service:user:1");
                });
    }

    /**
     * 普通 RedisTemplate 的 key 必须可读，value 与 hash value 必须显式配置序列化器。
     */
    @Test
    void shouldConfigureRedisTemplateSerializers() {
        contextRunner
                .withBean("redisTemplate", RedisTemplate.class, InMemoryRedisTemplates::create)
                .run(context -> {
                    RedisTemplate<?, ?> template = context.getBean("redisTemplate", RedisTemplate.class);
                    assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(template.getValueSerializer()).isInstanceOf(JdkSerializationRedisSerializer.class);
                    assertThat(template.getHashValueSerializer()).isInstanceOf(JdkSerializationRedisSerializer.class);
                });
    }

    /**
     * 生产模式应使用 Redisson 限流和幂等实现，而不是本地内存实现。
     */
    @Test
    void shouldUseRedissonImplementationsInProductionMode() {
        contextRunner.withPropertyValues("chaos.production-safety.production-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(RedissonRateLimiter.class);
                    assertThat(context).hasSingleBean(IdempotentRepository.class);
                    assertThat(context.getBean(IdempotentRepository.class))
                            .isInstanceOf(RedissonIdempotentRepository.class);
                });
    }

    /**
     * key 前缀应同步应用到 Spring Cache 的 RedisCacheManager 默认 key 前缀。
     */
    @Test
    void shouldApplyKeyPrefixToSpringCacheManagerBuilder() {
        contextRunner.withPropertyValues("chaos.redis.key-prefix=order-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisCacheManagerBuilderCustomizer.class);
                    RedisCacheManagerBuilderCustomizer customizer =
                            context.getBean(RedisCacheManagerBuilderCustomizer.class);
                    RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(
                                    mock(RedisConnectionFactory.class))
                            .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig());
                    customizer.customize(builder);
                    assertThat(builder.cacheDefaults().getKeyPrefixFor("user"))
                            .isEqualTo("order-service:user::");
                });
    }

    /**
     * 启用两级缓存开关后应装配 TwoLevelCacheManager 与 CacheAsideTemplate，并默认挂载 Pub/Sub 失效广播容器。
     */
    @Test
    void shouldRegisterTwoLevelCacheManagerAndTemplateWhenEnabled() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        org.mockito.Mockito.when(connectionFactory.getConnection()).thenReturn(connection);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.springframework.data.redis.connection.MessageListener listener = invocation.getArgument(0);
            byte[] channel = invocation.getArgument(1);
            if (listener instanceof org.springframework.data.redis.connection.SubscriptionListener subListener) {
                subListener.onChannelSubscribed(channel, 1L);
            }
            return null;
        }).when(connection).subscribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        contextRunner
                .withBean(RedisConnectionFactory.class, () -> connectionFactory)
                .withPropertyValues(
                        "chaos.redis.key-prefix=order-service",
                        "chaos.redis.two-level-cache.enabled=true",
                        "chaos.redis.two-level-cache.l1-max-size=500",
                        "chaos.redis.two-level-cache.l1-ttl=2m",
                        "chaos.redis.two-level-cache.l2-ttl=15m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CacheManager.class);
                    assertThat(context.getBean(CacheManager.class)).isInstanceOf(TwoLevelCacheManager.class);
                    assertThat(context).hasSingleBean(CacheAsideTemplate.class);
                    assertThat(context).hasBean("chaosTwoLevelCacheInvalidationListenerContainer");
                    assertThat(context.getBean("chaosTwoLevelCacheInvalidationListenerContainer"))
                            .isInstanceOf(RedisMessageListenerContainer.class);
                });
    }

    /**
     * 关闭 broadcast-invalidation 时不装配 Pub/Sub 监听容器。
     */
    @Test
    void shouldAllowDisablingTwoLevelCacheBroadcastInvalidation() {
        contextRunner
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withPropertyValues(
                        "chaos.redis.two-level-cache.enabled=true",
                        "chaos.redis.two-level-cache.broadcast-invalidation=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TwoLevelCacheManager.class);
                    assertThat(context).doesNotHaveBean("chaosTwoLevelCacheInvalidationListenerContainer");
                });
    }
}
