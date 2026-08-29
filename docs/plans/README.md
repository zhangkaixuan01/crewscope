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
- [M6：团队观测、飞书通知与 MVP 发布](M6-团队观测与MVP发布.md)
- [M7：开放用户体系与登录体验](M7-开放用户体系与登录体验.md)

M0 至 M6 已完成。M4 的 44 个任务和 [M4-Q04 Release Gate](../testing/M4-Q04-Release-Gate.md) 已全部关闭；最终 DeepSeek 真实模型固定矩阵为 29 / 36、端到端成功率 80.56%，CrewScope 自修改闭环与质量门禁通过。M4 全量门禁为 Maven 1517 / 1517、Vitest 237 / 237、Playwright/视觉/Axe 126 / 126。

M5 的 48 个任务和 [M5-Q04 Release Gate](../testing/M5-Q04-Release-Gate.md) 已全部关闭。当前已交付模型/Agent 配置与动态 AgentScope Model、个人/团队/Specialist Factory、Reviewer 证据和持久化闭环，以及 GitHub App/OAuth 身份验证、Repository Catalog/Preflight、受管 Mirror、AskPass/Lease Push、Draft PR 查询幂等、Webhook 去重、Action Worker、UNKNOWN/过期 Lease Fenced 对账、人工队列与终结、Fencing/Receipt 原子事务、V26 Claim 恢复和条件 Spring 装配。前端已闭合 Agent 与模型管理、Task 委托和配置预检、Review Workbench、GitHub Delivery Workbench，并完成全状态、响应式、键盘焦点、ARIA、Histoire、双视口视觉、Axe 与敏感字段 CI 门禁。最终门禁为 Maven 1862 / 1862、Vitest 311 / 311、Playwright/视觉/Axe 150 / 150；M5-Q01 固定攻击 84 / 84 被阻断，M5-Q02 固定故障 48 / 48 收敛，M5-Q03 Reviewer 质量门禁通过。

M6 的 50 个任务已全部完成，覆盖 Activity、Inbox、Audit、影子投影重建、三流 Cursor、固定模板 Lark 通知、只读 Team Observer、OTel/Prometheus、部署、备份恢复、故障、负载和 MVP Release Gate。[M6-Q04 MVP Release Gate](../testing/M6-Q04-MVP-Release-Gate.md) 已关闭，CrewScope Team Beta MVP Release 决定为 `PASS`。

M7 已拆分为 39 个任务：4 个 Spike、8 个领域/迁移任务、8 个基础设施任务、7 个应用/API 任务、8 个前端任务和 4 个质量任务。范围包括单 Organization 自托管本地账号、可配置开放注册、Spring Session Redis、正式登录/注册页、首次 Team Onboarding、默认 Personal Agent、一次性邀请链接、Operator/监控凭证分离、认证固定攻击集和 V30→V32 升级门禁。M7-S01 至 M7-S04、M7-D01 至 M7-D08、M7-I01 至 M7-I08、M7-A01 至 M7-A07 已完成；当前已交付注册、登录、Session 投影、当前账号管理、Onboarding、邀请、安全路由，以及闭合 DTO、错误、幂等、强版本、Audit 与 Spring/Jackson 装配合同。下一任务为 `M7-F01`。
