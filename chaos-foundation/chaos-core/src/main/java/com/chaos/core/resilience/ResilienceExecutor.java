package com.chaos.core.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 同步调用弹性执行器：组合并发舱壁、断路器与可选虚拟线程超时预算。
 */
public final class ResilienceExecutor {

    private static final ExecutorService VIRTUAL_TIMEOUT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ResilienceRegistry registry;

    /**
     * 创建绑定到指定注册中心的执行器。
     */
    public ResilienceExecutor(ResilienceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * 使用默认失败判定执行受保护调用。
     */
    public <T> T execute(String resourceName, Callable<T> action) throws Exception {
        return execute(resourceName, action, ex -> true);
    }

    /**
     * 在舱壁、断路器与超时预算保护下执行同步调用；{@code failureClassifier} 返回 {@code false} 的异常不会计入断路器失败率。
     */
    public <T> T execute(
            String resourceName,
            Callable<T> action,
            Predicate<Throwable> failureClassifier) throws Exception {
        Objects.requireNonNull(action, "action must not be null");
        Predicate<Throwable> countsAsFailure = failureClassifier == null ? ex -> true : failureClassifier;

        ResilienceRegistry.ResourceGuard guard = registry.guard(resourceName);
        SemaphoreBulkhead bulkhead = guard.bulkhead();
        if (bulkhead != null && !bulkhead.tryAcquire()) {
            registry.recordCall("bulkhead-full");
            throw new BulkheadFullException(resourceName, bulkhead.getMaxConcurrentCalls());
        }
        SlidingWindowCircuitBreaker breaker = guard.circuitBreaker();
        boolean breakerAcquired = false;
        try {
            if (!breaker.tryAcquirePermission()) {
                registry.recordCall("circuit-open");
                throw new CircuitBreakerOpenException(resourceName);
            }
            breakerAcquired = true;
            T result = invokeWithOptionalTimeout(resourceName, guard.policy(), action);
            breaker.onSuccess();
            registry.recordCall("success");
            return result;
        } catch (CallTimeoutExceededException ex) {
            if (breakerAcquired) {
                breaker.onFailure();
            }
            registry.recordCall("timeout");
            throw ex;
        } catch (Throwable ex) {
            if (breakerAcquired) {
                if (countsAsFailure.test(ex)) {
                    breaker.onFailure();
                    registry.recordCall("failure");
                } else {
                    breaker.onSuccess();
                }
            }
            if (ex instanceof Exception checked) {
                throw checked;
            }
            throw new RuntimeException(ex);
        } finally {
            if (bulkhead != null) {
                bulkhead.release();
            }
        }
    }

    private static <T> T invokeWithOptionalTimeout(
            String resourceName,
            ResiliencePolicy policy,
            Callable<T> action) throws Exception {
        if (!policy.hasTimeout()) {
            return action.call();
        }
        Duration timeout = policy.callTimeout();
        var capturedContext = com.chaos.core.context.RequestContext.current();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            capturedContext.ifPresent(com.chaos.core.context.RequestContext::set);
            try {
                return action.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new CheckedCallableWrapper(ex);
            } finally {
                com.chaos.core.context.RequestContext.clear();
            }
        }, VIRTUAL_TIMEOUT_EXECUTOR);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new CallTimeoutExceededException(resourceName, timeout, ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof CheckedCallableWrapper wrapper) {
                throw wrapper.checked;
            }
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static final class CheckedCallableWrapper extends RuntimeException {
        private final Exception checked;

        private CheckedCallableWrapper(Exception checked) {
            super(checked);
            this.checked = checked;
        }
    }
}
