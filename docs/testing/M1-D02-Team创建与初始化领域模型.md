# M1-D02：Team 创建与初始化领域模型

> 日期：2026-08-07<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立 Team 创建时必须同时成立的产品事实：一个有效创建者、一个 Team Owner、一个默认 Team Workspace、一份稳定 Membership、五个内置 TeamRole 和一条 TEAM_OWNER 授权。应用层在一个 REQUIRED 事务中提交完整初始化单元。

## Team

`Team` 保存：

```text
OrganizationId
TeamId
Name
OwnerMemberId
DefaultWorkspaceId
Status
Version
AuditMetadata
```

领域规则：

- 新 Team 为 ACTIVE，名称归一化并限制为 200 个字符；
- OwnerMemberId 和 DefaultWorkspaceId 是稳定引用，由初始化事务闭合外键图；
- 只有 ACTIVE Team 可以直接加入成员或创建邀请；
- 直接加入和邀请继续复用 M1-D01 的 ACTIVE USER、同 Organization 和邀请来源校验；
- Ownership 只能转移给本 Team 的 ACTIVE TeamMember；
- ARCHIVED 是终态，归档后不再接受成员与工作；
- 状态和 Ownership 变化推进 Version 与 AuditMetadata。

## Workspace

`Workspace` 区分：

```text
PERSONAL -> Organization + USER Owner
TEAM     -> Organization + Team + USER Owner
```

新 Workspace 必须具有同 Organization 的 ACTIVE USER Owner。默认 Team Workspace 复用 Team Name，WorkspaceId 与 Team.defaultWorkspaceId 精确一致。历史数据通过 `reconstitute` 保留 V1 允许为空的 Owner，新的创建入口不允许缺失 Owner。

Workspace 使用 `ACTIVE/ARCHIVED` 生命周期、乐观版本和 `AuditMetadata`。这里的 Owner 是当前管理责任引用，TeamRole 仍是授权事实，不能只依赖 Workspace Owner 获得访问权限。

## TeamInitialization

`TeamInitialization` 是 Team 创建事务的完整领域结果：

```text
Team
Default Team Workspace
Owner TeamMember
TEAM_OWNER / TEAM_ADMIN / TEAM_LEAD / MEMBER / AUDITOR
Owner MemberRole
```

初始化不变量：

- 创建者必须是同 Organization 的 ACTIVE USER Principal；
- Owner Membership 使用 BOOTSTRAP 来源并立即 ACTIVE；
- Team.ownerMemberId、Workspace Owner 和 Owner Membership 指向同一用户责任链；
- 五个内置角色必须定义齐全、各出现一次、ID 唯一、属于当前 Team 且为 ACTIVE；
- 普通 `MemberRole.grant` 禁止创建 TEAM_OWNER 授权；
- `MemberRole.grantOwner` 只接受 Team.ownerMemberId 指向的 ACTIVE Member；
- TEAM_OWNER 授权为 Team Scope、立即生效、无到期时间；
- 初始化返回值再次执行完整不变量校验，防止 Repository 返回破坏引用关系的结果。

## 应用事务

`TeamCreationService` 使用 `TransactionExecutor.required`。M1-D03 已把默认 Personal Agent 接入同一事务，当前按以下顺序调用应用 Port：

```text
TeamRepository
WorkspaceRepository
TeamMemberRepository
TeamRoleRepository
MemberRoleRepository
DefaultPersonalAgentRepository
```

Team 先写入，Owner Member 与默认 Workspace 在事务内闭合 Team 的延后引用，随后写入 Owner Grant，并原子初始化 Owner 的 Personal Agent Principal 与 AgentProfile。任一 Repository 失败时异常向事务边界传播，后续写入停止。

M1-D02 不创建 HTTP API、CommandReceipt、DomainEvent 或 Outbox；这些属于 M1-A01。M1-D03 已将默认 Personal Agent 接入同一个 Team 创建事务，M1-D07 增加 Owner Member、默认 Workspace 的延后外键和数据库并发约束，M1-D08 实现这些 Repository Port。

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-application -am clean test
```

M1-D02 新增 16 个测试：

- Team：4；
- Workspace：5；
- TeamInitialization 与唯一 Owner：4；
- TeamCreationService 事务：3。

专项结果：Domain 与 Application 共 111 个测试通过，0 失败、0 错误、0 跳过。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块全部构建成功，后端共 227 个测试通过，0 失败、0 错误、0 跳过。

## 后续

M1-D03 已实现默认 Personal Agent Principal、AgentProfile 和并发幂等创建策略。后续 M1-D04 扩展 WorkProject、WorkItem、Comment、ResourceLink 与状态机。
