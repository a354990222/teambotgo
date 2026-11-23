package com.chaos.http.client.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.core.exception.BizException;
import com.chaos.core.result.ApiResult;
import com.chaos.http.client.ServiceTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.Decoder;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OpenFeign 适配器测试。
 */
class ChaosFeignAdaptersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChaosFeignResultDecoder decoder = new ChaosFeignResultDecoder(
            new Decoder.Default(),
            objectMapper,
            (code, message) -> new BizException(new RemoteServiceErrorCode(code, message), message));

    private final ChaosFeignErrorDecoder errorDecoder = new ChaosFeignErrorDecoder(
            objectMapper,
            (code, message) -> new BizException(new RemoteServiceErrorCode(code, message), message));

    @Test
    void shouldUnwrapResultEnvelopeWhenTargetIsBusinessDto() throws Exception {
        Response response = jsonResponse(200, "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"orderNo\":\"A-1\"}}");

        OrderDto dto = (OrderDto) decoder.decode(response, OrderDto.class);

        assertThat(dto.orderNo()).isEqualTo("A-1");
    }

    @Test
    void shouldThrowTranslatedExceptionWhenEnvelopeHasErrorCode() {
        Response response = jsonResponse(200, "{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"订单不存在\",\"data\":null}");

        assertThatThrownBy(() -> decoder.decode(response, OrderDto.class))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在")
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code()).isEqualTo("ORDER_NOT_FOUND"));
    }

    @Test
    void shouldPassThroughWhenTargetIsApiResult() throws Exception {
        Response response = jsonResponse(200, "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"orderNo\":\"A-2\"},\"traceId\":\"t-1\",\"timestamp\":123}");
        Type apiResultType = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {OrderDto.class};
            }

            @Override
            public Type getRawType() {
                return ApiResult.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };

        @SuppressWarnings("unchecked")
        ApiResult<OrderDto> result = (ApiResult<OrderDto>) decoder.decode(response, apiResultType);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().orderNo()).isEqualTo("A-2");
    }

    @Test
    void shouldTranslateHttpErrorBodyIntoBizException() {
        Response response = jsonResponse(400, "{\"code\":\"COMMON_BAD_REQUEST\",\"message\":\"金额非法\",\"data\":null}");

        Exception ex = errorDecoder.decode("OrderApi#create", response);

        assertThat(ex).isInstanceOf(BizException.class)
                .hasMessage("金额非法");
        assertThat(((BizException) ex).errorCode().code()).isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    void shouldAttachBearerTokenWhenAuthorizationHeaderAbsent() {
        ServiceTokenProvider provider = new ServiceTokenProvider() {
            @Override
            public String token() {
                return "svc-jwt";
            }

            @Override
            public void invalidate() {
            }
        };
        ServiceTokenFeignInterceptor interceptor = new ServiceTokenFeignInterceptor(provider);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("Authorization")).containsExactly("Bearer svc-jwt");
    }

    private static Response jsonResponse(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("OK")
                .request(Request.create(Request.HttpMethod.GET, "/orders", Map.of(), null, StandardCharsets.UTF_8, null))
                .headers(Map.<String, Collection<String>>of("Content-Type", List.of("application/json")))
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    public record OrderDto(String orderNo) {
    }
}
