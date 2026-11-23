package com.chaos.core.exception;

import java.util.Locale;

/**
 * 框架内置通用错误码；成功为 0，失败使用 COMMON_ 语义标识，与 HTTP 状态分离。
 *
 * <p>业务系统可以定义自己的 {@link ErrorCode} 枚举，但不应修改这些基础错误码语义，
 * 以保证跨服务响应和监控统计口径一致。</p>
 */
public enum CommonErrorCode implements ErrorCode {

    SUCCESS("0", "success"),
    BAD_REQUEST("COMMON_BAD_REQUEST", "bad request"),
    UNAUTHORIZED("COMMON_UNAUTHORIZED", "unauthorized"),
    FORBIDDEN("COMMON_FORBIDDEN", "forbidden"),
    NOT_FOUND("COMMON_NOT_FOUND", "not found"),
    METHOD_NOT_ALLOWED("COMMON_METHOD_NOT_ALLOWED", "method not allowed"),
    UNSUPPORTED_MEDIA_TYPE("COMMON_UNSUPPORTED_MEDIA_TYPE", "unsupported media type"),
    TOO_MANY_REQUESTS("COMMON_TOO_MANY_REQUESTS", "too many requests"),
    CONFLICT("COMMON_CONFLICT", "data conflict; refresh and retry"),
    IDEMPOTENT_REJECTED("COMMON_IDEMPOTENT_REJECTED", "duplicate request"),
    INTERNAL_ERROR("COMMON_INTERNAL_ERROR", "internal server error"),
    SERVICE_UNAVAILABLE("COMMON_SERVICE_UNAVAILABLE", "service unavailable");

    private final String code;

    private final String message;

    CommonErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回对外稳定错误码。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误消息。
     */
    @Override
    public String message() {
        return message;
    }

    /**
     * 返回内置错误码专用的国际化消息 key，形如 {@code chaos.error.common.bad-request}。
     *
     * <p>不沿用 {@link ErrorCode#messageKey()} 的 {@code chaos.error.<code>} 约定：那个命名空间留给业务错误码，
     * 保持消息 key 独立于对外 code，确保错误码迁移不破坏应用已有的国际化文案覆盖。</p>
     */
    @Override
    public String messageKey() {
        return "chaos.error.common." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
