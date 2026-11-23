# chaos-service

## 职责

应用服务层能力：事务执行器、重试执行器、领域事件发布、JSON 编解码、异步上下文传播、分布式定时任务执行器（`com.chaos.service.job`）和通用观测配置。

## 依赖方式

Servlet 业务服务通过场景 starter `chaos-web-service-starter` 间接引入；MQ 消费者、批处理等非 Web 服务单独引入 `chaos-application-starter`。

```xml
<dependency>
    <groupId>com.chaos</groupId>
    <artifactId>chaos-application-starter</artifactId>
</dependency>
```

如果是业务应用，优先使用对应 starter；如果是 domain/application 纯接口依赖，才直接依赖功能模块。

## 配置

```yaml
chaos:
  service:
    retry:
      max-attempts: 3
      backoff-ms: 100      # 0 表示不退避
    context-propagation:
      micrometer-enabled: false
```

纯 application/domain 模块只依赖 `chaos-service` 或更小的接口模块。

### 异步上下文传播

`chaos-application-starter` 会自动注册 `TraceContextTaskDecorator`，用于在线程池和 `@Async` 任务中传播 trace、租户、用户和 MDC 上下文。

- 默认实现按**类型**让位：容器中已有任意 `TaskDecorator` 时不再注册。旧版本按 Bean 名判断，用户再定义一个装饰器会出现两个，
  Boot 通过 `getIfUnique()` 获取时两个都不生效，上下文静默丢失。
- 需要组合多个装饰器时自行声明：

```java
@Bean
TaskDecorator taskDecorator() {
    return new CompositeTaskDecorator(List.of(new TraceContextTaskDecorator(), new MyDecorator()));
}
```

- `CompletableFuture` 默认线程池、Reactor 等不经过 `TaskDecorator` 的异步边界，可开启
  `chaos.service.context-propagation.micrometer-enabled=true`，把 chaos 上下文注册到 Micrometer `ContextRegistry`
  （需要 `io.micrometer:context-propagation`）。默认关闭，因为恢复快照时会整体恢复 MDC，与 Micrometer Tracing 同时使用时需先验证日志字段。
- Java 21 虚拟线程（`spring.threads.virtual.enabled=true`）下，Spring Boot 自动装配的 `SimpleAsyncTaskExecutor` 同样会接入 `chaosTraceContextTaskDecorator`；
  由 `VirtualThreadContextPropagationTest` 与 `ChaosServiceAutoConfigurationTest` 覆盖验证多虚拟线程并发隔离与退出清理。
  注意不要在持久化长连接或高并发热路径使用不设池化上限的 `ThreadLocal` 缓存大对象，避免随虚拟线程数量放大内存占用。

### 重试

默认 `RetryExecutor` **不重试** `BizException`（含 `IdempotentRejectedException`），并沿异常 cause 链判断；
业务规则拒绝重试不会成功，只会放大下游压力。

### 事务与提交后副作用

`TransactionExecutor`（`SpringTransactionExecutor`）提供显式编程式事务边界；需要确保只在事务真正提交后才触发外部副作用（例如通知、缓存失效、非 outbox 异步投递）时，使用 `TransactionalSideEffects` / `TransactionMarks`：

- 当前处于活跃事务时自动注册 `TransactionSynchronization`，仅在 `afterCommit` 回调执行副作用；
- 当前不存在事务时立即执行，避免业务单元测试或非事务入口漏执行副作用。

### 领域事件

`TransactionalDomainEventPublisher` 在事务提交后发布事件：

- **需要写库的监听器必须使用 `@Transactional(propagation = Propagation.REQUIRES_NEW)`**。AFTER_COMMIT 阶段原事务已提交，
  以默认 `REQUIRED` 写库会加入已提交事务，数据静默丢失。
- 提交后监听器抛出的异常只记录 ERROR 日志，不传回调用方，避免调用方误判失败而重试造成重复数据；无事务时异常照常抛出。
- 需要可靠投递时使用 outbox（`chaos-mq`）在业务事务内落库。

### JSON 编解码

框架自动注册 `JsonUtils`，并复用应用的 `ObjectMapper`，因此 Spring Boot 配置的 Java Time 模块、命名策略和日期格式都会生效。业务代码通过构造器注入使用，不创建静态全局 Mapper：

```java
private final JsonUtils jsonUtils;

String json = jsonUtils.toJson(value);
OrderSnapshot snapshot = jsonUtils.fromJson(json, OrderSnapshot.class);
```

泛型反序列化使用 Jackson `TypeReference`。需要生成内容摘要或签名时，可用 `toCanonicalJson(JsonNode)` 递归按对象字段名排序；数组顺序保持不变。编解码失败统一抛出 `JsonOperationException`，业务边界可按需转换为业务异常。

### 指标

通用标签只保留 `framework=chaos`。旧版本给所有指标追加 `meter.namespace=http.server.requests`，属于错误标签，已移除。

### 分布式定时任务（`com.chaos.service.job`）

原独立模块 `chaos-job` 已合并至 `chaos-service`（包 `com.chaos.service.job`），定位为轻量分布式互斥与 Trace/MDC 上下文增强（不包含任务分片、失败重试调度或控制台运维；接入 XXL-JOB / PowerJob 时可将 `chaos.job.scheduling-enabled` 设为 `false`，任务体继续复用 `DistributedJobRunner`）：

- `DistributedJobRunner` / `JobExecutor` / `JobExecutionContext`：以 `chaos.job.lock.key-prefix + jobName` 尝试非阻塞抢锁，生成独立 traceId 并写入 MDC `jobName`，结束后释放锁并恢复调度线程原上下文。
- 多实例部署需引入 `chaos-redis-starter` 提供 `DistributedLock`；无锁 Bean 时退化为本地执行并输出 WARN。

```yaml
chaos:
  job:
    enabled: true              # 总开关
    scheduling-enabled: true   # 是否开启 @EnableScheduling
    lock:
      key-prefix: "chaos:job:"
      lease-time:              # 为空时使用 Redisson watchdog 自动续期
```

## 示例

```java
@Service
public class OrderApplicationService {

    private final TransactionExecutor transactionExecutor;
    private final RetryExecutor retryExecutor;
    private final DomainEventPublisher domainEventPublisher;

    public OrderApplicationService(TransactionExecutor transactionExecutor,
                                   RetryExecutor retryExecutor,
                                   DomainEventPublisher domainEventPublisher) {
        this.transactionExecutor = transactionExecutor;
        this.retryExecutor = retryExecutor;
        this.domainEventPublisher = domainEventPublisher;
    }

    public void submit() {
        retryExecutor.run(() -> transactionExecutor.run(() -> {
            // 执行业务用例并发布领域事件。
            domainEventPublisher.publish(new OrderSubmittedEvent("order-1"));
        }));
    }
}
```

异步任务上下文传播：

```java
@Bean
ThreadPoolTaskExecutor applicationTaskExecutor(TaskDecorator taskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(32);
    executor.setTaskDecorator(taskDecorator);
    return executor;
}
```

## 扩展点

- 优先注册 Spring Bean 覆盖默认实现。
- 不在业务代码中依赖 autoconfigure 类。
- 不跨层调用 infra 实现，domain/application 只依赖接口。
- 需要替换事务、重试、领域事件或 JSON 工具实现时，注册同类型 Bean 即可覆盖默认实现。
- 需要自定义异步上下文传播时，注册类型为 `TaskDecorator` 的 Bean（建议组合 `TraceContextTaskDecorator`）。

## 注意事项

- 保持模块职责单一，避免把无关能力塞入当前模块。
- 新增公共 API 后同步更新本手册和 architecture.md。
