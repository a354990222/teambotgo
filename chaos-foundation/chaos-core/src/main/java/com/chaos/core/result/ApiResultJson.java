package com.chaos.core.result;

import com.chaos.core.exception.ErrorCode;
import java.time.Instant;

/**
 * 无外部 JSON 依赖的统一失败响应序列化工具。
 *
 * <p>供 Servlet Filter、WebFlux 网关等无法直接使用 MVC 消息转换器的边界组件输出与 {@code Result} 完全一致的 JSON。</p>
 */
public final class ApiResultJson {

    private ApiResultJson() {
    }

    /**
     * 将错误码、文案与 traceId 序列化为标准失败响应 JSON。
     */
    public static String failure(ErrorCode errorCode, String message, String traceId) {
        return failure(errorCode.code(), message, traceId, Instant.now().toEpochMilli());
    }

    /**
     * 将错误码字符串、文案与 traceId 序列化为标准失败响应 JSON。
     */
    public static String failure(String code, String message, String traceId) {
        return failure(code, message, traceId, Instant.now().toEpochMilli());
    }

    /**
     * 将错误码字符串、文案、traceId 与时间戳序列化为标准失败响应 JSON。
     */
    public static String failure(String code, String message, String traceId, long timestamp) {
        return """
                {"code":"%s","message":"%s","data":null,"traceId":"%s","timestamp":%d}"""
                .formatted(escape(code), escape(message), escape(traceId), timestamp);
    }

    /**
     * 对 JSON 字符串值中的特殊和控制字符做转义。
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
