# M2-D04：AgentRuntimeSession 绑定与生命周期

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立 CrewScope Personal Agent Conversation 与 AgentScope 状态槽之间的耐久可信绑定，固定稳定身份、Team 隔离、配置版本、外部状态引用和生命周期规则，为 M2-D06/D07 持久化、M2-I02 Runtime Port 与 M2-I05 Redis AgentStateStore 提供领域契约。

M2-D04 不创建耐久 AgentRun、TaskExecution、ExecutionLease 或 Redis 状态实现。一次调用、恢复或取消仍属于后续 Runtime 与 API 任务；本任务保存可重复解析的 Session 业务事实。

## 绑定事实

`AgentRuntimeSession` 保存：

```text
AgentRuntimeSessionId
OrganizationId / TeamId / WorkspaceId
ConversationId
Owner TeamMemberId / USER PrincipalId
Personal Agent PrincipalId / AgentProfileId
AgentProfile Version
AgentScope userId / sessionId
AgentRuntimeStateReference
ACTIVE / DISABLED / ARCHIVED
Aggregate Version / AuditMetadata
```

初始化要求：

- Conversation、Team Workspace、TeamMember、Owner USER、Personal Agent Principal 和 AgentProfile 均由服务端查询；
- Conversation 必须 ACTIVE，Workspace 必须是同一 Team 的 ACTIVE TEAM Workspace；
- Owner 必须是 Conversation Owner 和 ACTIVE TeamMember；
- Personal Agent 必须是该成员在当前 Workspace 的 ACTIVE 默认 Agent，并与 Conversation 的 Agent Principal 一致；
- `AgentRuntimeSessionId` 从 Conversation、TeamMember 和 Personal Agent 确定性派生，重试生成同一候选；
- Application Service 在事务内调用 `initializeIfAbsent`，Repository 必须把并发请求解析为一个已提交结果；
- Repository 返回结果必须再次经过完整绑定校验，防止跨 Team、成员、Conversation、Profile 或未来配置版本的伪造结果。

## AgentScope Key 与状态引用

AgentScope 状态隔离键采用带版本、带类型的规范编码：

```text
userId    = crewscope:v1:user:{organizationId}:{teamMemberId}:{personalAgentPrincipalId}
sessionId = crewscope:v1:session:{conversationId}:{agentRuntimeSessionId}
```

`AgentRuntimeStateReference` 使用：

```text
crewscope:agent-state:v1:{agentRuntimeSessionId}
```

三个值全部从聚合的规范 UUID 派生。聚合重建时重新计算并比较，不能使用客户端提交的 `agentId`、`threadId`、`sessionId`、Principal 或状态引用。相同绑定在重试、停用、重新启用和配置刷新后保持同一 AgentScope 状态槽；不同 Team 的 TeamMember 与 Personal Agent 产生不同 `userId`。

## 配置版本与生命周期

```text
ACTIVE -> DISABLED -> ACTIVE
ACTIVE / DISABLED -> ARCHIVED
```

- ACTIVE Session 可以进入 AgentScope 调用；
- DISABLED 暂停调用并保留 AgentScope Key 与状态引用；
- 重新启用必须重新校验当前 Conversation、Workspace、Membership、Owner 和 ACTIVE Personal Agent，并更新固定的 AgentProfile Version；
- ACTIVE Session 可刷新到更高的 ACTIVE AgentProfile Version，不能回退或重复刷新；
- ARCHIVED 只接受绑定的 ARCHIVED Conversation，并保持终态；
- 所有生命周期修改要求 `expectedVersion`，成功后 Aggregate Version 与审计修改事实同时推进。

AgentProfile Version 是本次运行时配置解析的固定版本。模型、Prompt、Tool、Skill、Memory 与 Policy 的完整配置快照在后续 Runtime 任务中建立，但必须沿用该版本引用，不能从客户端覆盖。

## Application Port

`AgentRuntimeSessionRepository.initializeIfAbsent` 定义并发初始化语义：

- 候选不存在时原子持久化；
- 候选已存在时返回唯一已提交绑定；
- 按 Conversation、Owner TeamMember 与 Personal Agent 串行裁决；
- 不得留下部分写入或返回其他 Scope 的 Session。

`AgentRuntimeSessionService.ensurePersonal` 在 Required Transaction 中创建确定性候选、调用 Repository，并对返回值执行第二次可信事实校验。Domain 与 Application 保持纯 Java，由后续 Infrastructure Adapter 和 Spring Configuration 负责装配。

## 自动化验证

专项验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am clean test
```

结果：Domain 182 个测试、Application 110 个测试通过，0 失败、0 错误、0 跳过。M2-D04 新增 12 个测试：

- Domain：9 个；
- Application：3 个。

覆盖完整绑定、稳定 ID、AgentScope Key、状态引用、Team 隔离、Personal Agent 所有权、停用、重新启用、配置版本刷新、Conversation 归档、终态、乐观锁、伪造重建、重复初始化、12 路并发初始化及错误 Repository 返回。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，472 个后端测试全部通过，失败、错误、跳过均为 0。AgentScope Harness、Docker Sandbox、PostgreSQL、Redis、Flyway、Spring 装配与 Server API 回归通过。

文档与差异检查：

```bash
node scripts/check-doc-links.mjs
git diff --check
```

## 后续任务

M2-D05 建立 ProviderDefinition、ProviderImplementation、Connection、ConnectionGrant 和 ProviderBinding 最小领域模型。M2-D06/D07 将本任务 Session 字段、唯一 active 约束、乐观锁和 `initializeIfAbsent` Port 落到 PostgreSQL。M2-I02/I03/I05 使用本任务 AgentScope Key、配置版本与状态引用建立 Conversation Runtime 和 Redis 状态恢复。
