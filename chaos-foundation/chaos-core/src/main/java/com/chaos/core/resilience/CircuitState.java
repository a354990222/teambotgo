package com.chaos.core.resilience;

/**
 * 断路器状态机枚举。
 */
public enum CircuitState {
    /**
     * 闭合态：正常放行请求并统计滑动窗口失败率。
     */
    CLOSED,
    /**
     * 熔断打开态：快速失败拒绝请求，直到冷却等待期结束。
     */
    OPEN,
    /**
     * 半开探测态：放行有限探测请求以决定恢复闭合或重新打开。
     */
    HALF_OPEN
}
