# M2-D03：TaskIntent 与澄清契约

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立 Personal Agent 到 CrewScope 业务命令之间的版本化结构化输出边界，实现 TaskIntent 的项目、责任、修订、乐观并发和终态决策规则，为 M2-S03 Structured Output 验证、M2-D06 数据库迁移及 M2-A05/A07 确认闭环提供稳定契约。

## Structured Output

### TaskIntentV1

`TaskIntentV1` 是 Agent 产生的不可信 DTO：

| 字段 | 规则 |
|---|---|
| `schemaVersion` | 固定为 `1` |
| `objective` | 必填，最多 5,000 字符 |
| `acceptanceCriteria` | 1–20 项，每项必填且最多 1,000 字符 |
| `workProjectId` | 必填 canonical UUID |
| `ownerMemberId` | 必填 canonical UUID |
| `executorPrincipalId` | 可选 canonical UUID |
| `gateReviewerMemberId` | 可选 canonical UUID |

Agent 提供的 UUID 只是候选引用。Bean Validation 通过后，Application Service 必须从当前 Team/Workspace 服务端事实解析 WorkProject、Principal 和 TeamMember，再创建 `TaskIntentProposal`。客户端和模型不能声明 PrincipalType、Membership 状态、Scope 或职责分离结果。

### ClarificationRequestV1

`ClarificationRequestV1` 保存：

```text
schemaVersion = 1
summary
questions[1..10]
```

每个 `ClarificationQuestionV1` 包含稳定 `fieldKey`、问题、可选上下文、Required 标记和最多 5 个选项。空选项列表表示自由文本回答。Field Key 使用小写 snake_case 兼容形式，问题、上下文和选项都有明确长度上限。嵌套问题通过 `@Valid` 递归执行 Bean Validation。

结构化输出集合在构造后保持只读；非法空值仍保留到 Bean Validation 边界形成字段级错误，不在 DTO 构造器中提前转成领域异常。

## TaskIntent 领域模型

TaskIntent 保存：

```text
TaskIntentId
Conversation Scope / ConversationId
ProposedBy Agent PrincipalId
Schema Version
Proposal Revision
TaskIntentProposal
Status / Decision
Aggregate Version
AuditMetadata
```

创建规则：

- Conversation 必须 ACTIVE；
- 提案者必须是该 Conversation 的 ACTIVE AGENT Participant；
- WorkProject 必须 ACTIVE，且 Organization、Team、Workspace 与 Conversation 完全一致；
- Objective 和 Acceptance Criteria 在领域层再次归一化、限长并拒绝重复；
- 初始 `proposalRevision=1`、`version=0`、`status=DRAFT`；
- CreatedBy 固定为实际提案 Agent Principal。

## 责任提案

TaskIntent 的责任候选全部从服务端当前事实解析：

| 责任 | 必填 | 主体规则 |
|---|---:|---|
| Owner | 是 | ACTIVE USER 与匹配的 ACTIVE TeamMember |
| Executor | 否 | ACTIVE USER + TeamMember，或当前 Team 的 ACTIVE Agent |
| Gate Reviewer | 否 | ACTIVE USER 与匹配的 ACTIVE TeamMember |

默认职责分离要求 Gate Reviewer 与 Owner、Executor 使用不同 Principal/TeamMember。Owner 与 Executor 可以由同一成员承担。TaskIntent 只保存候选责任快照；M2-A05/A07 确认预检必须重新解析最新 Membership、Principal、WorkProject 和 ReviewerEligibilityPolicy，任何事实变化都使原确认输入失败或进入 EXPIRED。

M2 TaskIntent Structured Output 不接受模型提供 PolicyPack 覆盖证据。单人团队的显式 Reviewer 降级继续由服务端 ReviewerEligibilityPolicy 裁决，Agent 无法自行放宽规则。

## 生命周期与并发

```text
DRAFT -> READY
DRAFT / READY -> DRAFT       修订，Proposal Revision + 1
DRAFT / READY -> REJECTED
DRAFT / READY -> EXPIRED
READY -> CONFIRMED
```

规则：

- 修订替换完整 Proposal，内容必须发生变化，并重新进入 DRAFT；
- READY 表示当前 Revision 已具备确认条件；
- CONFIRMED 只能由提案中的人类 Owner 作出，并要求传入重新基于服务端事实建立的相同 Proposal；
- REJECTED、EXPIRED 必须保存非空原因；CONFIRMED 不保存伪造原因；
- CONFIRMED、REJECTED、EXPIRED 都是终态，重复确认、拒绝、过期或修订稳定拒绝；
- 所有修改携带 `expectedVersion`，不匹配时返回统一 Optimistic Lock Conflict；
- 每次状态或内容变化推进 Aggregate Version、UpdatedAt 和 UpdatedBy；
- Proposal Revision 只在内容修订时推进，状态迁移不会改变 Revision；
- 持久化还原重新校验 Revision、Proposal Scope、终态 Decision、Actor、时间和 Audit 一致性。

`READY -> CONFIRMED` 的领域迁移由 M2-A07 放入创建 WorkItem、Owner、可选 Executor/Gate Reviewer、ConversationWorkItemLink、DomainEvent、Outbox 和 CommandReceipt 的同一事务。本任务不提前创建 WorkItem 或外部副作用。

## 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain,crewscope-application -am clean test
```

结果：Domain 173 个测试、Application 107 个测试通过，0 失败、0 错误、0 跳过。M2-D03 新增 18 个测试：

- TaskIntent 领域、责任、Scope、状态机、Revision、expectedVersion 和终态还原：12 个；
- TaskIntentV1/ClarificationRequestV1 Bean Validation、集合不可变与 Jackson 往返：6 个。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，460 个后端测试全部通过，失败、错误、跳过均为 0。AgentScope 适配、Docker 沙箱、PostgreSQL、Redis、Flyway、Spring 装配与 Server API 回归通过。

文档与差异检查：

```bash
node scripts/check-doc-links.mjs
git diff --check
```

## 后续

M2-D04 实现 AgentRuntimeSession、稳定 AgentScope User/Session Key、配置版本和状态引用。M2-S03 使用本任务 Schema 验证 AgentScope Structured Output、Bean Validation、Interrupt/Resume 和过期输入。M2-D06/D07 按本任务 Proposal、Revision、Decision 和 Version 不变量实现表结构与 Repository Adapter。
