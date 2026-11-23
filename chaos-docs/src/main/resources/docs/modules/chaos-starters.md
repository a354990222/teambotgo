# chaos-starters

## 职责

面向使用方的依赖入口。starter 只聚合依赖、不含 Java 代码；自动装配逻辑全部在 [chaos-autoconfigure](chaos-autoconfigure.md)。

starter 分两层：

- **场景 starter**：按“要搭什么服务”选择，一个应用只选一个。
- **能力 starter**：按“还需要什么能力”追加。

## 怎么选

### 第一步：选场景 starter

| 我要搭建 | 场景 starter | 已包含 |
| --- | --- | --- |
| Servlet 业务服务 | `chaos-web-service-starter` | web（统一响应、全局异常、Trace、日志模板、OpenAPI、Actuator）、security（JWT / opaque 资源服务器、RBAC/ABAC、`@Permission`、`@DataScope`）、tenant、audit、application（事务、重试、领域事件、上下文传播、Prometheus） |
| API 网关 | `chaos-gateway-starter` | Spring Cloud Gateway、鉴权（只依赖 security-api）、租户校验、限流、黑名单、灰度、访问日志、审计、Trace、Actuator、Prometheus |
| 写非 Web 服务（MQ 消费者、批处理、定时任务） | `chaos-application-starter` | 事务、重试、领域事件、分布式定时任务、上下文传播、Prometheus；按需再加 `mq` / `mybatis` |
| OAuth2 授权服务器 | `chaos-authorization-starter` | authorization（password / sms grant、登录锁定、JWK 轮换、Redis 存储与 JWT 黑名单）、审计、Actuator、Prometheus |

注意：网关是 WebFlux 应用，不要和 `chaos-web-starter` / `chaos-web-service-starter`（Servlet）同时引入。

### 第二步：追加能力 starter

| 还需要 | 能力 starter | 说明 |
| --- | --- | --- |
| 数据库 | `chaos-mybatis-starter` | MyBatis-Plus、分页上限、多租户 SQL 改写、数据权限、审计字段、乐观锁 |
| Redis | `chaos-redis-starter` | Redisson、分布式锁、集群限流、幂等存储、布隆过滤器、延迟队列、两级缓存（可选 Caffeine L1 + Redis L2）与 `CacheAsideTemplate`；**多实例 / 生产环境必需** |
| 可靠消息与 Outbox | `chaos-mq-starter` | `chaos-mq` 抽象 + Kafka / RocketMQ / JDBC outbox 自动装配（带 `spring-boot-starter-jdbc` 时自动激活 outbox 落库与定时派发） |
| 状态机与流程 | `chaos-process-starter` | 无 Spring 依赖的状态机内核、流程端口及可选 JDBC / Redis 引擎（开发环境内存引擎在生产阻断启动） |
| 对象存储 | `chaos-storage-starter` | MinIO / OSS 适配器（按 `chaos.storage.provider` 启用） |
| 服务间调用 | `chaos-cloud-starter` | `chaos-http-client`（`ServiceHttpClient`、Feign `Result` 解包/错误翻译、熔断舱壁）+ OpenFeign + LoadBalancer + trace 透传（Servlet） |
| 响应式服务栈 | `chaos-cloud-reactive-starter` | `chaos-web-reactive`（WebFlux 统一 `ApiResult`、全局异常、Trace、非阻塞限流/幂等、XSS）+ `chaos-trace` + WebFlux + LoadBalancer + Actuator（不带 Feign） |
| Nacos | `chaos-cloud-nacos-starter` | 注册、配置约定及网关动态路由（`nacos-config` + `nacos-discovery`） |

以下基础 starter 已被场景 starter 包含或精简合并，一般不需要单独引入：`chaos-web-starter`、`chaos-security-starter`。只想要其中单项能力（例如只要统一响应、不要安全）时才单独使用；JDBC 审计落库、多租户与分布式定时任务能力已分别合并进场景 / 应用与各自核心模块（配合 `spring.datasource` + `chaos.audit.jdbc.enabled=true` 等开关即自动装配）。

## 示例

推荐以 [chaos-boot-parent](chaos-boot-parent.md) 为 parent：

```xml
<parent>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-boot-parent</artifactId>
    <version>1.0.0</version>
    <relativePath/>
</parent>

<dependencies>
    <dependency>
        <groupId>com.chaos</groupId>
        <artifactId>chaos-web-service-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.chaos</groupId>
        <artifactId>chaos-mybatis-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.chaos</groupId>
        <artifactId>chaos-redis-starter</artifactId>
    </dependency>
</dependencies>
```

配置从 [配置模板](../templates/README.md) 开始：场景模板 + 能力模板，开发配置不超过 10 个 `chaos.*` 配置项。

## 约束（架构测试校验）

- starter 不含 Java 代码，不依赖示例和测试模块。
- 场景 starter `chaos-web-service-starter` 只聚合能力 starter 及无独立 starter 的横切模块（`chaos-tenant`、`chaos-audit`），不重复声明外置三方栈（依赖方向规则 10）。
- 能力 starter 不得依赖场景 starter。
- 能力 starter 显式声明 `chaos-autoconfigure` + 功能库 + 所需三方框架（`chaos-autoconfigure` 中它们都是 optional）。
- 新增 starter 必须登记到 `chaos-dependencies`。
