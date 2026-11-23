package com.chaos.autoconfigure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.core.context.RequestContext;
import com.chaos.core.exception.BizException;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.service.context.TraceContextTaskDecorator;
import com.chaos.service.json.JsonUtils;
import com.chaos.service.retry.RetryExecutor;
import com.chaos.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * Service 自动装配测试。
 */
class ChaosServiceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChaosServiceAutoConfiguration.class))
            .withPropertyValues("chaos.service.retry.backoff-ms=0");

    /**
     * 默认注册上下文传播 TaskDecorator。
     */
    @Test
    void shouldRegisterTraceContextTaskDecorator() {
        contextRunner.run(context -> assertThat(context.getBean(TaskDecorator.class))
                .isInstanceOf(TraceContextTaskDecorator.class));
    }

    /**
     * 用户自定义 TaskDecorator 时框架让位，保证容器中只有一个装饰器，Boot 的 getIfUnique 才能生效。
     */
    @Test
    void shouldBackOffWhenCustomTaskDecoratorPresent() {
        contextRunner.withUserConfiguration(CustomDecoratorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskDecorator.class);
                    assertThat(context.getBean(TaskDecorator.class)).isNotInstanceOf(TraceContextTaskDecorator.class);
                });
    }

    /**
     * 默认重试执行器不重试业务异常，但会重试其他异常。
     */
    @Test
    void defaultRetryShouldSkipBizException() {
        contextRunner.run(context -> {
            RetryExecutor executor = context.getBean(RetryExecutor.class);
            AtomicInteger bizAttempts = new AtomicInteger();
            assertThatThrownBy(() -> executor.execute((Runnable) () -> {
                bizAttempts.incrementAndGet();
                throw new BizException(CommonErrorCode.BAD_REQUEST);
            })).isInstanceOf(BizException.class);
            assertThat(bizAttempts).hasValue(1);

            AtomicInteger ioAttempts = new AtomicInteger();
            assertThatThrownBy(() -> executor.execute((Runnable) () -> {
                ioAttempts.incrementAndGet();
                throw new IllegalStateException("temporary");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(ioAttempts).hasValue(3);
        });
    }

    /**
     * Micrometer 上下文桥接需要显式开启。
     */
    @Test
    void micrometerBridgeShouldBeOptIn() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean("chaosContextRegistryRegistrar"));
        contextRunner.withPropertyValues("chaos.service.context-propagation.micrometer-enabled=true")
                .run(context -> assertThat(context).hasBean("chaosContextRegistryRegistrar"));
    }

    @Test
    void shouldRegisterJsonUtilsWhenObjectMapperExists() {
        contextRunner.withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> assertThat(context).hasSingleBean(JsonUtils.class));
    }

    @Test
    void shouldRegisterJsonUtilsAfterJacksonAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ChaosServiceAutoConfiguration.class,
                        JacksonAutoConfiguration.class
                ))
                .run(context -> assertThat(context).hasSingleBean(JsonUtils.class));
    }

    @Test
    void shouldBackOffWhenCustomJsonUtilsExists() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonUtils custom = new JsonUtils(objectMapper);
        contextRunner.withBean(ObjectMapper.class, () -> objectMapper)
                .withBean(JsonUtils.class, () -> custom)
                .run(context -> assertThat(context.getBean(JsonUtils.class)).isSameAs(custom));
    }

    /**
     * 开启 spring.threads.virtual.enabled=true 时，Boot 装配的虚拟线程执行器应自动接入 TraceContextTaskDecorator。
     */
    @Test
    void shouldWireTaskDecoratorIntoVirtualThreadTaskExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ChaosServiceAutoConfiguration.class,
                        TaskExecutionAutoConfiguration.class
                ))
                .withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AsyncTaskExecutor executor = context.getBean(AsyncTaskExecutor.class);
                    TraceContext.start("auto-vt-trace", "auto-vt-span", "tenant-auto", "user-auto", "svc");
                    AtomicBoolean virtual = new AtomicBoolean();
                    AtomicReference<String> tenantId = new AtomicReference<>();
                    try {
                        CompletableFuture<Void> future = executor.submitCompletable(() -> {
                            virtual.set(Thread.currentThread().isVirtual());
                            tenantId.set(RequestContext.tenantId());
                        });
                        TraceContext.clear();
                        future.join();
                    } finally {
                        TraceContext.clear();
                    }
                    assertThat(virtual).isTrue();
                    assertThat(tenantId).hasValue("tenant-auto");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDecoratorConfiguration {

        @Bean
        TaskDecorator customTaskDecorator() {
            return runnable -> runnable;
        }
    }
}
