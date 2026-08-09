# M2-I01：Provider BindingResolver

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

实现 [ADR-006](../adr/ADR-006-ProviderBinding解析与授权.md) 的只读 Provider BindingResolver，在任何 Provider Tool 或 Runtime 装配前，从服务端可信 Scope、Binding Owner、外部执行身份、能力和资源事实解析唯一可用 Binding。

## 解析输入

```text
Organization / Team / Workspace / optional WorkProject
Binding Owner / optional external execution identity
ProviderType / requested capability and resource scope
optional Action explicit Binding / optional Task explicit Binding
```

客户端不能直接构造该输入。M2-I04、M2-A03 和后续 Task Runtime 从认证主体、Membership、Workspace、WorkProject、ProviderBinding 配置与 Policy 事实建立请求。

## 优先级与占位

```text
Action explicit
  -> Task explicit
  -> WorkProject
  -> Workspace for exact Owner
  -> Organization default when Owner is ORGANIZATION
```

显式 Binding 不可用时不回退。自动解析先定位最高存在的 ACTIVE 原始层级，再在该层重验当前事实；高层 Binding 因暂停依赖、Grant 撤销、版本变化或访问交集为空而失效时不回退，避免静默扩大权限。

同层唯一 ACTIVE 默认项优先。没有默认项时，一个有效候选直接解析，多个有效候选返回歧义。解析结果为 `RESOLVED`、`NOT_FOUND` 或 `AMBIGUOUS`，执行消费者只接受 `RESOLVED`。

## 当前事实闭合

每个候选重新读取并验证：

- ProviderDefinition ID、版本、类型和状态；
- ProviderImplementation ID、版本、接口兼容和状态；
- Connection ID、版本、Connector、Owner、状态和有效期；
- ConnectionGrant ID、版本、Grantee、状态、时间、能力和资源；
- ProviderBinding Scope、Owner、外部执行身份、状态和固化访问范围；
- 本次请求能力与资源范围的非空交集。

任一事实缺失或变化时忽略该无效候选；最高层因此没有有效候选时返回 `NOT_FOUND`。

## 验证范围

Application 契约测试覆盖优先级、默认项、同级歧义、显式收窄、层级失败关闭、Owner/执行身份隔离、Grant 交集和当前事实变化。PostgreSQL 集成测试使用 V7 Repository Adapter 覆盖真实候选查询、跨 Team/Workspace 收口、暂停/撤销、WorkProject 优先和歧义结果。

## 验证结果

已完成以下实现：

- Application 层提供框架无关的 `ProviderBindingResolver`、可信请求、解析层级与 `RESOLVED/NOT_FOUND/AMBIGUOUS` 结果契约；
- Resolver 精确匹配 Scope、Binding Owner、ProviderType 和 ExecutionIdentity，重读 Definition、Implementation、Connection 与 Grant，并将固化访问范围收窄到本次请求交集；
- JPA Candidate 查询对外部执行身份使用枚举精确匹配，对 connectionless Binding 使用 `IS NULL`，保留 V7 resolver 索引查询路径；
- Spring Boot 组合根使用 `ProviderApplicationConfiguration` 和构造器参数装配 Resolver，Domain/Application 不引入 Spring 注解；
- 最高层候选、显式 Binding 和默认项失效均失败关闭，不发生身份或权限范围回退。

专项验证：

```text
ProviderBindingResolverTest                 6 tests passed
M2JpaPersistenceIntegrationTest             9 tests passed
ApplicationCompositionConfigurationTest     1 test passed
```

Application 测试覆盖 Action/Task 显式优先级、WorkProject 层级占位、默认项、同层歧义、Owner 与 ExecutionIdentity 隔离、connectionless 隔离、Grant/请求交集、Definition/Implementation 版本和能力失效。PostgreSQL 测试覆盖真实 ExecutionIdentity/NULL 候选隔离、V7 索引路径、WorkProject 优先、同层歧义与当前事实失效；既有 M2 JPA Scope 测试继续覆盖 Organization、Team、Workspace 与 WorkProject 收口。Spring 组合测试证明 Resolver 仅装配一个 Bean。

全仓验证通过：

```text
mvn clean verify                  BUILD SUCCESS，536 个 Java 测试通过
node scripts/check-doc-links.mjs  76 个 Markdown 文件链接通过
git diff --check                  通过
```
