# M9-A05：状态流转可用性 API 契约

本文覆盖三类对象，三类都已交付：WorkItem（第一节，及「收口说明」一节的复核结论）、ReviewRequest（第四节）、TaskExecution（第五节）。

编号沿用既有小节号，不再重排：「收口说明」一节内的三个小节依次为入口形态、原因枚举、本轮收尾，其中本轮收尾的细项是 `3.x`；Review 面是第四节（细项 `4.x`），Task 面是第五节（细项 `5.x`）。`3.1`、`3.4` 这两个编号已被 `docs/plans/M9-产品体验重构与设计系统.md` 引用，改动会打断那份引用。

## 一、WorkItem：独立只读端点

### Endpoint

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions/availability`

接口返回当前成员对该 WorkItem 的状态流转可发现性。请求必须通过既有 Team Membership 与 WorkProject 可见性校验；成员身份由服务端会话解析。

### Response

```json
{
  "transitions": [
    {
      "actionId": "submit-review",
      "targetStatus": "IN_REVIEW",
      "label": "提交评审",
      "strength": "PRIMARY",
      "reversible": true,
      "enabled": true,
      "reason": null,
      "reasonMessage": null,
      "remedyLabel": null,
      "remedyRoute": null
    }
  ]
}
```

`transitions` 只包含既有 `WorkItem` 状态机允许的边，顺序按目标状态稳定排序。不可用动作仍返回，便于前端解释原因；`reason` 使用稳定枚举，不能返回 Provider、数据库或权限系统原文。

在 **WorkItem 面**上，八个原因常量中**只有 2 个可达**：`EXTERNAL_PROVIDER_MANAGED` 与 `PERMISSION_DENIED`。另外 2 个（`ARCHIVED`、`STATUS_NOT_ALLOWED`）虽然写了判定代码，却因构造方式而永远不产出；余下 4 个（`REVIEWER_REQUIRED`、`DUTY_SEPARATION_CONFLICT`、`GATE_NOT_PASSED`、`BLOCKED_BY_DEPENDENCY`）没有任何规则产出它们。三类的区分与证据见下节；其中两个常量在 **Review 面**已可达，见第四节。可发现性信息不构成授权边界，实际转移仍由 `WorkItemCommandService` 重新执行权限、状态、来源、版本和幂等校验。

## 收口说明：实际交付与计划文本的差异

本节记录复核结论，避免后续再按计划文本误判 A05 的完成度。

### 一、入口形态：独立只读端点，而非内联进列表响应

计划把 A05 写成「在 WorkItem、ReviewRequest、TaskExecution 三类对象的既有查询响应中内联 `availableActions[]`」。实际交付的是 WorkItem 归属的独立端点 `GET …/work-items/{id}/transitions/availability`，只读、只覆盖 WorkItem 一类对象，ReviewRequest 与 TaskExecution 的可用性投影不存在。（**本节写于排查当时**；ReviewRequest 一面已在第四节补交付，TaskExecution 仍缺。）

端点的内置约束（也是它没有做成批量接口的直接原因）：`transitions` 只包含 `WorkItem.allowedTransitionsFrom(status)` 给出的边，所以**「状态不允许」永远不会作为 `reason` 出现**——不允许的边根本不在列表里，而不是以免用的形态返回。这一点与计划里「原因至少覆盖状态不允许」的表述不同，是设计选择而非遗漏：列表本身就是状态机的投影，前端不需要为不可用的边做解释。

工作台侧同一份数据的形态本轮已改为 `WorkDeskItem.availableActions: WorkItemAvailableTransition[]`，与上面的对象形状一致，详见「三、本轮收尾」。

### 二、原因枚举：两侧命名漂移，且四个原因在投影与命令两侧都没有规则

ADR-027 冻结的八个原因与已交付的 `WorkItemTransitionBlockReason` 八个常量**不是同一组**：2 个同名、4 个改名、2 个只在 ADR 中、2 个只在实现中，覆盖范围并不相同。

| ADR-027 | 已交付 | 关系 |
|---|---|---|
| `STATUS_NOT_ALLOWED` | `STATUS_NOT_ALLOWED` | 同名；实现中不可达（见上节，边不在列表里） |
| `DUTY_SEPARATION_CONFLICT` | `DUTY_SEPARATION_CONFLICT` | 同名；实现中且命令侧都无规则 |
| `PERMISSION_REQUIRED` | `PERMISSION_DENIED` | 改名，语义一致（权限为 `TeamPermission.WORK_PARTICIPATE`） |
| `REVIEWER_NOT_ASSIGNED` | `REVIEWER_REQUIRED` | 改名，语义一致；两侧都无规则 |
| `PREREQUISITE_GATE_PENDING` | `GATE_NOT_PASSED` | 改名，语义一致；两侧都无规则 |
| `UNRESOLVED_BLOCKER` | `BLOCKED_BY_DEPENDENCY` | 改名，语义一致；两侧都无规则 |
| — | `EXTERNAL_PROVIDER_MANAGED` | 实现独有（`!item.source().isNative()`），ADR 未列 |
| — | `ARCHIVED` | 实现独有（`status() == ARCHIVED`），ADR 未列 |
| `VERSION_CONFLICT` | — | 仅 ADR；是命令期冲突，不适合作为流转可用性的静态裁决 |
| `OFFLINE_OR_STALE` | — | 仅 ADR；是客户端连通性状态，服务端无法裁决 |

四个「有常量、无规则」的原因（`REVIEWER_REQUIRED`、`DUTY_SEPARATION_CONFLICT`、`GATE_NOT_PASSED`、`BLOCKED_BY_DEPENDENCY`）在**投影侧和命令侧都没有任何判定代码**：`WorkItemTransitionAvailabilityQueryService.evaluate` 只检查归档、来源、权限三项；`WorkItemCommandService` 只校验权限、状态、来源、版本与幂等键。全仓库唯一的职责分离校验位于 `TaskIntentProposal.requireDutySeparation`，作用于「提议的 Gate Reviewer 不得与 Owner/Executor 是同一主体」，是任务意图校验，与 WorkItem 流转无关。

因此这四个原因不是「已实现但没接线」，而是**尚未存在的产品规则**。补它们要在投影侧新增判定，同时必须在命令侧新增同一套判定，否则违反 M9 反模式 #9（「可用性投影与命令校验各写一套判定」）——只做投影会造出界面上可用、执行时被拒的动作，正是该反模式要防的失败。这是一项需要单独论证的产品改动，不是接线工作。

同理，`remedy` 当前从未产出：三处 `disabled(...)` 都传 `Optional.empty()`，所以 `remedyLabel` / `remedyRoute` 恒为 `null`。计划中「`remedy` 坐标在无权限时为空而非指向不可达页面」这条验收项因此是**空真**——它成立，但是没有正向用例证明 remedy 在有下一步时确实给出坐标。

### 三、本轮收尾（A05 接线与对账）

上一轮复核留下的两个缺口本轮都已闭合，同时更正一处上一轮的结论。

#### 3.1 工作台可用性已接线

`WorkDeskItem.availableActions` 由 `string[]` 改为对象数组，元素类型就是本契约响应里的动作对象。服务端**刻意复用同一个嵌套 record** `WorkItemTransitionController.WorkItemAvailableTransitionResponse`，而不是在工作台里另写一个同形 record——两个面（详情端点的全量与工作台的子集）因此不可能序列化漂移。

工作台**只返回 `enabled` 的动作**，这是一个刻意减配：「禁用必有原因」这条不变量要求禁用项带着可解释的文案出现，而工作台列表行无法呈现按钮的禁用状态与原因，于是它被交给一个更短的诚实列表；禁用原因仍可在详情面板看到（那里拉取全量）。后果之一是这条不变量在工作台面**不会被触发**，也就无从违反；另一后果是，若日后要在首页解释禁用原因，必须回改本契约，而不是在首页另写一套判定。

哪些行可以有动作，由**事实**而非字符串约定决定：仓储为 WorkItem 行附带 `WorkItemTransitionSubject(projectId, status, nativeSource)`，`TASK_EXECUTION` / `REVIEW_REQUEST` / `HUMAN_GATE` / `INBOX` 行不带，应用层只对带 subject 的行求值。非 WorkItem 行因此**按构造成立**为空，不需要靠状态字符串猜。

权限按项目求值，但**每个请求只读一次**成员角色与授权：`WorkItemAccessPolicy.resolvePermission(...)` 返回一个纯函数化的 `WorkItemTransitionPermissionResolver`，工作台把它复用给页面上每一行、每一个项目。逐行调用 `hasPermission` 会重新读取 Team、成员关系、项目与两张授权表，这正是工作台预算无法承受的 N+1；现在的读取次数与条目数、项目数无关。

`availableActions` 由 `string[]` 变对象数组是**破坏性变更**。当时服务端恒返回 `[]`（`JdbcWorkDeskRepositoryAdapter.item(...)` 硬编码 `List.of()`），全仓库的三个引用点（`TodayPage.vue`、`workdesk/gateway.spec.ts`、`m9-workdesk.spec.ts`）都是自造夹具，没有真实消费者，因此没有线上兼容负担——这也是本轮可以直接改形状而不是加版本的原因。

#### 3.2 裁决只有一份，且有对账测试

反模式 #9 的对策是**结构性**的，不是靠测试兜住的：

- 可用性裁决体只存在于 `WorkItemTransitionAvailabilityProjector`。端点侧（全量）与工作台侧（仅启用）分别是它的 `all(...)` 与 `enabled(...)`，后者就是前者的过滤，不是第二套规则。
- 权限判定只存在于 `WorkItemAccessPolicy.granted(...)`。`hasPermission` 与 `WorkItemTransitionPermissionResolver.granted` 都委托到它，所以工作台页面与流转命令不可能对「谁可以参与」给出不同答案。

证据有两层：

- `WorkItemTransitionAvailabilityReconciliationTest` 在（状态 × 原生/外部 × 有/无权限）的**全矩阵**上把投影当作承诺、把命令当作权威：投影说 `enabled` 的边逐一真交给 `WorkItemCommandService.transition` 必须成功；投影说 `disabled` 的边必须失败，且失败原因与投影给出的 `reason` 对应。矩阵遍历同时断言「投影给出的边数等于状态机边数（仅当原生且有权限）」，因此两侧都不可能悄悄放宽。
- 该测试的非空跑性质是**实测**的，不是声明：把投影的权限分支去掉（让投影多许诺），测试失败；把命令的来源守卫去掉（让命令接受本来被拒的边），测试同样失败——后一次变更**不改变任何计数**，只有真的把边交给命令才会暴露，这正是对账循环而非计数断言在起作用的证据。

此外 `WorkItemAccessPolicyM3A07Test` 增加了一张逐格矩阵，断言 `resolvePermission(...).granted(p, WORK_PARTICIPATE)` 与 `hasPermission(..., p, WORK_PARTICIPATE, ...)` 在管理员 / 团队作用域授权 / 本项目授权 / 他项目授权 / 已撤销 / 已过期 / 角色不可授予 / 角色无该权限 / 无授权九个格子上**一致**，并同时断言一致的那个答案本身是对的——两条路径一致地错不算通过。

#### 3.3 更正：`ARCHIVED` 同样不可达

上一轮把可达原因记为 3 种（含 `ARCHIVED`），这是错的。`evaluate` 确实先判归档，但归档是终态，`WorkItem.allowedTransitionsFrom(ARCHIVED)` 为空，边集合在任何一个裁决到达之前就已经是空的——没有边可以标注 `ARCHIVED`。判定代码保留，是为了「归档态日后若出现出边，也不得被当作可执行」这一防御；它当前不是一条能产出的原因。

同类地，`STATUS_NOT_ALLOWED` 不可达是因为不允许的边不在列表里。两个常量的不可达原因不同（一个是边集合为空，一个是非法边被过滤），但结论一样。

#### 3.4 冻结原因的归属（仅 WorkItem 面）

四个「有常量、无规则」的原因本轮**冻结归档，不新增任何规则，零行为变更**。它们不是「已实现但没接线」，而是尚未存在的产品规则；补其中任何一条都要同时在投影侧与命令侧新增同一套判定，否则就造出界面上可用、执行时被拒的动作，正是反模式 #9 要防的失败。归属如下——**本表只描述 WorkItem 面**；`REVIEWER_REQUIRED` 与 `DUTY_SEPARATION_CONFLICT` 在 ReviewRequest 面已按同一纪律交付，见第四节：

| 原因 | 现状 | 归属里程碑 / 前置 |
|---|---|---|
| `PERMISSION_DENIED` | **可达**，投影与命令两侧都有规则 | 已交付 |
| `EXTERNAL_PROVIDER_MANAGED` | **可达**，两侧都有规则 | 已交付 |
| `STATUS_NOT_ALLOWED` | 不可达（非法边不在列表里），保留为常量 | 无规则需求；若要作为显式原因，需先讨论「为什么要把非法边也返回」 |
| `ARCHIVED` | 不可达（终态无边），保留为防御性判定 | 无规则需求 |
| `BLOCKED_BY_DEPENDENCY` | 无规则 | M11 `work_item_relation`——阻塞关系目前不是领域事实 |
| `GATE_NOT_PASSED` | WorkItem 面无规则；Task 面**可达** | WorkItem 面需单独产品立项（`domain/workitem` 下没有任何 Gate 概念）；Task 面见第五节 |
| `REVIEWER_REQUIRED` | WorkItem 面无规则；Review 面**可达** | WorkItem 面需单独产品立项；Review 面见第四节 |
| `DUTY_SEPARATION_CONFLICT` | WorkItem 面无规则；Review 面**可达** | WorkItem 面需单独产品立项；Review 面见第四节 |

**更正**：上一轮在这里写「全仓库唯一的职责分离校验在 `TaskIntentProposal.requireDutySeparation`」，这不准确。仓库里有两处职责分离校验，作用面不同：`TaskIntentProposal.requireDutySeparation` 校验的是「提议的 Gate Reviewer 不得与 Owner/Executor 同一主体」，是**任务意图**校验；`ReviewerEligibilityPolicy.evaluateGate` 校验的是「当前 Gate Reviewer 不得持有该 WorkItem 上活动的 OWNER/EXECUTOR 责任」，是**Gate 授权**校验，`ReviewGateApplicationService`、`ReviewDecision` 与 `GateReviewerAssignmentService` 都读它。Review 面的 `DUTY_SEPARATION_CONFLICT` 走的是后者。

**更正（WorkItem 面限定）**：`remedy` 在 WorkItem 面仍恒为 `null`（三处 `disabled(...)` 都传 `Optional.empty()`），本轮未改。因此计划里「`remedy` 坐标在无权限时为空而非指向不可达页面」在 WorkItem 面上仍是空真。Review 面已产出真实坐标，见第四节。

#### 3.5 本轮在真实数据库上发现并修掉的缺陷

为工作台选列时，适配器曾写成 `wi.source`。**`crewscope.work_item` 没有 `source` 列**，该表自 V1 起的列名是 `source_provider`（`V6` 的 `ck_work_item_source_values` 与 `ck_work_item_source_reference` 都基于它）。编译期无法发现，因为它是 SQL 字符串。

新增的 `JdbcWorkDeskRepositoryAdapterM9A05IntegrationTest` 在真实 PostgreSQL（Testcontainers + Flyway 迁移）上运行工作台的 `WORK_ITEM` 与 `BLOCKED` 两条 SELECT，断言原生行与外部 Provider 行的 `transitionSubject` 正确、其余四个 section 无行、且**任何行都不带已裁决的动作**（可用性不是该层的事）。这是本仓库第一个工作台数据库级测试，也是这条列名缺陷唯一的证据来源。

夹具不再需要计划里设想的 `session_replication_role = replica` 绕外键：按迁移后的真实外键顺序插入 organization → principal → team → workspace → team_member → work_project 即可，代价可接受，且不掩盖外键本身的约束。

## 四、ReviewRequest：内联进既有查询响应

本节更正「收口说明 · 一、入口形态」里「ReviewRequest 与 TaskExecution 的可用性投影不存在」这一结论中的前半句：ReviewRequest 已交付，TaskExecution 仍缺。

### 4.1 形状与入口

Review 面**没有**独立端点，而是把动作内联进既有两个查询响应：

- `GET …/tasks/{taskId}/attempts/{executionId}/reviews` — 列表，每项形如 `{ …ReviewRequestProjection, "availableActions": [...] }`
- `GET …/reviews/{reviewRequestId}` — 详情，响应新增同名字段

动作对象就是第一节那个线上形状，唯一差别是 `targetStatus` 取 `ReviewRequestStatus`。服务端用同一个映射器 `ReviewController.response(ReviewGateAction)` 产出它，复用 `AvailableActionResponse.of(...)`——三类对象共用一条线上契约，前端一套控件渲染三处。

```json
{
  "items": [
    {
      "id": "…",
      "status": "COMPLETED",
      "availableActions": [
        { "actionId": "execute", "targetStatus": "IN_PROGRESS", "label": "执行评审",
          "strength": "PRIMARY", "reversible": false, "enabled": false,
          "reason": "STATUS_NOT_ALLOWED", "remedyLabel": null, "remedyRoute": null },
        { "actionId": "decide", "targetStatus": "COMPLETED", "label": "记录决策",
          "strength": "PRIMARY", "reversible": false, "enabled": true,
          "reason": null, "remedyLabel": null, "remedyRoute": null }
      ]
    }
  ]
}
```

与工作台不同，Review 面返回**全量**（含禁用项与原因）：这里的列表行有能力解释禁用态。顺序是固定的目录顺序（`execute`、`decide`、`request-changes`、`re-review`），不是按可用性过滤后的顺序。

`ReviewRequestAvailability`（`projection` + `availableActions`）是列表元素类型；投递汇总 `TaskDeliverySummaryService` 只读 `projection()`——它读的是评审事实，不是 Gate 控件，所以「下一步能做什么」只在 Review 面回答，不折进投递汇总。

### 4.2 四个动作与原因的可达性

| 动作 | 目标状态 | 检查（按命令应用的顺序） | 可达原因 |
|---|---|---|---|
| `execute` | `IN_PROGRESS` | ① 活动 Reviewer Agent 责任 ② 状态 ∈ {OPEN, IN_PROGRESS} | `REVIEWER_REQUIRED`、`STATUS_NOT_ALLOWED` |
| `decide` | `COMPLETED` | ① Gate Reviewer 责任 ② 职责分离 ③ 状态 = COMPLETED | `REVIEWER_REQUIRED`、`DUTY_SEPARATION_CONFLICT`、`STATUS_NOT_ALLOWED` |
| `request-changes` | `COMPLETED` | 同 `decide` | 同 `decide` |
| `re-review` | `OPEN` | ① 活动 Reviewer Agent 责任 ② 状态 = INVALIDATED | `REVIEWER_REQUIRED`、`STATUS_NOT_ALLOWED` |

顺序是契约的一部分，不是实现巧合：`decide` 的前三道检查按此顺序，报告的是第一道未通过的。核对过的一处顺序依据——`ReviewGateApplicationService.record` 在调用 `ReviewDecision.initial/successor` **之前**先跑 `ReviewerResponsibility.requireGateReviewer` 与 `evaluateGate`，因此「没有 Reviewer 责任」先于「状态不是 COMPLETED」；`re-review` 相反地先解析当前 Reviewer 再看前驱状态，所以它的第一道检查是 Reviewer 而非状态（这一点由对账测试实测出来后才改正，见 4.4）。

需要注意的边界：`decide` 的「资格先于分离」由**外层服务**与 `ReviewDecision.requireAuthority` **共同**保证。`requireAuthority` 内部的顺序其实是版本 → 当前性 → **状态** → 任务一致性 → 资格 → 分离（状态检查在资格之前），只是外层服务在进入聚合前已经把资格与分离都查过一遍，所以对外可观察到的第一道拒绝仍是资格。也就是说，若日后有人把外层的预检删掉，可观察顺序会变成「状态先于资格」——投影与 4.2 的表都要跟着改。对账测试钉住的是外层的可观察行为。

**`PERMISSION_DENIED` 在 Review 面不可达**：三个端点都先要求 WorkItem 可见性，可见性不足时在投影之前就抛错，因此没有「可见但无权限」这个状态供投影标注。这不是遗漏，是该面的构造结果——与 WorkItem 面（权限是独立的一层）不同。

**`BLOCKED_BY_DEPENDENCY` 在 Review 面同样无规则**：它需要 M11 的 `work_item_relation`，与 WorkItem 面同一归属。

### 4.3 `remedy` 的第一个真实生产者

`REVIEWER_REQUIRED` 附带稳定站内坐标，由 `ReviewWorkbenchCoordinates.assignReviewer(WorkItem)` 产出：

```
/work?team={teamId}&project={projectId}&workItem={workItemId}&focus={workItemKey}
```

该坐标只含 UUID 与工作项 Key，因此通过前端的 `safeRoute`（拒绝非 `/` 前缀、`//`、`..`、`\` 与空白/`#`/`%`）。服务端给的是坐标而非 URL 文本。

