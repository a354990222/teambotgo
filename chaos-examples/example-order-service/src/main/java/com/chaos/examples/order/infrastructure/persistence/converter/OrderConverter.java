package com.chaos.examples.order.infrastructure.persistence.converter;

import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.Order;
import com.chaos.examples.order.infrastructure.persistence.po.OrderPO;

/**
 * 订单领域对象与持久化对象转换器。
 */
public final class OrderConverter {

    private OrderConverter() {
    }

    /**
     * 聚合根转持久化对象。
     */
    public static OrderPO toPO(Order order) {
        OrderPO po = new OrderPO();
        po.setId(order.id());
        po.setOrderNo(order.orderNo());
        po.setTenantId(order.tenantId());
        po.setBuyerId(order.buyerId());
        po.setAmount(order.amount());
        po.setStatus(order.status());
        return po;
    }

    /**
     * 持久化对象转读模型结果。
     */
    public static OrderResult toResult(OrderPO po) {
        return new OrderResult(
                po.getId(),
                po.getOrderNo(),
                po.getTenantId(),
                po.getBuyerId(),
                po.getAmount(),
                po.getStatus(),
                po.getCreatedAt()
        );
    }
}
