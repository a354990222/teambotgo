package com.chaos.redis.cache;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis Pub/Sub 的两级缓存 L1 跨实例失效同步桥接器。
 *
 * <p>写/淘汰/清空事件通过 {@link StringRedisTemplate#convertAndSend(String, Object)} 广播到指定 topic；
 * 订阅端收到消息并解码后交由本地回调执行本节点 L1 淘汰。</p>
 */
public class RedisTwoLevelCacheInvalidationBridge implements TwoLevelCacheInvalidator, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisTwoLevelCacheInvalidationBridge.class);

    private final StringRedisTemplate redisTemplate;
    private final String topic;
    private final Consumer<TwoLevelCacheInvalidationMessage> localConsumer;

    /**
     * 创建 Redis Pub/Sub 失效同步桥接器。
     *
     * @param redisTemplate 字符串 Redis 模板
     * @param topic 广播通道名称
     * @param localConsumer 本地 L1 失效消费逻辑
     */
    public RedisTwoLevelCacheInvalidationBridge(
            StringRedisTemplate redisTemplate,
            String topic,
            Consumer<TwoLevelCacheInvalidationMessage> localConsumer) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.topic = topic;
        this.localConsumer = Objects.requireNonNull(localConsumer, "localConsumer must not be null");
    }

    /**
     * 返回广播使用的 Redis Pub/Sub 通道名。
     */
    public String getTopic() {
        return topic;
    }

    @Override
    public void publish(TwoLevelCacheInvalidationMessage message) {
        if (message == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(topic, message.encode());
        } catch (RuntimeException ex) {
            log.warn("Failed to broadcast L1 cache invalidation on topic={}, cache={}: {}",
                    topic, message.cacheName(), ex.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        Optional<TwoLevelCacheInvalidationMessage> decoded = TwoLevelCacheInvalidationMessage.decode(payload);
        if (decoded.isEmpty()) {
            log.debug("Ignored malformed two-level cache invalidation payload on topic={}", topic);
            return;
        }
        try {
            localConsumer.accept(decoded.get());
        } catch (RuntimeException ex) {
            log.warn("Failed to apply L1 cache invalidation for cache={}: {}",
                    decoded.get().cacheName(), ex.getMessage());
        }
    }
}
