# M5-A02 Agent 模板与实例管理 API

> 状态：已完成
> 日期：2026-08-24

## 1. 交付范围

M5-A02 在版本化 AgentTemplate、AgentProfile、AgentConfigurationVersion 和动态 Agent Factory 基线上交付 Team-scoped Agent 管理边界：

- 合并 Organization 与当前 Team 的最新 ACTIVE AgentTemplate Catalog；
- 创建 USER-owned 与 TEAM-owned Agent Principal/Profile；
- Agent 列表、详情、启用、停用、归档；
- AgentConfigurationVersion 只读历史；
- Spring Boot 显式组合根、强 ETag、`Idempotency-Key`、`If-Match` 和 CommandReceipt。

Catalog 按目标 Ownership 过滤模板策略，并排除由 Team Membership 初始化负责的 `PERSONAL_ASSISTANT`。同一成员可以从同一模板创建多个独立 Specialist；默认 Personal Agent 保持每 TeamMember 唯一，不能由该 API 重复创建或改变生命周期。

## 2. Ownership 与授权

| Ownership | 创建与管理权限 | Principal 可见性 | Workspace |
|---|---|---|---|
| `USER` | 当前 ACTIVE TeamMember 创建并管理自己的 Agent | `PRIVATE` | 当前 Team 默认 Workspace |
| `TEAM` | 有效 Team-wide `AGENT_MANAGE`；平台管理员可代管 | `TEAM` | 当前 Team 默认 Workspace |
| `ORGANIZATION` | Team 路由不创建；后续由 Organization Workspace 路由承载 | `ORGANIZATION` | Organization Workspace |

普通 ACTIVE TeamMember 可以发现 TEAM-owned Agent，只能发现自己拥有的 USER-owned Agent。平台管理员可读取和管理当前 Team 的完整 Agent 集合。读取、首次命令执行与 Completed Receipt 回放都重新验证当前身份、Membership 和授权；角色撤销后旧幂等键不能恢复原权限。

服务端根据模板 RuntimeRole 固化 PrincipalType，根据 Ownership 固化 Visibility、Owner、Team Scope 和 Workspace。客户端只提交模板发布类型、Template Key/Version、OwnershipType 和显示名，不能注入 Principal ID、PrincipalType、Owner、Workspace 或内部 Scope。

## 3. API

```text
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/agent-templates

POST /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations
POST /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/activate
POST /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/disable
POST /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/archive
```
全部读取使用 `Cache-Control: no-store`，详情返回 AgentProfile 乐观版本的强 ETag。创建与生命周期命令要求 `Idempotency-Key`，生命周期命令同时要求强 `If-Match`。

Template Catalog 只公开产品能力、Ownership/ExecutionScope、可配置槽、Hash 和生命周期，不公开 System Prompt、Tool 明细或 Structured Output Schema。配置历史只公开连续 Revision、PreviousRevision、精确 TemplateVersion/ContentHash、非秘密模型选择坐标、ConfigurationHash 和创建审计，不公开 Prompt、Tool Payload、Credential、Endpoint、Provider Definition Hash 或原始策略载荷。

## 4. 原子性与历史稳定性

`JpaAgentInstanceRepositoryAdapter` 在同一事务创建 Agent Principal 与 AgentProfile。生命周期更新使用 Principal 和 AgentProfile 各自的乐观版本谓词，任一更新缺失或冲突都会回滚另一侧变更，禁止出现可行动 Principal 与禁用 Profile 不一致的状态。

每次创建或生命周期变更在同一事务提交业务事实、`AgentProfileChanged` DomainEvent、Outbox 和 CommandReceipt。Receipt 引用精确 DomainEvent 与提交后的 AgentProfile Version。

Template Catalog 每个 Publisher + Template Key 只返回当前最新版本；最新版本停用后不回退旧 ACTIVE 版本。既有 AgentProfile 和 AgentConfigurationVersion 继续引用原精确 TemplateVersion 与 ContentHash。

## 5. 验证

专项测试共 `23 / 23` 通过：

- application 9 项：默认 Personal、多个 Specialist、模板策略、USER/TEAM 权限、跨 Owner、角色撤销后 Receipt 回放和双生命周期同步；
- PostgreSQL 9 项：Principal/Profile 原子创建、双表生命周期、版本冲突回滚、最新模板不回退和既有 M5-I01 图回归；
- server 5 项：路由、强 ETag、命令头、Receipt、Template/Agent/Configuration DTO 白名单与 `no-store`。

验证命令：

```bash
./mvnw -q -pl crewscope-infrastructure,crewscope-server -am \
  -Dtest=AgentManagementApplicationServiceM5A02Test,AgentManagementControllerM5A02Test,M5I01ModelAgentPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
