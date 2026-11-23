package com.chaos.web.reactive.ratelimit;

import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.metrics.ChaosMeterNames;
import com.chaos.core.metrics.ChaosMetrics;
import com.chaos.core.metrics.NoopChaosMetrics;
import com.chaos.web.reactive.config.ChaosWebReactiveProperties;
import com.chaos.web.reactive.filter.ReactiveErrorResponseWriter;
import java.net.InetSocketAddress;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 响应式全局限流过滤器。
 */
public class ReactiveRateLimitWebFilter implements WebFilter, Ordered {

    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final Logger log = LoggerFactory.getLogger(ReactiveRateLimitWebFilter.class);

    private final ChaosWebReactiveProperties properties;
    private final ReactiveRateLimiter rateLimiter;
    private final ChaosMetrics metrics;

    /**
     * 创建响应式限流过滤器。
     */
    public ReactiveRateLimitWebFilter(
            ChaosWebReactiveProperties properties,
            ReactiveRateLimiter rateLimiter,
            ChaosMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        this.metrics = metrics == null ? NoopChaosMetrics.instance() : metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ChaosWebReactiveProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        for (String skip : config.getSkipPaths()) {
            if (skip != null && !skip.isBlank() && PATH_MATCHER.match(skip, path)) {
                return chain.filter(exchange);
            }
        }
        String key = resolveKey(exchange, path);
        return rateLimiter.tryAcquire(key, config.getDefaultPermitsPerSecond())
                .onErrorResume(ex -> {
                    log.warn("Reactive rate limiter error for key={}: {}", key, ex.getMessage());
                    if (config.isFailOpen()) {
                        metrics.increment(ChaosMeterNames.RATE_LIMIT_ERRORS,
                                ChaosMeterNames.TAG_SOURCE, "web-reactive",
                                ChaosMeterNames.TAG_OUTCOME, "fail-open");
                        return Mono.just(true);
                    }
                    metrics.increment(ChaosMeterNames.RATE_LIMIT_ERRORS,
                            ChaosMeterNames.TAG_SOURCE, "web-reactive",
                            ChaosMeterNames.TAG_OUTCOME, "fail-closed");
                    return Mono.just(false);
                })
                .flatMap(allowed -> {
                    if (Boolean.TRUE.equals(allowed)) {
                        return chain.filter(exchange);
                    }
                    metrics.increment(ChaosMeterNames.RATE_LIMIT_REJECTED,
                            ChaosMeterNames.TAG_SOURCE, "web-reactive",
                            ChaosMeterNames.TAG_DIMENSION, "ip-path");
                    return ReactiveErrorResponseWriter.write(
                            exchange, HttpStatus.TOO_MANY_REQUESTS, CommonErrorCode.TOO_MANY_REQUESTS);
                });
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    private static String resolveKey(ServerWebExchange exchange, String path) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String client = remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
        return client + ":" + path;
    }
}
