# M1：Team、WorkItem 与责任基础执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M1<br>
> 前置条件：M0 Release Gate 通过<br>
> 目标周期：2 周<br>
> 目标结果：成员可以创建 Team、WorkProject、WorkItem，并建立 Owner、Executor、Gate Reviewer 责任链<br>
> 当前进度：M1 全部完成，下一里程碑 `M2` Conversation 与 Personal Agent（2026-08-08）

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
| `M1-A01` | FEATURE | D02,D03,D08 | application/server | Team 创建、成员加入、Workspace 初始化和查询 API；识别 V5 遗留 Team 的待初始化状态并提供 Owner/默认 Workspace 补全入口 | [Team 应用用例与 API](../testing/M1-A01-Team应用与API.md)，覆盖幂等创建、Team Scope 权限、重复与并发成员、停用成员、7 条 HTTP 路由及遗留 Team 查询与并发补全 |
| `M1-A02` | TASK | A01 | application/infrastructure/server | Bootstrap 与基础 OIDC Subject 原子映射到 USER Principal；TeamMember 由 Team 创建和受控成员加入流程绑定 | [Bootstrap/OIDC 身份映射](../testing/M1-A02-Bootstrap与OIDC身份映射.md)，覆盖新用户、已有用户、并发 Subject、映射冲突、禁用账户、认证模式和入组边界 |
| `M1-A03` | FEATURE | D04,D08 | application/server | WorkProject 创建、列表、详情和项目 Key API | [WorkProject 应用与 API](../testing/M1-A03-WorkProject应用与API.md)，覆盖幂等创建、并发唯一 Key、Cursor、Scope 和权限 |
| `M1-A04` | FEATURE | A03,D04,D08 | application/server | WorkItem 与创建者初始 Owner 原子创建、状态迁移和乐观并发 Command API | [WorkItem 创建与状态迁移 API](../testing/M1-A04-WorkItem创建与状态迁移API.md)，覆盖完整字段、Owner 不变式、幂等、强 ETag、状态机、Scope 权限以及 Key 和版本并发 |
| `M1-A05` | FEATURE | A04 | application/server | WorkItem 列表、详情、评论和 ResourceLink Query/API | [WorkItem 查询与协作 API](../testing/M1-A05-WorkItem查询与协作API.md)，覆盖 Cursor、完整快照、Membership、Scope 权限、评论与 ResourceLink 幂等、事件原子性和安全 URL |
| `M1-A06` | FEATURE | D05,D06,D08 | application/server | Owner、Executor、Reviewer 分配、释放和查询 API | [责任分配与查询 API](../testing/M1-A06-责任分配与查询API.md)，覆盖服务端主体解析、权限、Owner ABA 防护、Reviewer 资格、幂等事件、释放版本和 PostgreSQL 并发串行化 |
| `M1-A07` | TASK | A05,A06 | application/infrastructure/server | WorkItem 时间线查询，M1 读取 DomainEvent/Audit 基线并返回统一 Cursor | [WorkItem 时间线查询 API](../testing/M1-A07-WorkItem时间线查询API.md)，覆盖事件白名单、Scope 可见性、DomainEvent/Audit 去重、稳定排序、专用 Cursor 和断点续传 |

所有 Command 返回 `commandId/domainEventId/committedVersion/correlationId`。客户端提供的 Principal、TeamRole 和责任身份只用于定位请求，授权事实由服务端解析。

M1 及后续业务的 Application Service 保持纯 Java，由 `crewscope-server/config/application` 中对应的 `<Business>ApplicationConfiguration` 通过 `@Bean` 装配。Controller 使用 `@RestController` 和单构造器注入，基础设施 Adapter 使用 `@Repository` 和边界事务注解。新增 Bean 同步加入 Spring Context 装配测试，不建立跨业务集中配置类。

### 4.1 M1-A01 接口与权限契约

M1-A01 提供 Team 基础纵向闭环：

```text
POST /api/v1/organizations/{organizationId}/teams
GET  /api/v1/organizations/{organizationId}/teams
GET  /api/v1/organizations/{organizationId}/teams/{teamId}
POST /api/v1/organizations/{organizationId}/teams/{teamId}/members
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/members
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/workspaces/default
POST /api/v1/organizations/{organizationId}/teams/{teamId}/initialization
```

创建 Team、加入成员和补全遗留 Team 是 Command，必须携带 `Idempotency-Key`，在一个事务内提交业务事实、DomainEvent、Outbox 和 CommandReceipt。Team 创建自动提交 Owner、默认 Team Workspace、五个内置 TeamRole、TEAM_OWNER Grant 和 Owner 默认 Personal Agent。成员加入自动提交 ACTIVE Membership、MEMBER Grant 和成员默认 Personal Agent。

