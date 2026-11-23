package com.chaos.autoconfigure.redis;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Redis starter 配置属性。
 */
@Validated
@ConfigurationProperties(prefix = "chaos.redis")
public class ChaosRedisProperties {

    /**
     * 幂等、分布式锁、限流、缓存 key、布隆过滤器和延迟队列的统一 key 前缀。
     *
     * <p>多个服务共用同一个 Redis 时强烈建议设置为应用名（例如 {@code ${spring.application.name}}），
     * 否则同名 key 会互相冲突。默认空字符串，与旧版本 key 保持兼容；修改前缀后旧 key 不再生效，
     * 请在发布窗口内评估锁互斥和延迟队列存量数据。JWT 黑名单需要跨服务共享，不受该前缀影响。</p>
     */
    private String keyPrefix = "";

    /**
     * Caffeine（L1）+ Redis（L2）两级缓存配置。
     */
    @Valid
    @NotNull(message = "chaos.redis.two-level-cache must not be null")
    private TwoLevelCache twoLevelCache = new TwoLevelCache();

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    public TwoLevelCache getTwoLevelCache() {
        return twoLevelCache;
    }

    public void setTwoLevelCache(TwoLevelCache twoLevelCache) {
        this.twoLevelCache = twoLevelCache == null ? new TwoLevelCache() : twoLevelCache;
    }

    /**
     * 本地 Caffeine + Redis 两级缓存配置。
     */
    public static class TwoLevelCache {

        /**
         * 是否启用 Caffeine（L1）+ Redis（L2）两级缓存。默认关闭，需显式开启。
         */
        private boolean enabled;

        /**
         * 每个缓存命名空间在本地 L1（Caffeine）中保留的最大条目数。
         */
        @Min(value = 1, message = "chaos.redis.two-level-cache.l1-max-size must be >= 1")
        private long l1MaxSize = 10_000;

        /**
         * 本地 L1（Caffeine）条目的写入过期时间。
         */
        @NotNull(message = "chaos.redis.two-level-cache.l1-ttl must not be null")
        private Duration l1Ttl = Duration.ofMinutes(5);

        /**
         * 共享 L2（Redis）条目的默认过期时间。
         */
        @NotNull(message = "chaos.redis.two-level-cache.l2-ttl must not be null")
        private Duration l2Ttl = Duration.ofMinutes(30);

        /**
         * 是否缓存 {@code null} 查询结果以防止缓存穿透。
         */
        private boolean cacheNullValues = true;

        /**
         * 是否开启按 key 分段单飞加载以防止热点缓存击穿。
         */
        private boolean singleFlight = true;

        /**
         * 是否通过 Redis Pub/Sub 向同应用其他实例广播 L1 本地缓存失效通知。
         */
        private boolean broadcastInvalidation = true;

        /**
         * 两级缓存跨实例 L1 失效广播使用的 Redis Pub/Sub 通道名（配置 {@code chaos.redis.key-prefix} 时自动叠加前缀）。
         */
        @NotBlank(message = "chaos.redis.two-level-cache.invalidation-topic must not be blank")
        private String invalidationTopic = "chaos:cache:invalidation";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getL1MaxSize() {
            return l1MaxSize;
        }

        public void setL1MaxSize(long l1MaxSize) {
            this.l1MaxSize = l1MaxSize;
        }

        public Duration getL1Ttl() {
            return l1Ttl;
        }

        public void setL1Ttl(Duration l1Ttl) {
            this.l1Ttl = l1Ttl == null ? Duration.ofMinutes(5) : l1Ttl;
        }

        public Duration getL2Ttl() {
            return l2Ttl;
        }

        public void setL2Ttl(Duration l2Ttl) {
            this.l2Ttl = l2Ttl == null ? Duration.ofMinutes(30) : l2Ttl;
        }

        public boolean isCacheNullValues() {
            return cacheNullValues;
        }

        public void setCacheNullValues(boolean cacheNullValues) {
            this.cacheNullValues = cacheNullValues;
        }

        public boolean isSingleFlight() {
            return singleFlight;
        }

        public void setSingleFlight(boolean singleFlight) {
            this.singleFlight = singleFlight;
        }

        public boolean isBroadcastInvalidation() {
            return broadcastInvalidation;
        }

        public void setBroadcastInvalidation(boolean broadcastInvalidation) {
            this.broadcastInvalidation = broadcastInvalidation;
        }

        public String getInvalidationTopic() {
            return invalidationTopic;
        }

        public void setInvalidationTopic(String invalidationTopic) {
            this.invalidationTopic = (invalidationTopic == null || invalidationTopic.isBlank())
                    ? "chaos:cache:invalidation"
                    : invalidationTopic.trim();
        }
    }
}
