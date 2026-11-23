# chaos-audit

## 职责

提供统一审计事件模型、审计发布端口、默认结构化日志发布器，以及可选 JDBC 持久化适配器（`com.chaos.audit.jdbc`）。源码目录为 `chaos-audit`，对外 artifactId 为 `chaos-audit`（`spring-jdbc`、`jackson-databind` 为 `optional`）。

## 依赖方式

业务应用通过 `chaos-web-service-starter`、`chaos-security-starter`、`chaos-authorization-starter` 或 `chaos-gateway-starter` 自动引入。

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-audit</artifactId>
</dependency>
```

## 配置

```yaml
chaos:
  audit:
    enabled: true
```

关闭审计时会注册 `NoopAuditEventPublisher`，避免调用方做空判断。

## 默认输出

默认发布器把审计事件写到独立的 `AUDIT_LOG` logger。生产环境建议在 logback 中为该 logger 配置单独的 appender
（独立文件、独立保留策略），或直接注册自定义 `AuditEventPublisher`。

需要框架内置的落库能力时，确保应用已引入 `spring-boot-starter-jdbc`（或 `chaos-mybatis-starter`），并显式配置 `chaos.audit.jdbc.enabled=true`（见下方 [JDBC 持久化适配](#jdbc-持久化适配)）。

## 内置事件

- `auth.login.success`
- `auth.login.failure`
- `auth.token.revoke`
- `auth.refresh.success`
- `auth.refresh.replay`
- `auth.session.kickout`
- `security.permission.denied`
- `gateway.auth.denied`
- `gateway.blacklist.denied`
- `gateway.tenant.denied`

## 示例

```java
auditEventPublisher.publish(AuditSupport.event(AuditAction.AUTH_LOGIN_SUCCESS, AuditOutcome.SUCCESS)
        .principalId(loginUser.userId())
        .tenantId(loginUser.tenantId())
        .clientId(registeredClient.getClientId())
        .attributes(AuditSupport.attributes("grantType", "password"))
        .build());
```

多个属性、且大多"有值才写"时用 `AuditAttributes`：

```java
.attributes(AuditAttributes.create()
        .putIfNotBlank("grantType", loginContext.grantType())
        .putIfNotBlank("deviceId", loginContext.deviceId())
        .put("rotated", rotated)
        .build())
```

`putIfNotBlank` 跳过空值（设备号、User-Agent 这类可能缺失的字段），`put` 只跳过 null
（布尔、计数这类"值本身就是结论"的字段不能被当成空值丢掉）。

## 扩展点

业务系统可以提供自己的 Spring Bean 覆盖默认日志发布器：

```java
@Bean
AuditEventPublisher auditEventPublisher(AuditRepository repository) {
    return repository::save;
}
```

推荐扩展方向：

- 写入审计表。
- 投递 Kafka/RocketMQ。
- 对接企业 SIEM 或安全审计平台。
- 按事件动作配置告警规则。

框架已经提供内置 JDBC 落库适配器（`com.chaos.audit.jdbc.JdbcAuditEventPublisher`）。

## 属性脱敏

`AuditAttributeSanitizer` / `DefaultAuditAttributeSanitizer` 位于本模块，所有发布器输出扩展属性前都应调用。

- 默认 `LoggingAuditEventPublisher` 已接入脱敏（旧版本直接打印 attributes，密码、验证码会进入日志平台），
  并把 `reason` 中的换行替换为空格，防止伪造审计日志行。
- 自动装配注册 `AuditAttributeSanitizer` Bean，自定义发布器可以直接注入复用。
- 脱敏规则见下方"脱敏策略"一节。

## 注意事项

- 审计事件只记录必要主体、租户、客户端、IP、URI、traceId 和原因，避免写入密码、验证码、完整 token 等敏感值。
- `AuditSupport.event` 读取的主体和租户来自 `RequestContext`，其值只来自认证结果或可信代理，不能被客户端请求头伪造。
- 框架模块只依赖 `AuditEventPublisher` 端口，不直接依赖数据库、MQ 或第三方审计 SDK。
- 新增审计动作时同步更新 `AuditAction`、模块文档和架构测试。

## 异步发布

审计事件默认在请求线程内同步发布。开启 JDBC 落库时，每次登录、每次权限拒绝都是一次 inline
数据库写入——扫描器批量打未授权接口会把审计写入变成可被外部触发的放大点。

```yaml
chaos:
  audit:
    async:
      enabled: true        # 默认 false；配合 JDBC 落库时强烈建议开启
      queue-capacity: 10000
```

开启后请求线程只做一次入队，实际写入在守护线程 `chaos-audit-publisher` 上完成。

- **队列满时丢弃，绝不阻塞**：队列满说明下游已经跟不上，此时阻塞业务只会把下游故障放大成全站故障。
  丢弃计入 `chaos.audit.events{outcome="dropped"}` 并按分钟节流打告警日志。
- 单条事件写入失败不会让工作线程退出，计入 `outcome="failed"`。
- 容器关闭时排空队列，避免优雅停机丢掉最后一批审计。
- **合规要求零丢失时不要开启异步**：改用同步写入，或把审计投递到 MQ。


## JDBC 持久化适配

`com.chaos.audit.jdbc.JdbcAuditEventPublisher` 在 `chaos.audit.jdbc.enabled=true` 且容器存在 `JdbcOperations` 时由 `ChaosAuditJdbcAutoConfiguration` 自动装配（优先级高于默认日志审计）。

1. 业务服务提供 `DataSource`（需引入 `spring-boot-starter-jdbc` 或 `chaos-mybatis-starter`）。
2. 执行 `classpath:db/chaos-audit-schema.sql`（或 `classpath:db/mysql/chaos-audit-schema.sql`）建表。
3. 配置 `chaos.audit.jdbc.enabled=true` 并强烈建议开启 `chaos.audit.async.enabled=true`。

```yaml
chaos:
  audit:
    enabled: true
    async:
      enabled: true
    jdbc:
      enabled: true
      table-name: chaos_audit_event
      fail-fast: false
      independent-transaction: true  # 存在事务管理器时使用 REQUIRES_NEW 独立事务写入
      application-generated-id: false
      mask-value: "******"
```

- **独立事务与字段截断**：`REQUIRES_NEW` 避免业务回滚吞掉失败/拒绝审计；写入前按表字段上限截断（action 128、outcome 32、principal/tenant/client/trace 128、ip 64、uri/reason 512）；表名只允许 `table` 或 `schema.table` 字母/数字/下划线格式。
- **脱敏策略**：默认对 `password`、`passwd`、`token`、`secret`、`credential`、`authorization`、`cookie`、`privatekey`、`apikey`、`smscode`、`verifycode`、`verificationcode`、`captcha`、`authcode` 等关键词包含匹配，对 `code`、`pin`、`otp`、`cvv`、`pwd` 精确匹配，对 `Bearer xxx` / `Basic xxx` / JWT 值特征脱敏。`chaos.audit.jdbc.sensitive-keywords` 可补充包含匹配列表。
- **应用生成主键**：`chaos.audit.jdbc.application-generated-id=true` 时必须提供 `com.chaos.core.id.IdGenerator` Bean 并在表中提供 `BIGINT id` 列，否则启动报错阻断。

异常分类、审计事务与安全诊断的当前约定见 [异常可靠性](../exception-reliability.md)。
