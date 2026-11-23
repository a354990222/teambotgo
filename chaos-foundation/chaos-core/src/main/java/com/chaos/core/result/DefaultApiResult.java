package com.chaos.core.result;

/**
 * {@link ApiResult} 的框架无关默认不可变实现。
 *
 * @param code 业务错误码，成功时为 {@code "0"}
 * @param message 响应消息
 * @param data 响应数据
 * @param traceId 当前请求 traceId
 * @param timestamp 响应生成时间戳，单位毫秒
 * @param <T> 响应数据类型
 */
public record DefaultApiResult<T>(
        String code,
        String message,
        T data,
        String traceId,
        long timestamp
) implements ApiResult<T> {
}
