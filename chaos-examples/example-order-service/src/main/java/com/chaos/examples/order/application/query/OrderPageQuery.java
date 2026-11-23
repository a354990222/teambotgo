package com.chaos.examples.order.application.query;

/**
 * 订单分页查询条件。
 *
 * @param current 当前页码
 * @param size 每页大小
 */
public record OrderPageQuery(long current, long size) {
}
