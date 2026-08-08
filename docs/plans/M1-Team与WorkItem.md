# M1：Team、WorkItem 与责任基础执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M1<br>
> 前置条件：M0 Release Gate 通过<br>
> 目标周期：2 周<br>
> 目标结果：成员可以创建 Team、WorkProject、WorkItem，并建立 Owner、Executor、Gate Reviewer 责任链<br>
> 当前进度：`M1-D08` 已完成（2026-08-08），下一项为 `M1-A01`

## 1. 出口结果

M1 完成后具备：

- USER、PERSONAL_AGENT、TEAM_AGENT、SPECIALIST_AGENT、SERVICE Principal；
- Team、TeamMember、TeamRole 和 Team Workspace；
- 每位成员唯一默认 Personal Agent；
- WorkProject、Native WorkItem、评论和资源链接；
- 唯一 Owner、Executor、Gate Reviewer 和 ReviewerEligibilityPolicy；
- Bootstrap/OIDC Principal 映射；
- Team、WorkItem、责任、看板和时间线 Web 闭环。

## 2. 依赖顺序

```text
M1-D01 -> M1-D02 -> M1-D03
M1-D01 -> M1-D04 -> M1-D05
M1-D06 依赖 D02、D03、D05
M1-D07 -> M1-D08
M1-A01 -> M1-A02
M1-A03 -> M1-A04 -> M1-A05
M1-A06 依赖 D05、D06、D08
M1-A05 + M1-A06 -> M1-A07
API 稳定 -> M1-F01 -> M1-F02 -> M1-F03 -> M1-F04
全部能力 -> M1-Q01
```

领域与数据优先完成。API 可以基于 Application Port 并行开发，前端使用稳定 Mock Schema 起步。

## 3. 领域与数据

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M1-D01` | TASK | M0 | domain | 实现 Principal、TeamMember、TeamRole、MemberRole、状态和值对象 | [身份与团队角色领域模型](../testing/M1-D01-身份与团队角色领域模型.md)与 35 个新增单元测试覆盖五类主体、成员生命周期、内置角色、Role Scope、有效期、撤销和终态时间一致性边界 |
| `M1-D02` | TASK | D01 | domain/application | 实现 Team 创建、Team Owner、默认 Team Workspace 和成员加入规则 | [Team 创建与初始化领域模型](../testing/M1-D02-Team创建与初始化领域模型.md)与 16 个新增测试覆盖完整初始化事务、唯一 Owner、Workspace Scope 和成员加入边界 |
| `M1-D03` | TASK | D01,D02 | domain/application | 实现默认 Personal Agent Principal、AgentProfile 和幂等创建策略 | [默认 Personal Agent 领域模型](../testing/M1-D03-默认Personal-Agent领域模型.md)与 11 个新增测试覆盖稳定身份、成员和 Workspace 边界、生命周期、事务接入及 12 路并发初始化 |
| `M1-D04` | TASK | D01 | domain/application | 扩展 WorkProject、WorkItem、Comment、ResourceLink 与状态机 | [WorkProject 与 WorkItem 领域模型](../testing/M1-D04-WorkProject与WorkItem领域模型.md)与 22 个新增测试覆盖 Scope、Key、字段、来源、权限边界、版本、归档、评论和资源链接 |
| `M1-D05` | TASK | D04 | domain/application | 实现 ResponsibilityAssignment、唯一 active Owner 与 Executor/Reviewer 分配 | [责任分配领域模型](../testing/M1-D05-责任分配领域模型.md)与 16 个新增测试覆盖责任创建、释放、审计、主体资格、唯一 Owner、版本冲突和 ABA 防护 |
| `M1-D06` | TASK | D02,D03,D05 | domain/application | 实现 ReviewerEligibilityPolicy，默认 Gate Reviewer 与 Owner/Executor 分离，支持单人团队 PolicyPack 降级 | [ReviewerEligibilityPolicy](../testing/M1-D06-ReviewerEligibilityPolicy.md)与 19 个新增测试覆盖职责分离、停用成员、Advisory Agent、PolicyPack 降级证据、双向绕过防护和责任链锁 |
| `M1-D07` | TASK | D03,D05,D06 | infrastructure | 新增 `V6__team_work_and_responsibility.sql`、Team Owner/默认 Workspace 延后外键、部分唯一索引、其他外键和乐观锁字段 | [Team 与责任数据迁移](../testing/M1-D07-Team与责任数据迁移.md)与 4 个新增 PostgreSQL 集成测试覆盖空库、V5→V6、Scope 外键和并发唯一约束 |
| `M1-D08` | TASK | D07 | infrastructure | 实现 Team、Member、AgentProfile、WorkProject、WorkItem、Comment、ResourceLink 与 Assignment Repository Entity/Mapper，使用 WorkItem 行锁实现责任链串行化 Port | [M1 持久化适配](../testing/M1-D08-M1持久化适配.md)，覆盖 Repository CRUD、分页、版本、映射和并发责任链集成测试 |

数据库约束至少覆盖：

- Team 中唯一成员身份；
- 成员唯一 active 默认 Personal Agent；Principal ID 与 AgentProfile ID 由稳定 TeamMember ID 分别派生，数据库仍以 active 默认 Profile 唯一约束作为最终并发裁决；
- WorkProject 中唯一 WorkItem Key；
- WorkItem 唯一 active Owner Assignment；
- Assignment 的 Subject、Role、Actor 和状态查询索引。

Gate Reviewer 的 active TeamMember、可见性和职责分离由应用规则校验，数据库保证引用完整性和并发唯一性。`DefaultPersonalAgentRepository.initializeIfAbsent` 必须在同一事务内原子写入 Principal 与 AgentProfile，发生并发竞争时返回已提交的完整 Agent 对，不允许留下孤立 Principal 或 Profile。

V6 升级保留无法推断责任人的 V5 Team，`owner_member_id/default_workspace_id` 允许成对为空。新 Team 在同一初始化事务中写入两项引用，延后外键在事务提交时校验 Owner TeamMember 和默认 Team Workspace 的完整 Scope。WorkItem 责任链以 WorkItem 行作为 D08 的串行化锁点，不创建独立锁表。

## 4. 应用用例与 API

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M1-A01` | FEATURE | D02,D03,D08 | application/server | Team 创建、成员加入、Workspace 初始化和查询 API；识别 V5 遗留 Team 的待初始化状态并提供 Owner/默认 Workspace 补全入口 | 幂等创建、越权、重复成员、停用成员、遗留 Team 查询与补全测试 |
| `M1-A02` | TASK | A01 | server | Bootstrap 用户与基础 OIDC Subject 映射到 USER Principal、TeamMember | 新用户、已有用户、Subject 冲突和禁用账户测试 |
| `M1-A03` | FEATURE | D04,D08 | application/server | WorkProject 创建、列表、详情和项目 Key API | 幂等创建、唯一 Key、Cursor 和权限测试 |
| `M1-A04` | FEATURE | A03,D04,D08 | application/server | WorkItem 创建、状态迁移和乐观并发 Command API | If-Match、Idempotency-Key、状态机和权限测试 |
| `M1-A05` | FEATURE | A04 | application/server | WorkItem 列表、详情、评论和 ResourceLink Query/API | Cursor、可见性、评论幂等和引用校验测试 |
| `M1-A06` | FEATURE | D05,D06,D08 | application/server | Owner、Executor、Reviewer 分配、释放和查询 API | 唯一 Owner、Reviewer 资格、并发冲突和 Audit 测试 |
| `M1-A07` | TASK | A05,A06 | application/server | WorkItem 时间线查询，M1 读取 DomainEvent/Audit 基线并返回统一 Cursor | 事件排序、可见性、重复事件和断点续传测试 |

