# M1-D04：WorkProject 与 WorkItem 领域模型

> 日期：2026-08-07<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立团队工作管理的稳定领域事实：WorkProject 组织长期工作，WorkItem 保存计划和交付状态，Comment 追加处理记录，ResourceLink 连接 Task、代码、PR 和 Artifact。M1-D04 完成领域模型与状态机，数据库扩展和完整 Repository Adapter 属于 M1-D07、M1-D08。

## WorkProject

WorkProject 保存：

```text
WorkProjectId
OrganizationId / TeamId / WorkspaceId
WorkProjectKey
Name
ACTIVE / ARCHIVED
Version
AuditMetadata
```

创建规则：

- Team 必须为 ACTIVE；
- Workspace 必须为该 Team 的 ACTIVE TEAM Workspace；
- 创建 Principal 必须为 ACTIVE，且位于同一 Organization/Team Scope；
- Project Key 使用 `[A-Z][A-Z0-9]{1,9}`；
- 名称归一化且不超过 200 个字符；
- ARCHIVED 是终态，不再创建 WorkItem 或修改项目。

TeamRole 和 TeamMember 权限由后续应用用例解析。领域层负责可信 Principal 状态和租户 Scope 的最后防线。

## WorkItem

M0 WorkItem 已具有 Scope、Key、Title、Status、Version 和 AuditMetadata。M1-D04 增加：

- `TASK/BUG/FEATURE/INCIDENT` 类型；
- Markdown Description；
- `LOW/MEDIUM/HIGH/URGENT` 优先级；
- 最多 20 个归一化 Label；
- DueAt；
- `CREWSCOPE/JIRA/ZENTAO/TAPD` 事实来源与外部引用；
- `ARCHIVED` 生命周期；
- 统一字段修订入口。

WorkItem Key 必须使用所属 WorkProject Key 前缀，总长度不超过 32。原生 WorkItem 的事实来源固定为 CREWSCOPE 且不携带外部引用；外部投影必须提供非 CREWSCOPE 来源和稳定外部引用。

字段修订作为一个乐观锁变更，同时推进 Version、UpdatedAt 和 UpdatedBy。Key、Scope 与事实来源在 WorkItem 生命周期内保持稳定。

M0 的 `create` 和简化 `reconstitute` 入口继续保留，默认生成 `TASK/MEDIUM/CREWSCOPE`，现有 Repository、事件与 API 不受 D04 影响。完整 M1 字段由 D07 迁移和 D08 Mapper 落库。

## 状态机

```text
BACKLOG -> READY -> IN_PROGRESS -> IN_REVIEW -> DONE
                         |              |
                         +-> BLOCKED <--+

BACKLOG / READY / IN_PROGRESS / IN_REVIEW / BLOCKED -> CANCELLED
BLOCKED -> READY / IN_PROGRESS / IN_REVIEW
DONE / CANCELLED -> ARCHIVED
```

DONE 和 CANCELLED 在归档前仍允许追加复盘 Comment 与交付 ResourceLink。ARCHIVED 是终态，禁止状态变化、字段修订、Comment 和 ResourceLink。

## Comment

`WorkItemComment` 是不可变 Markdown 记录：

- 绑定完整 WorkItem Scope 与 WorkItemId；
- Author 必须是同 Scope 的 ACTIVE Principal；
- 内容归一化且不超过 50,000 个字符；
- 原生 Comment 不携带外部 ID；
- 外部 Comment 必须保存 Provider 来源和外部 ID；
- Archived WorkItem 不接受新 Comment。

## ResourceLink

`WorkItemResourceLink` 是不可变 WorkGraph 关系，首批类型：

```text
TASK
CONVERSATION
REPOSITORY
BRANCH
COMMIT
PULL_REQUEST
ARTIFACT
EXTERNAL_URL
```

Resource Reference 使用不超过 2,000 个字符的非空稳定引用，可选 Label 不超过 200 个字符。创建者必须是同 Scope 的 ACTIVE Principal，Archived WorkItem 不接受新 Link。

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain -am clean test
```

M1-D04 新增 22 个测试：

- WorkProject：5；
- WorkItem M1 字段和状态机：8；
- WorkItemComment：5；
- WorkItemResourceLink：4。

覆盖 Team/Workspace Scope、Project Key、跨 Organization Principal、项目改名与归档、完整 WorkItem 字段、Key 前缀和长度、外部来源、字段修订、版本推进、Blocked 恢复、终态归档、Comment 来源和长度、ResourceLink 引用以及 Archived 写保护。

专项结果：Domain 104 个测试通过，0 失败、0 错误、0 跳过。该结果包含提交前审查补充的 MemberRole 终态时间一致性回归测试。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块全部构建成功，后端共 261 个测试通过，0 失败、0 错误、0 跳过。

## 后续

M1-D05 基于 WorkItem 实现 ResponsibilityAssignment、唯一 Active Owner 与 Executor/Reviewer 分配。M1-D07 增加 WorkProject、WorkItem 扩展、Comment 和 ResourceLink 数据约束，M1-D08 实现完整 Repository Entity 与 Mapper。