`DUTY_SEPARATION_CONFLICT` 与 `STATUS_NOT_ALLOWED` **刻意不带 remedy**：成员无法把自己从 OWNER/EXECUTOR 重叠里改出来，也无法把状态改回去，指向一个改变不了结果的页面比不给坐标更差。所以「无权限时为空而非指向不可达页面」这条验收项在 Review 面首次有了正向与反向两类用例。

### 4.4 同源与对账

**裁决体只有一份**：`ReviewGateAvailabilityProjector` 的声明式有序检查目录。它自己不含规则——每个检查读的是 `ReviewGateFacts`，而 `ReviewGateFacts.resolve(...)` 通过 `ReviewerResponsibility`（Reviewer 资格）与 `ReviewerEligibilityPolicy.evaluateGate`（职责分离）解析事实，正是命令读的那两个。

同时，重复的资格判定被抽成 **`ReviewerResponsibility`** 一个领域类：四个调用点（Gate 服务、`ReviewDecision`、Reviewer 执行服务、`ReviewRequestApplicationService.creationFacts`）原先各写一遍「是否持有当前活动 REVIEWER 责任」。`ReviewDecision.requireAuthority` 里两处检查的顺序也据此调整为「资格先于分离」——没有 Reviewer 责任的人需要的是派单，不是职责分离的说教，而投影对同样的事实给同样的原因。

