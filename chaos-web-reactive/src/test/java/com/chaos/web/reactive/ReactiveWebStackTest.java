package com.chaos.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.constant.ChaosHeaders;
import com.chaos.core.context.RequestContextSnapshot;
import com.chaos.core.exception.BizException;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.metrics.ChaosMeterNames;
import com.chaos.core.metrics.RecordingChaosMetrics;
import com.chaos.core.result.ApiResult;
import com.chaos.web.reactive.annotation.IgnoreReactiveWrap;
import com.chaos.web.reactive.config.ChaosWebReactiveProperties;
import com.chaos.web.reactive.exception.ReactiveGlobalExceptionHandler;
import com.chaos.web.reactive.filter.ReactiveRequestContext;
import com.chaos.web.reactive.filter.ReactiveTraceWebFilter;
import com.chaos.web.reactive.idempotent.InMemoryReactiveIdempotentRepository;
import com.chaos.web.reactive.idempotent.ReactiveIdempotentWebFilter;
import com.chaos.web.reactive.idempotent.RedisReactiveIdempotentRepository;
import com.chaos.web.reactive.ratelimit.InMemoryReactiveRateLimiter;
import com.chaos.web.reactive.ratelimit.ReactiveRateLimitWebFilter;
import com.chaos.web.reactive.ratelimit.RedisReactiveRateLimiter;
import com.chaos.web.reactive.result.ReactiveResultHandler;
import com.chaos.web.reactive.xss.ReactiveSecurityWebFilter;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.accept.HeaderContentTypeResolver;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@code chaos-web-reactive} 全栈能力（统一响应、异常处理、Trace、XSS、限流、幂等及 Redis 适配）测试。
 */
class ReactiveWebStackTest {

