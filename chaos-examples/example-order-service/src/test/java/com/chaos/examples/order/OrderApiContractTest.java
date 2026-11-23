package com.chaos.examples.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.result.ApiResult;
import com.chaos.domain.dto.PageResult;
import com.chaos.examples.order.api.OrderApiClient;
import com.chaos.examples.order.api.OrderDto;
import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.domain.OrderStatus;
import com.chaos.examples.order.interfaces.rest.response.OrderResponse;
import com.chaos.http.client.feign.ChaosFeignResultDecoder;
import com.chaos.web.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.Request;
import feign.Response;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 example-order-service REST 响应与 example-order-api OpenFeign 契约兼容。
 */
class OrderApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ChaosFeignResultDecoder decoder = new ChaosFeignResultDecoder(
            (response, type) -> null,
            objectMapper,
            (code, message) -> new ChaosException(
                    com.chaos.core.exception.CommonErrorCode.BAD_REQUEST, message));

    @Test
    void feignContractShouldDecodeOrderResponseEnvelope() throws Exception {
        OrderResponse serverResponse = OrderResponse.from(new OrderResult(
                10L, "ORD-001", "tenant-a", "buyer-1", new BigDecimal("88.50"),
                OrderStatus.CREATED, LocalDateTime.of(2026, 5, 25, 10, 0)));
        String json = objectMapper.writeValueAsString(Result.success(serverResponse));

        Method createMethod = OrderApiClient.class.getMethod(
                "createOrder", com.chaos.examples.order.api.CreateOrderApiRequest.class);
        Object decoded = decoder.decode(httpResponse(json), createMethod.getGenericReturnType());

        assertThat(decoded).isInstanceOfSatisfying(OrderDto.class, dto -> {
            assertThat(dto.id()).isEqualTo(10L);
            assertThat(dto.orderNo()).isEqualTo("ORD-001");
            assertThat(dto.status()).isEqualTo("CREATED");
            assertThat(dto.amount()).isEqualByComparingTo("88.50");
        });
    }

    @Test
    void feignContractShouldDecodePageOrderResponseEnvelope() throws Exception {
        OrderResponse serverResponse = OrderResponse.from(new OrderResult(
                11L, "ORD-002", "tenant-a", "buyer-2", new BigDecimal("19.90"),
                OrderStatus.PAID, LocalDateTime.of(2026, 5, 25, 11, 0)));
        PageResult<OrderResponse> page = new PageResult<>(List.of(serverResponse), 1, 1, 10);
        String json = objectMapper.writeValueAsString(Result.success(page));

        Method pageMethod = OrderApiClient.class.getMethod("pageOrders", long.class, long.class);
        Object decoded = decoder.decode(httpResponse(json), pageMethod.getGenericReturnType());

        assertThat(decoded).isInstanceOf(PageResult.class);
        PageResult<?> result = (PageResult<?>) decoded;
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).singleElement().isInstanceOfSatisfying(OrderDto.class, dto ->
                assertThat(dto.orderNo()).isEqualTo("ORD-002"));
    }

    private static Response httpResponse(String body) {
        Request request = Request.create(
                Request.HttpMethod.GET, "http://example-order-service/api/orders",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder()
                .status(200)
                .request(request)
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(body, StandardCharsets.UTF_8)
                .build();
    }
}