**对账测试** `ReviewGateAvailabilityReconciliationTest`：13 个矩阵格（状态 × Reviewer Agent 指派 × Gate Reviewer 指派 × 职责分离）× 4 个动作 = 52 次比对。投影说 `enabled` 的动作真交给对应命令服务必须成功；投影说 `disabled(reason)` 必须失败且原因是同一个。命令侧权威是三个真实服务（`ReviewerExecutionApplicationService`、`ReviewGateApplicationService`、`ReviewRequestApplicationService`），仓储用 mock，但**被校验的守卫全是真的**。

该测试的非空跑性质是实测的：

| 变异 | 结果 |
|---|---|
| 把 `decide` 的状态谓词改成恒真（投影多许诺） | 失败：`decide @ open, assigned: offered but refused` |
| 去掉 `execute` 的 Reviewer 前置检查（投影多许诺） | 失败：`execute @ open, no Reviewer Agent: offered but refused` |
| 把 `ReviewerResponsibility.holdsGateReviewer` 改成恒真（命令放宽，投影不变） | 失败：`decide @ open, no Gate Reviewer: … expected REVIEWER_REQUIRED but was STATUS_NOT_ALLOWED` |

第三个变异是**命令侧**的：它不改变投影的任何输出，只有真的把动作交给命令才会暴露。这正是对账循环而非计数断言在起作用的证据。

