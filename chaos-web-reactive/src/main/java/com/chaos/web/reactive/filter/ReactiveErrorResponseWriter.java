package com.chaos.web.reactive.filter;

import com.chaos.core.exception.ErrorCode;
import com.chaos.core.result.ApiResultJson;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * WebFlux 过滤器边界统一 {@code ApiResult} JSON 错误响应写入器。
 */
public final class ReactiveErrorResponseWriter {

    private static final String BUNDLE = "com.chaos.core.i18n.messages.chaos-core";
    private static final ConcurrentHashMap<Locale, Optional<ResourceBundle>> BUNDLES = new ConcurrentHashMap<>();

    private ReactiveErrorResponseWriter() {
    }

    /**
     * 向响应写入统一格式的 JSON 错误结构。
     */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = ApiResultJson.failure(
                errorCode, localize(errorCode, exchange), ReactiveRequestContext.traceId(exchange));
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * 按请求 {@code Accept-Language} 从 {@code chaos-core} 资源包解析提示文案。
     */
    public static String localize(ErrorCode errorCode, ServerWebExchange exchange) {
        String key = errorCode.messageKey();
        if (key == null || key.isBlank()) {
            return errorCode.message();
        }
        Locale locale = resolveLocale(exchange);
        return BUNDLES.computeIfAbsent(locale, ReactiveErrorResponseWriter::loadBundle)
                .filter(bundle -> bundle.containsKey(key))
                .map(bundle -> bundle.getString(key))
                .orElseGet(errorCode::message);
    }

    private static Locale resolveLocale(ServerWebExchange exchange) {
        if (exchange == null) {
            return Locale.ROOT;
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE);
        if (header == null || header.isBlank()) {
            return Locale.ROOT;
        }
        return Optional.ofNullable(exchange.getLocaleContext().getLocale()).orElse(Locale.ROOT);
    }

    private static Optional<ResourceBundle> loadBundle(Locale locale) {
        try {
            return Optional.of(ResourceBundle.getBundle(
                    BUNDLE,
                    locale,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)));
        } catch (java.util.MissingResourceException ex) {
            return Optional.empty();
        }
    }
}
