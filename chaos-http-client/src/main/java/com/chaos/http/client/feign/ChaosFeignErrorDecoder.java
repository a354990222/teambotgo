package com.chaos.http.client.feign;

import com.chaos.http.client.ServiceErrorTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * OpenFeign {@link ErrorDecoder}：从下游统一错误响应 {@code {"code":..., "message":...}} 还原业务异常。
 *
 * <p>无法解析出语义错误码时回退到 Feign 默认异常解码器，保留原始 HTTP 状态与响应信息。</p>
 */
public class ChaosFeignErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    private final ServiceErrorTranslator errorTranslator;

    private final ErrorDecoder fallback;

    public ChaosFeignErrorDecoder(ObjectMapper objectMapper, ServiceErrorTranslator errorTranslator) {
        this(objectMapper, errorTranslator, new Default());
    }

    public ChaosFeignErrorDecoder(
            ObjectMapper objectMapper,
            ServiceErrorTranslator errorTranslator,
            ErrorDecoder fallback) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.errorTranslator = Objects.requireNonNull(errorTranslator, "errorTranslator must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.body() != null) {
            try (InputStream in = response.body().asInputStream()) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length > 0) {
                    JsonNode tree = objectMapper.readTree(bytes);
                    if (tree != null && tree.isObject() && tree.hasNonNull("code")) {
                        String code = tree.path("code").asText("");
                        if (!code.isBlank()) {
                            String message = tree.path("message").asText("服务调用失败");
                            return errorTranslator.translate(code, message);
                        }
                    }
                }
            } catch (IOException ignored) {
                // 非 JSON 响应体回退给默认解码器。
            }
        }
        return fallback.decode(methodKey, response);
    }
}
