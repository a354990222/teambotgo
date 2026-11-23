package com.chaos.autoconfigure.web.reactive;

import com.chaos.autoconfigure.support.ProductionSafetyEnforcer;
import com.chaos.autoconfigure.support.UnsafeForProduction;
import com.chaos.core.metrics.ChaosMetrics;
import com.chaos.web.reactive.config.ChaosWebReactiveProperties;
import com.chaos.web.reactive.exception.ReactiveGlobalExceptionHandler;
import com.chaos.web.reactive.filter.ReactiveTraceWebFilter;
import com.chaos.web.reactive.idempotent.InMemoryReactiveIdempotentRepository;
import com.chaos.web.reactive.idempotent.ReactiveIdempotentRepository;
import com.chaos.web.reactive.idempotent.ReactiveIdempotentWebFilter;
import com.chaos.web.reactive.idempotent.RedisReactiveIdempotentRepository;
import com.chaos.web.reactive.ratelimit.InMemoryReactiveRateLimiter;
import com.chaos.web.reactive.ratelimit.ReactiveRateLimitWebFilter;
import com.chaos.web.reactive.ratelimit.ReactiveRateLimiter;
import com.chaos.web.reactive.ratelimit.RedisReactiveRateLimiter;
import com.chaos.web.reactive.result.ReactiveResultHandler;
import com.chaos.web.reactive.xss.ReactiveSecurityWebFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;

