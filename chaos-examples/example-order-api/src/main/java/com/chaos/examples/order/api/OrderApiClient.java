package com.chaos.examples.order.api;

import com.chaos.domain.dto.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务对外 OpenFeign 客户端契约。
 *
 * <p>消费方服务引入 {@code chaos-cloud-starter} 与 {@code example-order-api} 后，
 * 配合 {@code ChaosFeignResultDecoder} 可自动解包服务端 {@code Result<T>} 统一响应。</p>
 */
@FeignClient(name = "example-order-service", path = "/api/orders")
public interface OrderApiClient {

    /**
     * 分页查询当前租户订单。
     */
    @GetMapping
    PageResult<OrderDto> pageOrders(
            @RequestParam(name = "current", defaultValue = "1") @Positive long current,
            @RequestParam(name = "size", defaultValue = "10") @Positive @Max(100) long size);

    /**
     * 创建订单。
     */
    @PostMapping
    OrderDto createOrder(@Valid @RequestBody CreateOrderApiRequest request);
}
