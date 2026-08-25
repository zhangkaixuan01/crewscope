# CrewScope Architecture Decision Records

ADR 记录跨模块、长期有效、会影响数据模型或运行拓扑的技术决策。

## 状态

```text
PROPOSED -> ACCEPTED -> SUPERSEDED
                    -> DEPRECATED
```

已接受 ADR 的变更通过新的 ADR 完成，原文保留并链接替代记录。

## 索引

| ADR | 决策 | 状态 |
|---|---|---|
| [ADR-001](ADR-001-执行状态与租约.md) | Task、TaskExecution、StepExecution 与 Lease | ACCEPTED |
| [ADR-002](ADR-002-ExecutionWorkspace与Sandbox.md) | MVP ExecutionWorkspace 与 Sandbox 拓扑 | ACCEPTED |
| [ADR-003](ADR-003-ArtifactStore与Snapshot.md) | ArtifactStore 与 AgentStateSnapshot | ACCEPTED |
| [ADR-004](ADR-004-CredentialStore与动作凭证.md) | CredentialStore 与动作级凭证 | ACCEPTED |
| [ADR-005](ADR-005-事件与投影协议.md) | DomainEvent、Outbox、Audit 与实时事件 | ACCEPTED |
| [ADR-006](ADR-006-ProviderBinding解析与授权.md) | ProviderBinding 解析与授权固化 | ACCEPTED |
| [ADR-007](ADR-007-API命令与并发协议.md) | API 错误、幂等、版本、Cursor 与 Command Receipt | ACCEPTED |
| [ADR-008](ADR-008-可观测性与日志安全协议.md) | Correlation、Trace、结构化日志、脱敏与指标 | ACCEPTED |
| [ADR-009](ADR-009-会话执行所有权与恢复协议.md) | Agent Session FIFO、执行所有权与 Redis 恢复 | ACCEPTED |
| [ADR-010](ADR-010-ExecutionRuntime调用与流协议.md) | Conversation 与 Task ExecutionRuntime 调用、流、恢复与控制 | ACCEPTED |
| [ADR-011](ADR-011-AgentScopeNativeRuntime实例与恢复协议.md) | AgentScopeNativeRuntime 配置、实例、恢复与取消 | ACCEPTED |
| [ADR-012](ADR-012-PlatformExecutionContext与AgentScope安全中间件.md) | PlatformExecutionContext、AgentScope 安全 Middleware 与基础 Audit | ACCEPTED |
| [ADR-013](ADR-013-AgentScope事件映射与披露协议.md) | AgentScope 原始事件、AG-UI 瞬时事件与业务 Candidate 披露边界 | ACCEPTED |
| [ADR-014](ADR-014-Agent模型调用可观测与安全重试协议.md) | Agent 模型调用观测、安全重试、Fallback、日志与指标边界 | ACCEPTED |
| [ADR-015](ADR-015-Agent模型目录、连接与配置解析.md) | 个人/团队/组织模型目录、凭证连接、Agent 配置版本与运行解析 | ACCEPTED |
| [ADR-016](ADR-016-Agent所有权、模板与执行配置.md) | Agent 所有权、运行角色、模板、个人/团队执行配置与 Review 独立性 | ACCEPTED |
| [ADR-017](ADR-017-Reviewer证据与人工Gate边界.md) | Reviewer 最小上下文、Finding 证据、SELF_REVIEW 与成员 Gate 边界 | ACCEPTED |
| [ADR-018](ADR-018-GitHub连接与Draft-PR交付边界.md) | GitHub 双连接、动作凭证、Push 幂等与 Draft PR 对账边界 | ACCEPTED |
| [ADR-019](ADR-019-ActionBundle调度与外部结果对账协议.md) | ActionBundle 精确确认、事务 Dispatch、唯一 Receipt 与外部结果对账 | ACCEPTED |
| [ADR-020](ADR-020-投影代际重建与游标协议.md) | Projection Generation、影子重建、原子切换、Fencing 与 Cursor 过期 | ACCEPTED |
| [ADR-021](ADR-021-三流恢复与前端合并协议.md) | Team/Conversation/AG-UI 独立恢复、快照、Scope Epoch 与合并去重 | ACCEPTED |
| [ADR-022](ADR-022-Inbox与固定模板通知授权协议.md) | Inbox 来源/处置分离、固定模板通知策略预授权、Lark 精确身份与幂等投递 | ACCEPTED |
| [ADR-023](ADR-023-Team-Beta单机部署与发布验证协议.md) | Team Beta 单机拓扑、低基数观测、固定负载、备份恢复与发布门禁 | ACCEPTED |

## ADR 内容要求

每份 ADR 包含背景、决策、实现约束、结果、验证和重新评估条件。ADR 只记录关键取舍，具体开发步骤由 `docs/plans` 承载。
