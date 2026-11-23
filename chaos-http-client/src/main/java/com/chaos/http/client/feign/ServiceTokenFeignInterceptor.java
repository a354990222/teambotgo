package com.chaos.http.client.feign;

import com.chaos.http.client.ServiceTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.Objects;

/**
 * 将 {@link ServiceTokenProvider} 桥接到 OpenFeign 请求头，统一出站服务令牌透传或凭证申请。
 */
public class ServiceTokenFeignInterceptor implements RequestInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final ServiceTokenProvider tokenProvider;

    public ServiceTokenFeignInterceptor(ServiceTokenProvider tokenProvider) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template.headers().containsKey(AUTHORIZATION_HEADER)) {
            return;
        }
        String token = tokenProvider.token();
        if (token != null && !token.isBlank()) {
            template.header(AUTHORIZATION_HEADER, "Bearer " + token);
        }
    }
}