### 4.5 不可观察的前置条件（如实留档）

以下前提**不在投影面上**，因为它们是别的聚合的事实，不是 ReviewRequest 的事实。投影可能在事实允许时提供动作，而命令仍因这些原因拒绝：

| 前提 | 读它的地方 | 为什么不投影 |
|---|---|---|
| 当前 ContextPackage 权威（`requireCurrent`） | 每个 Gate 命令 | ContextPackage 漂移属于另一个聚合；投影只读 ReviewRequest 上冻结的引用 |
| 精确活动的 Reviewer Specialist Session | `execute` | 会话属于 Task 聚合 |
| 当前 Diff、TestEvidence、PolicySnapshot | `re-review` | `creationFacts` 在解析 Reviewer 与状态**之前**先解析它们 |

夹具与测试的边界同样如实记录：`ReviewGateAvailabilityReconciliationTest` 用的是 mock 的 `ReviewRequest`，因此聚合自身的状态迁移（`start`/`complete`）被打桩返回当前状态——`ReviewRequestTest` 才是它的归属；`re-review` 的 `successor` 迁移读前驱的字段而非访问器，mock 前驱无法驱动它，所以 `re-review` 的**启用方向**只断言到「命令没有因投影给出的原因拒绝」，而不是「命令成功」。这两条边界都在测试类的 javadoc 里写明。

