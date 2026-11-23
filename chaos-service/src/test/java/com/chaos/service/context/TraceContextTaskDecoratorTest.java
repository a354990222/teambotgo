package com.chaos.service.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.trace.TraceContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 异步任务 trace 上下文装饰器测试。
 */
class TraceContextTaskDecoratorTest {

    /**
     * 每个用例结束后清理线程上下文。
     */
    @AfterEach
    void tearDown() {
        TraceContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * 装饰器应在任务执行时恢复提交线程的 trace 上下文。
     */
    @Test
    void shouldPropagateTraceContextToDecoratedTask() {
        TraceContext.start("trace-task", "span-task", "tenant-a", "user-a", "service-a");
        TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();
        AtomicReference<String> traceId = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> traceId.set(TraceContext.traceId()));
        TraceContext.clear();
        decorated.run();

        assertThat(traceId).hasValue("trace-task");
        assertThat(TraceContext.traceId()).isBlank();
    }

    /**
     * 装饰器应在存在 Spring Security 时传播并恢复认证上下文。
     */
    @Test
    void shouldPropagateSecurityContextToDecoratedTask() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();
        AtomicReference<Authentication> captured = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> captured.set(SecurityContextHolder.getContext().getAuthentication()));
        SecurityContextHolder.clearContext();
        decorated.run();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getName()).isEqualTo("alice");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
