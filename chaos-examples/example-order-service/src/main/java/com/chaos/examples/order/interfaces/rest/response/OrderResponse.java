package com.chaos.examples.order.interfaces.rest.response;

import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单接口响应模型。
 *
 * @param id 订单 ID
 * @param orderNo 订单编号
 * @param tenantId 租户 ID
 * @param buyerId 买家 ID
 * @param amount 订单金额
 * @param status 订单状态
 * @param createdAt 创建时间
 */
public record OrderResponse(
        Long id,
        String orderNo,
        String tenantId,
        String buyerId,
        BigDecimal amount,
        OrderStatus status,
        LocalDateTime createdAt
) {

    /**
     * 从应用层结果转换。
     */
    public static OrderResponse from(OrderResult result) {
        return new OrderResponse(
                result.id(),
                result.orderNo(),
                result.tenantId(),
                result.buyerId(),
                result.amount(),
                result.status(),
                result.createdAt());
    }
}
