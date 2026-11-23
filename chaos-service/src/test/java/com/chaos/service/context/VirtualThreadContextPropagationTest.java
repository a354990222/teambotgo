package com.chaos.service.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.context.ContextMdcKeys;
import com.chaos.core.context.RequestContext;
import com.chaos.trace.TraceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Java 21 虚拟线程下的上下文传播与隔离验证。
 */
class VirtualThreadContextPropagationTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /**
     * 多个虚拟线程并发执行时，Trace、租户、用户、MDC 与 Spring Security 上下文必须各自准确传播且互不串扰。
     */
    @Test
    void shouldIsolateAndPropagateContextAcrossConcurrentVirtualThreads() throws Exception {
        TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();
        int taskCount = 64;
        List<CompletableFuture<Void>> futures = new ArrayList<>(taskCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                String expectedTrace = "vt-trace-" + i;
                String expectedTenant = "tenant-" + i;
                String expectedUser = "user-" + i;
                TraceContext.start(expectedTrace, "vt-span-" + i, expectedTenant, expectedUser, "order-service");
                SecurityContextHolder.getContext().setAuthentication(
                        new TestingAuthenticationToken(expectedUser, "n/a", List.of()));

                AtomicBoolean virtualThread = new AtomicBoolean();
                AtomicReference<String> observed = new AtomicReference<>();
                Runnable task = decorator.decorate(() -> {
                    virtualThread.set(Thread.currentThread().isVirtual());
                    String authName = SecurityContextHolder.getContext().getAuthentication() != null
                            ? SecurityContextHolder.getContext().getAuthentication().getName()
                            : null;
                    observed.set(String.join("|",
                            TraceContext.traceId(),
                            RequestContext.tenantId(),
                            RequestContext.userId(),
                            String.valueOf(MDC.get(ContextMdcKeys.TRACE_ID)),
                            String.valueOf(authName)));
                });

                TraceContext.clear();
                SecurityContextHolder.clearContext();
                MDC.clear();

                futures.add(CompletableFuture.runAsync(task, executor).thenRun(() -> {
                    assertThat(virtualThread).isTrue();
                    assertThat(observed).hasValue(String.join("|",
                            expectedTrace, expectedTenant, expectedUser, expectedTrace, expectedUser));
                }));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
    }

    /**
     * Spring {@link SimpleAsyncTaskExecutor} 开启虚拟线程后应正确执行装饰器并清理线程上下文。
     */
    @Test
    void shouldPropagateContextOnSpringVirtualThreadExecutor() throws Exception {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("chaos-vt-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(new TraceContextTaskDecorator());

        TraceContext.start("vt-spring-trace", "vt-spring-span", "tenant-vt", "user-vt", "svc");
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-vt", "n/a", List.of()));

        AtomicBoolean isVirtual = new AtomicBoolean();
        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> afterCompletionTrace = new AtomicReference<>();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            capturedTrace.set(TraceContext.traceId());
            capturedUser.set(RequestContext.userId());
        }, executor);

        TraceContext.clear();
        SecurityContextHolder.clearContext();
        future.join();

        CompletableFuture<Void> plainRun = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                afterCompletionTrace.set(TraceContext.traceId());
                plainRun.complete(null);
            } catch (Throwable ex) {
                plainRun.completeExceptionally(ex);
            }
        });
        plainRun.join();

        assertThat(isVirtual).isTrue();
        assertThat(capturedTrace).hasValue("vt-spring-trace");
        assertThat(capturedUser).hasValue("user-vt");
        assertThat(afterCompletionTrace).hasValue("");
    }
}
