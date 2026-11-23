package com.chaos.core.resilience;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.exception.CommonErrorCode;

/**
 * 并发舱壁已满（在途调用数达到上限）时抛出的异常。
 */
public class BulkheadFullException extends ChaosException {

    private final String resourceName;
    private final int maxConcurrentCalls;

    /**
     * 构造舱壁满载异常。
     *
     * @param resourceName 资源名
     * @param maxConcurrentCalls 允许的最大并发数
     */
    public BulkheadFullException(String resourceName, int maxConcurrentCalls) {
        super(CommonErrorCode.TOO_MANY_REQUESTS,
                "Bulkhead is full for resource " + resourceName + " (maxConcurrentCalls=" + maxConcurrentCalls + ")");
        this.resourceName = resourceName;
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    /**
     * 返回受保护的资源名称。
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * 返回允许的最大并发调用数。
     */
    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }
}
