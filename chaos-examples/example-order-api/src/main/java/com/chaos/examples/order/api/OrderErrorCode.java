package com.chaos.examples.order.api;

import com.chaos.core.exception.ErrorCode;

/**
 * 订单服务对外稳定错误码。
 */
public enum OrderErrorCode implements ErrorCode {

    /**
     * 订单金额不合法。
     */
    ORDER_AMOUNT_INVALID("ORDER_400", "order amount must be greater than 0"),

    /**
     * 订单不存在。
     */
    ORDER_NOT_FOUND("ORDER_404", "order not found");

    private final String code;

    private final String message;

    OrderErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
