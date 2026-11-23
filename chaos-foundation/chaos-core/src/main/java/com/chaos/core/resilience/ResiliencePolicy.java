package com.chaos.core.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * 熔断、并发舱壁与调用超时策略。
 *
 * @param failureRateThresholdPct 触发熔断的失败率百分比（1..100）
 * @param slidingWindowSize 统计失败率的滑动计数窗口大小（>= 1）
 * @param minimumNumberOfCalls 触发失败率判定前的最小样本数（>= 1）
 * @param waitDurationInOpenState 熔断打开后的冷却等待时间
 * @param permittedNumberOfCallsInHalfOpenState 半开态允许的探测请求数（>= 1）
 * @param maxConcurrentCalls 并发舱壁最大在途请求数；小于等于 0 表示不限制并发
 * @param callTimeout 单次调用超时预算；{@code null} 或非正数表示不额外覆盖底层客户端超时
 */
public record ResiliencePolicy(
        int failureRateThresholdPct,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState,
        int maxConcurrentCalls,
        Duration callTimeout
) {

    public ResiliencePolicy {
        if (failureRateThresholdPct < 1 || failureRateThresholdPct > 100) {
            throw new IllegalArgumentException("failureRateThresholdPct must be between 1 and 100");
        }
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("slidingWindowSize must be >= 1");
        }
        if (minimumNumberOfCalls < 1) {
            throw new IllegalArgumentException("minimumNumberOfCalls must be >= 1");
        }
        Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
        if (waitDurationInOpenState.isNegative() || waitDurationInOpenState.isZero()) {
            throw new IllegalArgumentException("waitDurationInOpenState must be positive");
        }
        if (permittedNumberOfCallsInHalfOpenState < 1) {
            throw new IllegalArgumentException("permittedNumberOfCallsInHalfOpenState must be >= 1");
        }
    }

    /**
     * 返回适用于多数微服务的默认弹性策略（无并发上限与额外超时覆盖）。
     */
    public static ResiliencePolicy defaults() {
        return new ResiliencePolicy(50, 20, 10, Duration.ofSeconds(15), 5, 0, null);
    }

    /**
     * 判断是否配置了有效的调用超时预算。
     */
    public boolean hasTimeout() {
        return callTimeout != null && !callTimeout.isNegative() && !callTimeout.isZero();
    }

    /**
     * 判断是否启用了并发舱壁限制。
     */
    public boolean hasBulkhead() {
        return maxConcurrentCalls > 0;
    }
}
