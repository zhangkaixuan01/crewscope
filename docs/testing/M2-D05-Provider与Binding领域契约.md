# M2-D05：Provider、Connection 与 Binding 领域契约

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-integration`

## 目标

建立 ProviderDefinition、ProviderImplementation、Connection、ConnectionGrant 与 ProviderBinding 的最小可信事实，固定能力兼容、外部身份所有权、授权交集、版本快照、撤销/过期和跨 Team 边界，为 M2-D06/D07 持久化、M2-I01 BindingResolver 与 M2-A06 内置 NativeWorkItem Provider 初始化提供稳定契约。

M2-D05 不实现默认优先级解析、不选择歧义 Binding、不调用外部系统，也不解析真实 Credential。BindingResolver、JPA Adapter、Provider API 与 Connector Worker 分别由后续 I01、D07、A06 和执行里程碑实现。

## Provider Registry

`ProviderDefinition` 保存：

```text
ProviderDefinitionId / OrganizationId
Stable Key / ProviderType / Interface Version / Display Name
Standard Capabilities
ACTIVE / DISABLED / ARCHIVED
Aggregate Version / AuditMetadata
```

`ProviderImplementation` 保存：

```text
ProviderImplementationId / OrganizationId
ProviderDefinitionId / Definition Interface Version / ProviderType
Stable Key / Implementation Version
Implemented Capabilities
Connection Requirement: NONE / REQUIRED
Optional Connector Key
ACTIVE / DISABLED / ARCHIVED
Aggregate Version / AuditMetadata
```

Implementation 必须与 ACTIVE Definition 位于同一 Organization、引用精确 Definition 和接口版本，能力只能是 Definition 标准能力的子集。`REQUIRED` 必须声明 Connector Key，`NONE` 禁止声明 Connector。现有 Native、GitHub 和 Lark Runtime Provider 统一使用 Domain `ProviderType`，应用层不保留第二套枚举。

## Owner 与 Connection

`ProviderOwner` 支持：

```text
USER          Organization + USER Principal
TEAM          Organization + Team
ORGANIZATION  Organization
```

Owner 形状保存规范 Owner ID 和类型化引用。USER 必须从 ACTIVE USER Principal 建立，TEAM 必须从 ACTIVE Team 建立。不同 Organization 的 Owner 不能进入同一 Connection、Grant 或 Binding。

`Connection` 表示一个 Owner 对外部系统身份的授权，保存 Connector Key、非敏感外部账户引用、CredentialId、可选有效期、状态、终态原因、版本和审计。生命周期为：

```text
ACTIVE -> SUSPENDED -> ACTIVE
ACTIVE / SUSPENDED -> REVOKED
ACTIVE / SUSPENDED -> EXPIRED（到达 expiresAt 后）
```

REVOKED 与 EXPIRED 是终态。所有修改要求 `expectedVersion`。Credential 明文仍只存在 CredentialStore/Vault，不进入 Provider 领域对象、日志或 Agent 上下文。

## ConnectionGrant

`ConnectionGrant` 保存 Connection Owner、Grantee、能力集合、资源集合、`validFrom/expiresAt`、状态、版本和审计。授权规则：

- Owner 可以授权给自身；
- ORGANIZATION Owner 可以向同组织 TEAM 或 USER 下放；
- TEAM 与 USER Owner 不能向其他 Owner 扩权；
- Grant 只有在自身 ACTIVE、时间有效且 Connection 当前可用时生效；
- REVOKED 与 EXPIRED 是终态，修改使用 `expectedVersion`。

有效访问是请求与 Grant 的双维交集：

```text
requested capabilities ∩ granted capabilities
requested resources    ∩ granted resources
```

任一维度交集为空即失败关闭。`ProviderResourceScope` 支持显式资源集合和不限制资源维度；显式集合使用规范化、不可变的稳定资源 Key。

## ProviderBinding

Binding 目标支持 ACTIVE Team Workspace 和 ACTIVE WorkProject。保存：

```text
ProviderBindingId / OrganizationId
Workspace or WorkProject Target
USER / TEAM / ORGANIZATION Binding Owner
ProviderDefinition ID + Version + ProviderType
ProviderImplementation ID + Version
Optional Connection ID + Version
Optional ConnectionGrant ID + Version
Optional External Execution Identity
Effective Capability and Resource Scope
Default Usage
ACTIVE / DISABLED / ARCHIVED
Aggregate Version / AuditMetadata
```

外部 Provider 创建 Binding 时要求 Connection、Grant 和执行身份完整存在；Connector、Organization、Grantee 与实现必须一致。Binding 的请求能力必须属于 Implementation，最终保存与 Grant 的非空能力/资源交集。Connection Owner 决定外部执行身份：

```text
USER          -> DELEGATED_USER
TEAM          -> TEAM_SERVICE_ACCOUNT
ORGANIZATION  -> ORGANIZATION_SERVICE_ACCOUNT
```

Native Provider 的 `ConnectionRequirement=NONE`，Connection、Grant 和执行身份全部为空。TEAM Binding Owner 必须与目标 Team 相同；USER 当前 Membership 和 ORGANIZATION 策略由服务端 Resolver 在每次读取时继续校验。

`ProviderBinding.currentAccess` 要求 Binding ACTIVE，并重新校验所有固化 ID、版本、Definition/Implementation 状态、Connection/Grant 状态、有效期和授权交集。任何依赖事实改变立即返回无可用访问，不依赖异步修改 Binding 行。Application `ProviderBindingCandidate.resolve` 把这组当前事实闭合为只读 Candidate，失败时采用拒绝策略。

## 生命周期和并发

Definition、Implementation 和 Binding 使用：

```text
ACTIVE -> DISABLED -> ACTIVE
ACTIVE / DISABLED -> ARCHIVED
```

ARCHIVED 是终态。Connection、Grant 和 Registry/Binding 的修改统一使用聚合版本与 `expectedVersion`；成功后版本、修改 Principal 和修改时间同时推进。Binding 固化依赖版本，避免恢复或执行时静默切换实现、外部身份或授权。

## 自动化验证

专项验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application,crewscope-integration -am clean test
```

结果：Domain 198 个测试、Application 112 个测试通过，Integration 编译通过；失败、错误、跳过均为 0。M2-D05 新增 18 个测试：

- Domain：16 个；
- Application：2 个。

覆盖 Definition/Implementation 兼容、能力扩张拒绝、Connector 形状、USER/TEAM/ORGANIZATION Owner、Connection 暂停/恢复/撤销/过期、Grant 下放、能力和资源交集、Grant 撤销/过期、外部与 connectionless Binding、Workspace/WorkProject 目标、跨 Team 拒绝、Connector 不匹配、依赖版本失效、Binding 生命周期和只读 Candidate。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，490 个后端测试全部通过，失败、错误、跳过均为 0。AgentScope Harness、Docker Sandbox、PostgreSQL、Redis、Flyway、Spring 装配、Server API 与 Native/GitHub/Lark Provider Adapter 回归通过。

文档与差异检查：

```bash
node scripts/check-doc-links.mjs
git diff --check
```

## 后续任务

M2-D06 新增 V7 Conversation、AgentRuntimeSession 与 Provider/Connection/Binding 表、复合 Scope 外键、审计字段和唯一 active 约束。M2-D07 实现 Mapper、JPA Adapter 与锁查询 Port。M2-I01 基于本任务 `ProviderBindingCandidate` 实现只读 BindingResolver、优先级、执行身份隔离和歧义失败关闭。
