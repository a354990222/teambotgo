package com.chaos.web.reactive.exception;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.exception.ErrorCode;
import com.chaos.core.exception.SafeExceptionDiagnostics;
import com.chaos.core.result.ApiResult;
import com.chaos.core.result.DefaultApiResult;
import com.chaos.web.reactive.filter.ReactiveErrorResponseWriter;
import com.chaos.web.reactive.filter.ReactiveRequestContext;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * WebFlux 响应式全局异常处理器。
 *
 * <p>将框架统一异常、参数绑定校验异常、数据冲突与未知异常转换为统一 {@link ApiResult} 结构，
 * 与 Servlet 侧 {@code GlobalExceptionHandler} 保持一致的错误码、状态码与敏感信息防泄漏约束。</p>
 */
@RestControllerAdvice
public class ReactiveGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReactiveGlobalExceptionHandler.class);

    private static final Set<String> SECURITY_EXCEPTION_TYPES = Set.of(
            "org.springframework.security.access.AccessDeniedException",
            "org.springframework.security.core.AuthenticationException");

    private static final Set<String> DATA_CONFLICT_TYPES = Set.of(
            "org.springframework.dao.DuplicateKeyException",
            "org.springframework.dao.PessimisticLockingFailureException",
            "org.springframework.dao.OptimisticLockingFailureException");

    /**
     * 处理框架统一异常。
     */
    @ExceptionHandler(ChaosException.class)
    public Mono<ResponseEntity<ApiResult<Void>>> handleChaosException(
            ChaosException ex, ServerWebExchange exchange) {
        HttpStatus status = resolveStatus(ex.errorCode());
        if (status.is5xxServerError()) {
            log.error("Reactive server error: code={} diagnostic={}",
                    ex.errorCode().code(), SafeExceptionDiagnostics.describe(ex));
            return Mono.just(ResponseEntity.status(status).body(
                    failure(ex.errorCode(), ReactiveErrorResponseWriter.localize(ex.errorCode(), exchange), exchange)));
        }
        String message = (ex.getMessage() == null || ex.getMessage().isBlank())
                ? ReactiveErrorResponseWriter.localize(ex.errorCode(), exchange)
                : ex.getMessage();
        return Mono.just(ResponseEntity.status(status).body(failure(ex.errorCode(), message, exchange)));
    }

    /**
     * 处理 WebFlux 绑定与 JSR-303 字段校验异常。
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResult<Void>>> handleBindException(
            WebExchangeBindException ex, ServerWebExchange exchange) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .collect(Collectors.joining("; "));
        String message = detail.isEmpty()
                ? ReactiveErrorResponseWriter.localize(CommonErrorCode.BAD_REQUEST, exchange)
                : detail;
        return Mono.just(ResponseEntity.badRequest().body(failure(CommonErrorCode.BAD_REQUEST, message, exchange)));
    }

    /**
     * 处理方法参数级约束违例。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Mono<ResponseEntity<ApiResult<Void>>> handleConstraintViolation(
            ConstraintViolationException ex, ServerWebExchange exchange) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .filter(msg -> msg != null && !msg.isBlank())
                .collect(Collectors.joining("; "));
        String message = detail.isEmpty()
                ? ReactiveErrorResponseWriter.localize(CommonErrorCode.BAD_REQUEST, exchange)
                : detail;
        return Mono.just(ResponseEntity.badRequest().body(failure(CommonErrorCode.BAD_REQUEST, message, exchange)));
    }

    /**
     * 处理请求体或查询参数解析失败。
     */
    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ApiResult<Void>>> handleInputException(
            ServerWebInputException ex, ServerWebExchange exchange) {
        String message = ReactiveErrorResponseWriter.localize(CommonErrorCode.BAD_REQUEST, exchange);
        return Mono.just(ResponseEntity.badRequest().body(failure(CommonErrorCode.BAD_REQUEST, message, exchange)));
    }

    /**
     * 处理其余异常：放行 Spring Security 异常、识别数据冲突并返回 409、保留 ErrorResponse 状态码、其余返回 500。
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResult<Void>>> handleException(Exception ex, ServerWebExchange exchange)
            throws Exception {
        if (isInstanceOfAny(ex, SECURITY_EXCEPTION_TYPES)) {
            throw ex;
        }
        if (isInstanceOfAny(ex, DATA_CONFLICT_TYPES)) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(
                    failure(CommonErrorCode.CONFLICT,
                            ReactiveErrorResponseWriter.localize(CommonErrorCode.CONFLICT, exchange),
                            exchange)));
        }
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus resolved = HttpStatus.resolve(errorResponse.getStatusCode().value());
            HttpStatus status = resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
            ErrorCode code = status.is4xxClientError() ? CommonErrorCode.BAD_REQUEST : CommonErrorCode.INTERNAL_ERROR;
            return Mono.just(ResponseEntity.status(status).body(
                    failure(code, ReactiveErrorResponseWriter.localize(code, exchange), exchange)));
        }
        log.error("Unhandled reactive exception: diagnostic={}", SafeExceptionDiagnostics.describe(ex), ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                failure(CommonErrorCode.INTERNAL_ERROR,
                        ReactiveErrorResponseWriter.localize(CommonErrorCode.INTERNAL_ERROR, exchange),
                        exchange)));
    }

    private static ApiResult<Void> failure(ErrorCode code, String message, ServerWebExchange exchange) {
        return new DefaultApiResult<>(
                code.code(),
                message,
                null,
                ReactiveRequestContext.traceId(exchange),
                Instant.now().toEpochMilli());
    }

    private static HttpStatus resolveStatus(ErrorCode errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code = errorCode.code();
        if (CommonErrorCode.BAD_REQUEST.code().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        if (CommonErrorCode.UNAUTHORIZED.code().equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (CommonErrorCode.FORBIDDEN.code().equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if (CommonErrorCode.NOT_FOUND.code().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (CommonErrorCode.TOO_MANY_REQUESTS.code().equals(code)) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (CommonErrorCode.CONFLICT.code().equals(code)
                || CommonErrorCode.IDEMPOTENT_REJECTED.code().equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if (CommonErrorCode.SERVICE_UNAVAILABLE.code().equals(code)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (CommonErrorCode.INTERNAL_ERROR.code().equals(code)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private static boolean isInstanceOfAny(Throwable ex, Set<String> classNames) {
        Class<?> current = ex.getClass();
        while (current != null && current != Object.class) {
            if (classNames.contains(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
