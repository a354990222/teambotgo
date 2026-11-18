# 异常处理可靠性约定

- Gateway 限流的错误恢复限定在限流器 Publisher，下游执行错误原样传播。
- 密码登录只将 BadCredentialsException / UsernameNotFoundException 计入错误次数；认证服务故障转换为 server_error，账号状态拒绝不计数。
- 授权策略缓存到期后加载失败即拒绝，不自动延长旧快照。
- JSON 绑定失败不记录原始异常消息；未经分类的 IllegalArgumentException 返回固定 INTERNAL_ERROR / 500，输入校验应使用显式 BizException 或绑定校验异常。
- JDBC 审计 SUCCESS 加入已有业务事务；独立事务配置用于非 SUCCESS 或无业务事务的事件。关键业务使用同步发布与 fail-fast，禁止使用可丢弃的异步队列。异步包装器依据 publishWithOutcome 的结果计数。
- 任务清理始终恢复 TraceContext；解锁失败在已有执行异常上作为 suppressed 保存。
- ServiceHttpClient 普通 POST 不自动重放网络故障，Idempotency-Key 仅传输，不代表已验证的对端幂等契约。GET 可重试一次。

- outbox 积压统计包含 PENDING、RETRYING、SENDING，任一统计不可用则报告未知，不能把重试任务当作零积压。
