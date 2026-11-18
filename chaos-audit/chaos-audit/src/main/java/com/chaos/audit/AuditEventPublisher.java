package com.chaos.audit;

/**
 * 审计事件发布器。
 *
 * <p>框架只依赖该端口；业务侧可以替换为数据库、Kafka、RocketMQ 或专用审计中心实现。</p>
 */
@FunctionalInterface
public interface AuditEventPublisher {

    /**
     * 发布审计事件。
     *
     * @param event 审计事件
     */
    void publish(AuditEvent event);

    /**
     * 发布并报告实际结果；允许降级的实现必须在未保存时返回 false，供异步包装器正确计数。
     * 默认实现适用于失败时抛异常的同步发布器，不表示端到端消息投递确认。
     */
    default boolean publishWithOutcome(AuditEvent event) {
        publish(event);
        return true;
    }
}
