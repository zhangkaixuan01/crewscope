# CrewScope 里程碑执行清单

本目录把《CrewScope 实施计划》的里程碑拆成可领取、可验证、可关闭的执行任务。

## 1. 文档层次

```text
CrewScope-实施计划.md     里程碑、范围、依赖、周期和 Release Gate
CrewScope-前端设计规范.md 前端信息架构、视觉 Token、组件与验收规则
plans/M*-*.md            当前及下一个里程碑的可执行 Backlog
adr/ADR-*.md             跨模块关键技术决策
```

总实施计划保持稳定。里程碑任务清单在进入该里程碑前完成细化，后续里程碑只保留 Feature 级范围，避免基于尚未验证的技术假设过早拆分。

## 2. 任务类型

| 类型 | 用途 |
|---|---|
| `SPIKE` | 验证接口、拓扑或风险，输出可复现证据与决策 |
| `TASK` | 单模块、单适配器或单基础能力实现 |
| `FEATURE` | 可演示的纵向产品能力 |
| `HARDENING` | 安全、性能、并发、恢复和故障测试 |

## 3. 编号规则

```text
M{里程碑}-{工作流}{序号}
```

工作流代码：

| 代码 | 工作流 |
|---|---|
| `S` | Spike 与架构验证 |
| `D` | 领域与数据库 |
| `E` | 事件、Outbox 与投影 |
| `I` | 基础设施与外部适配 |
| `A` | API、认证与服务端 |
| `F` | 前端 |
| `Q` | 测试、质量与发布 |

## 4. 任务大小

- 单个 `TASK` 目标工期为 0.5–2 个工作日；
- 单个 `SPIKE` 目标工期为 1–3 个工作日；
- 超过 3 个工作日的任务在开始前继续拆分；
- 一个任务只设置一个主要交付结果和一个责任人；
- 任务关闭时必须提交代码、测试和运行证据。

## 5. Definition of Ready

任务进入开发前满足：

1. 目标、范围和非目标明确；
2. 前置任务已完成或拥有可用 Stub；
3. 涉及的 ADR 已接受；
4. 数据库、API、事件和 Structured Output 变更已识别；
5. 验证方式可以自动执行或形成明确人工证据。

## 6. Definition of Done

任务完成时满足：

1. 实现已合并并通过编译；
2. 复杂逻辑、公开 API 和安全边界包含必要注释；
3. 单元、集成、契约或端到端测试覆盖本任务风险；
4. 权限、幂等、并发、失败和恢复路径完成验证；
5. API、事件、配置、迁移和错误码文档同步更新；
6. 日志、指标、Trace 和 Audit 覆盖关键路径；
7. `./mvnw clean verify` 与相关前端检查通过；
8. 任务卡记录验证命令、结果和 Artifact 链接。

## 7. WorkItem 映射

任务进入 CrewScope 或 GitHub 时使用：

```text
title       [M0-S01] HarnessAgent 最小调用验证
type        SPIKE / TASK / FEATURE / HARDENING
milestone   M0
owner       唯一 TeamMember
labels      workstream、module、risk
depends_on  任务 ID 列表
acceptance  执行清单中的验证条件
evidence    Test、PR、Artifact、ADR 和演示链接
```

任务状态由实际 WorkItem 管理，Markdown 保存计划基线和拆分口径。

## 8. 当前执行清单

- [M0：工程与数据基线](M0-工程与数据基线.md)
- [M1：Team、WorkItem 与责任基础](M1-Team与WorkItem.md)
- [M2：Conversation 与 Personal Agent](M2-Conversation与Personal-Agent.md)
- [M3：耐久 Task Runtime](M3-耐久Task-Runtime.md)
- [M4：AgentScope 原生 Coding Agent](M4-AgentScope原生Coding-Agent.md)
- [M5：Agent 模型、个人执行 Agent、Review 与 GitHub Draft PR](M5-Agent模型与Review交付.md)

M0 至 M4 已完成。M4 的 44 个任务和 [M4-Q04 Release Gate](../testing/M4-Q04-Release-Gate.md) 已全部关闭；最终 DeepSeek 真实模型固定矩阵为 29 / 36、端到端成功率 80.56%，CrewScope 自修改闭环与质量门禁通过。M4 全量门禁为 Maven 1517 / 1517、Vitest 237 / 237、Playwright/视觉/Axe 126 / 126。

M5 已拆分为 48 个任务，详细落实 [ADR-015](../adr/ADR-015-Agent模型目录、连接与配置解析.md)、[ADR-016](../adr/ADR-016-Agent所有权、模板与执行配置.md)、[ADR-017](../adr/ADR-017-Reviewer证据与人工Gate边界.md)、[ADR-018](../adr/ADR-018-GitHub连接与Draft-PR交付边界.md) 与 [ADR-019](../adr/ADR-019-ActionBundle调度与外部结果对账协议.md)。M5-S01 已用 AgentScope 2.0.0 的真实 HarnessAgent、OpenAIChatModel 和双 Loopback Provider 冻结动态模型 Adapter Registry、Connection 隔离、Tool/Structured Output、Retry/Fallback 边界。M5-S02 已冻结旧 Agent 的确定性 Ownership/Role/Template 回填、PERSONAL/TEAM Binding 隔离与 PolicySnapshot v1/v2 兼容规则。M5-S03 已冻结 Reviewer 最小 ContextPackage、严格 Finding 证据、重复合并、SELF_REVIEW Advisory 与 TeamMember Gate 边界。M5-S04 已冻结 GitHub 双连接身份、Repository Catalog、动作级 AskPass、受管 Mirror、原子 Push Lease、Draft PR 查询对账、最小权限和安全错误边界。M5-S05 已冻结精确 ActionBundle、提交后 Dispatch、Lease/Fencing、唯一 Receipt、UNKNOWN、Webhook/主动查询合并和人工终态协议。五个 Spike 已全部关闭，下一任务为 `M5-D01`。
