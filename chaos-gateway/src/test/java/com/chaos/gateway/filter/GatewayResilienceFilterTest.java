package com.chaos.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.resilience.ResiliencePolicy;
import com.chaos.core.resilience.ResilienceRegistry;
import com.chaos.gateway.config.ChaosGatewayProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * {@link GatewayResilienceFilter} 与路由级弹性策略单元测试。
 */
class GatewayResilienceFilterTest {

    @Test
    void shouldOpenCircuitOn5xxAndRejectExcessConcurrencyWith429() {
        ChaosGatewayProperties.Resilience props = new ChaosGatewayProperties.Resilience();
        props.setFailureRateThresholdPct(50);
        props.setSlidingWindowSize(2);
        props.setMinimumNumberOfCalls(2);
        props.setWaitDurationInOpenState(Duration.ofSeconds(15));
        ChaosGatewayProperties.RouteResilienceRule slowRule = new ChaosGatewayProperties.RouteResilienceRule();
        slowRule.setCallTimeout(Duration.ofMillis(50));
        props.setRules(Map.of("slow-route", slowRule));

        ResilienceRegistry registry = new ResilienceRegistry(
                "gateway", props.toDefaultPolicy(), props.toPolicyOverrides(), null);
        GatewayResilienceFilter filter = new GatewayResilienceFilter(registry);
        assertThat(filter.getOrder()).isEqualTo(GatewayResilienceFilter.FILTER_ORDER);

        Route orderRoute = Route.async().id("order-route").uri("http://localhost:8081")
                .predicate(ex -> true).build();

        // 连续两次 503 后，第三次直接 503 熔断拒绝（不打到下游）
        AtomicInteger downstreamHits = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            MockServerWebExchange exchange = exchangeWithRoute("/api/orders/1", orderRoute);
            filter.filter(exchange, ex -> {
                downstreamHits.incrementAndGet();
                ex.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return Mono.empty();
            }).block();
        }
        MockServerWebExchange openExchange = exchangeWithRoute("/api/orders/1", orderRoute);
        filter.filter(openExchange, ex -> {
            downstreamHits.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(downstreamHits.get()).isEqualTo(2);
        assertThat(openExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(openExchange.getResponse().getBodyAsString().block()).contains("COMMON_SERVICE_UNAVAILABLE");

        // 超时路由命中 50ms 预算返回 503 JSON
        Route slowRoute = Route.async().id("slow-route").uri("http://localhost:8082")
                .predicate(ex -> true).build();
        MockServerWebExchange slowExchange = exchangeWithRoute("/api/slow", slowRoute);
        filter.filter(slowExchange, ex -> Mono.delay(Duration.ofMillis(200)).then()).block();
        assertThat(slowExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // 并发舱壁满载返回 429 JSON
        ResiliencePolicy bhPolicy = new ResiliencePolicy(
                50, 4, 4, Duration.ofSeconds(10), 2, 1, null);
        GatewayResilienceFilter bhFilter = new GatewayResilienceFilter(new ResilienceRegistry("gateway", bhPolicy));
        Route bhRoute = Route.async().id("bh-route").uri("http://localhost:8083")
                .predicate(ex -> true).build();
        ServerWebExchange first = exchangeWithRoute("/api/bh", bhRoute);
        ServerWebExchange second = exchangeWithRoute("/api/bh", bhRoute);
        bhFilter.filter(first, ex -> bhFilter.filter(second, inner -> Mono.empty())).block();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private static MockServerWebExchange exchangeWithRoute(String path, Route route) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }
}
