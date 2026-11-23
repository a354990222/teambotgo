package com.chaos.examples.order.application.command;

import java.math.BigDecimal;

/**
 * 创建订单命令。
 *
 * @param orderNo 订单编号
 * @param buyerId 买家 ID
 * @param amount 订单金额
 */
public record CreateOrderCommand(String orderNo, String buyerId, BigDecimal amount) {
}
