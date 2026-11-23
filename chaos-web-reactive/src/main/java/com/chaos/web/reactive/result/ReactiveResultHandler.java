package com.chaos.web.reactive.result;

import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.result.ApiResult;
import com.chaos.core.result.DefaultApiResult;
import com.chaos.web.reactive.annotation.IgnoreReactiveWrap;
import com.chaos.web.reactive.filter.ReactiveRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;
import org.springframework.web.reactive.result.method.annotation.ResponseBodyResultHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebFlux 统一 {@link ApiResult} 响应包装处理器。
 *
 * <p>在默认 {@link ResponseBodyResultHandler}（order=100）之前执行，将非 {@link ApiResult}、
 * 非 {@link HttpEntity}、非二进制与非流式控制器返回值统一封装为 {@link ApiResult}；
 * 支持 {@link IgnoreReactiveWrap} 注解与路径白名单跳过包装。</p>
 */
public class ReactiveResultHandler extends ResponseBodyResultHandler {

    private static final MethodParameter API_RESULT_PARAM = resolveTemplateParameter();
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final List<String> excludePaths;

    /**
     * 创建 WebFlux 统一响应包装处理器。
     */
    public ReactiveResultHandler(
            List<HttpMessageWriter<?>> writers,
            RequestedContentTypeResolver resolver,
            ReactiveAdapterRegistry registry,
            List<String> excludePaths) {
        super(writers, resolver, registry);
        this.excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
        setOrder(-1);
    }

    @SuppressWarnings("unused")
    private static Mono<ApiResult<Object>> apiResultTemplateMethod() {
        return Mono.empty();
    }

    private static MethodParameter resolveTemplateParameter() {
        try {
            return new MethodParameter(ReactiveResultHandler.class.getDeclaredMethod("apiResultTemplateMethod"), -1);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Failed to resolve ApiResult template MethodParameter", ex);
        }
    }

    @Override
    public boolean supports(HandlerResult result) {
        if (!super.supports(result)) {
            return false;
        }
        MethodParameter source = result.getReturnTypeSource();
        if (source.hasMethodAnnotation(IgnoreReactiveWrap.class)
                || source.getContainingClass().isAnnotationPresent(IgnoreReactiveWrap.class)) {
            return false;
        }
        Class<?> resolved = resolveUnwrappedType(result);
        if (resolved == null) {
            return true;
        }
        return !HttpEntity.class.isAssignableFrom(resolved)
                && !Resource.class.isAssignableFrom(resolved)
                && !byte[].class.isAssignableFrom(resolved)
                && !ApiResult.class.isAssignableFrom(resolved);
    }

    @Override
    public Mono<Void> handleResult(ServerWebExchange exchange, HandlerResult result) {
        String path = exchange.getRequest().getPath().value();
        if (isExcludedPath(path) || isStreamingAccept(exchange)) {
            return super.handleResult(exchange, result);
        }
        String traceId = ReactiveRequestContext.traceId(exchange);
        Object value = result.getReturnValue();
        if (value == null) {
            return writeBody(Mono.just(successWithTrace(null, traceId)), API_RESULT_PARAM, result.getReturnTypeSource(), exchange);
        }
        if (value instanceof Mono<?> mono) {
            Mono<ApiResult<Object>> wrapped = mono
                    .<ApiResult<Object>>map(item -> wrapIfNeeded(item, traceId))
                    .switchIfEmpty(Mono.fromSupplier(() -> successWithTrace(null, traceId)));
            return writeBody(wrapped, API_RESULT_PARAM, result.getReturnTypeSource(), exchange);
        }
        if (value instanceof Flux<?> flux) {
            Mono<ApiResult<Object>> wrapped = flux.collectList()
                    .map(list -> successWithTrace(list, traceId));
            return writeBody(wrapped, API_RESULT_PARAM, result.getReturnTypeSource(), exchange);
        }
        return writeBody(
                Mono.just(wrapIfNeeded(value, traceId)),
                API_RESULT_PARAM,
                result.getReturnTypeSource(),
                exchange);
    }

    private Class<?> resolveUnwrappedType(HandlerResult result) {
        ReactiveAdapter adapter = getAdapter(result);
        if (adapter != null && result.getReturnType().hasGenerics()) {
            return result.getReturnType().getGeneric(0).resolve();
        }
        return result.getReturnType().resolve();
    }

    private boolean isExcludedPath(String path) {
        for (String pattern : excludePaths) {
            if (pattern != null && !pattern.isBlank() && PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStreamingAccept(ServerWebExchange exchange) {
        String accept = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return false;
        }
        return accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)
                || accept.contains(MediaType.APPLICATION_NDJSON_VALUE);
    }

    private static ApiResult<Object> wrapIfNeeded(Object value, String traceId) {
        if (value instanceof ApiResult<?> existing) {
            @SuppressWarnings("unchecked")
            ApiResult<Object> cast = (ApiResult<Object>) existing;
            return cast;
        }
        return successWithTrace(value, traceId);
    }

    private static ApiResult<Object> successWithTrace(Object data, String traceId) {
        return new DefaultApiResult<>(
                CommonErrorCode.SUCCESS.code(),
                CommonErrorCode.SUCCESS.message(),
                data,
                Objects.requireNonNullElse(traceId, ""),
                Instant.now().toEpochMilli());
    }
}
