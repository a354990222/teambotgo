# chaos-cloud（OpenFeign 与服务间调用）

> 仓库中不存在 `chaos-feign` 模块。服务间受限 HTTP 客户端与 OpenFeign Result 解包/错误翻译位于 [chaos-http-client](chaos-http-client.md)，Trace 透传拦截器 `TraceFeignRequestInterceptor` 位于 `chaos-trace`（`com.chaos.trace.feign`，feign-core 为可选依赖），由 `ChaosCloudAutoConfiguration` 统一装配；
> Servlet 服务通过 `chaos-cloud-starter` 引入；响应式服务使用 `chaos-cloud-reactive-starter`（不带 OpenFeign）。

## 职责

- 通过 `ServiceHttpClients` / `ServiceHttpClient` 提供带超时约束、外部地址校验（防 SSRF）、禁止重定向与 Trace 透传的 `RestTemplate` 包装；
- 通过 `TraceFeignRequestInterceptor` 自动透传 W3C Trace Context（`traceparent`、`tracestate`、`baggage`）及兼容请求头；
- 通过 `ChaosFeignResultDecoder` 与 `ChaosFeignErrorDecoder` 自动解包下游统一 `Result<T>` 响应并翻译为 `BizException`；
- 存在 `ServiceTokenProvider` Bean 时，自动注册 `ServiceTokenFeignInterceptor` 透传内部服务令牌。

## 依赖方式

引入 `chaos-cloud-starter`。

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-cloud-starter</artifactId>
</dependency>
```

## 配置

由业务启动类自行声明 `@EnableFeignClients`，`ChaosCloudAutoConfiguration` 会根据 `ChaosCloudProperties`（`chaos.cloud.*`）装配：

```yaml
chaos:
  cloud:
    feign:
      unwrap-result: true          # 自动解包下游统一 Result<T> 响应，业务/远端失败码转换为 BizException
```

## 示例

```java
// 1. OpenFeign 调用：在启动类声明 @EnableFeignClients 后直接注入 FeignClient
//    TraceFeignRequestInterceptor 会自动写入 X-Trace-Id、X-Span-Id、traceparent、tracestate、baggage；
//    若定义了 ServiceTokenProvider Bean，ServiceTokenFeignInterceptor 自动附加令牌。
// 2. 受限 HTTP 客户端：
ServiceHttpClient client = serviceHttpClients.create(Duration.ofSeconds(2), Duration.ofSeconds(5));
```

## 扩展点

- 优先注册 Spring Bean 覆盖默认实现（如自定义 `ServiceErrorTranslator` 或 `ServiceTokenProvider`）。
- 不在业务代码中依赖 autoconfigure 类。
- 不跨层调用 infra 实现，domain/application 只依赖接口。
- 通过 `TraceHeaders.outgoing()` 构建出站请求头，每次 Feign 调用都会创建新的 spanId。

## 注意事项

- 保持模块职责单一，避免把无关能力塞入当前模块；更多细节见 [chaos-http-client](chaos-http-client.md)。
- 新增公共 API 后同步更新本手册和 architecture.md。
- Feign 只负责请求头透传与信封解包，真实分布式链路 span 由 Micrometer Observation 或 Java agent 完成。
