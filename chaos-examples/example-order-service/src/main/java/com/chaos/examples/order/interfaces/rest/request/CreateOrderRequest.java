package com.chaos.examples.order.interfaces.rest.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 创建订单请求。
 *
 * @param orderNo 订单编号
 * @param buyerId 买家 ID
 * @param amount 订单金额
 */
public record CreateOrderRequest(
        @NotBlank(message = "订单编号不能为空") String orderNo,
        @NotBlank(message = "买家 ID 不能为空") String buyerId,
        @NotNull(message = "订单金额不能为空") @DecimalMin(value = "0.01", message = "订单金额必须大于 0") BigDecimal amount
) {
}