### 4.6 三类对象的交付位置

| 对象 | 交付位置 | 详见 |
|---|---|---|
| WorkItem | 独立只读端点 + 工作台 `availableActions` 内联 | 第一、二节 |
| ReviewRequest | 评审列表与详情的 `availableActions` 内联（全量，含禁用项） | 本节 |
| TaskExecution | 任务 attempts 的 `availableActions` 内联（全量，含禁用项） | 第五节 |

## 五、TaskExecution：内联进任务 attempts 响应

### 5.1 形状与入口

`GET …/tasks/{taskId}/attempts` 的每个 attempt 形如 `{ …TaskExecutionProjection, "availableActions": [...] }`，动作对象仍是第一节那个线上形状，`targetStatus` 取 `TaskExecutionStatus`，由 `TaskQueryController.response(TaskControlAction)` 映射到 `AvailableActionResponse.of(...)`。

四个动作的 `targetStatus` 是**命令执行后的状态**，不是它内部请求的那个状态：

| 动作 | `targetStatus` | 强度 | 可逆 |
|---|---|---|---|
| `pause` | `PAUSE_REQUESTED` | SECONDARY | 是 |
| `resume` | `READY` | SECONDARY | 是 |
| `cancel` | `CANCEL_REQUESTED` | DANGER | 否 |
| `retry` | `READY` | PRIMARY | 是 |

