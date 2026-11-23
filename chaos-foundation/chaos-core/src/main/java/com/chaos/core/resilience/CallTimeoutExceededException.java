package com.chaos.core.resilience;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.exception.CommonErrorCode;
import java.time.Duration;

/**
 * 调用超过配置超时预算时抛出的异常。
 */
public class CallTimeoutExceededException extends ChaosException {

    private final String resourceName;
    private final Duration timeout;

    /**
     * 构造超时预算超限异常。
     *
     * @param resourceName 资源名称
     * @param timeout 配置的超时预算
     * @param cause 原始超时异常（可为 {@code null}）
     */
    public CallTimeoutExceededException(String resourceName, Duration timeout, Throwable cause) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE,
                "Call timeout exceeded for resource " + resourceName + " after " + timeout,
                cause);
        this.resourceName = resourceName;
        this.timeout = timeout;
    }

    /**
     * 返回超时的资源名称。
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * 返回配置的超时预算。
     */
    public Duration getTimeout() {
        return timeout;
    }
}
