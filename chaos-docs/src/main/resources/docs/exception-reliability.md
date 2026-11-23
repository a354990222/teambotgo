# 异常处理可靠性约定

- Gateway 限流的错误恢复限定在限流器 Publisher，下游执行错误原样传播。
- 密码登录只将 BadCredentialsException / UsernameNotFoundException 计入错误次数；认证服务故障转换为 server_error，账号状态拒绝不计数。
- 授权策略缓存到期后加载失败即拒绝，不自动延长旧快照。
- JSON 绑定失败不记录原始异常消息；未经分类的 IllegalArgumentException 返回固定 INTERNAL_ERROR / 500，输入校验应使用显式 BizException 或绑定校验异常。
- JDBC 审计 SUCCESS 加入已有业务事务；独立事务配置用于非 SUCCESS 或无业务事务的事件。关键业务使用同步发布与 fail-fast，禁止使用可丢弃的异步队列。异步包装器依据 publishWithOutcome 的结果计数。
- 任务清理始终恢复 TraceContext；解锁失败在已有执行异常上作为 suppressed 保存。
- ServiceHttpClient 普通 POST 不自动重放网络故障，Idempotency-Key 仅传输，不代表已验证的对端幂等契约。GET 可重试一次。

- outbox 积压统计包含 PENDING、RETRYING、SENDING，任一统计不可用则报告未知，不能把重试任务当作零积压。

## 数据库写入冲突

Servlet 全局异常处理器将 Spring DAO 的 `DuplicateKeyException`、`PessimisticLockingFailureException`（含死锁、获取锁失败）和 `OptimisticLockingFailureException` 及其子类返回为 HTTP 409 / `CommonErrorCode.CONFLICT`。响应使用固定可国际化文案，日志只记录异常类型，不包含 SQL、重复键值或原始异常链。其他数据完整性错误、连接故障和未知异常仍返回 500；不递归查找任意包装异常的 cause，避免把未知故障误判为业务冲突。

框架只负责 HTTP 边界转换，不重试 SQL、不吞掉事务内异常。仓储需要特定业务码时在对应写入边界将重复键转换为业务异常（例如 `USER_DUPLICATE`）；业务异常仍使用原有映射。数据库唯一索引负责并发裁决，预查询仅改善提示。后台任务与消息消费没有 HTTP 边界，继续接收原始 DAO 异常，由调用者处理整个事务的失败与重试。

通过异常类继承链的全限定名分类，与已有 Spring Security 可选依赖处理一致；`chaos-web` 不新增运行时 DAO/JDBC 依赖。不改变 `IDEMPOTENT_REJECTED` 的“重复请求”语义。

## 通用错误码与 HTTP 状态分离

框架仅以 `"0"` 表示成功；失败响应使用 `COMMON_*`，HTTP 状态不变。内置码优先使用框架的显式 HTTP 映射，不能被业务后缀映射误判（例如限流和内部故障不能变成 400）。应用自定义错误码仍经应用映射；旧应用自定义的数字码保持原有 HTTP 映射，但框架自身不再产生数字失败码。

Spring ErrorResponse 中其他标准 4xx 状态使用 `COMMON_<HTTP 枚举名>`（例如 410 对应 `COMMON_GONE`）；未命名状态使用 `COMMON_HTTP_ERROR`，实际状态保留在 HTTP 层。未单独列出的 5xx 使用 `COMMON_INTERNAL_ERROR`。不把异常消息或未知状态原因原文回显。完整清单和客户端迁移要求见 modules/chaos-core.md。
