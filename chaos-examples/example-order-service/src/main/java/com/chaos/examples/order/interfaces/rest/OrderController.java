package com.chaos.examples.order.interfaces.rest;

import com.chaos.domain.dto.PageResult;
import com.chaos.examples.order.application.OrderApplicationService;
import com.chaos.examples.order.application.command.CreateOrderCommand;
import com.chaos.examples.order.application.query.OrderPageQuery;
import com.chaos.examples.order.application.result.OrderResult;
import com.chaos.examples.order.interfaces.rest.request.CreateOrderRequest;
import com.chaos.examples.order.interfaces.rest.response.OrderResponse;
import com.chaos.security.annotation.AccessAttribute;
import com.chaos.security.annotation.RequireAccess;
import com.chaos.web.idempotent.Idempotent;
import com.chaos.web.ratelimit.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口适配器。
 */
@Validated
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    /**
     * 创建订单接口适配器。
     */
    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    /**
     * 健康探测接口，便于不带 token 验证服务是否启动。
     */
    @GetMapping("/ping")
    @RateLimit(permitsPerSecond = 50)
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "time", Instant.now());
    }

    /**
     * 分页查询当前租户订单。
     *
     * <p>接口层限制单页最多 100 条，MyBatis 分页插件的 max-limit 作为兜底。</p>
     */
    @GetMapping
    @RateLimit(permitsPerSecond = 20)
    @RequireAccess(action = "order:read", resourceType = "order")
    public PageResult<OrderResponse> pageOrders(
            @RequestParam(defaultValue = "1") @Positive long current,
            @RequestParam(defaultValue = "10") @Positive @Max(100) long size) {
        return orderApplicationService.pageOrders(new OrderPageQuery(current, size))
                .map(OrderResponse::from);
    }

    /**
     * 创建当前租户订单。
     *
     * <p>演示 ABAC：动作要求 {@code order:create} 权限，同时把订单金额作为资源属性交给授权策略，
     * 大额订单的拒绝规则写在 {@code chaos.security.access.policies} 配置里，不需要改代码。</p>
     */
    @PostMapping
    @Idempotent
    @RequireAccess(
            action = "order:create",
            resourceType = "order",
            resourceId = "#request.orderNo",
            attributes = {
                    @AccessAttribute(name = "amount", value = "#request.amount"),
                    @AccessAttribute(name = "buyerId", value = "#request.buyerId")
            })
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResult result = orderApplicationService.createOrder(new CreateOrderCommand(
                request.orderNo(),
                request.buyerId(),
                request.amount()
        ));
        return OrderResponse.from(result);
    }
}
