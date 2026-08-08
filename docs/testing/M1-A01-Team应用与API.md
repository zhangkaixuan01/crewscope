# M1-A01：Team 应用用例与 API

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

交付 Team 创建、列表、详情、成员加入、成员列表、默认 Workspace 查询和 V5 遗留 Team 补全闭环。所有写操作复用 M0 的幂等命令、DomainEvent、Outbox、CommandReceipt 和统一错误协议；Request Hash 覆盖可信 Actor、Scope、Causation 与规范业务字段。

## 用例边界

### Team 创建

当前认证主体必须是目标 Organization 的 ACTIVE USER Principal。首次执行原子提交：

```text
Team
Owner TeamMember
Default Team Workspace
5 个内置 TeamRole
TEAM_OWNER MemberRole
Owner 默认 Personal Agent Principal + AgentProfile
TEAM_CREATED DomainEvent + Outbox
CommandReceipt
```

相同 `Idempotency-Key` 和规范化请求返回原 Receipt。相同 Key 对应不同 Team 名称时返回 `idempotency_conflict`。

### 成员加入

调用者必须是 ACTIVE TeamMember，并通过当前有效的 Team Scope MemberRole 获得 `MEMBER_MANAGE`。WorkProject Scope Grant 不能提升为 Team 管理权限。目标 Principal 由服务端按 Organization 与 ID 查询，必须是 ACTIVE USER。加入命令原子提交 ACTIVE Membership、内置 MEMBER Grant、默认 Personal Agent、`TEAM_MEMBER_JOINED` 事件和 Receipt。

同一 Team 内已有 ACTIVE、INVITED、SUSPENDED、LEFT 或 REMOVED Membership 的用户均不能通过加入接口重复创建 Membership。成员加入以 Team 行锁作为串行化点，不同幂等键并发加入同一用户时只提交一套 Membership、Grant、Personal Agent 和事件，另一个请求返回稳定领域拒绝。恢复与重新邀请在后续独立生命周期命令中实现。

### 查询

Team 列表只返回当前 USER 拥有 ACTIVE Membership 的已初始化 Team。Team 详情、成员列表和默认 Workspace 查询要求当前用户是该 Team 的 ACTIVE Member。停用 Principal 或停用 Membership 不具有读取权限。

### 遗留 Team 补全

V6 合法保留 `owner_member_id/default_workspace_id` 成对为空的 V5 Team。查询 Port 直接读取初始化状态，避免完整 Team Mapper 抛出持久化异常。平台管理员可查询 `INITIALIZATION_REQUIRED` 状态并选择同 Organization 的 ACTIVE USER 作为 Owner。

补全命令锁定遗留 Team，在一个事务内写入 Owner、默认 Workspace、五个内置角色、Owner Grant、默认 Personal Agent、`TEAM_INITIALIZATION_COMPLETED` 事件和 Receipt。并发或重复补全只能产生一个初始化结果；普通成员和跨 Organization 主体不能执行补全。

## HTTP 契约

```text
POST /api/v1/organizations/{organizationId}/teams
GET  /api/v1/organizations/{organizationId}/teams
GET  /api/v1/organizations/{organizationId}/teams/{teamId}
POST /api/v1/organizations/{organizationId}/teams/{teamId}/members
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/members
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/workspaces/default
POST /api/v1/organizations/{organizationId}/teams/{teamId}/initialization
```

Command 首次执行和重放返回 `202 Accepted` 的统一 Receipt；重放增加 `Idempotency-Replayed: true`。查询返回 `Cache-Control: no-store`，单对象返回强 `ETag`。

## 身份边界

Controller 从认证上下文取得认证 Subject，再由服务端 Principal 解析 Port 得到可信 USER Principal 和平台管理员标记。`organizationId`、`teamId` 和目标 `userPrincipalId` 是资源定位值，应用层仍校验完整 Organization、Team、Principal、Membership 和 Role Scope。

M1-A02 负责创建 Bootstrap/OIDC Principal 映射。M1-A01 只定义并消费解析后的可信身份。

## 验证范围

- Team 创建首次执行、幂等重放和请求冲突；
- 跨 Organization 创建拒绝；
- Owner、Workspace、角色、Grant、默认 Personal Agent 与事件原子性；
- Owner 加入成员成功；
- 无 `MEMBER_MANAGE` 越权拒绝；
- WorkProject Scope Grant 不能执行 Team 成员管理；
- 重复 Membership 和停用目标 Principal 拒绝；
- 不同幂等键并发加入同一用户只产生一个成员结果；
- 停用调用者 Principal 或 Membership 查询拒绝；
- Team 列表、详情、成员和默认 Workspace Scope 隔离；
- 遗留 Team 状态查询、管理员补全、普通成员拒绝和并发补全；
- Web API 的状态码、Receipt、Replay Header、ETag 和安全错误信封。

## 验证结果

- Team 应用服务覆盖创建、重放、幂等冲突、成员权限、Scope 越权、重复成员、停用主体、成员查询和遗留补全；
- PostgreSQL 集成测试覆盖 Team/Workspace/Member/Role/Personal Agent 原子持久化、成员并发加入和遗留 Team 并发补全；
- 7 条公开 Team 路由均经过 WebFlux HTTP 契约测试；
- `./mvnw clean verify`、文档链接检查和 `git diff --check` 通过。
