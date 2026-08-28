# M7-D04 AccountOrganizationBinding 领域契约

> 任务：`M7-D04`<br>
> 日期：2026-08-28<br>
> 状态：完成<br>
> 关联决策：[ADR-024](../adr/ADR-024-Account与Principal身份边界.md)

## 1. 交付目标

M7-D04 将 Account 与 Organization 行为身份边界落为生产领域和应用契约：

- `AccountOrganizationBinding` 单向保存 Account、Organization 与 USER Principal 稳定 ID；
- `AccountOrganizationKey` 与 `OrganizationPrincipalKey` 固定数据库双唯一坐标；
- `ACTIVE / DISABLED` 形成闭合可逆状态机，映射端点创建后保持不变；
- `AccountOrganizationBindingService` 将并发相同绑定收敛到同一已提交事实；
- `AccountOrganizationPrincipalResolver` 只解析完整现有身份链，不从请求 Organization 创建 Principal；
- Principal 与 TeamMember 继续不引用 Account、LoginIdentity 或 Binding。

## 2. Binding 不变量

创建与重新启用 Binding 同时要求：

```text
UserAccount canAuthenticate
Principal type = USER
Principal scope = Organization
Principal organization = binding organization
Principal status = ACTIVE
```

Binding 只保存 `bindingId / accountId / organizationId / principalId / status / version / lifecycle`。状态变化推进业务版本与更新时间；版本达到 `Long.MAX_VALUE` 后失败关闭。重新启用必须提交原 Account 与原 Principal，不能借状态变化替换任何映射端点。

两个唯一坐标分别表达一个 Account 在一个 Organization 只能有一个当前映射，以及一个 Organization USER Principal 只能属于一个 Account：

```text
(account_id, organization_id)
(organization_id, principal_id)
```

两类冲突统一为 `account_organization_binding_conflict`，错误详情不包含 Account ID、Principal ID 或数据库约束名。

## 3. 并发与既有 Principal

绑定服务只接受调用方已经解析并验证的 Principal。相同 Account、Organization 与 Principal 的并发创建由 Repository 唯一边界裁决；冲突方重新读取 Account/Organization 规范键并复验 Principal，完全相同的请求返回已提交 Binding。相同 Account 指向不同 Principal，或相同 Organization Principal 指向另一个 Account，保持统一冲突。

Bootstrap 升级可以直接绑定带有 `bootstrap/crewscope-monitor` ExternalIdentity 的现有 USER Principal。服务复用其稳定 Principal ID，不修改 ExternalIdentity，也不创建 TeamMember；Team Membership 继续由 Onboarding、Invitation、SCIM 或管理员成员用例创建。

## 4. 请求解析

请求解析使用以下状态交集：

```text
UserAccount ACTIVE
AND LoginIdentity usable and owned by Account
AND AccountOrganizationBinding ACTIVE
AND Principal ACTIVE / USER / Organization Scope
AND Principal Organization matches requested Organization
```

任一条件缺失时返回空解析结果。未绑定 Organization 请求不会写入 Binding 或 Principal。Resolver 依赖独立 `OrganizationPrincipalReader`，该只读端口只提供 `findById`，没有旧 `PrincipalRepository.provisionUser` 能力，因此 URL、Header 或其他客户端 Organization 输入无法触发身份 Provision。

Team 访问继续在解析结果上叠加 ACTIVE TeamMember 与当前 TeamRole；Binding 创建本身不授予 Team 权限。

## 5. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=AccountOrganizationBindingM7D04Test,AccountOrganizationResolutionM7D04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

22 个测试通过，0 Failure、0 Error、0 Skip：

| 模块 | 测试数 |
| --- | ---: |
| `crewscope-domain` | 13 |
| `crewscope-application` | 9 |
| 合计 | 22 |

覆盖 ACTIVE Binding 创建、Account 全部不可认证状态、Principal 类型/Scope/Organization/状态冲突、双唯一键、映射不可变、状态机、版本上下界、安全冲突、Principal/TeamMember 零反向依赖、未绑定 Organization 零写入、Identity 所有权、只读端口能力形状、16 路并发收敛、双坐标所有权冲突和 Bootstrap Principal 原位复用。

## 6. 后续边界

- M7-D05 在稳定 Account/Organization Binding 上实现 TeamInvitation 与一次性接受；
- M7-D07 通过 V31 PostgreSQL 唯一索引、复合外键和迁移 Guard 落地正式存储约束；
- M7-I01 实现 Account、Identity、Credential 与 Binding 的 R2DBC Repository；
- M7-I05 将只读身份链接入 Spring Security Session 恢复与当前授权复验；
- M7-A02/A06 将空解析统一映射为不可枚举认证失败与公开 Session 投影；
- M7-A04 在显式邀请接受事务中创建 TeamMember，不改变 Binding 领域职责。
