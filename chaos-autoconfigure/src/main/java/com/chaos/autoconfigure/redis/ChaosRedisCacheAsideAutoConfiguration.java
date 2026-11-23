package com.chaos.autoconfigure.redis;

import com.chaos.redis.cache.CacheAsideTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式 {@link CacheAsideTemplate} 自动装配，必须在 {@link ChaosRedisAutoConfiguration} 与 Spring Boot
 * {@code CacheAutoConfiguration} 之后执行，以便复用两级缓存或默认 {@link CacheManager}。
 */
@AutoConfiguration(
        after = ChaosRedisAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration")
@ConditionalOnClass(CacheAsideTemplate.class)
public class ChaosRedisCacheAsideAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CacheManager.class)
    static class CacheAsideConfiguration {

        @Bean
        @ConditionalOnBean(CacheManager.class)
        @ConditionalOnMissingBean
        public CacheAsideTemplate cacheAsideTemplate(CacheManager cacheManager) {
            return new CacheAsideTemplate(cacheManager);
        }
    }
}
