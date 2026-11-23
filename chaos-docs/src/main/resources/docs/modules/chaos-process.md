# chaos-process

> 跨模块设计、状态机 DSL 与生产安全约束详见 [状态机与流程引擎能力](../capabilities/process-engine.md)。

## 职责

提供框架无关的状态机内核与工作流引擎核心契约（包 `com.chaos.process`、`com.chaos.process.statemachine`、`com.chaos.process.support`）：

- **状态机内核（`com.chaos.process.statemachine`）**：`StateMachineEngine`、`StateMachineDefinition`、`StateMachineTransition`、`StateMachineGuard`、`StateMachineAction`、`StateMachineResult`；
- **流程引擎端口与领域模型（`com.chaos.process`）**：`ProcessEngine`、`ProcessDefinition`、`ProcessInstance`、`ProcessTask`、`ProcessNode`、`ProcessTransition`、`ProcessInstanceCommand`、`ProcessTaskClaimCommand`、`ProcessTaskCompletionCommand`；
- **开发/测试内存适配（`com.chaos.process.support`）**：`InMemoryProcessEngine`（标有 `@UnsafeForProduction`，生产环境会被 `ProductionSafetyEnforcer` 默认阻断启动）。

本模块仅依赖 `chaos-core`，不依赖 Spring、JDBC 或 Redis。

## 依赖方式

开发/测试或自定义持久化引擎时引入 `chaos-process-starter`：

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-process-starter</artifactId>
</dependency>
```

纯领域/应用接口模块也可直接依赖 `com.chaos:chaos-process`。

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

- `chaos.process.engine=jdbc`：引入 [chaos-process-jdbc](chaos-process-jdbc.md)（`chaos-process-jdbc-starter`）；
- `chaos.process.engine=redis`：引入 [chaos-process-redis](chaos-process-redis.md)（`chaos-process-redis-starter`）；
- `chaos.process.engine=none`：由业务自行提供 `ProcessEngine` Bean。

## 注意事项

- `StateMachineEngine` 是无状态对象，状态转换持久化与并发控制应由外层事务协调。
- 生产环境启用内存实现 `InMemoryProcessEngine` 将在启动时被生产安全检查拒绝。
