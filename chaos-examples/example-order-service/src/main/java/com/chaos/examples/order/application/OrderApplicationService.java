package com.chaos.examples.order.application;

import com.chaos.core.exception.BizException;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.domain.dto.PageResult;
import com.chaos.domain.model.DomainEvent;
import com.chaos.examples.order.application.command.CreateOrderCommand;
import com.chaos.examples.order.application.port.OrderQueryRepository;
import com.chaos.examples.order.application.query.OrderPageQuery;
import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.Order;
import com.chaos.examples.order.domain.OrderCreatedEvent;
import com.chaos.examples.order.domain.OrderRepository;
import com.chaos.mq.MessageEnvelope;
import com.chaos.mq.reliable.OutboxPublisher;
import com.chaos.security.annotation.DataScope;
import com.chaos.security.api.auth.LoginUser;
import com.chaos.security.api.auth.LoginUserProvider;
import com.chaos.service.transaction.TransactionExecutor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 订单应用服务。
 *
 * <p>演示 DDD 应用层职责：数据权限注解、事务边界、调用聚合根、在同一事务内把领域事件写入 outbox。
 * 接口鉴权已在入口层 {@code OrderController} 的 {@code @RequireAccess} 统一收口。</p>
 */
@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;

    private final OrderQueryRepository orderQueryRepository;

    private final OutboxPublisher outboxPublisher;

    private final TransactionExecutor transactionExecutor;

    private final LoginUserProvider loginUserProvider;

    /**
     * 创建订单应用服务。
     */
    public OrderApplicationService(
            OrderRepository orderRepository,
            OrderQueryRepository orderQueryRepository,
            OutboxPublisher outboxPublisher,
            TransactionExecutor transactionExecutor,
            LoginUserProvider loginUserProvider) {
        this.orderRepository = orderRepository;
        this.orderQueryRepository = orderQueryRepository;
        this.outboxPublisher = outboxPublisher;
        this.transactionExecutor = transactionExecutor;
        this.loginUserProvider = loginUserProvider;
    }

    /**
     * 分页查询订单，按数据权限 order 过滤。
     */
    @DataScope("order")
    public PageResult<OrderResult> pageOrders(OrderPageQuery query) {
        return orderQueryRepository.pageOrders(query);
    }

    /**
     * 创建当前登录用户所属租户的订单。
     *
     * <p>租户取自认证结果（JWT claim），而不是客户端请求头，避免伪造 X-Tenant-Id 跨租户写入。</p>
     */
    public OrderResult createOrder(CreateOrderCommand command) {
        LoginUser user = loginUserProvider.currentUser()
                .orElseThrow(() -> new BizException(CommonErrorCode.UNAUTHORIZED));
        if (user.tenantId() == null || user.tenantId().isBlank()) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "当前用户未绑定租户");
        }
        return transactionExecutor.execute(() -> {
            Order order = Order.create(command.orderNo(), user.tenantId(), command.buyerId(), command.amount());
            Order saved = orderRepository.save(order);
            saved.domainEvents().forEach(this::publishToOutbox);
            saved.clearDomainEvents();
            return OrderResult.from(saved);
        });
    }

    private void publishToOutbox(DomainEvent event) {
        if (event instanceof OrderCreatedEvent created) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderNo", created.aggregateId());
            payload.put("tenantId", created.tenantId());
            payload.put("buyerId", created.buyerId());
            payload.put("amount", created.amount());
            outboxPublisher.publish(new MessageEnvelope<>(
                    created.eventId(), OrderCreatedEvent.TOPIC, "created", payload, Map.of(), created.occurredAt()));
        }
    }
}
