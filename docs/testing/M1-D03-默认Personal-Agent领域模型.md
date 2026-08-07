# M1-D03：默认 Personal Agent 领域模型

> 日期：2026-08-07<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

为每个 TeamMember 建立唯一、稳定、可审计的默认 Personal Agent，并把 Owner 的默认 Agent 纳入 Team 创建事务。M1-D03 建立身份与配置事实，真实对话、AgentScope Runtime、模型、Prompt、Tool、Skill 和 Memory 接入属于 M2。

## 产品边界

默认 Personal Agent 以 TeamMember 为归属单位：

```text
TeamMember
  -> PERSONAL_AGENT Principal
  -> PERSONAL AgentProfile
  -> Team Workspace
```

同一 USER Principal 加入不同 Team 时拥有不同的 Personal Agent。每个 Agent 使用当前 Team Scope，Owner 是成员对应的 USER Principal，可发现性为 PRIVATE。AgentProfile 绑定 TeamMember、Team Workspace 和 Agent Principal，保存类型、默认标记、状态、版本与审计元数据。

该设计使团队权限、ProviderBinding、后续 Memory 和审计链保持隔离。用户仍是责任主体，Personal Agent 是成员在当前 Team 内的委托执行身份。

## 领域模型

新增：

- `AgentProfileId`；
- `AgentProfileType`：`PERSONAL/TEAM/SPECIALIST`；
- `AgentProfileStatus`：`ACTIVE/DISABLED/ARCHIVED`；
- `AgentProfile`；
- `PersonalAgentInitialization`。

Personal Agent 初始化不变量：

- Owner 必须是同 Organization 的 ACTIVE USER；
- Owner 必须与 ACTIVE TeamMember 的 USER Principal 一致；
- Workspace 必须是该成员所在 Team 的 ACTIVE TEAM Workspace；
- Agent Principal 必须是 ACTIVE、PRIVATE、Team Scope 的 `PERSONAL_AGENT`；
- Agent Principal Owner 必须是成员 USER Principal；
- AgentProfile 必须是 ACTIVE、默认 `PERSONAL` Profile；
- Profile、Principal、TeamMember 和 Workspace 的 Organization、Team 与 ID 引用必须完整一致；
- `ARCHIVED` AgentProfile 是终态，`ACTIVE/DISABLED` 支持受控切换并推进版本和审计字段。

`AgentProfile.ownerMemberId` 仅对 Personal Profile 必填，为后续 Team Agent 和 Specialist Agent 保留其他 Owner 形态。M1 不提前固化模型、Prompt 和能力配置结构。

## 稳定身份与并发幂等

`PersonalAgentInitialization` 使用稳定 TeamMember ID 和不同命名空间派生 Principal ID、AgentProfile ID。同一成员的重试和并发请求生成相同候选 ID，Principal 与 Profile 的 ID 彼此不同。

应用层新增 `DefaultPersonalAgentRepository.initializeIfAbsent`。Port 契约要求实现：

1. 按 TeamMember 原子初始化；
2. 不存在时同时写入 Principal 与 AgentProfile；
3. 已存在时返回完整的现有 Agent 对；
4. 并发竞争只产生一个提交结果；
5. 不留下孤立 Principal 或 AgentProfile。

`DefaultPersonalAgentService` 在 REQUIRED 事务中调用该 Port，并对 Repository 返回结果再次执行成员和 Workspace 校验。M1-D07 增加 active 默认 Profile 唯一约束，M1-D08 实现数据库锁定、插入竞争解析和对象映射。

## Team 创建事务

`TeamCreationService` 当前提交顺序：

```text
Team
Team Workspace
Owner TeamMember
Built-in TeamRoles
Owner MemberRole
Owner Personal Agent Principal + AgentProfile
```

所有写入位于同一个 `TransactionExecutor.required` 边界。Personal Agent 初始化失败会使完整 Team 创建事务回滚。

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-application -am clean test
```

M1-D03 新增 11 个测试：

- AgentProfile：4；
- PersonalAgentInitialization：4；
- DefaultPersonalAgentService：3。

覆盖默认 Profile 引用、跨 Team Workspace 拒绝、错误 Owner 拒绝、生命周期、稳定派生 ID、停用成员与用户、Principal/Profile 配对、Repository 返回值校验、重复请求和 12 路并发初始化。

专项结果：Domain 81 个测试、Application 41 个测试通过，合计 122 个；0 失败、0 错误、0 跳过。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块全部构建成功，后端共 238 个测试通过，0 失败、0 错误、0 跳过。

## 后续

M1-D04 扩展 WorkProject、WorkItem、Comment、ResourceLink 与状态机。M1-D07 和 M1-D08 分别完成数据库约束与 Repository Adapter，M1-A01 再暴露 Team 创建和成员初始化 API、CommandReceipt、DomainEvent 与 Outbox。
