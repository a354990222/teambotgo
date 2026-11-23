package com.chaos.core.resilience;

import com.chaos.core.metrics.ChaosMeterNames;
import com.chaos.core.metrics.ChaosMetrics;
import com.chaos.core.metrics.NoopChaosMetrics;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 断路器与并发舱壁注册中心。
 */
public class ResilienceRegistry {

    private final ResiliencePolicy defaultPolicy;
    private final Map<String, ResiliencePolicy> policyOverrides;
    private final String sourceTag;
    private final ChaosMetrics metrics;
    private final LongSupplier nanoTimeSource;
    private final ConcurrentMap<String, ResourceGuard> guards = new ConcurrentHashMap<>();

    /**
     * 使用默认策略与空指标创建注册中心。
     */
    public ResilienceRegistry(String sourceTag, ResiliencePolicy defaultPolicy) {
        this(sourceTag, defaultPolicy, Map.of(), NoopChaosMetrics.instance(), System::nanoTime);
    }

    /**
     * 创建支持按资源覆盖策略与治理指标上报的注册中心。
     */
    public ResilienceRegistry(
            String sourceTag,
            ResiliencePolicy defaultPolicy,
            Map<String, ResiliencePolicy> policyOverrides,
            ChaosMetrics metrics) {
        this(sourceTag, defaultPolicy, policyOverrides, metrics, System::nanoTime);
    }

    /**
     * 完整构造方法（支持单元测试注入单调时钟）。
     */
    public ResilienceRegistry(
            String sourceTag,
            ResiliencePolicy defaultPolicy,
            Map<String, ResiliencePolicy> policyOverrides,
            ChaosMetrics metrics,
            LongSupplier nanoTimeSource) {
        this.sourceTag = (sourceTag == null || sourceTag.isBlank()) ? "core" : sourceTag;
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy must not be null");
        this.policyOverrides = policyOverrides == null
                ? Map.of()
                : Collections.unmodifiableMap(new ConcurrentHashMap<>(policyOverrides));
        this.metrics = metrics == null ? NoopChaosMetrics.instance() : metrics;
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
    }

    /**
     * 获取或创建指定资源的断路器与舱壁守卫。
     */
    public ResourceGuard guard(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName must not be blank");
        }
        return guards.computeIfAbsent(resourceName, name -> {
            ResiliencePolicy policy = policyOverrides.getOrDefault(name, defaultPolicy);
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(
                    name,
                    policy,
                    nanoTimeSource,
                    (from, to) -> metrics.increment(
                            ChaosMeterNames.RESILIENCE_STATE_TRANSITIONS,
                            ChaosMeterNames.TAG_SOURCE, sourceTag,
                            ChaosMeterNames.TAG_OUTCOME, to.name().toLowerCase(Locale.ROOT)));
            SemaphoreBulkhead bulkhead = policy.hasBulkhead()
                    ? new SemaphoreBulkhead(name, policy.maxConcurrentCalls())
                    : null;
            return new ResourceGuard(policy, breaker, bulkhead);
        });
    }

    /**
     * 上报一次调用决策结果指标。
     */
    public void recordCall(String outcome) {
        metrics.increment(
                ChaosMeterNames.RESILIENCE_CALLS,
                ChaosMeterNames.TAG_SOURCE, sourceTag,
                ChaosMeterNames.TAG_OUTCOME, outcome);
    }

    /**
     * 返回注册中心来源标签（gateway / feign / http-client 等）。
     */
    public String getSourceTag() {
        return sourceTag;
    }

    /**
     * 返回默认弹性策略。
     */
    public ResiliencePolicy getDefaultPolicy() {
        return defaultPolicy;
    }

    /**
     * 单个受保护资源的策略、断路器与可选舱壁组合。
     *
     * @param policy 弹性策略
     * @param circuitBreaker 断路器实例
     * @param bulkhead 并发舱壁（未启用时为 {@code null}）
     */
    public record ResourceGuard(
            ResiliencePolicy policy,
            SlidingWindowCircuitBreaker circuitBreaker,
            SemaphoreBulkhead bulkhead
    ) {
    }
}
