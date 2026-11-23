# 集成测试

## 目标

`chaos-framework` 的普通 `mvn test` 只运行快速单元测试和架构测试；真实基础设施验证放入 `chaos-integration-test` profile，避免日常开发强依赖 Docker。

## 运行方式

运行前需要启动 Docker Desktop 或其他兼容 Docker API 的容器运行时，并确保当前用户可以访问 Docker socket。

```bash
./mvnw -B -gs .mvn/settings-central.xml -s .mvn/settings-central.xml -Pchaos-integration-test verify
```

只跑单个模块的集成测试：

```bash
./mvnw -B -Pchaos-integration-test -pl chaos-data/chaos-redis -am verify
```

`chaos-integration-test` profile 通过 `build-helper-maven-plugin` 把 `src/integration-test/java` 和 `src/integration-test/resources`
加入测试源码，再由 `maven-failsafe-plugin` 执行 `**/*IT.java`。

CI 中由 `.github/workflows/ci.yml` 的 `integration-test` job 执行，GitHub ubuntu runner 自带 Docker。
在此之前该 profile 从未进入 CI，导致 `RedisInfrastructureIT` 引用已迁移的类而长期编译失败却无人发现；
现在任何 IT 编译或运行失败都会阻断合并。

## 覆盖范围

仓库现有 5 个 `*IT` 集成测试类，覆盖 Redis、MySQL 与 Kafka 真实容器链路：

| 模块 | 测试类 | 基础设施 | 验证点 |
| --- | --- | --- | --- |
| `chaos-redis` | `RedisInfrastructureIT` | `redis:7.4-alpine` | Redisson 分布式锁、幂等 key/响应快照、滑动窗口限流、Lua 脚本 |
| `chaos-security-redis` | `RedisJwtRevocationServiceIT` | `redis:7.4-alpine` | 跨实例共享 JWT 黑名单撤销与 TTL 过期 |
| `chaos-security-redis` | `RedisAuthorizationPolicySourceIT` | `redis:7.4-alpine` | Redis 动态 ABAC 授权策略加载与更新 |
| `chaos-mybatis` | `MybatisTenantInfrastructureIT` | `mysql:8.4` | MyBatis-Plus 多租户拦截器真实 SQL 改写 |
| `chaos-mq` | `KafkaMessagePublisherIT` | Kafka（Testcontainers） | Kafka 消息发送确认、`message-id` 与 W3C `traceparent` 头 |

## RocketMQ 策略

RocketMQ 容器通常需要 NameServer、Broker 和较长启动等待时间，镜像兼容性也更依赖本地 Docker 环境。当前阶段不放入默认 `chaos-integration-test`，避免拖慢所有基础设施验证。

后续建议单独增加 `chaos-rocketmq-integration-test` profile，并满足以下条件：

- 使用固定 RocketMQ 镜像版本。
- 显式声明 NameServer 和 Broker 等待策略。
- 只验证 `RocketMqMessagePublisher` 的 destination、message-id 和 trace header。
- CI 单独分组运行，失败不阻塞 Redis、MySQL、Kafka 基础链路。

## 约束

- 集成测试类统一命名为 `*IT`。
- 集成测试源码放在 `src/integration-test/java`。
- 不把 Testcontainers 依赖放入 starter 聚合模块。
- 不在普通单元测试里启动 Docker。
