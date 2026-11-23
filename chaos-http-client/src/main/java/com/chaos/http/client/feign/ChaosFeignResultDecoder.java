package com.chaos.http.client.feign;

import com.chaos.core.result.ApiResult;
import com.chaos.core.result.DefaultApiResult;
import com.chaos.http.client.ServiceErrorTranslator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.Decoder;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Set;

/**
 * OpenFeign {@link Decoder} 适配器：自动解包服务端 {@code Result} / {@link ApiResult} 信封。
 *
 * <p>行为约定：</p>
 * <ul>
 *     <li>接口声明直接返回业务类型（如 {@code OrderDto}）时，若响应体为 {@code {"code":"0","data":...}}，
 *     自动解包 {@code data} 字段；若 {@code code} 非成功码则通过 {@link ServiceErrorTranslator} 抛出异常。</li>
 *     <li>接口声明显式返回 {@code Result<T>} / {@link ApiResult}、{@code ResponseEntity<T>} 或 {@code byte[]} 时，
 *     保持原样透传反序列化，兼容既有契约写法。</li>
 * </ul>
 */
public class ChaosFeignResultDecoder implements Decoder {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "SUCCESS");

    private final Decoder delegate;

    private final ObjectMapper objectMapper;

    private final ServiceErrorTranslator errorTranslator;

    public ChaosFeignResultDecoder(
            Decoder delegate,
            ObjectMapper objectMapper,
            ServiceErrorTranslator errorTranslator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.errorTranslator = Objects.requireNonNull(errorTranslator, "errorTranslator must not be null");
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (type == void.class || type == Void.class || response.status() == 204 || response.body() == null) {
            return delegate.decode(response, type);
        }
        if (isExplicitEnvelopeOrRawType(type)) {
            if (isInterfaceApiResult(type)) {
                return decodeAsDefaultApiResult(response, type);
            }
            return delegate.decode(response, type);
        }
        byte[] bytes;
        try (InputStream in = response.body().asInputStream()) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0) {
            return null;
        }
        JsonNode tree = objectMapper.readTree(bytes);
        if (tree != null && tree.isObject() && tree.has("code") && tree.has("data")) {
            String code = tree.path("code").asText();
            if (!SUCCESS_CODES.contains(code)) {
                throw errorTranslator.translate(code, tree.path("message").asText("服务调用失败"));
            }
            JsonNode data = tree.get("data");
            if (data == null || data.isNull()) {
                return null;
            }
            return objectMapper.convertValue(data, objectMapper.constructType(type));
        }
        return objectMapper.readValue(bytes, objectMapper.constructType(type));
    }

    private Object decodeAsDefaultApiResult(Response response, Type type) throws IOException {
        JavaType targetType;
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            JavaType dataType = args.length == 1
                    ? objectMapper.constructType(args[0])
                    : objectMapper.constructType(Object.class);
            targetType = objectMapper.getTypeFactory().constructParametricType(DefaultApiResult.class, dataType);
        } else {
            targetType = objectMapper.constructType(DefaultApiResult.class);
        }
        try (InputStream in = response.body().asInputStream()) {
            return objectMapper.readValue(in, targetType);
        }
    }

    private static boolean isInterfaceApiResult(Type type) {
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw == ApiResult.class;
        }
        return type == ApiResult.class;
    }

    private static boolean isExplicitEnvelopeOrRawType(Type type) {
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return ApiResult.class.isAssignableFrom(raw)
                    || "org.springframework.http.ResponseEntity".equals(raw.getName());
        }
        if (type instanceof Class<?> cls) {
            return ApiResult.class.isAssignableFrom(cls)
                    || byte[].class.equals(cls)
                    || String.class.equals(cls)
                    || "org.springframework.http.ResponseEntity".equals(cls.getName());
        }
        return false;
    }
}
