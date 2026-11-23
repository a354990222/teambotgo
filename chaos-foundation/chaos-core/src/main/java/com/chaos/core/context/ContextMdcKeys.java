package com.chaos.core.context;

/**
 * 框架统一的 MDC 字段名常量。
 *
 * <p>供基础安全、上下文与可观测性模块共享，避免各模块硬编码字符串造成漂移。</p>
 */
public final class ContextMdcKeys {

    /**
     * 链路追踪 ID。
     */
    public static final String TRACE_ID = "traceId";

    /**
     * 当前调用跨度 ID。
     */
    public static final String SPAN_ID = "spanId";

    /**
     * 用户 ID。
     */
    public static final String USER_ID = "userId";

    /**
     * 租户 ID。
     */
    public static final String TENANT_ID = "tenantId";

    /**
     * 应用名称。
     */
    public static final String APP_NAME = "appName";

    /**
     * 请求 URI。
     */
    public static final String URI = "uri";

    /**
     * 客户端 IP。
     */
    public static final String IP = "ip";

    /**
     * 请求耗时，单位毫秒。
     */
    public static final String COST = "cost";

    private ContextMdcKeys() {
    }
}