所有 Command 返回 `commandId/domainEventId/committedVersion/correlationId`。客户端提供的 Principal、TeamRole 和责任身份只用于定位请求，授权事实由服务端解析。

## 5. 前端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M1-F01` | FEATURE | A01,A03 | web | 实现 ScopeSwitcher、Today/Work 导航、Team/WorkProject 切换、成员管理视图和权限守卫 | Store/组件测试、URL 状态恢复、响应式与未授权跳转测试 |
| `M1-F02` | FEATURE | A04,A05,F01 | web | 实现 WorkItem 创建、筛选、List/Board 视图和共享 WorkItemCard；视图状态进入 URL | Vitest 与 Playwright 覆盖创建、Cursor、视图切换、筛选恢复和看板分组 |
| `M1-F03` | FEATURE | A04,A05,F02 | web | 实现 WorkItem 详情模板、详情抽屉、状态迁移、评论、ResourceLink 和 Conversation 占位跳转 | 版本冲突、键盘/Focus、评论、深链接和详情刷新测试 |
| `M1-F04` | FEATURE | A06,A07,F03 | web | 实现 ResponsibilityChain、Owner/Executor/Reviewer 分配、资格提示、时间线及“与 Personal Agent 讨论/交给 Agent 处理”占位入口 | 责任分配、职责分离提示、冲突刷新、Timeline Cursor 和组件状态测试 |

## 6. 质量与验收

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M1-Q01` | HARDENING | 全部 | 全模块 | 建立 M1 纵向 E2E、权限矩阵、并发约束、迁移回归、视觉回归、可访问性和竞品非雷同检查 | 干净环境创建 Team 到责任完整 WorkItem 的流程通过，关键截图和设计差异记录归档 |

M1-Q01 至少覆盖：

1. 用户创建 Team 后自动成为 Team Owner；
2. Team Workspace 与默认 Personal Agent 原子创建；
3. 并发初始化不会产生重复默认 Personal Agent；
4. WorkItem 始终存在一个有效 Owner；
5. 默认策略阻止 Owner/Executor 成为同一 WorkItem 的 Gate Reviewer；
6. 单人团队显式降级策略可以选择本人 Gate Review，并产生 AuditEvent；
7. 未授权成员无法读取 Team、WorkItem、责任和时间线；
8. 两人并发修改 WorkItem 或 Owner 时返回稳定版本冲突；
9. 页面刷新和 Cursor 续传后责任与时间线一致；
10. 桌面与窄屏下 List/Board、详情抽屉和 ResponsibilityChain 可用，键盘与 Focus 行为正确；
11. 关键页面截图与 `vibe-kanban`、`multica` 参考截图在导航、布局、Token、组件和任务流上具有明确差异；
12. `./mvnw clean verify`、`pnpm build` 和 Playwright M1 用例通过。

## 7. M1 非目标

- Personal Agent 真实对话；
- TaskIntent 与 Conversation；
- TaskExecution、Lease 和 Task Token；
- Coding Agent、Worktree 和 Sandbox；
- GitHub 与飞书真实 Provider；
- REQUEST_HELP、Contribution、Handoff 和 Takeover。