查询接口以当前认证 USER Principal 作为授权主体。Team 列表只返回该用户具有 ACTIVE Membership 的 Team；Team 详情、成员和默认 Workspace 要求 ACTIVE Membership。加入成员要求调用者通过 Team Scope Grant 具有 `MEMBER_MANAGE`，WorkProject Scope Grant 不能提升为 Team 管理权限。目标必须是同 Organization 的 ACTIVE USER Principal，已有任何 Membership 的用户不能重复加入。成员加入以 Team 行锁串行化，不同幂等键并发加入同一用户时只有一个事务成功。

V5 遗留 Team 以 `INITIALIZATION_REQUIRED` 返回，不经过完整 Team Mapper。只有服务端认证信息授予的平台管理员可以读取和补全该状态；补全命令显式选择同 Organization 的 ACTIVE USER 作为 Owner，并原子创建 Owner Membership、默认 Workspace、内置角色、Owner Grant 和默认 Personal Agent。普通 Team API 不把缺少 Owner/Workspace 的数据当成已初始化 Team 使用。

M1-A01 的服务端身份解析只消费认证后 Principal，不接受请求体中的调用者 Principal。M1-A02 已完成 Bootstrap 与 OIDC Subject 到 USER Principal 的创建和绑定。

### 4.2 M1-A02 登录身份与成员边界

服务端从 Spring Security `Authentication` 提取认证事实：

```text
Bootstrap -> provider=bootstrap, subject=username
OIDC      -> provider=oidc/{registrationId}, subject=sub
```

OIDC 的 `name/preferred_username/email` 只用于显示名，`sub` 用作稳定身份键。`Organization + Provider + Subject` 由 PostgreSQL 唯一索引裁决。首次认证使用 `INSERT ... ON CONFLICT DO NOTHING` 原子创建 ACTIVE、Organization Scope、ORGANIZATION 可见的 USER Principal；已有认证主体复用同一 Principal；不同 OIDC Registration 的相同 Subject 保持独立。

部署配置使用 `CREWSCOPE_OIDC_ORGANIZATION_ID` 将当前 OIDC ClientRegistration 绑定到唯一 Organization。请求路径中的 Organization 必须与该绑定一致，URL 不能创建跨租户 Principal。

首次创建 Principal 时在同一事务提交 `USER_IDENTITY_MAPPED` DomainEvent 与 Outbox。事件 Payload 只保存 Provider，不保存原始 Subject。映射到其他 Principal 类型或 Team Scope 时返回 `identity_mapping_conflict`；`SUSPENDED/DISABLED/ARCHIVED` Principal 拒绝访问；Organization 不存在时返回稳定 Not Found。

TeamMember 由 Team 创建、遗留 Team 补全和管理员成员加入命令创建。认证成功只建立 Principal，不产生任意 Team Membership。创建 Team 后 A01 自动创建 Owner Membership；管理员添加用户后 A01 创建 MEMBER Membership。Team 查询继续校验 ACTIVE Membership 和 Team Scope Role Grant。

安全模式由 `CREWSCOPE_SECURITY_MODE` 显式选择：

```text
bootstrap  HTTP Basic + 服务端 ROLE_ADMIN + API Profile 关闭 CSRF
oidc       OAuth2 Login + 浏览器 Session + Cookie CSRF Token
```

未知模式、缺少 Organization 绑定和缺少 ClientRegistration 的 OIDC 配置使应用启动失败。管理员权限只来自服务端 Authentication Authority。

### 4.3 M1-A03 WorkProject 契约

WorkProject 使用 Team 子资源 API：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects?after={cursor}&limit={limit}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/keys/{projectKey}
```

创建命令要求当前 USER 具有 ACTIVE Membership，并通过当前有效的 Team Scope Grant 获得 `WORK_PROJECT_MANAGE`。WorkProject Scope Grant 不提供 Team 下新建项目的权限。项目固定创建在 Team 默认 Workspace，提交 `WORK_PROJECT_CREATED`、Outbox 和 CommandReceipt。

列表、详情和 Key 可用性查询要求 ACTIVE Membership。详情发现 Team Scope 不匹配时按不可见资源返回 Not Found。列表使用 `updatedAt + WorkProjectId` 降序 Keyset Cursor，默认每页 50 条、最大 100 条。Key 可用性接口只提供表单反馈；创建事务锁定 Team 后再次检查 Key，数据库 `(team_id, project_key)` 唯一约束作为最终完整性保证。

### 4.4 M1-A04 WorkItem Command 契约

WorkItem 使用完整 Team 和 WorkProject Scope 的子资源 API：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions
```

