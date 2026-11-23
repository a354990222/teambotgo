# chaos-process-redis

## 职责

`ProcessEngine` 端口的 Redis 持久化适配（包 `com.chaos.process.redis`），核心实现为 `RedisProcessEngine`。

特点：

- 基于 `StringRedisTemplate` 将流程定义、实例、任务与幂等请求以 JSON 快照存入 Redis，并用 Redis Set 维护索引；
- 任务认领（`claim`）与完成（`complete`）通过 Lua CAS 脚本校验 `"version":N` 后原子覆写，避免多实例并发覆盖同一任务版本；
- 支持启动/完成请求幂等重放与多实例租约过期重认领；
- 仅支持 `START`、`USER_TASK`、`END` 节点类型。

## 依赖方式

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-process-redis-starter</artifactId>
</dependency>
```

## 配置

```yaml
chaos:
  process:
    enabled: true
    engine: redis
```

需要 Redis 服务开启持久化（AOF/RDB）、主从复制及 Lua 脚本执行能力。

## 注意事项

- 默认 key 前缀为 `chaos:process`，自定义前缀仅允许 `[A-Za-z0-9:_-]+`。
- 对强事务一致性与长期审计留存要求较高的流程，建议使用 [chaos-process-jdbc](chaos-process-jdbc.md)。
