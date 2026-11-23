package com.chaos.web.reactive.xss;

import com.chaos.core.exception.CommonErrorCode;
import com.chaos.web.reactive.filter.ReactiveErrorResponseWriter;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 响应式 XSS 防护与基础安全响应头过滤器。
 */
public class ReactiveSecurityWebFilter implements WebFilter, Ordered {

    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    private static final List<String> DANGEROUS_PATTERNS = List.of(
            "<script", "</script", "javascript:", "onerror=", "onload=");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (hasDangerousQueryParam(exchange)) {
            return ReactiveErrorResponseWriter.write(
                    exchange, HttpStatus.BAD_REQUEST, CommonErrorCode.BAD_REQUEST);
        }
        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        setHeaderIfAbsent(responseHeaders, "X-Content-Type-Options", "nosniff");
        setHeaderIfAbsent(responseHeaders, "X-Frame-Options", "DENY");
        setHeaderIfAbsent(responseHeaders, "Referrer-Policy", "no-referrer");
        return chain.filter(exchange);
    }

    private static void setHeaderIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    private static boolean hasDangerousQueryParam(ServerWebExchange exchange) {
        return exchange.getRequest().getQueryParams().values().stream()
                .flatMap(List::stream)
                .anyMatch(ReactiveSecurityWebFilter::containsDangerousFragment);
    }

    static boolean containsDangerousFragment(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String pattern : DANGEROUS_PATTERNS) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