创建命令要求 ACTIVE Membership，并由 Team Scope 或目标 WorkProject Scope Grant 提供 `WORK_CREATE`。迁移命令要求 `WORK_PARTICIPATE`。其他项目的 Grant 不提供权限，WorkItem 必须同时匹配 URL 中的 Organization、Team、Workspace 和 WorkProject Scope。外部 Provider 投影由来源系统管理状态，本地迁移接口只处理 Native WorkItem。

两个命令都要求 `Idempotency-Key`，并在一个事务内提交业务事实、DomainEvent、Outbox 和 CommandReceipt。创建锁定 WorkProject 后裁决项目内 WorkItem Key，并把当前创建者写为 ACTIVE 初始 Owner；`WORK_ITEM_CREATED` Payload 保存 Owner Assignment 与 Principal 坐标，数据库 `(project_id, item_key)` 和 active Owner 唯一约束兜底。迁移要求 `If-Match: "<version>"` 强 ETag；缺失返回 `428 precondition_required`，弱 ETag、通配符和多值返回 `400 invalid_if_match`，版本竞争返回 `409 optimistic_lock_conflict` 和 `currentVersion`。

### 4.5 M1-A05 WorkItem 查询与协作契约

WorkItem 查询和不可变协作子资源使用同一完整 Scope 路径：

```text
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links
```

所有查询要求当前 USER 在目标 Team 具有 ACTIVE Membership。列表固定在 URL 指定的 WorkProject，支持 `status`、`after` 和 `limit`，以 `updatedAt + WorkItemId` 降序 Keyset Cursor 分页。详情在同一只读事务快照中返回 WorkItem、Comment 和 ResourceLink，并使用 WorkItem Version 返回强 ETag。URL 和持久化对象的 Organization、Team、Workspace、WorkProject Scope 必须全部一致；不匹配的资源按 Not Found 处理。

添加评论和 ResourceLink 要求 Team Scope 或目标 WorkProject Scope 的当前有效 Grant 提供 `WORK_PARTICIPATE`，其他项目的 Grant 不提供权限。两个命令都要求 `Idempotency-Key`，在同一事务提交协作事实、DomainEvent、Outbox 和 CommandReceipt；同键同内容返回原 Receipt，同键不同内容返回幂等冲突。Native 与外部来源 WorkItem 均可追加 CrewScope 本地协作信息，`ARCHIVED` WorkItem 拒绝新增协作事实。

Comment 是不可变 Markdown 文本。ResourceLink 是不可变 WorkGraph 关系；`EXTERNAL_URL` 只接受无嵌入凭证、无控制字符、具有有效 Host 的绝对 HTTP/HTTPS URL。查询响应使用 `Cache-Control: no-store`，详情 ETag 只表达 WorkItem 聚合版本，不作为不可变子资源的并发控制条件。

### 4.6 M1-A06 责任分配与查询契约

ResponsibilityAssignment 使用 WorkItem 完整 Scope 下的管理 API：

```text
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/owner
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/executors
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/gate-reviewers
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/advisory-reviewers
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/{assignmentId}/releases
```

查询要求目标 Team ACTIVE Membership，返回当前 ACTIVE 责任链及服务端解析的 Principal 显示名。所有写命令要求 Team Scope 或目标 WorkProject Scope 的有效 `RESPONSIBILITY_MANAGE` Grant。请求只提交目标 `actorPrincipalId`，服务端读取 Principal 和 TeamMembership；USER 必须是目标 Team ACTIVE Member，Agent 必须属于目标 Team Scope。归档 WorkItem 不接受新责任。

Owner 只能是 USER。Native WorkItem 创建时由服务端把创建者设为初始 Owner；Owner 管理接口正常路径同时提交当前 `expectedAssignmentId` 和 `expectedVersion`，服务端锁定 WorkItem 并比较 Assignment 身份与版本，防止版本相同但 Assignment 已更换的 ABA 覆盖。无期望 Assignment 的首次设置能力只用于兼容尚未补全责任的导入或遗留数据。Owner 不提供单独释放接口，替换在一个事务内释放旧 Owner 并创建新 Owner，任何新提交的 Native WorkItem 都保持唯一 ACTIVE Owner。

Executor 支持 ACTIVE USER Member 和 Team Scope Agent，同一 Principal/Role 不可重复 Active 分配。Gate Reviewer 必须是 ACTIVE USER Member，并由服务端 `GateReviewerPolicyProvider` 解析 ReviewerEligibilityPolicy；默认使用职责严格分离，Owner 或 Executor 不能成为同一 WorkItem 的 Gate Reviewer。单人团队 PolicyPack 降级通过同一服务端 Provider 注入，客户端不能声明 PolicyPack 或绕过理由。SPECIALIST_AGENT 可以分配为 Advisory Reviewer，其结果不具有 Gate 效力。

