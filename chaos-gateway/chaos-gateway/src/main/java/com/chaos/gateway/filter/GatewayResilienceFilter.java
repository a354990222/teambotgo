package com.chaos.gateway.filter;

import com.chaos.core.resilience.ResiliencePolicy;
import com.chaos.core.resilience.ResilienceRegistry;
import com.chaos.core.resilience.SemaphoreBulkhead;
import com.chaos.core.resilience.SlidingWindowCircuitBreaker;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 路由级断路器、并发舱壁与超时预算过滤器。
 *
 * <p>执行顺序位于路由解析（{@code RouteToRequestUrlFilter}）之后、Netty 转发之前，
 * 按路由 ID 独立统计 {@code 5xx}、连接异常与超时失败率；熔断打开或超时时返回统一 {@code 503}
 * {@code ApiResult} JSON，舱壁满载时返回统一 {@code 429} {@code ApiResult} JSON。</p>
 */
public class GatewayResilienceFilter implements GlobalFilter, Ordered {

    /**
     * 位于路由解析（order=10000）之后、Netty 转发（LOWEST_PRECEDENCE）之前。
     */
    public static final int FILTER_ORDER = 10_100;

    private final ResilienceRegistry registry;

    /**
     * 创建 Gateway 弹性过滤器。
     */
    public GatewayResilienceFilter(ResilienceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String resource = resolveRouteKey(exchange);
        ResilienceRegistry.ResourceGuard guard = registry.guard(resource);
        SemaphoreBulkhead bulkhead = guard.bulkhead();
        if (bulkhead != null && !bulkhead.tryAcquire()) {
            registry.recordCall("bulkhead-full");
            return GatewayErrorResponseWriter.tooManyRequests(exchange);
        }

        SlidingWindowCircuitBreaker breaker = guard.circuitBreaker();
        if (!breaker.tryAcquirePermission()) {
            if (bulkhead != null) {
                bulkhead.release();
            }
            registry.recordCall("circuit-open");
            return GatewayErrorResponseWriter.serviceUnavailable(exchange);
        }

        AtomicBoolean outcomeRecorded = new AtomicBoolean(false);
        ResiliencePolicy policy = guard.policy();
        Mono<Void> downstream = chain.filter(exchange);
        if (policy.hasTimeout()) {
            downstream = downstream.timeout(policy.callTimeout());
        }

        return downstream
                .then(Mono.<Void>fromRunnable(() -> {
                    if (outcomeRecorded.compareAndSet(false, true)) {
                        HttpStatusCode status = exchange.getResponse().getStatusCode();
                        if (status != null && status.is5xxServerError()) {
                            breaker.onFailure();
                            registry.recordCall("failure");
                        } else {
                            breaker.onSuccess();
                            registry.recordCall("success");
                        }
                    }
                }))
                .onErrorResume(ex -> {
                    if (outcomeRecorded.compareAndSet(false, true)) {
                        if (ex instanceof TimeoutException) {
                            breaker.onFailure();
                            registry.recordCall("timeout");
                            return GatewayErrorResponseWriter.serviceUnavailable(exchange);
                        }
                        if (isServerError(ex)) {
                            breaker.onFailure();
                            registry.recordCall("failure");
                        } else {
                            breaker.onSuccess();
                        }
                    }
                    return Mono.<Void>error(ex);
                })
                .doFinally(signal -> {
                    if (bulkhead != null) {
                        bulkhead.release();
                    }
                });
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    private static String resolveRouteKey(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && route.getId() != null && !route.getId().isBlank()) {
            return route.getId();
        }
        String path = exchange.getRequest().getURI().getRawPath();
        return (path == null || path.isBlank()) ? "gateway-default" : path;
    }

    private static boolean isServerError(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getStatusCode().is5xxServerError();
        }
        return true;
    }
}