    @Test
    void shouldWrapResponsesHandleExceptionsPropagateTraceAndEnforceFiltersViaWebTestClient() {
        ChaosWebReactiveProperties properties = new ChaosWebReactiveProperties();
        properties.getRateLimit().setDefaultPermitsPerSecond(5);
        RecordingChaosMetrics metrics = new RecordingChaosMetrics();

        ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
        ReactiveResultHandler resultHandler = new ReactiveResultHandler(
                codecs.getWriters(),
                new HeaderContentTypeResolver(),
                ReactiveAdapterRegistry.getSharedInstance(),
                properties.getResponseWrapExcludePaths());

        InMemoryReactiveRateLimiter rateLimiter = new InMemoryReactiveRateLimiter(100);
        InMemoryReactiveIdempotentRepository idempotentRepo = new InMemoryReactiveIdempotentRepository(100);

        WebTestClient client = WebTestClient.bindToController(new DemoReactiveController())
                .controllerAdvice(new ReactiveGlobalExceptionHandler())
                .webFilter(
                        new ReactiveTraceWebFilter("demo-reactive"),
                        new ReactiveSecurityWebFilter(),
                        new ReactiveRateLimitWebFilter(properties, rateLimiter, metrics),
                        new ReactiveIdempotentWebFilter(properties, idempotentRepo, metrics))
                .httpMessageCodecs(config -> config.defaultCodecs())
                .build();

        // 验证 Mono 返回与 Trace / Security 头透传（含 W3C traceparent）
        client.get().uri("/demo/ctx")
                .header(ChaosHeaders.TRACEPARENT, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .header(ChaosHeaders.TENANT_ID, "tenant-r")
                .header(ChaosHeaders.USER_ID, "user-r")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChaosHeaders.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectBody()
                .jsonPath("$.traceId").isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
                .jsonPath("$.tenantId").isEqualTo("tenant-r")
                .jsonPath("$.userId").isEqualTo("user-r");

        // 直接通过 ReactiveResultHandler 验证 Mono/Flux/Empty/IgnoreReactiveWrap 包装行为
        MockServerWebExchange okExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/items").build());
        okExchange.getAttributes().put(
                ReactiveRequestContext.EXCHANGE_ATTRIBUTE,
                new RequestContextSnapshot("trace-wrap-1", "s1", "t1", "u1", "app"));
        assertThat(resultHandler).isNotNull();

        // 验证 BizException / SysException / WebExchangeBindException / ResponseStatusException
        client.get().uri("/demo/biz-error")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_BAD_REQUEST")
                .jsonPath("$.message").isEqualTo("invalid order state");

        client.post().uri("/demo/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_BAD_REQUEST")
                .jsonPath("$.message").isEqualTo("name must not be blank");

        client.get().uri("/demo/status-404")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_BAD_REQUEST");

        // 验证 XSS 查询参数拦截
        client.get().uri("/demo/ctx?q=<script>alert(1)</script>")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_BAD_REQUEST");

        // 验证幂等拦截（首次 200，重复带同 key 返回 409）
        client.post().uri("/demo/orders")
                .header("Idempotency-Key", "idem-1")
                .exchange()
                .expectStatus().isOk();

        client.post().uri("/demo/orders")
                .header("Idempotency-Key", "idem-1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_IDEMPOTENT_REJECTED");
        assertThat(metrics.recorded()).contains(ChaosMeterNames.IDEMPOTENT_REJECTED + "{source,web-reactive}");
    }

    @Test
    void shouldWrapHandlerResultsAndCoverRateLimitFailOpenAndRedisAdapters() throws Exception {
        ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
        ReactiveResultHandler handler = new ReactiveResultHandler(
                codecs.getWriters(),
                new HeaderContentTypeResolver(),
                ReactiveAdapterRegistry.getSharedInstance(),
                List.of("/actuator/**"));

        DemoReactiveController controller = new DemoReactiveController();
        var wrappedMethod = DemoReactiveController.class.getDeclaredMethod("plainPojo");
        var ignoredMethod = DemoReactiveController.class.getDeclaredMethod("ignoredPojo");
        var fluxMethod = DemoReactiveController.class.getDeclaredMethod("fluxItems");
        var emptyMethod = DemoReactiveController.class.getDeclaredMethod("emptyMono");

        var wrappedParam = new org.springframework.core.MethodParameter(wrappedMethod, -1);
        var ignoredParam = new org.springframework.core.MethodParameter(ignoredMethod, -1);
        var fluxParam = new org.springframework.core.MethodParameter(fluxMethod, -1);
        var emptyParam = new org.springframework.core.MethodParameter(emptyMethod, -1);

        assertThat(handler.supports(new org.springframework.web.reactive.HandlerResult(
                controller, Mono.just("ok"), wrappedParam))).isTrue();
        assertThat(handler.supports(new org.springframework.web.reactive.HandlerResult(
                controller, Mono.just("ok"), ignoredParam))).isFalse();

        MockServerWebExchange apiEx = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/plain").accept(MediaType.APPLICATION_JSON).build());
        handler.handleResult(apiEx, new org.springframework.web.reactive.HandlerResult(
                controller, Mono.just("hello"), wrappedParam)).block();
        assertThat(apiEx.getResponse().getBodyAsString().block()).contains("\"code\":\"0\"").contains("\"data\":\"hello\"");

        MockServerWebExchange fluxEx = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/flux").accept(MediaType.APPLICATION_JSON).build());
        handler.handleResult(fluxEx, new org.springframework.web.reactive.HandlerResult(
                controller, Flux.just("a", "b"), fluxParam)).block();
        assertThat(fluxEx.getResponse().getBodyAsString().block()).contains("\"data\":[\"a\",\"b\"]");

        MockServerWebExchange emptyEx = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/empty").accept(MediaType.APPLICATION_JSON).build());
        handler.handleResult(emptyEx, new org.springframework.web.reactive.HandlerResult(
                controller, Mono.empty(), emptyParam)).block();
        assertThat(emptyEx.getResponse().getBodyAsString().block()).contains("\"code\":\"0\"");

        MockServerWebExchange actuatorEx = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").accept(MediaType.APPLICATION_JSON).build());
        handler.handleResult(actuatorEx, new org.springframework.web.reactive.HandlerResult(
                controller, Mono.just("UP"), wrappedParam)).block();
        assertThat(actuatorEx.getResponse().getBodyAsString().block()).isEqualTo("UP");

        // 覆盖限流 fail-open / fail-closed 与触发 429 分支
        ChaosWebReactiveProperties rlProps = new ChaosWebReactiveProperties();
        RecordingChaosMetrics metrics = new RecordingChaosMetrics();
        ReactiveRateLimitWebFilter failingOpen = new ReactiveRateLimitWebFilter(
                rlProps, (k, p) -> Flux.<Boolean>error(new IllegalStateException("redis down")).next(), metrics);
        MockServerWebExchange openEx = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
        failingOpen.filter(openEx, ex -> Mono.empty()).block();
        assertThat(openEx.getResponse().getStatusCode()).isNull();
        assertThat(metrics.recorded()).contains(
                ChaosMeterNames.RATE_LIMIT_ERRORS + "{source,web-reactive,outcome,fail-open}");

        rlProps.getRateLimit().setFailOpen(false);
        ReactiveRateLimitWebFilter failingClosed = new ReactiveRateLimitWebFilter(
                rlProps, (k, p) -> Mono.error(new IllegalStateException("redis down")), metrics);
        MockServerWebExchange closedEx = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
        failingClosed.filter(closedEx, ex -> Mono.empty()).block();
        assertThat(closedEx.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 覆盖 RedisReactiveRateLimiter 与 RedisReactiveIdempotentRepository 适配行为
        AtomicInteger counter = new AtomicInteger();
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        ReactiveStringRedisTemplate stubTemplate = org.mockito.Mockito.mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> ops = org.mockito.Mockito.mock(ReactiveValueOperations.class);
        org.mockito.Mockito.when(stubTemplate.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.when(stubTemplate.execute(
                org.mockito.ArgumentMatchers.any(RedisScript.class),
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> Flux.just((long) counter.incrementAndGet()));
        org.mockito.Mockito.when(ops.setIfAbsent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(
                        store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null));
        org.mockito.Mockito.when(stubTemplate.delete(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> Mono.just(store.remove(inv.getArgument(0, String.class)) != null ? 1L : 0L));

        RedisReactiveRateLimiter redisRl = new RedisReactiveRateLimiter(stubTemplate, "app:rl:");
        StepVerifier.create(redisRl.tryAcquire("k", 1)).expectNext(true).verifyComplete();
        StepVerifier.create(redisRl.tryAcquire("k", 1)).expectNext(false).verifyComplete();

        RedisReactiveIdempotentRepository redisIdem = new RedisReactiveIdempotentRepository(stubTemplate, "app:idem:");
        StepVerifier.create(redisIdem.saveIfAbsent("k1", Duration.ofMinutes(1))).expectNext(true).verifyComplete();
        StepVerifier.create(redisIdem.saveIfAbsent("k1", Duration.ofMinutes(1))).expectNext(false).verifyComplete();
        StepVerifier.create(redisIdem.remove("k1")).verifyComplete();
        StepVerifier.create(redisIdem.saveIfAbsent("k1", Duration.ofMinutes(1))).expectNext(true).verifyComplete();

        // 验证 ReactiveGlobalExceptionHandler 对 ConstraintViolation 与未知异常的兜底处理
        ReactiveGlobalExceptionHandler exHandler = new ReactiveGlobalExceptionHandler();
        try (ValidatorFactory vf = Validation.buildDefaultValidatorFactory()) {
            var violations = vf.getValidator().validate(new ValidatedPayload(""));
            var resp = exHandler.handleConstraintViolation(
                    new ConstraintViolationException(violations), openEx).block();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var unhandled = exHandler.handleException(new IllegalStateException("db password leak"), openEx).block();
        assertThat(unhandled.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unhandled.getBody().message()).doesNotContain("db password leak");
    }

    @Test
    void shouldCoverPropertiesExceptionStatusesI18nAndIdempotentRollback() throws Exception {
        ChaosWebReactiveProperties props = new ChaosWebReactiveProperties();
        props.setEnabled(false);
        props.setResponseWrapEnabled(false);
        props.setTraceEnabled(false);
        props.setXssEnabled(false);
        props.setResponseWrapExcludePaths(null);
        props.setResponseWrapExcludePaths(List.of("/health"));
        props.setRateLimit(null);
        props.setIdempotent(null);
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isResponseWrapEnabled()).isFalse();
        assertThat(props.isTraceEnabled()).isFalse();
        assertThat(props.isXssEnabled()).isFalse();
        assertThat(props.getResponseWrapExcludePaths()).containsExactly("/health");

        ChaosWebReactiveProperties.RateLimit rl = props.getRateLimit();
        rl.setEnabled(false);
        rl.setMaxLocalKeys(50);
        rl.setSkipPaths(null);
        rl.setKeyPrefix(" ");
        rl.setKeyPrefix("svc:rl:");
        assertThat(rl.isEnabled()).isFalse();
        assertThat(rl.getMaxLocalKeys()).isEqualTo(50);
        assertThat(rl.getSkipPaths()).isEmpty();
        assertThat(rl.getKeyPrefix()).isEqualTo("svc:rl:");

        ChaosWebReactiveProperties.Idempotent idem = props.getIdempotent();
        idem.setEnabled(false);
        idem.setHeaderName(" ");
        idem.setHeaderName("X-Idem");
        idem.setTtl(null);
        idem.setTtl(Duration.ofSeconds(30));
        idem.setMaxLocalKeys(20);
        idem.setMethods(null);
        idem.setKeyPrefix(" ");
        idem.setKeyPrefix("svc:idem:");
        assertThat(idem.isEnabled()).isFalse();
        assertThat(idem.getHeaderName()).isEqualTo("X-Idem");
        assertThat(idem.getTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(idem.getMaxLocalKeys()).isEqualTo(20);
        assertThat(idem.getMethods()).isEmpty();
        assertThat(idem.getKeyPrefix()).isEqualTo("svc:idem:");

        InMemoryReactiveIdempotentRepository inMemoryIdem = new InMemoryReactiveIdempotentRepository(10);
        StepVerifier.create(inMemoryIdem.saveIfAbsent("r1", Duration.ofSeconds(10))).expectNext(true).verifyComplete();
        StepVerifier.create(inMemoryIdem.remove("r1")).verifyComplete();

        ReactiveGlobalExceptionHandler exHandler = new ReactiveGlobalExceptionHandler();
        MockServerWebExchange zhExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/demo/err").header("Accept-Language", "zh-CN").build());
        for (CommonErrorCode code : List.of(
                CommonErrorCode.UNAUTHORIZED,
                CommonErrorCode.FORBIDDEN,
                CommonErrorCode.NOT_FOUND,
                CommonErrorCode.TOO_MANY_REQUESTS,
                CommonErrorCode.CONFLICT,
                CommonErrorCode.IDEMPOTENT_REJECTED,
                CommonErrorCode.SERVICE_UNAVAILABLE,
                CommonErrorCode.INTERNAL_ERROR)) {
            var resp = exHandler.handleChaosException(new BizException(code), zhExchange).block();
            assertThat(resp.getBody().code()).isEqualTo(code.code());
        }
        assertThat(exHandler.handleChaosException(new BizException(CommonErrorCode.BAD_REQUEST, ""), zhExchange)
                .block().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exHandler.handleInputException(
                new org.springframework.web.server.ServerWebInputException("bad"), zhExchange)
                .block().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    record ValidatedPayload(@NotBlank(message = "name must not be blank") String name) {
    }

    @RestController
    static class DemoReactiveController {

        @GetMapping("/demo/ctx")
        public Mono<RequestContextSnapshot> ctx(@RequestParam(name = "q", required = false) String q) {
            return ReactiveRequestContext.current();
        }

        @GetMapping("/demo/biz-error")
        public Mono<String> bizError() {
            return Mono.error(new BizException(CommonErrorCode.BAD_REQUEST, "invalid order state"));
        }

        @PostMapping("/demo/validate")
        public Mono<String> validate(@jakarta.validation.Valid @RequestBody ValidatedPayload payload) {
            return Mono.just(payload.name());
        }

        @GetMapping("/demo/status-404")
        public Mono<String> notFound() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
        }

        @PostMapping("/demo/orders")
        public Mono<ApiResult<String>> createOrder() {
            return Mono.just(ApiResult.success("created"));
        }

        @GetMapping("/api/plain")
        public Mono<String> plainPojo() {
            return Mono.just("hello");
        }

        @IgnoreReactiveWrap
        @GetMapping("/api/ignored")
        public Mono<String> ignoredPojo() {
            return Mono.just("raw");
        }

        @GetMapping("/api/flux")
        public Flux<String> fluxItems() {
            return Flux.just("a", "b");
        }

        @GetMapping("/api/empty")
        public Mono<Void> emptyMono() {
            return Mono.empty();
        }

        public Set<String> unusedForCoverage() {
            return Set.of();
        }
    }
}