Executor、Gate Reviewer 和 Advisory Reviewer 使用 Assignment `If-Match: "<version>"` 释放；Owner 必须走原子替换。所有写命令要求 `Idempotency-Key`，并在一个事务内提交 Assignment、DomainEvent、Outbox 和 CommandReceipt。同键同请求返回原 Receipt，同键不同请求返回幂等冲突。责任链查询使用 `Cache-Control: no-store`。

### 4.7 M1-A07 WorkItem 时间线契约

WorkItem 时间线使用完整 Scope 下的只读 API：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/timeline?after={cursor}&limit={limit}
```

查询要求目标 Team ACTIVE Membership，并在读取事件前校验 URL 与 WorkProject/WorkItem 的 Organization、Team、Workspace 和 Project Scope。M1 只公开 WorkItem 创建/状态、Comment、ResourceLink 和 Responsibility 的已评审业务事件；未知事件和安全审计不会因 Payload 关联 WorkItem 而自动暴露。

M1 Repository Port 统一读取 DomainEvent 和 AuditEvent。DomainEvent 与其 Audit 投影共享 DomainEvent ID 作为 `canonicalEventId`，查询在分页前去重并优先返回 DomainEvent 事实。M6 切换到物化 Activity 读模型时保持 Application Service 和 HTTP 契约稳定。

时间线以 `occurredAt + canonicalEventId` 倒序 Keyset 分页，默认 50、最大 100。Cursor 是带时间线类型与版本的不透明 Base64URL 令牌，不能与 WorkItem 列表 Cursor 混用；`after` 从上一页最后一项继续读取更早事件。同一微秒内使用 PostgreSQL UUID 顺序裁决，断点续传不重复、不遗漏 Cursor 之后的历史事件。响应返回 Actor、Aggregate、Correlation/Causation、Outcome 和结构化 Payload，并使用 `Cache-Control: no-store`。

## 5. 前端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M1-F01` | FEATURE | A01,A03 | web | 实现 ScopeSwitcher、Today/Work 导航、Team/WorkProject 切换、成员管理视图和权限守卫 | [Scope 与团队管理前端](../testing/M1-F01-Scope与团队管理前端.md)，23 个 Vitest 与 8 个 Playwright 场景覆盖 API Gateway、Store、URL 恢复、范围切换、异步竞态、成员视图、权限跳转、OIDC CSRF 和桌面/窄屏视觉基线 |
| `M1-F02` | FEATURE | A04,A05,F01 | web | 实现 WorkItem 创建、筛选、List/Board 视图和共享 WorkItemCard；视图状态进入 URL | [WorkItem 集合前端](../testing/M1-F02-WorkItem集合前端.md)，30 个 Vitest 与桌面/窄屏 14 次 Playwright 执行覆盖真实 API Gateway、创建刷新、Cursor 去重、视图切换、筛选恢复、看板分组和跨 Scope 竞态 |
| `M1-F03` | FEATURE | A04,A05,F02 | web | 实现 WorkItem 详情模板、详情抽屉、状态迁移、评论、ResourceLink 和 Conversation 占位跳转 | [WorkItem 详情与协作前端](../testing/M1-F03-WorkItem详情与协作前端.md)，40 个 Vitest 与桌面/窄屏 20 次 Playwright 执行覆盖强版本前置条件、冲突刷新、评论、ResourceLink、键盘/Focus、深链接和 Conversation 跳转 |
| `M1-F04` | FEATURE | A06,A07,F03 | web | 实现 ResponsibilityChain、Owner/Executor/Reviewer 分配、资格提示、时间线及“与 Personal Agent 讨论/交给 Agent 处理”占位入口 | [责任链与时间线前端](../testing/M1-F04-责任链与时间线前端.md)，55 个 Vitest 与桌面/窄屏 24 次 Playwright 执行覆盖 Owner ABA 期望、Assignment ETag、职责分离提示、冲突刷新、Timeline Cursor 去重和 Agent 占位边界 |

## 6. 质量与验收

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M1-Q01` | HARDENING | 全部 | 全模块 | 建立 M1 纵向 E2E、权限矩阵、并发约束、迁移回归、视觉回归、可访问性和竞品非雷同检查 | [M1 Release Gate](../testing/M1-Q01-Release-Gate.md) 十二项验收全部通过；真实 PostgreSQL 纵向链路、405 个后端测试、55 个前端测试、32 次浏览器执行、Axe WCAG 扫描、6 份 M1 视觉基线及[竞品差异审查](../reviews/M1-Q01-前端竞品差异与可访问性审查.md)归档 |

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
