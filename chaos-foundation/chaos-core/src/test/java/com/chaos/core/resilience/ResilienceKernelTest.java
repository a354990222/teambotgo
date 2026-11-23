package com.chaos.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chaos.core.context.RequestContext;
import com.chaos.core.context.RequestContextSnapshot;
import com.chaos.core.metrics.ChaosMeterNames;
import com.chaos.core.metrics.RecordingChaosMetrics;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@link SlidingWindowCircuitBreaker}、{@link SemaphoreBulkhead}、{@link ResilienceRegistry}
 * 与 {@link ResilienceExecutor} 单元测试。
 */
class ResilienceKernelTest {

    @Test
    void shouldTransitionClosedToOpenToHalfOpenToClosedAndBackToOpen() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ResiliencePolicy policy = new ResiliencePolicy(
                50, 4, 4, Duration.ofSeconds(10), 2, 0, null);
        RecordingChaosMetrics metrics = new RecordingChaosMetrics();
        ResilienceRegistry registry = new ResilienceRegistry(
                "feign", policy, Map.of(), metrics, clock::get);
        SlidingWindowCircuitBreaker breaker = registry.guard("order-service").circuitBreaker();

        assertEquals(CircuitState.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquirePermission());
        breaker.onSuccess();
        assertTrue(breaker.tryAcquirePermission());
        breaker.onSuccess();
        assertTrue(breaker.tryAcquirePermission());
        breaker.onFailure();
        assertTrue(breaker.tryAcquirePermission());
        breaker.onFailure();

        assertEquals(CircuitState.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission());

        // 冷却期未过前保持 OPEN；冷却期满后进入 HALF_OPEN 并只放行 2 次探测
        clock.addAndGet(Duration.ofSeconds(11).toNanos());
        assertTrue(breaker.tryAcquirePermission());
        assertEquals(CircuitState.HALF_OPEN, breaker.getState());
        assertTrue(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission());

        // 2 次探测全部成功 -> 恢复 CLOSED
        breaker.onSuccess();
        breaker.onSuccess();
        assertEquals(CircuitState.CLOSED, breaker.getState());

        // 再次打满失败率 -> OPEN -> HALF_OPEN -> 探测失败重新 OPEN
        for (int i = 0; i < 4; i++) {
            assertTrue(breaker.tryAcquirePermission());
            breaker.onFailure();
        }
        assertEquals(CircuitState.OPEN, breaker.getState());
        clock.addAndGet(Duration.ofSeconds(11).toNanos());
        assertTrue(breaker.tryAcquirePermission());
        assertTrue(breaker.tryAcquirePermission());
        breaker.onFailure();
        breaker.onSuccess();
        assertEquals(CircuitState.OPEN, breaker.getState());

        long openTransitions = metrics.recorded().stream()
                .filter(item -> item.equals(
                        ChaosMeterNames.RESILIENCE_STATE_TRANSITIONS + "{source,feign,outcome,open}"))
                .count();
        assertEquals(2L, openTransitions);
    }

    @Test
    void shouldEnforceBulkheadTimeoutBudgetAndIgnoreNonResilienceErrors() throws Exception {
        ResiliencePolicy policy = new ResiliencePolicy(
                50, 4, 2, Duration.ofSeconds(5), 1, 1, Duration.ofMillis(80));
        RecordingChaosMetrics metrics = new RecordingChaosMetrics();
        ResilienceRegistry registry = new ResilienceRegistry("http-client", policy, Map.of(), metrics);
        ResilienceExecutor executor = new ResilienceExecutor(registry);

        // 4xx 等非故障异常不计入断路器失败率
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> executor.execute("svc-a", () -> {
                throw new IllegalArgumentException("client 400");
            }, ex -> !(ex instanceof IllegalArgumentException)));
        }
        assertEquals(CircuitState.CLOSED, registry.guard("svc-a").circuitBreaker().getState());

        // 验证超时预算与 RequestContext 传播
        RequestContext.set(new RequestContextSnapshot("trace-res-1", "span-1", "t1", "u1", "app"));
        try {
            String traceObserved = executor.execute("svc-b", () ->
                    RequestContext.current().map(RequestContextSnapshot::traceId).orElse(""));
            assertEquals("trace-res-1", traceObserved);
        } finally {
            RequestContext.clear();
        }

        assertThrows(CallTimeoutExceededException.class, () -> executor.execute("svc-timeout", () -> {
            Thread.sleep(300);
            return "late";
        }));
        assertTrue(metrics.recorded().contains(
                ChaosMeterNames.RESILIENCE_CALLS + "{source,http-client,outcome,timeout}"));

        // 验证并发舱壁满载快速拒绝
        ResiliencePolicy bulkheadPolicy = new ResiliencePolicy(
                50, 4, 2, Duration.ofSeconds(5), 1, 1, null);
        ResilienceExecutor bhExecutor = new ResilienceExecutor(
                new ResilienceRegistry("feign", bulkheadPolicy));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<String> first = pool.submit(() -> bhExecutor.execute("svc-bh", () -> {
                inside.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "ok";
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            assertThrows(BulkheadFullException.class, () -> bhExecutor.execute("svc-bh", () -> "second"));
            release.countDown();
            assertEquals("ok", first.get(5, TimeUnit.SECONDS));
        }
    }
}
