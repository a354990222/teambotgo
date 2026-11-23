package com.chaos.web.reactive.idempotent;

import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.metrics.ChaosMeterNames;
import com.chaos.core.metrics.ChaosMetrics;
import com.chaos.core.metrics.NoopChaosMetrics;
import com.chaos.web.reactive.config.ChaosWebReactiveProperties;
import com.chaos.web.reactive.filter.ReactiveErrorResponseWriter;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 响应式请求幂等校验过滤器。
 *
 * <p>当配置的变更方法（默认 POST/PUT/PATCH/DELETE）请求携带 {@code Idempotency-Key}（或自定义头名）时，
 * 进行原子防重占位；若命中重复提交则返回 {@code 409} 统一 {@code ApiResult} JSON，若下游处理抛出异常则释放占位键。</p>
 */
public class ReactiveIdempotentWebFilter implements WebFilter, Ordered {

    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private final ChaosWebReactiveProperties properties;
    private final ReactiveIdempotentRepository repository;
    private final ChaosMetrics metrics;

    /**
     * 创建响应式幂等过滤器。
     */
    public ReactiveIdempotentWebFilter(
            ChaosWebReactiveProperties properties,
            ReactiveIdempotentRepository repository,
            ChaosMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.metrics = metrics == null ? NoopChaosMetrics.instance() : metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ChaosWebReactiveProperties.Idempotent config = properties.getIdempotent();
        if (!config.isEnabled()) {
            return chain.filter(exchange);
        }
        HttpMethod method = exchange.getRequest().getMethod();
        if (method == null || config.getMethods().stream().noneMatch(m -> m.equalsIgnoreCase(method.name()))) {
            return chain.filter(exchange);
        }
        String key = exchange.getRequest().getHeaders().getFirst(config.getHeaderName());
        if (key == null || key.isBlank()) {
            return chain.filter(exchange);
        }
        String scopedKey = method.name() + ":" + exchange.getRequest().getPath().value() + ":" + key.trim();
        return repository.saveIfAbsent(scopedKey, config.getTtl())
                .flatMap(acquired -> {
                    if (!Boolean.TRUE.equals(acquired)) {
                        metrics.increment(ChaosMeterNames.IDEMPOTENT_REJECTED,
                                ChaosMeterNames.TAG_SOURCE, "web-reactive");
                        return ReactiveErrorResponseWriter.write(
                                exchange, HttpStatus.CONFLICT, CommonErrorCode.IDEMPOTENT_REJECTED);
                    }
                    return chain.filter(exchange)
                            .onErrorResume(ex -> repository.remove(scopedKey).then(Mono.error(ex)));
                });
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
