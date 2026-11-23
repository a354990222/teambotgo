# chaos-framework 优化路线

## 目标

持续把 `chaos-framework` 从通用脚手架演进为企业级微服务治理平台。所有优化必须保持模块边界清晰、starter 只做依赖聚合、autoconfigure 独立、业务代码不侵入 framework。

> **这份文档只记录“要做什么”和“做到什么程度算完成”。**
> 每一项具体改了哪些类、哪些配置，看仓库根目录的 `CHANGELOG.md`；
> 能力的用法与边界，看对应的[能力文档](index.md#cross-module-capabilities)或模块文档。

## 执行原则

- 优先补齐已经声明但尚未形成闭环的能力。
- 优先做可配置、可替换、可测试的基础能力。
- 每完成一项同步更新模块文档、架构测试和示例。
- 不引入巨型 common，不新增无边界 util。
- 不使用 `spring.factories`，只使用 Spring Boot 3 `AutoConfiguration.imports`。

## 未决事项

以下是已知但尚未排期或依赖外部运行时条件的项，按影响面排序。

### 工程门禁

| 事项 | 现状 | 备注 |
| --- | --- | --- |
| CI 首次运行 | 仓库已推送到 GitHub，但 Actions 尚未在其上跑过一次 | CI、CodeQL、Dependabot、dependency-review 的实际行为有待首次运行验证 |
| 覆盖率门槛 | 全部 36 个 Java 代码模块（含全部核心库、响应式 Web 模块、自动装配、测试支持及 4 个示例模块）已在 `-Pchaos-coverage` 下启用 `70% 指令 / 55% 分支` 硬门槛（`pom.xml` 全局报告下限默认 `0.00`） | 仅纯 POM 聚合模块、纯依赖 starter、`chaos-docs`、原型模板与测试治理模块除外 |
| javadoc doclint | 已全量清理并通过 `doclint=all,-missing` | 根 POM 通过 `${chaos.javadoc.doclint}` 默认启用 `all,-missing` |
| API 兼容检查暂时关闭 | `chaos.api.check.skip=true` | 1.0.0 是首发版本没有可比基线。发布 1.0.1 时必须改回 `false` 并把 `compareVersion` 设为上一个已发布版本 —— 忘了改的话 `chaos-release` profile 的 enforcer 会在 `validate` 阶段直接拦住（见 [release-governance.md](release-governance.md) 防止门禁空转），不再依赖人记得 |
| 架构测试断言强度 | 已消除 `LayerBoundaryArchitectureTest` 中全部 `.java`/`pom.xml`/`.yml` 的文本子串断言，并在 `chaos-autoconfigure` 引入 ArchUnit `1.4.1` 执行字节码级校验 | `LayerBoundaryArchitectureTest` 当前使用 POM XML DOM、SnakeYAML 与 `ProjectModel.codeWithoutComments(...)`，`ArchitectureRulesArchUnitTest` 负责字节码规则 |

### 能力与运行时验证状态

| 事项 | 现状 | 备注 |
| --- | --- | --- |
| 熔断、舱壁与超时预算 | 已基于 `chaos-core` 原生弹性内核（`SlidingWindowCircuitBreaker` + `SemaphoreBulkhead` + `ResilienceExecutor`）接入 Gateway 与 OpenFeign / `ServiceHttpClients` | 离线仓库未缓存外部 `resilience4j` 实现制品；如需替换为 Resilience4j 生态适配器，可在在线仓库补充依赖后接入 |
| 定时任务 | 分布式定时任务能力已合并进 `chaos-service`（`com.chaos.service.job`），定位仍为“只做分布式锁互斥 + Trace/MDC 上下文” | 无分片、失败重试、执行记录、手动触发；需完整调度台时由业务侧接入 XXL-Job / PowerJob 并关闭 `chaos.job.scheduling-enabled` |
| 响应式业务栈 | 已新增 `chaos-web-reactive` 并在 `chaos-cloud-reactive-starter` 与 `ChaosWebReactiveAutoConfiguration` 装配完成 | 提供 WebFlux 统一 `ApiResult`、全局异常、Reactor Context Trace、非阻塞 Redis/InMemory 限流与幂等、XSS 防护头 |
| 虚拟线程 | 已验证 `spring.threads.virtual.enabled=true` 下 `TaskExecutionAutoConfiguration` 自动接入 `TraceContextTaskDecorator`，并补充了 `VirtualThreadContextPropagationTest` 与文档说明 | 仍需在开启真实连接池 / Redisson 的全链路压测下观察 `synchronized` pin 线程与池大小表现 |
| 限流失败告警 | 网关 `chaos.gateway.rate-limit.fail-open` 默认 `true`，已在 [chaos-gateway.md](modules/chaos-gateway.md) 给出 Prometheus 告警规则示例 | 需在部署侧监控体系实际导入 `chaos.ratelimit.errors{outcome="fail-open"}` 与熔断告警规则 |
| 集成测试覆盖面 | 5 个 `*IT` 类（Redis / MySQL / Kafka） | RocketMQ、MinIO / OSS、Nacos、网关端到端、授权服务器 JDBC 存储仍无 `*IT`；本地与 CI 需开启 Docker 运行 `-Pchaos-integration-test`，`smoke` job 只在主干 push 时运行 |

## 已交付能力与验收标准

下表是历史交付记录，保留是因为「验收标准」一列说明了每项能力做到什么程度才算闭环，
新增同类能力时可作为参照。全部已完成。

| 优先级 | 状态 | 优化项 | 目标 | 验收标准 |
| --- | --- | --- | --- | --- |
| P0 | 已完成 | Gateway 全局限流闭环 | 补齐网关入口限流治理 | 支持按 IP、用户、租户、路径、路由组合限流；支持本地默认实现；支持 fail-open/fail-closed；429 统一 JSON 响应；有单测和文档 |
| P0 | 已完成 | Gateway 熔断降级标准化 | 统一后端不可用时的错误响应 | 下游连接失败、超时和未知异常输出统一 JSON；保留 traceId；后续可扩展到 Resilience4j |
| P0 | 已完成 | Refresh Token 安全增强 | 防止 refresh token 重放和异常复用 | 支持 refresh 成功审计、重放嫌疑审计；Redis 授权存储清理旧 refresh token 索引；补充测试 |
| P0 | 已完成 | 审计持久化扩展示例 | 让审计从日志兜底扩展到落库/MQ | 提供 JDBC 落库实现；包含表结构；文档说明敏感字段脱敏 |
| P1 | 已完成 | 租户生命周期模块 | 支撑 SaaS 租户状态治理 | 新增 `chaos-tenant`；支持租户启停、冻结、套餐和隔离模式；Gateway/Security/MyBatis 可读取租户状态 |
| P1 | 已完成 | 数据权限增强 | 提升复杂业务数据权限表达能力 | 支持表别名、多字段、IN/LIKE/BETWEEN、角色/部门/用户组合条件；空条件继续默认拒绝 |
| P1 | 已完成 | MQ 可靠消息 | 提供企业级消息一致性基础 | 支持 outbox、发送确认、消费幂等、死信处理、重试和告警审计 |
| P1 | 已完成 | 配置治理增强 | 降低 starter 使用成本 | 所有配置类补齐校验；输出配置元数据；文档列出配置表；危险默认值启动提示 |
| P2 | 已完成 | OpenTelemetry 兼容层 | 提升可观测生态兼容性 | 支持 W3C Trace Context 和 baggage；Web、Gateway、Feign、MQ 一致透传；保持 SkyWalking agent 接入 |
| P2 | 已完成 | Testcontainers 集成测试 | 提升真实基础设施验证能力 | Redis、MySQL、Kafka 关键链路有集成测试；RocketMQ 使用独立 profile 策略 |
| P2 | 已完成 | 示例工程完整链路 | 给引用方可运行落地样板 | 增加 `auth-server`、`order-api`、`order-service`、`gateway` 与 `docker-compose.yml`；演示 DDD 四层、契约测试与 JWT/Redis 链路 |
| P2 | 已完成 | 发布治理 | 支撑 framework 产品化发布 | BOM 兼容矩阵、依赖收敛、API 变更检查、changelog、starter 依赖树文档 |
| P2 | 已完成 | 状态机与流程引擎基础能力 | 提供业务状态转换和流程待办的统一扩展点 | 独立核心模块与 Starter；状态机 Guard/Action；流程版本、候选人、租约、乐观锁和幂等；JDBC/Redis 适配器；生产内存实现阻断 |
| P0 | 已完成 | 授权服务器生产安全检查 | 提示生产环境中的开发默认值 | prod/production/prd 下检查 localhost issuer、noop secret、memory client、临时 JWK、内存授权仓储、Noop JWT 撤销、空审计和互踢本地索引；默认 fail-fast 阻断启动 |
| P0 | 已完成 | 异步上下文与虚拟线程传播治理 | 避免线程池、异步任务及虚拟线程 trace/租户/Security/MDC 丢失或串扰 | `ContextPropagation` 接入 `TraceContext`、`TenantContext`、`SecurityContextPropagation`、`DataScopeContext`；`TaskDecorator` 自动适配线程池与虚拟线程 |
| P0 | 已完成 | CI 与示例链路 Smoke | 提升提交质量和示例可验证性 | GitHub Actions 跑单测、发布治理 validate、结构扫描；提供 JWT 示例链路 smoke 脚本 |
| P0 | 已完成 | Noop/InMemory 生产安全保护 | 提示危险兜底实现进入生产 | 生产模式检测危险 Noop / InMemory 默认实现并默认 fail-fast 阻断启动 |
| P1 | 已完成 | MQ outbox JDBC 适配器 | 让可靠消息从端口走向可落库实现 | `JdbcOutboxMessageRepository`（现位于 `chaos-mq`）、批量清理、多实例 claim、建表脚本、健康检查 |
| P1 | 已完成 | 幂等响应回放与错误文案国际化 | 超时重放与双语提示闭环 | 重复请求回放首次状态码/响应体/Location；`MessageSource` 中英文错误文案与 `Accept-Language` 支持 |
| P0 | 已完成 | 治理指标、健康检查与审计异步化 | 让框架决策可观测且不阻塞业务线程 | 12 项低基数治理指标、outbox 积压健康检查、异步有界审计队列、`ChaosBuiltInDiagnosticRules`（含 Nacos 隔离校验） |
| P0 | 已完成 | 跨栈服务调用与模块收口 | 消除 `chaos-transaction`/`chaos-storage-document` 碎片并补齐 Feign 契约 | 合并至 `chaos-service` / `chaos-excel`；新增 `chaos-http-client` 与 `ServiceHttpClient` / `ChaosFeignResultDecoder` / `ChaosFeignErrorDecoder` |
| P0 | 已完成 | 架构断言去假化、doclint 与全覆盖门槛 | 让架构约束与覆盖率门禁真正不可绕过 | 启用 `doclint=all,-missing`；全部 36 个 Java 代码模块启用 `70%/55%` JaCoCo 门槛；接入 ArchUnit `1.4.1` + POM DOM + 去注释源码扫描 |
| P0 | 已完成 | 熔断、并发舱壁与超时预算 | 为网关转发与服务间 HTTP/Feign 调用补齐真实故障隔离 | `chaos-core` 新增 `SlidingWindowCircuitBreaker` / `SemaphoreBulkhead` / `ResilienceRegistry` / `ResilienceExecutor`；接入 `GatewayResilienceFilter`、`ChaosResilientFeignClient` 与 `ResilientHttpRequestInterceptor` |
| P1 | 已完成 | WebFlux 响应式业务栈 | 让非网关响应式业务服务具备统一契约与治理防线 | 新增 `chaos-web-reactive`，在 `ChaosWebReactiveAutoConfiguration` 与 `chaos-cloud-reactive-starter` 提供统一 `ApiResult`、全局异常、Trace、Redis/内存限流与幂等及安全头 |
| P1 | 已完成 | 本地 + Redis 两级缓存 | 补齐热点缓存防穿透、防击穿、跨实例广播失效与 `@Cacheable` 两级支撑 | `chaos-redis` 新增 `TwoLevelCacheManager` / `TwoLevelCache`（Caffeine L1 + Redis L2）、`CacheAsideTemplate` 与 `RedisTwoLevelCacheInvalidationBridge`（Redis Pub/Sub 跨实例 L1 主动失效） |
| P1 | 已完成 | 供应链门禁与 chaos-python 收口 | 补上依赖 CVE、SBOM 与 Python 中间件 | CycloneDX SBOM、enforcer、CI dependency-review；`chaos-python/chaos-tracing` ASGI/WSGI 中间件与 ruff/mypy strict |
