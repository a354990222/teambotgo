package com.chaos.autoconfigure.cloud;

import com.chaos.core.resilience.ResiliencePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Spring Cloud 与跨服务调用自动装配配置属性。
 */
@Validated
@ConfigurationProperties(prefix = "chaos.cloud")
public class ChaosCloudProperties {

    /**
     * OpenFeign 客户端适配配置。
     */
    @Valid
    @NotNull(message = "chaos.cloud.feign must not be null")
    private Feign feign = new Feign();

    /**
     * 服务间调用（OpenFeign 与 {@code ServiceHttpClients}）熔断、并发舱壁与超时预算配置。
     */
    @Valid
    @NotNull(message = "chaos.cloud.resilience must not be null")
    private Resilience resilience = new Resilience();

    public Feign getFeign() {
        return feign;
    }

    public void setFeign(Feign feign) {
        this.feign = feign == null ? new Feign() : feign;
    }

    public Resilience getResilience() {
        return resilience;
    }

    public void setResilience(Resilience resilience) {
        this.resilience = resilience == null ? new Resilience() : resilience;
    }

    /**
     * OpenFeign 适配配置。
     */
    public static class Feign {

        /**
         * 是否自动解包服务端 Result/ApiResult 信封，并在业务错误码非成功时翻译为 BizException。
         */
        private boolean unwrapResult = true;

        public boolean isUnwrapResult() {
            return unwrapResult;
        }

        public void setUnwrapResult(boolean unwrapResult) {
            this.unwrapResult = unwrapResult;
        }
    }

    /**
     * 下游调用熔断、并发舱壁与超时预算配置。
     */
    public static class Resilience {

        /**
         * 是否为 OpenFeign 与 {@code ServiceHttpClients} 启用熔断及舱壁保护，默认开启。
         */
        private boolean enabled = true;

        /**
         * 触发断路器打开的失败率百分比阈值（1..100）。
         */
        @Min(value = 1, message = "chaos.cloud.resilience.failure-rate-threshold-pct must be >= 1")
        @Max(value = 100, message = "chaos.cloud.resilience.failure-rate-threshold-pct must be <= 100")
        private int failureRateThresholdPct = 50;

        /**
         * 滑动统计窗口大小（按最近请求次数统计）。
         */
        @Min(value = 1, message = "chaos.cloud.resilience.sliding-window-size must be >= 1")
        private int slidingWindowSize = 20;

        /**
         * 计算失败率前至少需要的调用样本数。
         */
        @Min(value = 1, message = "chaos.cloud.resilience.minimum-number-of-calls must be >= 1")
        private int minimumNumberOfCalls = 10;

        /**
         * 断路器处于 OPEN 状态后的冷却等待时间。
         */
        @NotNull(message = "chaos.cloud.resilience.wait-duration-in-open-state must not be null")
        private Duration waitDurationInOpenState = Duration.ofSeconds(15);

        /**
         * 半开探测期允许放行的请求次数。
         */
        @Min(value = 1, message = "chaos.cloud.resilience.permitted-number-of-calls-in-half-open-state must be >= 1")
        private int permittedNumberOfCallsInHalfOpenState = 5;

        /**
         * 单目标服务并发舱壁上限；0 表示不限制并发。
         */
        @Min(value = 0, message = "chaos.cloud.resilience.max-concurrent-calls must be >= 0")
        private int maxConcurrentCalls = 0;

        /**
         * 单次下游调用额外超时预算；留空表示沿用底层 HTTP 客户端超时设置。
         */
        private Duration callTimeout;

        /**
         * 转换为内核弹性策略对象。
         */
        public ResiliencePolicy toPolicy() {
            return new ResiliencePolicy(
                    failureRateThresholdPct,
                    slidingWindowSize,
                    minimumNumberOfCalls,
                    waitDurationInOpenState,
                    permittedNumberOfCallsInHalfOpenState,
                    maxConcurrentCalls,
                    callTimeout);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureRateThresholdPct() {
            return failureRateThresholdPct;
        }

        public void setFailureRateThresholdPct(int failureRateThresholdPct) {
            this.failureRateThresholdPct = failureRateThresholdPct;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState == null
                    ? Duration.ofSeconds(15)
                    : waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public Duration getCallTimeout() {
            return callTimeout;
        }

        public void setCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
        }
    }
}
