package com.chaos.examples.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.core.exception.BizException;
import com.chaos.domain.dto.PageResult;
import com.chaos.examples.order.application.command.CreateOrderCommand;
import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.Order;
import com.chaos.examples.order.domain.OrderCreatedEvent;
import com.chaos.mq.MessageEnvelope;
import com.chaos.security.api.auth.LoginUser;
import com.chaos.service.transaction.TransactionExecutor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 订单应用服务测试。
 */
class OrderApplicationServiceTest {

    private final List<MessageEnvelope<?>> outbox = new ArrayList<>();

    private final List<Order> saved = new ArrayList<>();

    private final AtomicReference<LoginUser> currentUser = new AtomicReference<>();

    private final OrderApplicationService service = new OrderApplicationService(
            order -> {
                order.markPersisted(100L, LocalDateTime.now());
                saved.add(order);
                return order;
            },
            query -> new PageResult<>(List.of(), 0, query.current(), query.size()),
            outbox::add,
            new DirectTransactionExecutor(),
            () -> Optional.ofNullable(currentUser.get()));

    @AfterEach
    void clearLoginUser() {
        currentUser.set(null);
    }

    /**
     * 创建订单时租户取自登录用户，并在事务内写入 outbox。
     */
    @Test
    void shouldUseAuthenticatedTenantAndWriteOutbox() {
        currentUser.set(new LoginUser("10001", "alice", "tenant-a", Set.of("user"), Set.of("order:create")));

        OrderResult summary = service.createOrder(
                new CreateOrderCommand("A-100", "10001", new BigDecimal("9.90")));

        assertThat(summary.id()).isEqualTo(100L);
        assertThat(summary.tenantId()).isEqualTo("tenant-a");
        assertThat(outbox).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo(OrderCreatedEvent.TOPIC);
            assertThat(message.messageId()).isNotBlank();
        });
        assertThat(saved.getFirst().domainEvents()).isEmpty();
    }

    /**
     * 未登录时拒绝创建订单。
     */
    @Test
    void shouldRejectUnauthenticatedCreation() {
        assertThatThrownBy(() -> service.createOrder(
                new CreateOrderCommand("A-101", "10001", BigDecimal.ONE)))
                .isInstanceOf(BizException.class);
        assertThat(outbox).isEmpty();
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {

        @Override
        public <T> T execute(Supplier<T> action) {
            return action.get();
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }
    }
}
