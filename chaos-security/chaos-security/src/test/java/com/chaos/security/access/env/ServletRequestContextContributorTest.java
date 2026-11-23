package com.chaos.security.access.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.security.api.access.AccessEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link ServletRequestContextContributor} 单元测试。
 */
class ServletRequestContextContributorTest {

    private final ServletRequestContextContributor contributor = new ServletRequestContextContributor();

    @AfterEach
    void clearRequestAttributes() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldContributeMethodPathAndFallbackClientIp() {
        Map<String, Object> noRequest = new LinkedHashMap<>();
        contributor.contribute(noRequest);
        assertThat(noRequest).isEmpty();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.setRemoteAddr("192.168.1.50");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Map<String, Object> env = new LinkedHashMap<>();
        contributor.contribute(env);
        assertThat(env)
                .containsEntry(AccessEnvironment.HTTP_METHOD, "POST")
                .containsEntry(AccessEnvironment.HTTP_PATH, "/api/orders")
                .containsEntry(AccessEnvironment.CLIENT_IP, "192.168.1.50");

        Map<String, Object> presetIp = new LinkedHashMap<>();
        presetIp.put(AccessEnvironment.CLIENT_IP, "10.0.0.1");
        contributor.contribute(presetIp);
        assertThat(presetIp).containsEntry(AccessEnvironment.CLIENT_IP, "10.0.0.1");
    }
}
