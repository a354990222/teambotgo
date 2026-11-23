package com.chaos.http.client.feign;

import com.chaos.core.exception.ErrorCode;
import java.util.Objects;

/**
 * 透传下游微服务错误码的轻量 {@link ErrorCode} 实现。
 */
public final class RemoteServiceErrorCode implements ErrorCode {

    private final String code;

    private final String message;

    public RemoteServiceErrorCode(String code, String message) {
        this.code = (code == null || code.isBlank()) ? "COMMON_INTERNAL_ERROR" : code;
        this.message = (message == null || message.isBlank()) ? "remote service error" : message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public String messageKey() {
        return "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RemoteServiceErrorCode that)) {
            return false;
        }
        return Objects.equals(code, that.code) && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message);
    }
}
