package com.chaos.tenant.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.context.RequestContext;
import com.chaos.tenant.NoopTenantStatusProvider;
import com.chaos.tenant.TenantAccessValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 过滤器提前拒绝时也返回框架语义错误码。 */
class TenantAccessServletFilterTest {
    @Test
    void missingTenantReturnsSemanticForbiddenCodeAndStopsChain() throws Exception {
        RequestContext.clear();
        var filter = new TenantAccessServletFilter(
                new TenantAccessValidator(new NoopTenantStatusProvider(), true), List.of());
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response,
                (request, ignored) -> { throw new AssertionError("denied request reached chain"); });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"COMMON_FORBIDDEN\"")
                .contains("\"traceId\"")
                .contains("\"timestamp\"");
    }
}
