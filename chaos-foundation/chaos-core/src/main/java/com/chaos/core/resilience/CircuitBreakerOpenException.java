package com.chaos.core.resilience;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.exception.CommonErrorCode;

/**
 * 断路器处于打开状态（快速失败）时抛出的异常。
 */
public class CircuitBreakerOpenException extends ChaosException {

    private final String resourceName;

    /**
     * 构造断路器打开异常。
     *
     * @param resourceName 熔断资源名
     */
    public CircuitBreakerOpenException(String resourceName) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE, "Circuit breaker is OPEN for resource: " + resourceName);
        this.resourceName = resourceName;
    }

    /**
     * 返回触发熔断的资源名称。
     */
    public String getResourceName() {
        return resourceName;
    }
}
