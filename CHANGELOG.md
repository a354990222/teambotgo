# Changelog

所有重要变更都记录在这里。版本格式遵循语义化版本：`MAJOR.MINOR.PATCH`。

## 1.0.0 — 2026-11-23

> **首个对外发布版本（Initial Public Release）**
> 基于 Java 21、Spring Boot `3.5.16` 与 Spring Cloud `2025.0.3`，面向企业微服务与 API 网关场景提供开箱即用的 DDD 分层基座、安全合规治理、可观测链路与生产防御门禁。

### 一、模块化架构与 Starter 体系

- **收敛后的 54 模块精简反应堆**：
  - `chaos-dependencies` 作为无 parent 的独立公共 BOM 与唯一版本来源，全部 POM 统一采用 `${revision}`（定义于 `.mvn/maven.config`）；`chaos-boot-parent` 作为推荐业务应用 parent 发布，仅携带应用编译/测试/repackage 约定。
  - 21 个高内聚运行时模块（微适配器均以 `<optional>true</optional>` + `@ConditionalOnClass` 内聚至所属域核心模块）：
    - **基础与编排**：`chaos-foundation/`（`chaos-core`、`chaos-domain`）、`chaos-trace`（W3C Trace、MDC、OpenFeign 透传与 Micrometer 指标实现）、`chaos-service`（编程式事务与提交后副作用 `TransactionalSideEffects`、重试、领域事件、异步/虚拟线程上下文传播、分布式定时任务 `com.chaos.service.job.DistributedJobRunner`）。
    - **安全、租户与审计**：`chaos-security/`（`chaos-security-api` 无 Spring 安全契约、`chaos-security` Servlet 资源服务器、`chaos-authorization` OAuth2 授权服务器、`chaos-security-redis` 跨栈 Redis 黑名单与授权存储）、`chaos-tenant`、`chaos-audit`（结构化日志、有界异步发布与可选 JDBC 落库）。
    - **Web、网关与调用**：`chaos-web`（Servlet MVC）、`chaos-web-reactive`（WebFlux 响应式业务栈）、`chaos-gateway`（Spring Cloud Gateway 过滤器与可选 Nacos 动态路由）、`chaos-http-client`（`ServiceHttpClients`、OpenFeign 响应解包与熔断舱壁）。
    - **数据、流程、消息与存储**：`chaos-data/`（`chaos-mybatis`、`chaos-redis`、`chaos-excel` 基于 Apache POI `5.5.1` 含文档安全校验）、`chaos-process`（状态机内核与可选 JDBC/Redis 持久化引擎）、`chaos-mq`（可靠消息 Outbox 与可选 JDBC/Kafka/RocketMQ 适配）、`chaos-storage`（对象存储端口、本地存储与可选 OSS/MinIO 适配）。
  - 全部 `@AutoConfiguration` 集中于单一 `chaos-autoconfigure`（`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`），所有特征库与三方框架均声明为 `optional`。
- **双层 Starter 依赖入口（14 个纯 POM 聚合模块，零 Java 源码）**：
  - **场景 Starter**：`chaos-web-service-starter`（Servlet 业务服务一站式入口）、`chaos-gateway-starter`（WebFlux API 网关）、`chaos-authorization-starter`（OAuth2 授权服务器，内置 Actuator + Prometheus）、`chaos-application-starter`（非 Web 后台/消费者/定时任务）。
  - **能力 Starter**：`chaos-web-starter`、`chaos-security-starter`、`chaos-cloud-starter`、`chaos-cloud-reactive-starter`、`chaos-cloud-nacos-starter`、`chaos-mybatis-starter`、`chaos-redis-starter`、`chaos-mq-starter`、`chaos-process-starter`、`chaos-storage-starter`。
- **脚手架、示例与测试支持**：
  - `chaos-archetypes/`：提供 `chaos-archetype-web-service`、`chaos-archetype-gateway`、`chaos-archetype-auth-server` 三套离线可验证的脚手架工程（`chaos-boot-parent` + DDD 分层 + 场景配置模板）。
  - `chaos-examples/`：完整覆盖 `example-auth-server`、`example-gateway`、`example-order-api`、`example-order-service` 与本地联调 `docker-compose.yml`。
  - `chaos-test-support`（`test` scope 复用夹具）：`@WithChaosContext`、`ChaosMockMvcSecurity`、`InMemoryRedisTemplates`、`ChaosContainers`、`DddArchitectureAssertions`、`ProductionSafetyTestSupport`。
  - `chaos-python/chaos-tracing`：与框架版本对齐的 Python W3C Trace 中间件（ASGI / WSGI / gRPC），支持 `ruff`、`mypy --strict` 与 `pytest`。

### 二、核心能力与安全韧性

- **跨传输栈统一契约与 Web 防线**：
  - `chaos-core` 提供 `ApiResult` / `DefaultApiResult` / `ApiResultJson`，确保 Servlet、WebFlux 业务服务与 API 网关错误响应字段完全一致。
  - `chaos-web`（Servlet）提供统一 `Result` 包装、`GlobalExceptionHandler`、可信代理客户端 IP 与身份头防伪造（`ChaosHeaders` / `ForwardedClientIpResolver`）、LRU 限流、`@Idempotent` 三态幂等（含可选响应体回放）及中英文错误文案 `MessageSource`。
  - `chaos-web-reactive`（WebFlux）提供非阻塞 `ReactiveResultHandler`、`ReactiveGlobalExceptionHandler`、`ReactiveTraceWebFilter`（Reactor Context）、Redis/内存非阻塞限流与幂等及安全响应头（与网关栈存在时自动让位）。
