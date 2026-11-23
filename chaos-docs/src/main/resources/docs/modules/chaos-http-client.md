# chaos-http-client

## 职责

服务间 HTTP 与 OpenFeign 调用适配模块（包 `com.chaos.http.client` 与 `com.chaos.http.client.feign`）：

- **受限 `RestTemplate` 客户端工厂**：`ServiceHttpClients` / `ServiceHttpClient`，强制统一连接与读取超时（`ServiceHttpDefaults`）、校验外部地址避免 SSRF 风险（`ServiceHttpAddress`）、禁止自动跟随 HTTP 重定向，并自动透传 `TraceHeaders.outgoing()`；
- **OpenFeign 统一响应解包与错误翻译**：`ChaosFeignResultDecoder` 将下游返回的 `Result<T>` / `DefaultApiResult<T>` 信封解包为接口返回类型；`ChaosFeignErrorDecoder` 与 `ServiceErrorTranslator` 将下游失败编码（含 HTTP 非 2xx 或业务失败码）翻译为 `BizException`（`RemoteServiceErrorCode`）；
- **内部服务令牌透传**：`ServiceTokenProvider`（内置 `RequestBearerTokenProvider`、`ClientCredentialsTokenProvider`）与 `ServiceTokenFeignInterceptor`，由业务方按需注册 Bean 启用；
- **熔断、并发舱壁与超时预算**：基于 `chaos-core` 内核 `ResilienceRegistry` / `SlidingWindowCircuitBreaker` / `SemaphoreBulkhead` 提供 `ChaosResilientFeignClient`（按目标 Feign 服务隔离）与 `ResilientHttpRequestInterceptor`（按目标主机隔离），仅将 `5xx`、传输 `IOException` 与超时计入失败率，不误伤 `4xx` 业务拒绝。

## 依赖方式

通过 `chaos-cloud-starter` 自动引入：

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-cloud-starter</artifactId>
</dependency>
```

只使用无 Feign 的 `ServiceHttpClient` 时也可直接依赖 `com.chaos:chaos-http-client`（`feign-core` 与 `spring-cloud-starter-openfeign` 为可选依赖）。

## 配置

由 `ChaosCloudAutoConfiguration`（`chaos.cloud.*`）统一装配：

```yaml
chaos:
  cloud:
    feign:
      # 是否自动解包下游 Result<T> 响应并在非成功码时抛出 BizException
      unwrap-result: true
    resilience:
      enabled: true
      failure-rate-threshold-pct: 50
      sliding-window-size: 20
      minimum-number-of-calls: 10
      wait-duration-in-open-state: 15s
      permitted-number-of-calls-in-half-open-state: 5
      max-concurrent-calls: 0
```

## 示例

使用 `ServiceHttpClients` 构建受限 HTTP 客户端：

```java
ServiceHttpClient client = serviceHttpClients.create(Duration.ofSeconds(2), Duration.ofSeconds(5));
```

使用 OpenFeign 调用示例订单接口（参考 [chaos-examples](chaos-examples.md)）：业务启动类显式标注 `@EnableFeignClients`，下游返回 `Result<OrderDto>` 时由 `ChaosFeignResultDecoder` 自动解包。

## 扩展点

- 注册自定义 `ServiceErrorTranslator` Bean 覆盖默认下游错误翻译逻辑；
- 注册 `ServiceTokenProvider` Bean（例如 `RequestBearerTokenProvider` 或 `ClientCredentialsTokenProvider`）后，自动装配会自动启用 `ServiceTokenFeignInterceptor`；
- 关闭 `chaos.cloud.feign.unwrap-result=false` 时回退到 Spring Cloud OpenFeign 原生解码器。

## 注意事项

- `ChaosCloudAutoConfiguration` 不声明无参 `@EnableFeignClients`（避免错误扫描自动装配包），由业务启动类自行声明。
- 通过 `chaos.cloud.resilience.*` 可调整断路器窗口、半开探测数、单目标服务并发舱壁上限及调用超时预算；断路器打开或舱壁满载时分别抛出 `CircuitBreakerOpenException`（503）与 `BulkheadFullException`（429），并上报 `chaos.resilience.calls{source=feign}` / `chaos.resilience.state.transitions` 指标。
