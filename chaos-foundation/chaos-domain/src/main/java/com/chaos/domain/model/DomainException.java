package com.chaos.domain.model;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.core.exception.ErrorCode;

/**
 * 领域层业务规则与不变式异常。
 *
 * <p>聚合根与领域服务不应依赖 Spring 或 Web 框架，也不宜直接抛出含义模糊的 {@link IllegalArgumentException}
 * （会被 Web 全局异常处理器视为内部故障返回 500）。领域不变式校验失败时抛出该异常，Web 层将按 4xx 业务拒绝处理。</p>
 */
public class DomainException extends ChaosException {

    /**
     * 使用默认参数错误码（{@link CommonErrorCode#BAD_REQUEST}）创建领域异常。
     *
     * @param message 面向调用方的校验失败原因
     */
    public DomainException(String message) {
        super(CommonErrorCode.BAD_REQUEST, message);
    }

    /**
     * 使用指定领域错误码创建领域异常。
     *
     * @param errorCode 领域错误码
     */
    public DomainException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 使用指定领域错误码与消息创建领域异常。
     *
     * @param errorCode 领域错误码
     * @param message 面向调用方的错误消息
     */
    public DomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 使用指定领域错误码、消息与底层原因创建领域异常。
     *
     * @param errorCode 领域错误码
     * @param message 面向调用方的错误消息
     * @param cause 底层异常
     */
    public DomainException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
