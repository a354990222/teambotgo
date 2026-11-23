package com.chaos.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.core.resilience.BulkheadFullException;
import com.chaos.core.resilience.CircuitBreakerOpenException;
import com.chaos.core.resilience.CircuitState;
import com.chaos.core.resilience.ResiliencePolicy;
import com.chaos.core.resilience.ResilienceRegistry;
import com.chaos.http.client.feign.ChaosResilientFeignClient;
import feign.Client;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestTemplate;

/**
 * {@link ChaosResilientFeignClient} 与 {@link ResilientHttpRequestInterceptor} 单元测试。
 */
class ResilienceHttpClientTest {

    @Test
    void feignClientShouldOpenCircuitOn5xxAndIoExceptionsButIgnore4xx() throws Exception {
        ResiliencePolicy policy = new ResiliencePolicy(
                50, 4, 2, Duration.ofSeconds(10), 1, 0, null);
        ResilienceRegistry registry = new ResilienceRegistry("feign", policy);

        AtomicInteger statusToReturn = new AtomicInteger(404);
        AtomicInteger downstreamInvocations = new AtomicInteger();
        Client delegate = (request, options) -> {
            downstreamInvocations.incrementAndGet();
            int code = statusToReturn.get();
            if (code == -1) {
                throw new IOException("connection refused");
            }
            return Response.builder()
                    .status(code)
                    .reason("test")
                    .request(request)
                    .headers(Map.<String, Collection<String>>of())
                    .body("{}", StandardCharsets.UTF_8)
                    .build();
        };

        ChaosResilientFeignClient client = new ChaosResilientFeignClient(delegate, registry);
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new Target.HardCodedTarget<>(Object.class, "order-service", "http://order-service"));
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://order-service/api/orders/1",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                template);

        // 4xx 响应不应触发断路器打开
        for (int i = 0; i < 3; i++) {
            Response resp = client.execute(request, new Request.Options());
            assertThat(resp.status()).isEqualTo(404);
        }
        assertThat(registry.guard("order-service").circuitBreaker().getState()).isEqualTo(CircuitState.CLOSED);

        // 连续 5xx 与 IOException 触发断路器打开并快速失败
        statusToReturn.set(503);
        assertThat(client.execute(request, new Request.Options()).status()).isEqualTo(503);
        statusToReturn.set(-1);
        assertThatThrownBy(() -> client.execute(request, new Request.Options()))
                .isInstanceOf(IOException.class);
        assertThat(registry.guard("order-service").circuitBreaker().getState()).isEqualTo(CircuitState.OPEN);

        int callsBeforeOpen = downstreamInvocations.get();
        assertThatThrownBy(() -> client.execute(request, new Request.Options()))
                .isInstanceOf(CircuitBreakerOpenException.class);
        assertThat(downstreamInvocations.get()).isEqualTo(callsBeforeOpen);
    }

    @Test
    void restTemplateInterceptorShouldEnforceCircuitAndBulkhead() throws Exception {
        ResiliencePolicy policy = new ResiliencePolicy(
                50, 2, 2, Duration.ofSeconds(10), 1, 1, null);
        ResilienceRegistry registry = new ResilienceRegistry("http-client", policy);
        ServiceHttpClients factory = new ServiceHttpClients(new RestTemplateBuilder(), registry);
        RestTemplate restTemplate = factory.forTimeouts(1000, 1000);
        assertThat(restTemplate.getInterceptors()).hasSize(1);

        ResilientHttpRequestInterceptor interceptor = new ResilientHttpRequestInterceptor(registry);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, "http://inventory-svc:8082/ping");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<HttpStatus> first = pool.submit(() -> {
                var resp = interceptor.intercept(req, new byte[0], (r, b) -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    MockClientHttpResponse mockResp = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                    mockResp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return mockResp;
                });
                return HttpStatus.valueOf(resp.getStatusCode().value());
            });

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> interceptor.intercept(req, new byte[0],
                    (r, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK)))
                    .isInstanceOf(BulkheadFullException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
        }

        // 针对新主机：连续两次 502 达到 minimumNumberOfCalls=2 且失败率 100% -> 打开断路器
        MockClientHttpRequest cbReq = new MockClientHttpRequest(HttpMethod.GET, "http://payment-svc:8083/pay");
        for (int i = 0; i < 2; i++) {
            var resp = interceptor.intercept(cbReq, new byte[0],
                    (r, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.BAD_GATEWAY));
            assertThat(resp.getStatusCode().value()).isEqualTo(502);
        }
        assertThatThrownBy(() -> interceptor.intercept(cbReq, new byte[0],
                (r, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK)))
                .isInstanceOf(CircuitBreakerOpenException.class);

        assertThat(List.of("ok")).hasSize(1);
    }
}
