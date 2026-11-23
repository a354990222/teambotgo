package com.chaos.core.resilience;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * 基于计数滑动窗口的线程安全断路器状态机（{@code CLOSED -> OPEN -> HALF_OPEN}）。
 *
 * <p>内部仅使用极短临界区的 {@link ReentrantLock} 更新窗口环形数组与状态枚举，
 * 不在临界区内执行任何下游 I/O，兼容 JDK 21 虚拟线程调度。</p>
 */
public class SlidingWindowCircuitBreaker {

    private final String resourceName;
    private final ResiliencePolicy policy;
    private final LongSupplier nanoTimeSource;
    private final BiConsumer<CircuitState, CircuitState> transitionListener;
    private final ReentrantLock lock = new ReentrantLock();

    private final boolean[] window;
    private int cursor;
    private int recordedCount;
    private int failureCount;

    private CircuitState state = CircuitState.CLOSED;
    private long openUntilNanos;
    private int halfOpenAcquired;
    private int halfOpenCompleted;
    private int halfOpenFailures;

    /**
     * 使用系统单调时钟创建断路器。
     */
    public SlidingWindowCircuitBreaker(String resourceName, ResiliencePolicy policy) {
        this(resourceName, policy, System::nanoTime, (from, to) -> {
        });
    }

    /**
     * 创建支持自定义时钟与状态切换回调的断路器。
     */
    public SlidingWindowCircuitBreaker(
            String resourceName,
            ResiliencePolicy policy,
            LongSupplier nanoTimeSource,
            BiConsumer<CircuitState, CircuitState> transitionListener) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName must not be blank");
        }
        this.resourceName = resourceName;
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        this.transitionListener = transitionListener == null ? (from, to) -> {
        } : transitionListener;
        this.window = new boolean[policy.slidingWindowSize()];
    }

    /**
     * 尝试获取一次调用许可；当处于 {@code OPEN} 且冷却未结束，或 {@code HALF_OPEN} 探测配额用尽时返回 {@code false}。
     */
    public boolean tryAcquirePermission() {
        CircuitState previous = null;
        CircuitState next = null;
        boolean permitted = false;
        lock.lock();
        try {
            long now = nanoTimeSource.getAsLong();
            if (state == CircuitState.OPEN) {
                if (now - openUntilNanos < 0) {
                    return false;
                }
                previous = state;
                transitionToHalfOpen();
                next = state;
            }
            if (state == CircuitState.HALF_OPEN) {
                if (halfOpenAcquired >= policy.permittedNumberOfCallsInHalfOpenState()) {
                    return false;
                }
                halfOpenAcquired++;
                permitted = true;
            } else {
                permitted = true;
            }
        } finally {
            lock.unlock();
        }
        if (previous != null && next != null) {
            transitionListener.accept(previous, next);
        }
        return permitted;
    }

    /**
     * 记录一次成功调用。
     */
    public void onSuccess() {
        recordOutcome(false);
    }

    /**
     * 记录一次失败调用（传输异常、超时或 5xx 响应）。
     */
    public void onFailure() {
        recordOutcome(true);
    }

    /**
     * 返回当前断路器状态（若处于 {@code OPEN} 且等待时间已过，会顺带感知为可进入半开态，但不改变外部只读语义之外的状态）。
     */
    public CircuitState getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回资源名称。
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * 返回当前绑定的策略。
     */
    public ResiliencePolicy getPolicy() {
        return policy;
    }

    private void recordOutcome(boolean failed) {
        CircuitState previous = null;
        CircuitState next = null;
        lock.lock();
        try {
            if (state == CircuitState.OPEN) {
                return;
            }
            if (state == CircuitState.HALF_OPEN) {
                halfOpenCompleted++;
                if (failed) {
                    halfOpenFailures++;
                }
                if (halfOpenCompleted >= policy.permittedNumberOfCallsInHalfOpenState()) {
                    previous = state;
                    double failureRatePct = halfOpenFailures * 100.0 / halfOpenCompleted;
                    if (failureRatePct >= policy.failureRateThresholdPct()) {
                        transitionToOpen(nanoTimeSource.getAsLong());
                    } else {
                        transitionToClosed();
                    }
                    next = state;
                }
                return;
            }

            if (recordedCount == window.length) {
                if (window[cursor]) {
                    failureCount--;
                }
            } else {
                recordedCount++;
            }
            window[cursor] = failed;
            if (failed) {
                failureCount++;
            }
            cursor = (cursor + 1) % window.length;

            if (recordedCount >= policy.minimumNumberOfCalls()) {
                double failureRatePct = failureCount * 100.0 / recordedCount;
                if (failureRatePct >= policy.failureRateThresholdPct()) {
                    previous = state;
                    transitionToOpen(nanoTimeSource.getAsLong());
                    next = state;
                }
            }
        } finally {
            lock.unlock();
        }
        if (previous != null && next != null) {
            transitionListener.accept(previous, next);
        }
    }

    private void transitionToOpen(long nowNanos) {
        state = CircuitState.OPEN;
        openUntilNanos = nowNanos + policy.waitDurationInOpenState().toNanos();
        resetWindow();
    }

    private void transitionToHalfOpen() {
        state = CircuitState.HALF_OPEN;
        halfOpenAcquired = 0;
        halfOpenCompleted = 0;
        halfOpenFailures = 0;
    }

    private void transitionToClosed() {
        state = CircuitState.CLOSED;
        resetWindow();
    }

    private void resetWindow() {
        java.util.Arrays.fill(window, false);
        cursor = 0;
        recordedCount = 0;
        failureCount = 0;
    }
}
