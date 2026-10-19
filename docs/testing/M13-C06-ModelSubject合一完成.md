# M13-C06：ModelSubject 双胞胎合一 — 完成记录

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C06 工作包（Review §3.3：ModelBillingSubject ↔ ModelCredentialSubject 96↔96）。
> 性质：纯结构收敛；唯一登记偏差为形状校验错误字段统一（见 §2），不涉及 API/DB/事件形状。

## 1. 交付：一个 record，两个位置

`ModelBillingSubject` 与 `ModelCredentialSubject` 逐行同构（字段、工厂、`isAllowedFor` 逻辑、形状校验完全一致），唯一差异是形状校验抛出的字段名。合并为 `ModelSubject`（domain/model，102 行）：

- 三个工厂 `organization` / `team` / `principal` 与形状校验原样保留。
- `isAllowedFor(ModelConnectionOwner)` 的 USER/TEAM/ORGANIZATION 判定逐字保留。
- `ModelConnection` 的两个位置字段改名为同一类型：`ModelSubject credentialSubject`（承载凭据绑定作用域）与 `ModelSubject billingSubject`（承载用量记账作用域），position 仍由字段名表达；`requireCredentialBinding`/`requireBillingSubject` 各自的错误字段与文案原样保留。
- 24 个引用文件的类型名机械替换，15 个文件删除重复 import；无逻辑编辑。

## 2. 行为等价性与登记偏差

- 字段、工厂语义、`isAllowedFor` 判定、null 检查顺序与消息逐字保留。
- **登记偏差 1 项（唯一）**：形状校验（`requireValidShape`）抛出的字段名由 `modelConnection.billingSubject` / `modelConnection.credentialSubject` 统一为 `modelConnection.subject`。原因：两个 record 合并后构造函数无法区分调用位置，而字段名是两者唯一的差异面；若保留两个字段名必须保留两个类型，合一即不成立。
  - 影响面评估：生产路径无法构造非法形状——三个工厂只会产出合法形状，持久层 mapper 从已约束的列重建；全仓检索未发现任何测试或调用方断言/依赖该字段名。`ModelConnection` 面向调用方的两个作用域校验仍分别抛出 `modelConnection.credentialSubject` 与 `modelConnection.billingSubject`，对外错误键不变。
  - 按计划铁律登记于此；如 Q01 复核认定需要逐字保留旧字段名，回退方案为恢复两个 record 并共享一个包私有形状校验器（会放弃类型合一）。

## 3. 行数盘点（如实入档）

| 口径 | 数值 |
| --- | --- |
| 基线 | 192 行（96+96） |
| 现况 | `ModelSubject` 102 行 + 24 文件引用机械替换（15 处重复 import 删除） |
| diff 口径 | +128 / −335（含未跟踪新文件 +102），**净 −105** |

## 4. 测试证据

| 面 | 结果 |
| --- | --- |
| 全模块编译（`-DskipTests compile`，六模块） | BUILD SUCCESS（912/1196/147/21/473/393 源文件） |
| domain 模块 test | 892 用例，0 失败 / 0 错误 / 0 跳过 |
| application 定向（6 类：解析器、嵌入、连接服务、凭据服务、可用性缓存、目录） | 49/49 |
| server 定向 `ModelManagementControllerM5A01Test` | 5/5 |

## 5. 复跑入口

```sh
./mvnw -pl crewscope-domain test
./mvnw -pl crewscope-application -am test -Dtest='AgentExecutionConfigurationResolverTest,TeamEmbeddingServiceTest,ModelConnectionApplicationServiceM5A01Test,ModelConnectionCredentialServiceTest,CachedModelConnectionAvailabilityVerifierTest,SelectableModelCatalogServiceM5I04Test' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl crewscope-server -am test -Dtest='ModelManagementControllerM5A01Test' -Dsurefire.failIfNoSpecifiedTests=false
```

全 reactor 与真实栈由 Q01 收口统一背书。