Pause 停在 `PAUSE_REQUESTED`（Worker 还要走到安全点）、Cancel 停在 `CANCEL_REQUESTED`（同一命令内可能收敛到 `CANCELLED`）、Retry 不改动失败的 attempt 而是发布一个 READY 后继——三处都不是「跨过的边」，而是「成员同意的结果状态」。

与 Review 面一样，这里返回**全量**（含禁用项与原因）。

### 5.2 可达原因

| 原因 | 何时产出 |
|---|---|
| `PERMISSION_DENIED` | 成员不持有该 WorkItem 上活动的 OWNER / EXECUTOR 责任（`TaskControlAuthority.granted`） |
| `GATE_NOT_PASSED` | 该 attempt 停在只有人能决定的问题上（`WAITING` + 等待原因为 `CONFIRMATION` / `REVIEW` / `USER_INPUT`） |
| `STATUS_NOT_ALLOWED` | 其余情况：状态本身不允许该命令 |

`GATE_NOT_PASSED` 与 `STATUS_NOT_ALLOWED` **可执行的命令集合完全相同**，两者的差别只在**措辞**：同一个被拒的动作，停在人工决策点上时说的是「Gate 未通过」，否则说的是「状态不允许」。这一点由 `blockedReason` 一处产出，投影与命令都读它。

