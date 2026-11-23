package com.chaos.autoconfigure.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chaos.core.diagnostic.ChaosDiagnosticException;
import com.chaos.web.reactive.exception.ReactiveGlobalExceptionHandler;
import com.chaos.web.reactive.filter.ReactiveTraceWebFilter;
import com.chaos.web.reactive.idempotent.InMemoryReactiveIdempotentRepository;
import com.chaos.web.reactive.idempotent.ReactiveIdempotentRepository;
import com.chaos.web.reactive.idempotent.ReactiveIdempotentWebFilter;
import com.chaos.web.reactive.idempotent.RedisReactiveIdempotentRepository;
import com.chaos.web.reactive.ratelimit.InMemoryReactiveRateLimiter;
import com.chaos.web.reactive.ratelimit.ReactiveRateLimitWebFilter;
import com.chaos.web.reactive.ratelimit.ReactiveRateLimiter;
import com.chaos.web.reactive.ratelimit.RedisReactiveRateLimiter;
import com.chaos.web.reactive.result.ReactiveResultHandler;
import com.chaos.web.reactive.xss.ReactiveSecurityWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * {@link ChaosWebReactiveAutoConfiguration} 自动装配单元测试。
 */
class ChaosWebReactiveAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader(org.springframework.cloud.gateway.filter.GlobalFilter.class))
            .withConfiguration(AutoConfigurations.of(
                    WebFluxAutoConfiguration.class,
                    ChaosWebReactiveAutoConfiguration.class));

    @Test
    void shouldRegisterAllReactiveWebComponentsWhenNotGateway() {
        contextRunner
                .withPropertyValues("spring.application.name=reactive-order")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ReactiveGlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(ReactiveResultHandler.class);
                    assertThat(context).hasSingleBean(ReactiveTraceWebFilter.class);
                    assertThat(context).hasSingleBean(ReactiveSecurityWebFilter.class);
                    assertThat(context).hasSingleBean(ReactiveRateLimitWebFilter.class);
                    assertThat(context).hasSingleBean(ReactiveIdempotentWebFilter.class);
                    assertThat(context.getBean(ReactiveRateLimiter.class))
                            .isInstanceOf(InMemoryReactiveRateLimiter.class);
                    assertThat(context.getBean(ReactiveIdempotentRepository.class))
                            .isInstanceOf(InMemoryReactiveIdempotentRepository.class);
                });
    }

    @Test
    void shouldPreferRedisReactiveStoresAndEnforceProductionSafety() {
        contextRunner
                .withBean(ReactiveStringRedisTemplate.class, () -> mock(ReactiveStringRedisTemplate.class))
                .withPropertyValues("chaos.production-safety.production-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ReactiveRateLimiter.class))
                            .isInstanceOf(RedisReactiveRateLimiter.class);
                    assertThat(context.getBean(ReactiveIdempotentRepository.class))
                            .isInstanceOf(RedisReactiveIdempotentRepository.class);
                });

        contextRunner
                .withPropertyValues("chaos.production-safety.production-mode=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ChaosDiagnosticException.class);
                });
    }

    @Test
    void shouldBackOffInsideGatewayClasspath() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebFluxAutoConfiguration.class,
                        ChaosWebReactiveAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ReactiveGlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(ReactiveTraceWebFilter.class);
                });
    }
}
