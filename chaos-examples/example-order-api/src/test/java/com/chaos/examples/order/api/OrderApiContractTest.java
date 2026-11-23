package com.chaos.examples.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@code example-order-api} 对外契约 DTO 与错误码测试。
 */
class OrderApiContractTest {

    @Test
    void shouldExposeErrorCodesAndOrderDto() {
        assertThat(OrderErrorCode.ORDER_AMOUNT_INVALID.code()).isEqualTo("ORDER_400");
        assertThat(OrderErrorCode.ORDER_AMOUNT_INVALID.message()).isEqualTo("order amount must be greater than 0");
        assertThat(OrderErrorCode.ORDER_NOT_FOUND.code()).isEqualTo("ORDER_404");
        assertThat(OrderErrorCode.ORDER_NOT_FOUND.message()).isEqualTo("order not found");

        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
        OrderDto dto = new OrderDto(10L, "ORD-10", "tenant-a", "buyer-1", new BigDecimal("88.50"), "CREATED", now);
        assertThat(dto.id()).isEqualTo(10L);
        assertThat(dto.orderNo()).isEqualTo("ORD-10");
        assertThat(dto.tenantId()).isEqualTo("tenant-a");
        assertThat(dto.buyerId()).isEqualTo("buyer-1");
        assertThat(dto.amount()).isEqualByComparingTo("88.50");
        assertThat(dto.status()).isEqualTo("CREATED");
        assertThat(dto.createdAt()).isEqualTo(now);
    }

    @Test
    void shouldValidateCreateOrderApiRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            CreateOrderApiRequest valid = new CreateOrderApiRequest("ORD-1", "buyer-1", new BigDecimal("1.00"));
            assertThat(validator.validate(valid)).isEmpty();

            CreateOrderApiRequest invalid = new CreateOrderApiRequest("", "", BigDecimal.ZERO);
            assertThat(validator.validate(invalid)).hasSize(3);
        }
    }
}
