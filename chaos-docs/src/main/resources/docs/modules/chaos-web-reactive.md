# chaos-web-reactive

## 职责

面向 Spring WebFlux 响应式业务微服务的治理增强模块（包 `com.chaos.web.reactive`）：

- **统一响应封装**：`ReactiveResultHandler`（继承 `ResponseBodyResultHandler`，`order = -1`），自动将非 `ApiResult`、非 `HttpEntity`/`Resource`/`byte[]` 的 `Mono` / `Flux` / 同步控制器返回包装为统一 `ApiResult`（携带当前请求 `traceId`）；支持 `@IgnoreReactiveWrap` 注解与路径白名单跳过（默认排除 `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`），同时跳过 SSE / NDJSON 流式响应；
- **统一全局异常处理**：`ReactiveGlobalExceptionHandler`（`@RestControllerAdvice`），处理 `ChaosException`、`WebExchangeBindException`、`ConstraintViolationException`、`ServerWebInputException`、数据写入冲突（409）、Spring `ErrorResponse` 异常及未知异常，输出与 MVC 一致的 `ApiResult<Void>`；
- **Trace 与上下文传播**：`ReactiveTraceWebFilter`（`HIGHEST_PRECEDENCE + 2`）解析 `X-Trace-Id`、`traceparent`、`X-Span-Id`、`X-Tenant-Id`、`X-User-Id`，回写 `X-Trace-Id`，并通过 Reactor `Context` 与 `ReactiveRequestContext.current()` 向下游算子传播；
- **非阻塞限流与幂等**：`ReactiveRateLimitWebFilter`（`ReactiveRateLimiter`：`RedisReactiveRateLimiter` Lua 脚本或兜底 `InMemoryReactiveRateLimiter`）与 `ReactiveIdempotentWebFilter`（`ReactiveIdempotentRepository`：`RedisReactiveIdempotentRepository` 原子 `SET NX` 或兜底 `InMemoryReactiveIdempotentRepository`）；
- **XSS 与安全响应头**：`ReactiveSecurityWebFilter` 写入 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer` 并校验查询参数中的危险脚本片段。

## 依赖方式

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-web-reactive-starter</artifactId>
</dependency>
```

> 实际入口为 [chaos-cloud-reactive-starter](chaos-starters.md)（已聚合 `chaos-web-reactive`、`spring-boot-starter-webflux`、`chaos-trace`、`chaos-autoconfigure` 与 `loadbalancer`/`actuator`/`validation`）；不可与基于 Servlet 的 `chaos-web` / `chaos-web-service-starter` 同服务混用。如需多实例集群限流与幂等，请同时引入 `spring-boot-starter-data-redis-reactive`。

## 配置

```yaml
chaos:
  web:
    reactive:
      enabled: true
      response-wrap-enabled: true
      response-wrap-exclude-paths:
        - /actuator/**
        - /v3/api-docs/**
        - /swagger-ui/**
      trace-enabled: true
      xss-enabled: true
      rate-limit:
        enabled: true
        default-permits-per-second: 100
        fail-open: true
        skip-paths:
          - /actuator/health/**
      idempotent:
        enabled: true
        header-name: Idempotency-Key
        ttl: 10m
        methods: [POST, PUT, PATCH, DELETE]
```

## 注意事项

- 在 Spring Cloud Gateway 进程中（存在 `GlobalFilter` 类时），`ChaosWebReactiveAutoConfiguration` 自动让位给 Gateway 自带的鉴权/限流/降级链，避免过滤器冲突。
- 无 `ReactiveStringRedisTemplate` 时会注册内存版限流器和幂等仓储，在生产模式下会被 `ProductionSafetyEnforcer` 拦截阻断启动。
- 业务处理器读取当前 Trace/租户/用户上下文时，应使用 `ReactiveRequestContext.current()` 在响应式流中订阅读取，避免在异步线程切换中误用同步 `ThreadLocal`。