/**
 * WebFlux 响应式业务栈自动装配。
 *
 * <p>仅在响应式 Web 应用且非 Spring Cloud Gateway 进程中启用（网关已有独立的安全、限流与降级过滤器链），
 * 为业务微服务提供统一 {@code ApiResult} 包装、全局异常处理、Trace/身份传播、限流、幂等及安全响应头。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
        "org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(value = DispatcherHandler.class, name = "com.chaos.web.reactive.config.ChaosWebReactiveProperties")
@ConditionalOnMissingClass("org.springframework.cloud.gateway.filter.GlobalFilter")
@ConditionalOnProperty(prefix = "chaos.web.reactive", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChaosWebReactiveProperties.class)
public class ChaosWebReactiveAutoConfiguration {

    /**
     * 注册 WebFlux 统一全局异常处理器。
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactiveGlobalExceptionHandler reactiveGlobalExceptionHandler() {
        return new ReactiveGlobalExceptionHandler();
    }

    /**
     * 注册 WebFlux 统一 {@code ApiResult} 响应包装处理器。
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveResultHandler.class)
    @ConditionalOnBean({ServerCodecConfigurer.class, RequestedContentTypeResolver.class})
    @ConditionalOnProperty(prefix = "chaos.web.reactive", name = "response-wrap-enabled", havingValue = "true", matchIfMissing = true)
    public ReactiveResultHandler chaosReactiveResultHandler(
            ServerCodecConfigurer codecConfigurer,
            RequestedContentTypeResolver contentTypeResolver,
            ObjectProvider<ReactiveAdapterRegistry> adapterRegistryProvider,
            ChaosWebReactiveProperties properties) {
        return new ReactiveResultHandler(
                codecConfigurer.getWriters(),
                contentTypeResolver,
                adapterRegistryProvider.getIfAvailable(ReactiveAdapterRegistry::getSharedInstance),
                properties.getResponseWrapExcludePaths());
    }

    /**
     * 注册响应式 Trace 与请求上下文传播过滤器。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.web.reactive", name = "trace-enabled", havingValue = "true", matchIfMissing = true)
    public ReactiveTraceWebFilter reactiveTraceWebFilter(
            @Value("${spring.application.name:application}") String applicationName) {
        return new ReactiveTraceWebFilter(applicationName);
    }

    /**
     * 注册响应式安全响应头与 XSS 拦截过滤器。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.web.reactive", name = "xss-enabled", havingValue = "true", matchIfMissing = true)
    public ReactiveSecurityWebFilter reactiveSecurityWebFilter() {
        return new ReactiveSecurityWebFilter();
    }

    /**
     * 存在 {@link ReactiveStringRedisTemplate} 时优先注册基于 Redis Lua 的分布式限流与幂等实现。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ReactiveStringRedisTemplate.class)
    static class RedisReactiveStoreConfiguration {

        @Bean
        @ConditionalOnBean(ReactiveStringRedisTemplate.class)
        @ConditionalOnMissingBean(ReactiveRateLimiter.class)
        public ReactiveRateLimiter redisReactiveRateLimiter(
                ReactiveStringRedisTemplate redisTemplate,
                ChaosWebReactiveProperties properties) {
            return new RedisReactiveRateLimiter(redisTemplate, properties.getRateLimit().getKeyPrefix());
        }

        @Bean
        @ConditionalOnBean(ReactiveStringRedisTemplate.class)
        @ConditionalOnMissingBean(ReactiveIdempotentRepository.class)
        public ReactiveIdempotentRepository redisReactiveIdempotentRepository(
                ReactiveStringRedisTemplate redisTemplate,
                ChaosWebReactiveProperties properties) {
            return new RedisReactiveIdempotentRepository(redisTemplate, properties.getIdempotent().getKeyPrefix());
        }
    }

    /**
     * 注册内存响应式限流器（无 Redis 时兜底，生产环境触发安全拦截）。
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveRateLimiter.class)
    @UnsafeForProduction(value = InMemoryReactiveRateLimiter.class,
            message = "chaos-web-reactive: 生产环境不能使用 InMemoryReactiveRateLimiter，请引入 spring-boot-starter-data-redis-reactive")
    public ReactiveRateLimiter inMemoryReactiveRateLimiter(ChaosWebReactiveProperties properties) {
        return new InMemoryReactiveRateLimiter(properties.getRateLimit().getMaxLocalKeys());
    }

    /**
     * 注册响应式限流过滤器。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.web.reactive.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ReactiveRateLimitWebFilter reactiveRateLimitWebFilter(
            ChaosWebReactiveProperties properties,
            ReactiveRateLimiter rateLimiter,
            ObjectProvider<ChaosMetrics> metricsProvider) {
        return new ReactiveRateLimitWebFilter(properties, rateLimiter, metricsProvider.getIfAvailable());
    }

    /**
     * 注册内存响应式幂等仓储（无 Redis 时兜底，生产环境触发安全拦截）。
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveIdempotentRepository.class)
    @UnsafeForProduction(value = InMemoryReactiveIdempotentRepository.class,
            message = "chaos-web-reactive: 生产环境不能使用 InMemoryReactiveIdempotentRepository，请引入 spring-boot-starter-data-redis-reactive")
    public ReactiveIdempotentRepository inMemoryReactiveIdempotentRepository(ChaosWebReactiveProperties properties) {
        return new InMemoryReactiveIdempotentRepository(properties.getIdempotent().getMaxLocalKeys());
    }

    /**
     * 注册响应式幂等校验过滤器。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chaos.web.reactive.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ReactiveIdempotentWebFilter reactiveIdempotentWebFilter(
            ChaosWebReactiveProperties properties,
            ReactiveIdempotentRepository repository,
            ObjectProvider<ChaosMetrics> metricsProvider) {
        return new ReactiveIdempotentWebFilter(properties, repository, metricsProvider.getIfAvailable());
    }

    /**
     * 注册生产安全检查器，阻止仅限开发实现的内存响应式限流/幂等流入生产。
     */
    @Bean
    @ConditionalOnMissingBean(name = "chaosWebReactiveProductionSafetyChecker")
    public SmartInitializingSingleton chaosWebReactiveProductionSafetyChecker(
            Environment environment,
            ConfigurableListableBeanFactory beanFactory) {
        return new ProductionSafetyEnforcer(environment, beanFactory);
    }
}
