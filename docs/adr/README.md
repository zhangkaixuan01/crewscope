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

## ADR 内容要求

每份 ADR 包含背景、决策、实现约束、结果、验证和重新评估条件。ADR 只记录关键取舍，具体开发步骤由 `docs/plans` 承载。
