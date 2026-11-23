# chaos-process

> 跨模块设计、状态机 DSL 与生产安全约束详见 [状态机与流程引擎能力](../capabilities/process-engine.md)。

## 职责

提供框架无关的状态机内核与工作流引擎核心契约（包 `com.chaos.process`、`com.chaos.process.statemachine`、`com.chaos.process.support`）：

- **状态机内核（`com.chaos.process.statemachine`）**：`StateMachineEngine`、`StateMachineDefinition`、`StateMachineTransition`、`StateMachineGuard`、`StateMachineAction`、`StateMachineResult`；
- **流程引擎端口与领域模型（`com.chaos.process`）**：`ProcessEngine`、`ProcessDefinition`、`ProcessInstance`、`ProcessTask`、`ProcessNode`、`ProcessTransition`、`ProcessInstanceCommand`、`ProcessTaskClaimCommand`、`ProcessTaskCompletionCommand`；
- **开发/测试内存适配（`com.chaos.process.support`）**：`InMemoryProcessEngine`（标有 `@UnsafeForProduction`，生产环境会被 `ProductionSafetyEnforcer` 默认阻断启动）。

本模块仅依赖 `chaos-core`（`spring-jdbc`、`spring-data-redis`、`jackson-databind`、`jackson-datatype-jsr310` 均为 `optional`）。

## 依赖方式

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-process-starter</artifactId>
</dependency>
```

纯领域/应用接口模块也可直接依赖 `com.chaos:chaos-process`。若切换 JDBC/Redis 引擎，保证应用已引入 `spring-boot-starter-jdbc` 或 `spring-boot-starter-data-redis`（如 `chaos-mybatis-starter` / `chaos-redis-starter`）即可，无需单独添加适配器 starter。

## 配置

由 `ChaosProcessAutoConfiguration`（`chaos.process.*`）装配：

```yaml
chaos:
  process:
    enabled: true
    # memory | jdbc | redis | none（生产不得使用 memory）
    engine: memory
```

## 扩展点与持久化实现

- `chaos.process.engine=jdbc`：启用内置 `JdbcProcessEngine`（包 `com.chaos.process.jdbc`）。将 `ProcessDefinition` 与聚合作快照序列化为 JSON 落库，同时将 `tenant_id`、`process_code`、`definition_version`、`status`、`claim_expires_at`、`version` 建索引字段，支持多实例认领租约与乐观锁；建表 SQL 参考 `classpath:db/chaos-process-schema-h2.sql`（含 `chaos_process_definition`、`chaos_process_instance`、`chaos_process_task`、`chaos_process_request`）。
- `chaos.process.engine=redis`：启用内置 `RedisProcessEngine`（包 `com.chaos.process.redis`），基于 `StringRedisTemplate` 与 Lua CAS 原子校验 `"version":N` 覆写，支持幂等重放与租约过期重认领；默认 key 前缀 `chaos:process`。
- `chaos.process.engine=none`：由业务自行提供 `ProcessEngine` Bean。

## 注意事项

- `StateMachineEngine` 是无状态对象，状态转换持久化与并发控制应由外层事务协调。
- 生产环境启用内存实现 `InMemoryProcessEngine` 将在启动时被生产安全检查拒绝。
