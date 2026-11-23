package com.chaos.examples.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chaos.domain.dto.PageResult;
import com.chaos.examples.order.application.port.OrderQueryRepository;
import com.chaos.examples.order.application.query.OrderPageQuery;
import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.Order;
import com.chaos.examples.order.domain.OrderRepository;
import com.chaos.examples.order.infrastructure.persistence.converter.OrderConverter;
import com.chaos.examples.order.infrastructure.persistence.mapper.OrderMapper;
import com.chaos.examples.order.infrastructure.persistence.po.OrderPO;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis Plus 的订单仓储实现，同时实现写模型仓储和读模型查询端口。
 */
@Repository
public class OrderRepositoryImpl implements OrderRepository, OrderQueryRepository {

    private final OrderMapper orderMapper;

    /**
     * 创建订单仓储。
     */
    public OrderRepositoryImpl(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 分页查询；单页上限由 chaos.mybatis.pagination.max-limit 兜底。
     */
    @Override
    public PageResult<OrderResult> pageOrders(OrderPageQuery query) {
        Page<OrderPO> page = orderMapper.selectPage(
                Page.of(query.current(), query.size()),
                new LambdaQueryWrapper<OrderPO>().orderByDesc(OrderPO::getId)
        );
        List<OrderResult> records = page.getRecords().stream().map(OrderConverter::toResult).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 插入订单并回填主键和审计字段。
     */
    @Override
    public Order save(Order order) {
        OrderPO po = OrderConverter.toPO(order);
        orderMapper.insert(po);
        order.markPersisted(po.getId(), po.getCreatedAt());
        return order;
    }
}
