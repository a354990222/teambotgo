package com.chaos.redis.cache;

/**
 * 两级缓存 L1 失效广播发布端口。
 */
@FunctionalInterface
public interface TwoLevelCacheInvalidator {

    /**
     * 向同应用其他实例广播一次 L1 缓存失效事件。
     *
     * @param message 失效广播消息
     */
    void publish(TwoLevelCacheInvalidationMessage message);

    /**
     * 不执行跨实例广播的空实现。
     */
    static TwoLevelCacheInvalidator noop() {
        return message -> {
        };
    }
}
