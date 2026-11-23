# chaos-process-jdbc

## 职责

`ProcessEngine` 端口的 JDBC 持久化适配（包 `com.chaos.process.jdbc`），核心实现为 `JdbcProcessEngine`。

特点：

- 将 `ProcessDefinition` 与聚合作快照序列化为 JSON 存储，同时将 `tenant_id`、`process_code`、`definition_version`、`status`、`claim_expires_at`、`version` 建索引字段落库；
- 支持租户隔离、启动与完成请求幂等重放（校验相同 `requestId` 对应相同指令，指令篡改直接拒绝）；
- 支持任务认领租约（`claimLease` 过期后允许其他候选人重新认领）与基于 `version` 的乐观锁更新；
- 仅支持 `START`、`USER_TASK`、`END` 节点类型。

## 依赖方式

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-process-jdbc-starter</artifactId>
</dependency>
```

## 配置

```yaml
chaos:
  process:
    enabled: true
    engine: jdbc
```

需由应用 Flyway / Liquibase 或运维脚本预先建表，建表 SQL 参考模块内 `resources/db/chaos-process-schema-h2.sql`（含 `chaos_process_definition`、`chaos_process_instance`、`chaos_process_task`、`chaos_process_request` 四张表，可按目标数据库方言调整）。

## 注意事项

- 若构造器传入 `TransactionTemplate`，`start` 与 `complete` 均在事务边界内执行；由 `ChaosProcessAutoConfiguration` 装配时自动注入容器中的 `JdbcOperations` 与 `ObjectMapper`。
- 自定义表前缀时仅允许 `[A-Za-z_][A-Za-z0-9_]*`，防止 SQL 拼接注入。
