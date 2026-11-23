# chaos-architecture-tests

## 职责

本模块是仓库治理测试，名称直接表明它不是给业务方使用的测试支持库（后者是 `chaos-test-support`）。

仓库级架构治理测试（test scope，包 `com.chaos.architecture`），在 `./mvnw test` 阶段执行。

| 测试类 | 所在模块 | 内容 |
| --- | --- | --- |
| `DependencyDirectionArchitectureTest` | `chaos-architecture-tests` | 模块依赖方向规则 1~10（见 [architecture.md 1.1](../architecture.md#11-依赖方向规则)），失败信息标明规则编号、模块、文件与 import |
| `LayerBoundaryArchitectureTest` | `chaos-architecture-tests` | 基于 POM XML DOM 解析、SnakeYAML 与去注释源码剖析的模块边界、BOM 双向一致性、发布治理与配置安全检查 |
| `ArchetypeArchitectureTest` | `chaos-architecture-tests` | 校验 `chaos-archetypes` 的配置模板与文档模板对齐以及 Velocity 占位约束 |
| `ProjectModel` | `chaos-architecture-tests` | 共享的 POM XML DOM 解析、注释剥离、import / package 扫描 |
| `ArchitectureRulesArchUnitTest` | `chaos-autoconfigure` | 基于 ArchUnit 1.4.1 的字节码级规则校验（包隔离、`@AutoConfiguration` 位置、审计发布调用、`@Validated`、`Result`/`DefaultApiResult` 结构一致性等） |

`LayerBoundaryArchitectureTest` 与 `ArchitectureRulesArchUnitTest` 联合约束：

- 核心契约模块（`chaos-core`、`chaos-security-api`、`chaos-domain`、`chaos-audit`、`chaos-tenant`、`chaos-mq`、`chaos-storage`）不得引入 Spring、数据库、Redis、MQ、对象存储 SDK；扫描目录必须真实存在，模块改名后不会被静默跳过。
- starter 无 Java 代码、autoconfigure 仅含装配逻辑且使用 `AutoConfiguration.imports`、禁止 `package-info.java`。
- 公共 BOM 与真实模块双向一致；根 POM 不再声明 `dependencyManagement`；模块 POM 不写冗余的 `${project.version}`。
- 承载 `@ConfigurationProperties` 的模块必须引入 configuration processor 并启用 `@Validated`。
- 自动装配顺序、生产安全检查、审计发布等关键约定（`.java` 与 `pom.xml` 检查均经注释剥离或 DOM 解析，不会被注释文本误放行）。

## 发布与依赖

本模块没有 `src/main`，不提供对外 API：设置了 `maven.deploy.skip=true`，不发布，也不在 `chaos-dependencies` 中登记。
业务方不要依赖 `chaos-architecture-tests`。

## 运行

```bash
./mvnw -B -pl chaos-architecture-tests test
# 运行字节码级 ArchUnit 规则（chaos-autoconfigure）
./mvnw -B -pl chaos-autoconfigure -am -Dtest=ArchitectureRulesArchUnitTest -Dsurefire.failIfNoSpecifiedTests=false test
```

运行真实基础设施集成测试：

```bash
./mvnw -B -Pchaos-integration-test verify
```

## 扩展点

- 增加新的核心契约模块时，同步加入 `CONTRACT_SOURCE_ROOTS`。
- 增加任何带 Java 源码的新模块时，在 `DependencyDirectionArchitectureTest.BASE_PACKAGES` 登记基础包，否则规则 9 失败。
- `chaos-architecture-tests` 本身不依赖业务模块，因此借助 XML DOM、SnakeYAML 与 `ProjectModel.codeWithoutComments(...)` 实现无编译期依赖的结构检查，可在 `-pl chaos-architecture-tests` 独立运行；字节码级 ArchUnit 规则放在汇聚全部可选库类路径的 `chaos-autoconfigure`（`ArchitectureRulesArchUnitTest`）中执行，二者互补。

## 注意事项

- 普通 `./mvnw test` 不启动 Docker；只有 `chaos-integration-test` profile 会运行 Testcontainers。
- 集成测试类命名为 `*IT`，源码放入对应模块的 `src/integration-test/java`。
