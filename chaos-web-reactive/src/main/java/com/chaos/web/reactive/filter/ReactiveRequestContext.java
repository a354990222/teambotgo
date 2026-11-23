package com.chaos.web.reactive.filter;

import com.chaos.core.context.RequestContextSnapshot;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 在 WebFlux 响应式链路中读取 {@link RequestContextSnapshot} 的工具。
 */
public final class ReactiveRequestContext {

    /**
     * {@link ServerWebExchange} 属性中保存当前请求快照的键。
     */
    public static final String EXCHANGE_ATTRIBUTE = RequestContextSnapshot.class.getName();

    private ReactiveRequestContext() {
    }

    /**
     * 从当前 Reactor Context 读取由 {@link ReactiveTraceWebFilter} 写入的请求上下文快照。
     */
    public static Mono<RequestContextSnapshot> current() {
        return Mono.deferContextual(ctx -> ctx.hasKey(RequestContextSnapshot.class)
                ? Mono.just(ctx.get(RequestContextSnapshot.class))
                : Mono.empty());
    }

    /**
     * 从 {@link ServerWebExchange} 读取请求快照（可能为 {@code null}）。
     */
    public static RequestContextSnapshot fromExchange(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        return exchange.getAttribute(EXCHANGE_ATTRIBUTE);
    }

    /**
     * 提取当前请求的 traceId（不存在时返回空字符串）。
     */
    public static String traceId(ServerWebExchange exchange) {
        RequestContextSnapshot snapshot = fromExchange(exchange);
        return snapshot == null || snapshot.traceId() == null ? "" : snapshot.traceId();
    }
}
