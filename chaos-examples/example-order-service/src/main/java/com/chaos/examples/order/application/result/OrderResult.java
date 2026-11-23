package com.chaos.examples.order.application.result;

import com.chaos.examples.order.domain.Order;
import com.chaos.examples.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单摘要读模型。
 *
 * @param id 订单 ID
 * @param orderNo 订单编号
 * @param tenantId 租户 ID
 * @param buyerId 买家 ID
 * @param amount 订单金额
 * @param status 订单状态
 * @param createdAt 创建时间
 */
public record OrderResult(
        Long id,
        String orderNo,
        String tenantId,
        String buyerId,
        BigDecimal amount,
        OrderStatus status,
        LocalDateTime createdAt
) {

    /**
     * 从聚合根转换。
     */
    public static OrderResult from(Order order) {
        return new OrderResult(
                order.id(),
                order.orderNo(),
                order.tenantId(),
                order.buyerId(),
                order.amount(),
                order.status(),
                order.createdAt());
    }
}
