package com.chaos.examples.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chaos.domain.dto.PageResult;
import com.chaos.examples.order.application.OrderApplicationService;
import com.chaos.examples.order.application.query.OrderPageQuery;
import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.Order;
import com.chaos.examples.order.domain.OrderCreatedEvent;
import com.chaos.examples.order.domain.OrderStatus;
import com.chaos.examples.order.infrastructure.messaging.LoggingMessagePublisher;
import com.chaos.examples.order.infrastructure.persistence.converter.OrderConverter;
import com.chaos.examples.order.infrastructure.persistence.mapper.OrderMapper;
import com.chaos.examples.order.infrastructure.persistence.po.OrderPO;
import com.chaos.examples.order.infrastructure.persistence.repository.OrderRepositoryImpl;
import com.chaos.examples.order.interfaces.rest.OrderController;
import com.chaos.examples.order.interfaces.rest.request.CreateOrderRequest;
import com.chaos.examples.order.interfaces.rest.response.OrderResponse;
import com.chaos.mq.MessageEnvelope;
import com.chaos.security.api.auth.LoginUser;
import com.chaos.tenant.TenantStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 订单示例各层组件单元测试。
 */
class OrderComponentsAndControllerTest {

    @Test
    void shouldCoverOrderControllerAndDtos() {
        OrderApplicationService appService = mock(OrderApplicationService.class);
        OrderResult sample = new OrderResult(1L, "ORD-1", "tenant-a", "u1", new BigDecimal("19.90"),
                OrderStatus.CREATED, LocalDateTime.now());
        when(appService.pageOrders(new OrderPageQuery(1, 10)))
                .thenReturn(new PageResult<>(List.of(sample), 1, 1, 10));
        when(appService.createOrder(any())).thenReturn(sample);

        OrderController controller = new OrderController(appService);
        assertThat(controller.ping()).containsEntry("status", "ok").containsKey("time");

        PageResult<OrderResponse> page = controller.pageOrders(1, 10);
        assertThat(page.records()).singleElement().extracting(OrderResponse::orderNo).isEqualTo("ORD-1");

        OrderResponse created = controller.createOrder(new CreateOrderRequest("ORD-1", "u1", new BigDecimal("19.90")));
        assertThat(created.id()).isEqualTo(1L);
        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void shouldCoverPersistenceConverterAndRepository() {
        OrderMapper mapper = mock(OrderMapper.class);
        OrderRepositoryImpl repository = new OrderRepositoryImpl(mapper);

        Order domainOrder = Order.create("ORD-2", "tenant-a", "u2", new BigDecimal("88.00"));
        OrderPO converted = OrderConverter.toPO(domainOrder);
        assertThat(converted.getOrderNo()).isEqualTo("ORD-2");
        assertThat(converted.getStatus()).isEqualTo(OrderStatus.CREATED);

        when(mapper.insert(any(OrderPO.class))).thenAnswer(inv -> {
            OrderPO arg = inv.getArgument(0);
            arg.setId(99L);
            arg.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
            return 1;
        });

        Order saved = repository.save(domainOrder);
        assertThat(saved.id()).isEqualTo(99L);
        assertThat(saved.createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0));

        Page<OrderPO> mpPage = new Page<>(1, 5);
        mpPage.setTotal(1);
        mpPage.setRecords(List.of(converted));
        converted.setId(99L);
        converted.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        when(mapper.selectPage(any(), any())).thenReturn(mpPage);

        PageResult<OrderResult> page = repository.pageOrders(new OrderPageQuery(1, 5));
        assertThat(page.records()).singleElement().extracting(OrderResult::id).isEqualTo(99L);
    }

    @Test
    void shouldCoverExampleOrderConfigurationAndLoggingPublisher() {
        ExampleOrderConfiguration config = new ExampleOrderConfiguration();
        var tenantProvider = config.tenantStatusProvider();
        assertThat(tenantProvider.get("tenant-a").status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenantProvider.get("tenant-frozen").status()).isEqualTo(TenantStatus.FROZEN);
        assertThat(tenantProvider.get("other").status()).isEqualTo(TenantStatus.UNKNOWN);

        LoginUser admin = new LoginUser("1", "admin", "tenant-a", Set.of("admin"), Set.of());
        LoginUser user = new LoginUser("2", "bob", "tenant-a", Set.of("user"), Set.of());
        assertThat(config.dataScopeProvider(Optional::empty).condition("other")).isEmpty();
        assertThat(config.dataScopeProvider(Optional::empty).condition("order")).isEmpty();
        assertThat(config.dataScopeProvider(() -> Optional.of(admin)).condition("order"))
                .isPresent().get().extracting(c -> c.rules().getFirst().column()).isEqualTo("tenant_id");
        assertThat(config.dataScopeProvider(() -> Optional.of(user)).condition("order"))
                .isPresent().get().extracting(c -> c.rules().getFirst().column()).isEqualTo("buyer_id");

        LoggingMessagePublisher publisher = (LoggingMessagePublisher) config.exampleMessagePublisher();
        publisher.publish(new MessageEnvelope<>("m1", OrderCreatedEvent.TOPIC, "tag", "{}", Map.of(), null));
    }
}
