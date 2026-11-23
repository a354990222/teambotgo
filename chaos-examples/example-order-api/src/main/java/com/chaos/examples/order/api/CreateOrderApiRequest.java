package com.chaos.examples.order.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 跨服务创建订单请求 DTO。
 *
 * @param orderNo 订单编号
 * @param buyerId 买家 ID
 * @param amount 订单金额
 */
public record CreateOrderApiRequest(
        @NotBlank(message = "orderNo must not be blank") String orderNo,
        @NotBlank(message = "buyerId must not be blank") String buyerId,
        @NotNull @DecimalMin("0.01") BigDecimal amount
) {
}
