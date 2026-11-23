package com.chaos.web.reactive.filter;

import com.chaos.core.constant.ChaosHeaders;
import com.chaos.core.context.RequestContextSnapshot;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 响应式 Trace 与请求上下文过滤器。
 *
 * <p>解析入站 W3C {@code traceparent} / {@code X-Trace-Id} / {@code X-Tenant-Id} / {@code X-User-Id}，
 * 回写响应头 {@code X-Trace-Id}，并通过 Reactor {@code Context} 与 {@link ServerWebExchange} 属性向后续响应式算子传播。</p>
 */
public class ReactiveTraceWebFilter implements WebFilter, Ordered {

    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private final String applicationName;

    /**
     * 创建响应式 Trace 过滤器。
     */
    public ReactiveTraceWebFilter(String applicationName) {
        this.applicationName = applicationName == null ? "" : applicationName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String traceId = firstNonBlank(
                headers.getFirst(ChaosHeaders.TRACE_ID),
                parseTraceParentTraceId(headers.getFirst(ChaosHeaders.TRACEPARENT)),
                generateHex16());
        String spanId = firstNonBlank(headers.getFirst(ChaosHeaders.SPAN_ID), generateHex16());
        String tenantId = firstNonBlank(headers.getFirst(ChaosHeaders.TENANT_ID), "");
        String userId = firstNonBlank(headers.getFirst(ChaosHeaders.USER_ID), "");

        RequestContextSnapshot snapshot = new RequestContextSnapshot(
                traceId, spanId, tenantId, userId, applicationName);
        exchange.getAttributes().put(ReactiveRequestContext.EXCHANGE_ATTRIBUTE, snapshot);
        exchange.getResponse().getHeaders().set(ChaosHeaders.TRACE_ID, traceId);

        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(RequestContextSnapshot.class, snapshot));
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    private static String parseTraceParentTraceId(String traceParent) {
        if (traceParent == null || traceParent.isBlank()) {
            return null;
        }
        String[] parts = traceParent.trim().split("-");
        if (parts.length >= 4 && parts[1].length() == 32) {
            return parts[1];
        }
        return null;
    }

    private static String generateHex16() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String firstNonBlank(String... values) {
        for (String val : values) {
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return "";
    }
}