`PERMISSION_DENIED` 在这里**可达**——与 Review 面相反。差别在权限是不是独立的一层：Task attempts 端点先要求 WorkItem 可见性（可见性不足直接抛错、不返回该行），但在可见的前提下仍可能没有控制权，于是控制权被投影成一个原因而不是一个异常。

### 5.3 与命令同源，且权限只读一次

四个动作的许可谓词**就是命令自己的前置条件**：`TaskExecution::canRequestPause` / `canResume` / `canRequestCancel` / `canRetry`，`requestPause`、`requestCancel` 与 resume 路径内部调用同一批方法。投影不含自己的规则，只负责把每个命令与它将携带的原因配对——因此目录在这条路径上是一个**闭集**（四个命令），而不是像 WorkItem 那样的状态机边表。

控制权取决于 **WorkItem 上的活动责任**，与 attempt 无关，所以 `TaskQueryService.attemptRows` **每次请求只读一次**责任，再对同一 Task 的每个 attempt 复用：一个有很多次尝试的 Task 不会变成同样多次的责任读取。这和工作台的处理是同一种手法。

对账测试是 `TaskControlAvailabilityReconciliationTest`。

### 5.4 不可观察的前提与空白

- `resume` 与 `retry` 还需要**一个被中断的 AgentRun 及其匹配的待决 interrupt**，那是另外的聚合。投影在 execution 事实允许时报「可用」，命令仍可能因缺少该运行时事实而拒绝。这条缺口记录在 `TaskControlAvailabilityProjector` 的类注释里。
- Task 面**不产出 `remedy`**：`TaskControlAction.disabled(...)` 没有携带 remedy 的重载，成员不能替自己解决控制权或状态问题，指向一个改变不了结果的页面比不给坐标更差（与 Review 面 `DUTY_SEPARATION_CONFLICT` 的处理同理）。`TransitionRemedy` 目前**唯一**的真实生产者仍是 Review 面的 `REVIEWER_REQUIRED`。

