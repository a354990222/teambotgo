package com.chaos.core.result;

import com.chaos.core.context.RequestContext;
import com.chaos.core.context.RequestContextSnapshot;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.exception.ErrorCode;
import java.time.Instant;

/**
 * 跨传输栈（Servlet / WebFlux / RPC）统一的响应信封契约。
 *
 * <p>位于 {@code chaos-core}，不依赖 Spring Web 或 Servlet API，供业务响应、网关降级与非 Web 契约共享。</p>
 *
 * @param <T> 响应数据类型
 */
public interface ApiResult<T> {

    /**
     * 返回业务错误码，成功时为 {@code "0"}。
     */
    String code();

    /**
     * 返回响应展示消息。
     */
    String message();

    /**
     * 返回业务数据载荷。
     */
    T data();

    /**
     * 返回当前请求 traceId。
     */
    String traceId();

    /**
     * 返回响应生成时间戳（毫秒）。
     */
    long timestamp();

    /**
     * 判断响应是否代表成功。
     */
    default boolean isSuccess() {
        return CommonErrorCode.SUCCESS.code().equals(code());
    }

    /**
     * 创建携带数据的成功响应。
     */
    static <T> ApiResult<T> success(T data) {
        return of(CommonErrorCode.SUCCESS, data, CommonErrorCode.SUCCESS.message());
    }

    /**
     * 创建无数据的成功响应。
     */
    static ApiResult<Void> success() {
        return of(CommonErrorCode.SUCCESS, null, CommonErrorCode.SUCCESS.message());
    }

    /**
     * 创建失败响应。
     */
    static ApiResult<Void> failure(ErrorCode errorCode) {
        return of(errorCode, null, errorCode.message());
    }

    /**
     * 使用自定义展示消息创建失败响应。
     */
    static ApiResult<Void> failure(ErrorCode errorCode, String message) {
        return of(errorCode, null, message);
    }

    /**
     * 按指定错误码、数据和消息创建统一响应。
     */
    static <T> ApiResult<T> of(ErrorCode errorCode, T data, String message) {
        String traceId = RequestContext.current().map(RequestContextSnapshot::traceId).orElse("");
        return new DefaultApiResult<>(errorCode.code(), message, data, traceId, Instant.now().toEpochMilli());
    }
}
