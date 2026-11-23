package com.chaos.examples.order.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单跨服务传输 DTO。
 *
 * <p>使用基础类型（如 {@code String status}）而非服务内部领域枚举，避免契约演进时破坏消费方反序列化。</p>
 *
 * @param id 订单 ID
 * @param orderNo 订单编号
 * @param tenantId 租户 ID
 * @param buyerId 买家 ID
 * @param amount 订单金额
 * @param status 订单状态
 * @param createdAt 创建时间
 */
public record OrderDto(
        Long id,
        String orderNo,
        String tenantId,
        String buyerId,
        BigDecimal amount,
        String status,
        LocalDateTime createdAt
) {
}