- **原生故障隔离（CircuitBreaker / Bulkhead / Timeout Budget）**：
  - `chaos-core` 内置无外置重型依赖的 `SlidingWindowCircuitBreaker`、`SemaphoreBulkhead`、`ResilienceRegistry` 与 `ResilienceExecutor`。
  - 已分别通过 `GatewayResilienceFilter`（按路由隔离）和 `ChaosResilientFeignClient` / `ResilientHttpRequestInterceptor`（按目标服务隔离）保护跨服务通信，统一输出 `429`/`503` 与 `chaos.resilience.*` 指标。
- **全链路安全与多租户隔离**：
  - OAuth2 授权服务器支持扩展 `grant_type`（`password`、`sms_code`、`refresh_token` 重放检测）、登录失败锁定、JWK 密钥轮换、注销实时撤销与设备/客户端维度互踢索引。
  - 统一 RBAC + ABAC 策略模型（`chaos-security-api`）支撑网关粗粒度鉴权与下游 `@Permission`/`@RequireAccess` 细粒度及数据权限控制；网关与资源服务器共享同一 Redis JWT 撤销索引（`chaos-security-redis`）。
  - MyBatis-Plus 集成多租户 SQL 改写与数据权限拦截器，租户缺失与空数据权限条件默认采用 `DENY`（fail-closed）策略，并在 SQL 拼接层面严格转义与校验标识格式。
- **二级缓存、可靠消息与对象存储**：
  - `chaos-redis` 提供 Redisson 分布式锁、幂等仓储、限流、布隆过滤器、延迟队列，以及 Caffeine L1 + Redis L2 `TwoLevelCacheManager`、`CacheAsideTemplate`（支持空值防穿透、按 key 单飞防击穿与 Redis Pub/Sub 跨实例 L1 广播失效）。
  - `chaos-mq` 提供与业务同事务落库的 Outbox 写入（`OutboxPublisher`）、多实例防重抢占派发（`claimDueMessages` / `completeClaim`）、死信退避、定时清理与幂等消费还原。
  - `chaos-storage` 提供路径穿越校验、`max-object-size` 流式截断及 `1s ~ 7d` 预签名 TTL 校验。

### 三、可观测性、启动诊断与工程质量门禁

- **可观测性**：统一 W3C Trace 透传、`X-Trace-Id` 兼容、虚拟线程与 `TaskDecorator`/`ContextRegistry` 上下文传播，输出低基数框架治理指标（`ChaosMeterNames`）与 `/actuator/health` 中的 `chaosRuntime`、`chaosMqOutbox` 组件。
- **启动诊断与生产安全防线**：
  - 启动时打印携带构建版本号的 Chaos Banner 与脱敏启动摘要报告，接入自定义 `ChaosDiagnosticRule` 排查 Nacos 命名空间、异步审计、代理网段、技术栈误混等常见风险。
  - 生产模式（`prod`/`production`/`prd` 或 `chaos.production-safety.production-mode=true`）下检测 `InMemory*`、`Noop*`、`localhost` issuer、`{noop}` secret 等开发默认实现时默认 `fail-fast` 阻断启动，并以统一“问题 / 原因 / 修复建议”的 `ChaosDiagnosticException` 暴露。
- **质量与发布治理**：
  - 架构守卫：`chaos-architecture-tests`（10 条 POM/源码依赖方向与分层边界测试）+ `ArchitectureRulesArchUnitTest`（ArchUnit `1.4.1` 字节码约束）+ `OptionalDependencyIsolationTest`（真实独立 JVM 移除模块校验无 `NoClassDefFoundError`）。
  - 覆盖率与文档门禁：全部 26 个 Java 代码模块在 `-Pchaos-coverage` 下强制满足 `70%` 指令 / `55%` 分支 JaCoCo 门槛；开启 Javadoc `doclint=all,-missing`；提供 `scripts/generate-configuration-reference.py`（从配置元数据自动生成并校验 288 项配置文档）。
  - 发布治理：`chaos-release` profile 集成 Maven Enforcer 依赖收敛、CycloneDX SBOM、`japicmp` API 兼容防空转守卫（`1.0.1+` 强制开启对比）及 Central Portal 发布配置。

### 四、接入时需注意的默认行为

- **可靠消息写入入口**：业务事务内注入 `OutboxPublisher`（`ReliableMessagePublisher` 只实现 `OutboxPublisher`，不实现 `MessagePublisher`），同时存在 `JdbcOperations` 与 Kafka/RocketMQ Template 时自动启动 Outbox 派发（需先执行建表脚本）。
- **幂等与分页默认值**：`@Idempotent` 默认要求携带幂等 key（缺失返回 400）；MyBatis 分页单页默认上限 `500`；租户拦截器与数据权限空条件默认 `DENY`。
- **可信代理与身份头**：`X-Forwarded-For` 与 `X-User-Id` / `X-Tenant-Id` 默认均不直接信任，须配置 `trusted-proxies`（网关剥离外部身份头后由内部按需开启 `trust-identity-headers`）。
- **生产环境切换**：生产 profile 下内存限流、内存幂等、内存流程引擎、空 JWT 撤销与开发期客户端配置会被阻断，上线前应引入 `chaos-redis-starter` 并按各 `docs/checklists/*.md` 完成配置。
