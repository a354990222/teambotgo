package com.chaos.http.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link RequestBearerTokenProvider} 单元测试。
 */
class RequestBearerTokenProviderTest {

    private final RequestBearerTokenProvider provider = new RequestBearerTokenProvider();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldExtractBearerTokenCaseInsensitively() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "bearer token-abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(provider.token()).isEqualTo("token-abc");
    }

    @Test
    void shouldReturnRawAuthorizationWhenNoBearerPrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "raw-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(provider.token()).isEqualTo("raw-token");
    }

    @Test
    void shouldReturnNullWhenNoRequestOrBlankHeader() {
        assertThat(provider.token()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "   ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(provider.token()).isNull();

        provider.invalidate();
    }
}
