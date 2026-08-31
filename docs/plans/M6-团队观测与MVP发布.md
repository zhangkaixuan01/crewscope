# M6：团队观测、飞书通知与 MVP 发布执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M6<br>
> 前置条件：M5 Release Gate 通过，M0 Outbox/Projection/Audit、M1 Team/WorkItem、M2 Conversation、M3 Runtime、M4 Coding、M5 Review/Action/GitHub 契约稳定<br>
> 目标周期：3–4 周，按纵向波次推进<br>
> 目标结果：团队成员通过 Activity、Inbox、Audit 和只读 Team Observer 获得共享工作视野；固定模板通知可靠投递到飞书；完整 MVP 闭环具备可观测、可恢复、可部署和可重复验收能力<br>
> 当前进度：`M6-S01` 至 `M6-S05`、`M6-D01` 至 `M6-D09`、`M6-E01` 至 `M6-E07`、`M6-I01` 至 `M6-I10`、`M6-A01` 至 `M6-A07`、`M6-F01` 至 `M6-F08`、`M6-Q01` 至 `M6-Q04` 全部完成，ADR-020 至 ADR-023 已接受；CrewScope Team Beta MVP Release 决定为 `PASS`（2026-08-28）

## 1. 出口结果与范围

M6 完成后具备：

- WorkItem Activity、Team Activity、成员 Inbox 和安全 Audit 查询投影；
- Outbox、Consumer Receipt、Projection Checkpoint、影子重建、代际切换、Dead Letter 与重放闭环；
- Team Event、Conversation Event 与 AG-UI 各自可恢复的游标协议，以及前端确定性合并去重；
- “我的负责”“我的执行”“待 Review”“待确认”“异常”五类 Inbox 视图；
- 默认 Team Service Principal、只读 `team-observer@1` AgentTemplate 与 TEAM-owned AgentProfile；
- Lark Connection、管理员确认的成员映射、固定模板通知、去重、重试、回执和失败收件箱；
- OTel Trace、Prometheus 指标、低基数标签、日志脱敏、运维诊断和 Audit Explorer；
- 可部署镜像、Docker Compose、配置校验、数据备份恢复与 MVP 运维手册；
- 完整“对话—WorkItem—Coding—Review—确认—Draft PR—Activity/Inbox/Lark/Audit”演示链路；
- 安全、故障、恢复、负载、浏览器和 M6 Release Gate 证据。

M6 不包含飞书入站对话、消息驱动任务、任意文本发送、文件上传、群聊搜索、邮件通知、Plugin 市场、Team Agent 写操作、定时 Autopilot、PR 自动合并、生产发布、Kubernetes、多 Organization OIDC、跨区域容灾或月度线上 SLO。MVP 的 Team Agent 只做对话式查询与只读汇总；Lark 只做成员查询和平台批准的固定模板出站通知。

## 2. 产品闭环

```text
Domain Command 同事务提交业务事实、DomainEvent 与 Outbox
  -> Outbox Publisher 至少一次发布
  -> Activity / Inbox / Audit / Notification Intent 投影幂等消费
  -> Team Activity 与成员 Inbox 通过可恢复游标实时更新
  -> Team Observer 使用只读工具汇总进度、阻塞、Review 积压和风险
  -> 固定模板通知形成策略授权的 PlannedAction
  -> Notification Worker 使用短期 Lark 凭证投递并保存唯一 Receipt
  -> 失败按有界退避重试，最终进入失败 Inbox 与人工再次投递
  -> 成员从 Activity、Inbox、Conversation、Task、PR 和 Audit 双向定位同一事实
```

Conversation Mode 提供 Team Observer 对话入口和 Inbox/Activity 卡片；Control Mode 提供 Team Activity、我的 Inbox、Audit Explorer、Lark 设置、通知投递与运行健康管理页。两个入口读取同一服务端投影，不在浏览器扫描业务列表反向聚合团队事实。

## 3. 核心决策

### 3.1 Activity、Inbox 与 Audit

- `DomainEvent` 是业务真相，`ActivityEvent`、Inbox 来源、通知意图和 Audit 查询视图均为可重建投影；
- Activity Payload 使用按 EventType/SchemaVersion 注册的公开白名单，不把原始 DomainEvent Payload 直接返回浏览器或模型；
- Inbox 的“来源仍需处理”是投影事实，成员的 `READ/ACTED/ARCHIVED` 是独立权威处置事实；影子重建只能替换来源投影，不能清除成员处置状态；
- Inbox 唯一键由 `organization + member + itemType + sourceType + sourceId + sourceRevision` 构成；责任释放、Review 失效或 Action 终结通过新事实关闭旧项，不物理删除历史；
- AuditEvent 继续只追加；M6 增加授权查询、脱敏投影和导出上限，不把 Audit 变成可编辑业务对象。

### 3.2 投影重建与实时游标

- 投影消费继续使用 `consumerName + eventId` 幂等回执，并在同一事务更新投影与 Checkpoint；
- 重建使用新的 Projection Generation 从 DomainEvent 起点构建影子表，完成数量、Hash、版本缺口和抽样校验后原子切换当前代际；禁止先清空在线投影再重放；
- Team Activity 使用 Team 内单调 `teamSequence`，WorkItem 过滤允许序号空洞；Cursor 绑定 Organization、Team、Projection Generation、投影版本和过滤条件并完成签名；代际切换后旧 Cursor 返回稳定过期错误并要求刷新快照；
- Team Event、Conversation Event 与 AG-UI 是三条不同一致性边界，各自保存 Cursor。前端按 Event ID 去重、按 `occurredAt + eventId` 显示，不把三条流伪装成一个全局事务顺序；
- 断线恢复先拉取每条流各自的缺口，再进入实时订阅；Cursor 过期返回稳定错误并触发有界快照刷新。

### 3.3 飞书通知是受策略控制的外部动作

- Lark Connection 和成员映射由管理员显式验证；禁止按姓名、展示名或模糊邮箱自动绑定成员；
- 通知只使用版本化固定模板和结构化变量白名单，Agent、成员输入和 DomainEvent 文本不能成为任意消息正文；
- 每次出站通知创建 `NOTIFY_COLLABORATION` PlannedAction，保存 TemplateVersion、Recipient Mapping、ProviderBinding、Policy、变量 Hash 和去重键；
- MVP 通知使用 `POLICY_PREAUTHORIZED` 授权模式：当前 Team Policy、成员通知偏好、有效 ProviderBinding 和固定模板共同形成不可变 Authorization Snapshot，不要求每条消息人工确认；
- `POLICY_PREAUTHORIZED` 只适用于固定模板的 `NOTIFY_COLLABORATION`；GitHub Push、Draft PR 和其他 M5 Action 继续要求原有成员 Gate 与精确 Confirmation，通知扩展不能改变其构造、恢复或授权不变量；
- 任一模板、收件人、Binding、策略或变量变化都会产生新 Digest；Worker 只执行精确 Digest，使用短期凭证并保存唯一逻辑 Receipt；
- 限流、超时和响应丢失进入查询或幂等重试协议；达到上限后进入 `FAILED_FINAL` 与成员/管理员失败 Inbox，人工再次投递创建新命令而不是改写历史。

### 3.4 Team Observer

- M6 创建默认 Team Service Principal、`team-observer@1` 和每 Team 唯一 TEAM-owned AgentProfile；已有完整 Team 使用确定性迁移补齐。迁移只创建 `DISABLED` Profile，不猜测 ModelConnection 或 Configuration；管理员完成有效 TEAM Binding 和 Preflight 后，专用运行时在首次安全调用时完成就绪激活，新 Team 遵循同一规则；
- Team Observer 只使用团队模型连接以及 `team.activity.read`、`team.inbox.summary.read`、`workitem.summary.read`、`task.summary.read`、`artifact.summary.read` 五类只读工具；
- 工具在每次调用时复验当前成员可见性和 Team Scope，返回脱敏、有界、分页的摘要，不返回 Prompt、Credential、原始 Audit Payload、命令输出或私有成员事实；
- Structured Output 只包含进度、阻塞、Review 积压、待确认、异常和证据链接；不能创建 WorkItem、分配责任、执行 Task、提交 Gate、确认 Action 或发送通知；
- Team Observer 同时服务 Conversation Mode 的对话式问答和 Control Mode 的只读团队摘要，两处共享同一 AgentProfile、模型配置和投影事实。

### 3.5 可观测与发布

- Trace 使用 Correlation/Causation 链关联 Conversation、Task、AgentRun、Review、Action、Outbox、Projection、Notification 和 Provider；
- Organization、Team、WorkItem、Task、Member、Correlation、Event 和 Provider 外部 ID 只进入受控 Trace/日志字段，不能成为 Prometheus 标签；
- 指标按 outcome、type、status、providerKey、projectionName、workerRole 等受控枚举聚合；
- 部署镜像使用非 Root、只读根文件系统和外部 Secret 注入；Compose 只用于单机 Team Beta，不宣称 Kubernetes 高可用；
- 发布前负载门禁验证 P95 投影和 READY Claim 延迟小于 2 秒；月度可用性继续作为上线后 SLO。

## 4. 依赖顺序

```text
M6-S01 -> M6-D01,D02,D06,D07 -> M6-D08 -> M6-E01..E04 -> M6-I01,I02
M6-S02 -> M6-D01,D07 -> M6-E05 -> M6-A01 -> M6-F01,F02
M6-S03 + S04 -> M6-D03,D04 -> M6-D08,D09 -> M6-E04 -> M6-I03..I06
M6-D05 -> M6-D09 -> M6-I07 -> M6-A05 -> M6-F06
M6-S05 -> M6-I08..I10 -> M6-A06,A07 -> M6-F07

M6-I01,I02 + M6-E01..E07 -> M6-A01..A03,A06
M6-I03..I06 -> M6-A04 -> M6-F05
M6-A01..A04 -> M6-F01..F05
M6-A05 -> M6-F06
M6-A06,A07 -> M6-F07
M6-F02..F07 -> M6-F08

权限与披露边界完成 -> M6-Q01
恢复、重建与外部投递完成 -> M6-Q02
完整运行链路完成 -> M6-Q03
全部能力 -> M6-Q04
```

## 5. Spike 与架构验证

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-S01` | SPIKE | M5-Q04 | infrastructure/application | 已完成：对照现有 Outbox Publisher、Consumer Receipt、CheckpointedProjectionRunner 与 Audit 投影，冻结多 Aggregate 投影顺序、版本缺口、影子 Generation、原子切换和重建恢复协议 | [ADR-020](../adr/ADR-020-投影代际重建与游标协议.md) 与 [M6-S01 验证记录](../spikes/M6-S01-投影代际与影子重建验证记录.md)；6 个 PostgreSQL/Testcontainers 场景覆盖双写/重启收敛、版本缺口、事务回滚、失败构建、成员处置、原子切换、Cursor、Fencing 和校验失效 |
| `M6-S02` | SPIKE | M2-I06,M2-A03,M3-A03,M5-A08 | application/server/web | 已完成：冻结 Team Event、Conversation Event 与 AG-UI 独立恢复坐标、快照补发、过期恢复、Scope Epoch 和前端合并去重协议 | [ADR-021](../adr/ADR-021-三流恢复与前端合并协议.md) 与 [M6-S02 验证记录](../spikes/M6-S02-三流Cursor与Scope恢复验证记录.md)；6 个可控 SSE 场景覆盖独立断线补发、AG-UI Segment 重放、Team Generation 过期快照、旧 Scope 迟到帧、无全局顺序合并和失败关闭 |
| `M6-S03` | SPIKE | M5-D08,D09 | domain/application | 已完成：冻结 Inbox 来源与成员处置分离、固定模板通知意图、`POLICY_PREAUTHORIZED` Authorization Snapshot、PlannedAction/Receipt 和失败再次投递领域协议；S04 已补齐 Lark 外部适配并接受 ADR | [ADR-022](../adr/ADR-022-Inbox与固定模板通知授权协议.md) 与 [M6-S03 验证记录](../spikes/M6-S03-Inbox与固定模板通知授权验证记录.md)；7 个场景覆盖重建保留处置、来源/投递去重、授权漂移、模板失败关闭、M5 Confirmation 隔离和再次投递历史不可变 |
| `M6-S04` | SPIKE | ADR-004,ADR-006 | integration/infrastructure | 已完成：使用 Loopback Lark OpenAPI 冻结 Tenant Token、精确 Tenant/Open ID 成员映射、固定模板消息、限流、响应丢失、Provider UUID、Message ID 查询和安全错误协议，并完成通知授权 ADR | [ADR-022](../adr/ADR-022-Inbox与固定模板通知授权协议.md) 与 [M6-S04 验证记录](../spikes/M6-S04-Lark-OpenAPI与通知投递验证记录.md)；6 个场景覆盖 Token 隔离/刷新、精确映射、Timeout 唯一消息、429/5xx、撤权/模板失败关闭、Endpoint/错误脱敏 |
| `M6-S05` | SPIKE | M3-Q03,M4-Q04,M5-Q04 | server/infrastructure/ci | 已完成：冻结单机 Team Beta 部署拓扑、OTel/Prometheus 字段、低基数约束、固定负载与 nearest-rank P95、三组件备份恢复和三层 Release Gate 环境 | [ADR-023](../adr/ADR-023-Team-Beta单机部署与发布验证协议.md) 与 [M6-S05 验证记录](../spikes/M6-S05-Team-Beta部署与发布验证记录.md)；6 个场景覆盖七服务拓扑、环境 Fingerprint、Series 预算、三轮负载、故障门槛、Hash 恢复和 Required Step 零跳过 |

## 6. 领域、迁移与持久化契约

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-D01` | TASK | S01,S02 | domain/application | 已完成：实现稳定 ActivityEvent、ActivityVisibilityPolicy、版本化公开 Payload Schema、类型化 Subject/Actor/Reference、TeamSequence、ProjectionName/Generation、规范 Filter Fingerprint、完整 Cursor Scope、Team/WorkItem 共用 Query Port 与严格升序 Page | [M6-D01 Activity 领域与 Cursor Scope 契约](../testing/M6-D01-Activity领域与Cursor-Scope契约.md)；12 个新增测试覆盖 Payload 白名单、可见性、Schema 演进、同 Team 单调序号、跨 Team/Organization/Generation/Schema/Filter 拒绝、Cursor Scope 篡改、共享事件身份和历史重建恢复 |
| `M6-D02` | TASK | S03,D01 | domain/application | 已完成：实现五类 InboxSource/InboxItem、稳定 SourceKey/Revision/Item ID、类型/优先级/截止时间、关闭原因、Generation 外 InboxDisposition、服务端合并 View 与成员 READ/ACTED/ARCHIVED 强 ETag 命令 | [M6-D02 Inbox 来源与成员处置契约](../testing/M6-D02-Inbox来源与成员处置契约.md)；9 个新增测试覆盖重复来源、责任替换、Review 失效、Confirmation 终结、异常恢复、跨代重建保留处置、陈旧 ETag 和跨成员拒绝 |
| `M6-D03` | TASK | S03,M5-D08,D09 | domain/application | 已完成：实现 NotificationTemplate/Version、精确变量 Schema、Preference、Inbox Intent、全量 AuthorizationSnapshot、`NOTIFY_COLLABORATION` 参数、独立 PlannedAction、Delivery/Receipt 状态机、授权漂移失效、自动去重和强版本再次投递命令；M5 GitHub Action 继续使用 Gate 与 Human Confirmation | [M6-D03 固定模板通知与再次投递契约](../testing/M6-D03-固定模板通知与再次投递契约.md)；9 个新增测试及 43 个联合回归覆盖任意文本、未知/旧模板、全坐标漂移、重复计划、Timeout/UNKNOWN、最终失败、历史不可变、再次投递和 M5 Confirmation 边界 |
| `M6-D04` | TASK | S04,ADR-004,ADR-006 | domain/application | 已完成：实现类型化 Lark 外部身份、版本化 ExternalTenant、短期精确 Proof、管理员确认的 MemberMapping、双唯一 Repository Port、发送前重新授权的 CollaborationRecipient 和拆分后的 Connection 能力 | [M6-D04 Lark 外部身份与成员映射契约](../testing/M6-D04-Lark外部身份与成员映射契约.md)；12 个新增测试覆盖精确 Open ID、Tenant/User 双唯一、Organization 隔离、跨 Scope/版本漂移、Provider Version 刷新重新确认、迟到 Proof、映射撤销、Connection 撤销和敏感字段边界 |
| `M6-D05` | TASK | M5-D01..D05 | domain/application | 已完成：定义固定 `team-observer@1`、每 Team 确定性 Service Principal/TEAM-owned Profile、默认禁用与配置 Preflight 启用门禁、TeamSummaryRequest/Result、五类只读 Tool 与数据范围不变量 | [M6-D05 Team Observer 领域与启用契约](../testing/M6-D05-Team-Observer领域与启用契约.md)；12 个专项测试与 1 个通用入口联合回归覆盖每 Team 唯一、未配置不可启用、TEAM Model Binding、只读 Tool 精确集合、结构化摘要、成员可见性、零写权限、通用入口防绕过和迁移恢复 |
| `M6-D06` | TASK | S01,M0-D06 | domain/application | 已完成：实现追加写 AuditQueryEvent、14 类 EventCategory、Outcome、Initiator/Actor/Agent、Subject/Provider/Correlation 引用、Schema 白名单脱敏摘要、保留级别、组合 Filter、绑定 Scope Cursor、当前权限复验和有界导出 | [M6-D06 Audit 查询与有界导出契约](../testing/M6-D06-Audit查询与有界导出契约.md)；14 个新增测试覆盖只追加读边界、未知 Payload 失败关闭、Secret/PII/URL 拒绝、Team Admin/平台管理员权限、PostgreSQL UUID Keyset、31 天与 10,000 行导出上限 |
| `M6-D07` | TASK | S01 | domain/application | 已完成：实现 ProjectionDefinition、Generation/Pointer/RebuildJob、ValidationResult、切换状态机、Fencing Lease、Checkpoint/DeadLetter 引用和管理员强确认/幂等/强版本命令不变量 | [M6-D07 投影代际重建与管理员命令契约](../testing/M6-D07-投影代际重建与管理员命令契约.md)；13 个专项测试覆盖单活/单影子、失败不可切换、校验失效、旧 Worker 禁写、取消、重试、版本冲突、越权零写和 Audit/DomainEvent 安全形状 |
| `M6-D08` | TASK | D01..D04,D06,D07 | infrastructure | 已完成：新增 `V27__activity_inbox_notification.sql`，落地 Generation-aware Activity/Inbox/Notification、独立 InboxDisposition、Projection Definition/Generation/Pointer/Rebuild/Validation/Receipt/Checkpoint/DeadLetter/CommandReceipt，并扩展 Audit 分类、保留级别、Provider 安全引用、Keyset 索引与追加写保护；保留旧 Checkpoint 并回填 Generation 1 供滚动升级 | [M6-D08 Activity、Inbox、Notification 与投影代际迁移契约](../testing/M6-D08-Activity-Inbox-Notification与投影代际迁移契约.md)；10 个迁移门禁和 32 个关联回归覆盖空库、V26→V27、非默认 Schema、Fencing、跨租户 FK、处置跨代保留、原子切换、通知回执、Audit 只追加及旧 Runner 兼容 |
| `M6-D09` | TASK | D04,D05,D08 | infrastructure | 已完成：新增 `V28__lark_mapping_and_team_observer.sql`，落地版本化 Lark ExternalTenant、短期 Proof、双唯一 MemberMapping、历史保护和通知 Mapping 兼容外键；为完整初始化的既有 ACTIVE Team 确定性补齐 Organization `team-observer@1`、`DISABLED` TEAM_AGENT Principal/Profile，在所有 Organization 全局预检内置模板坐标，且不生成 ModelConnection/Configuration | [M6-D09 Lark 成员映射与 Team Observer 迁移契约](../testing/M6-D09-Lark成员映射与Team-Observer迁移契约.md)；5 个专项测试覆盖空库、V27→V28、非默认 Schema、重复迁移、Java/SQL 稳定 ID/Hash、部分 Team、双唯一、跨 Scope、历史保护、零模型配置、无候选 Team 组织的保留坐标冲突和事务回滚 |

## 7. 事件、Outbox 与投影

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-E01` | FEATURE | S01,D07,D08 | infrastructure/server | 已完成：保留 Outbox `SKIP LOCKED`/Claim 过期/旧 Token 边界，实现持久化 Registry 动态路由、ACTIVE 优先的 Generation 独立事务、Receipt/Checkpoint/Fencing、Definition Version 写前复验、有界 Keyset 历史重放、规范快照验证和 Pointer-first 原子切换；旧 Checkpoint Runner 保持滚动升级兼容 | [M6-E01 Generation-aware 投影运行时与原子切换](../testing/M6-E01-Generation-aware投影运行时与原子切换.md)；6 个专项与 34 个联合回归覆盖多实例 Claim、过期接管、在线先提交、重复 Receipt、Definition 错配拒绝、缺口/失败回滚、旧 Fencing、切换竞态和进程重启 |
| `M6-E02` | FEATURE | D01,D08,E01 | application/infrastructure | 已完成：实现按 `EventType + SchemaVersion` 精确匹配的 35 个事件类型、40 个安全 Schema 坐标 Registry，以及 Generation-aware `team-activity` Projector；Task V1/V2 均显式评审，公开 Payload 只提取白名单标量，未知事件和有效非 Team Provider 事件安全忽略，已注册损坏事件失败关闭；首事件原子引导 Definition/Generation/Pointer，同 Team 跨 Aggregate 写入串行分配序号，历史与在线代际使用同源规范 Hash | [M6-E02 安全 Activity EventType Registry 与 Projector](../testing/M6-E02-安全Activity-EventType-Registry与Projector.md)；13 个专项测试覆盖公开 Payload、敏感字段排除、Task V2、精确 Schema、重复事件、同/跨 Aggregate 顺序、首事件引导、非 Team Provider、未知事件、事务回滚和影子重建 Hash |
| `M6-E03` | FEATURE | D02,D08,E01 | application/infrastructure | 已完成：实现按 `EventType + SchemaVersion` 精确匹配的 16 个事件坐标 Registry 和 Generation-aware `member-inbox` Projector；按当前责任、冻结 Reviewer/Confirmer 和成员资格生成/关闭五类来源，迟到打开事件服从当前权威终态；当前 Pointer 来源与 Generation 外 Disposition 服务端合并，无处置返回 `UNREAD@0`，影子重建与切换保留稳定 Item ID 和成员状态 | [M6-E03 Inbox Projector 与 Disposition 合并查询](../testing/M6-E03-Inbox-Projector与Disposition合并查询.md)；9 个专项测试覆盖 Owner/Executor/Reviewer/Confirmer/Task 与 Action 异常、离队、职责释放、旧 Review/Action、防重复、成功零噪声、重建切换和 `READ@1` 保留 |
| `M6-E04` | FEATURE | D03,D04,D08,E01,E03 | application/infrastructure | 已完成：实现与 `member-inbox` 共代际的 Notification Intent Projector，按五类固定策略、精确模板与变量、成员偏好、DND 和 Lark Mapping/Binding/Connection/Grant/Tenant 当前授权产生策略授权 PlannedAction；影子代际只构建 Intent，Pointer 切换后计划；最终失败生成 Inbox，成功再次投递关闭，通知失败来源禁止递归 | [M6-E04 固定模板通知意图投影与失败 Inbox 闭环](../testing/M6-E04-固定模板通知意图投影与失败Inbox闭环.md)；7 个专项测试覆盖相同来源零重复、DND、映射缺失/撤销、完整授权漂移、不可变模板升级、影子切换、失败闭环和投影回环防护 |
| `M6-E05` | FEATURE | S02,D01,E01,E02 | application/server | 已完成：冻结 Team Realtime Event Store 的同读快照/高水位/缺口契约，实现版本化 Base64URL + HMAC-SHA256 Cursor、带 Key ID 的有界轮换、时间与完整 Scope 校验，以及首批预读、逐页串行补发、空轮询合并、无位置心跳、代际/保留过期和单连接独立 Position；Conversation/AG-UI Cursor 保持独立 | [M6-E05 Team Realtime Event Store 与签名 Cursor/SSE 恢复](../testing/M6-E05-Team-Realtime-Event-Store与签名Cursor-SSE恢复.md)；14 个专项测试覆盖双连接、断线、批量缺口、慢消费者、同微秒顺序、心跳、快照竞态、Cursor 篡改/过期、Scope 切换、Key 轮换和 fail-closed 装配 |
| `M6-E06` | TASK | D06,E01,E02..E04 | infrastructure | 已完成：建立 96 个 `EventType + SchemaVersion` 精确坐标的 M0–M6 Audit Registry；扩展追加写 Projector，映射 Category/Outcome/Retention、Initiator/Actor/Agent、Correlation/Causation 和 Provider 安全引用，只保存白名单低基数摘要；已注册未知字段失败回滚，未注册事件保留 `SYSTEM/STANDARD` 事实且原始 Payload 零复制；Legacy 追加写行保持不可变并以空摘要参与规范校验；提供 DomainEvent 历史与 Audit 当前行规范 Count/SHA-256 校验 | [M6-E06 安全 Audit Registry 与追加写 Projector](../testing/M6-E06-安全Audit-Registry与追加写Projector.md)；24 个专项/回归测试覆盖精确坐标、失败 Outcome、身份链、Provider Hash、未知字段、Secret/PII 探针、未注册事件、Legacy 空摘要、追加写回归和规范一致性 |
| `M6-E07` | TASK | E01..E06 | application/server | 已完成：建立 Projection、Outbox、DeadLetter、Cursor、Notification 五组件健康契约，成员只见枚举状态和聚合计数，管理员额外获得 Generation/Rebuild/有界错误与闭集恢复坐标；Outbox/Projection DeadLetter 和 Notification 恢复绑定 Expected Version、强确认、Command ID、语义 SHA-256 与原子 Audit/Receipt 契约；提供低基数 Metric Sample、配置阈值和 I01/I02 Port 条件装配 | [M6-E07 运行健康诊断与受审计恢复命令](../testing/M6-E07-运行健康诊断与受审计恢复命令.md)；18 个专项测试覆盖成员/管理员分层、跨 Scope/未来时间、强确认、精确回放、语义复用、并发冲突、98 项 Audit Registry、指标字段和 Spring 失败关闭 |

## 8. 基础设施、Provider 与运行平台

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-I01` | TASK | D08,E02..E07 | infrastructure | 已完成：实现 Activity Query/TeamRealtimeEventStore、Audit Query/有界导出、NotificationPlanRepository 和 OperationsHealthQueryPort 的 PostgreSQL Adapter；复用当前代际 Inbox 与 Generation 外 Disposition Adapter；Activity/Audit 使用稳定 Keyset，Notification 持久化重建复验授权/动作/投递 Digest 与确定性 ID，Operations 以固定查询集合聚合 Generation/Rebuild/DeadLetter 安全坐标 | [M6-I01 PostgreSQL 查询 Adapter 与 Keyset](../testing/M6-I01-PostgreSQL查询Adapter与Keyset.md)；10 个真实 PostgreSQL 专项测试及既有 Inbox 联合回归覆盖稳定 Cursor、并发去重/CAS、代际隔离、跨租户、Digest 篡改失败关闭和索引执行计划 |
| `M6-I02` | TASK | E01,E07,I01 | infrastructure/server | 已完成：实现 ProjectionAdministrationRepository、OperationsRecoveryRepository、Projection Supervisor、Startup Recovery、周期调度、Retention/Cleanup 与安全 Operations Port；管理员状态变更、CommandReceipt、DomainEvent、Outbox 和 Audit 原子提交，在线代际和影子代际使用独立 Worker Claim；通知恢复只生成新调度事实，外部投递留给 I03 | [M6-I02 投影管理、Supervisor 与受审计恢复](../testing/M6-I02-投影管理Supervisor与受审计恢复.md)；14 个专项测试覆盖真实 PostgreSQL 完整管理生命周期、Receipt 回放、三类恢复、并发收敛、历史不可变、多实例接管、Startup Recovery、旧 Fencing、Cursor、清理保护、Actuator/Spring 条件装配和低基数摘要 |
| `M6-I03` | FEATURE | D03,E04,I01 | application/infrastructure | 已完成：实现 Provider 无关的 Notification 写 Worker与独立查询恢复 Worker、PostgreSQL Claim/Lease/单调 Fencing、动作级短期 Credential Handle、稳定 Provider UUID、发送/查询归一化结果、有界指数退避、失败终结、确定性唯一 Receipt 和受审计人工再次投递调度消费；过期 RUNNING 先持久化 UNKNOWN 再查询，旧 Worker 必须同时匹配 Organization/Version/Worker/Token/未过期 Lease 才能回写 | [M6-I03 Notification Worker 与查询恢复](../testing/M6-I03-Notification-Worker与查询恢复.md)；10 个 M6-I03 专项测试及 5 个 I01 联合回归覆盖事务提交前零调用、重复调度零重复消息、响应丢失查询恢复、并发 Claim、过期 Lease 接管、旧 Worker 零回写、唯一逻辑 Receipt、人工再次投递、配置边界和 Spring 失败关闭 |
| `M6-I04` | FEATURE | S04,D04 | integration/server | 已完成：实现无通用 URL/Method/Body 入口的固定操作 Lark OpenAPI Client；每次调用复验 Connection/Grant/Credential 与动作能力，Tenant Token Cache Key 闭合 Organization、Connection/Grant/Credential/Secret Version 和 Tenant，按 Key Single Flight、有界容量与 60 秒安全余量；401 精确失效并最多刷新一次，429/5xx 交给 I03 Worker，Credential Handle 与响应 Buffer 及时清理，Spring 固定生产 Origin 并仅显式允许字面量 Loopback | [M6-I04 Lark Connector 与 Tenant Token 安全缓存](../testing/M6-I04-Lark-Connector与Tenant-Token安全缓存.md)；16 个 I04/S04/Spring 测试覆盖 Tenant 隔离、并发 Single Flight、两次 401、撤权/轮换、能力、固定成员/消息操作、403/404/429/5xx、读写超时、取消、非法/超大响应、SSRF、脱敏和配置失败关闭 |
| `M6-I05` | FEATURE | D04,I04 | integration/application | 已完成：实现 `LarkCollaborationProvider` 固定 Tenant/精确 `open_id` 查询、ADR-006 当前授权 Preflight、安全健康状态、`PROVIDER_MANAGE` 管理员映射验证、稳定 Keyset 分页、双唯一 PostgreSQL Adapter 和 Spring 条件装配；Token Cache 命中后仍复验 Connection/Grant/Credential | [M6-I05 Lark Collaboration Provider 与映射 Preflight](../testing/M6-I05-Lark-Collaboration-Provider与映射Preflight.md)；26 个专项及联合回归覆盖 Tenant/User 精确身份、分页、429、映射冲突、Owner/Scope、撤权、缓存后再校验、数据库和 Provider 契约 |
| `M6-I06` | FEATURE | D03,I03..I05 | integration/application | 已完成：实现当前发布版本复验的五类固定模板渲染、Claim 绑定短期 Lark Credential、当前 Member/Mapping/Tenant/Binding/Connection/Grant 写前复验、32 位稳定 UUID 投递、同 UUID 响应丢失恢复、精确 Message ID 确认和单调 Receipt Observation 合并；任意正文入口与飞书入站保持关闭 | [M6-I06 固定模板 Lark 投递与 Receipt 恢复](../testing/M6-I06-固定模板Lark投递与Receipt恢复.md)；专项及联合回归覆盖模板/变量 Hash、双层 JSON 转义、响应丢失、重复请求、授权漂移外部零写、观察时间乱序、Receipt 身份冲突、唯一逻辑 Receipt 和任意文本拒绝 |
| `M6-I07` | FEATURE | D05,D09,E02,E03,I01 | agentscope/application | 已完成：实现精确 `team-observer@1` Runtime Registry、Credential 打开前 TEAM Model Factory、五个参数封闭只读 Tool、逐次成员复验、最小 Context、冻结 Structured Output、调用级证据目录、Team/成员/Session 状态隔离和安全 Evidence Path 选择 | [M6-I07 Team Observer AgentScope 只读运行时](../testing/M6-I07-Team-Observer-AgentScope只读运行时.md)；真实 Harness Loopback 与攻击测试覆盖五段摘要、写 Tool 缺失、跨 Team/成员、私有事实、Prompt 注入、虚构 Evidence、撤权竞态和超限查询拒绝 |
| `M6-I08` | TASK | S05,E07,I02,I03,I07 | infrastructure/server | 已完成：建立强类型低基数 `OperationalTelemetry` 端口，为 Outbox、Projection、SSE、Inbox、Notification、Lark 和 Team Observer 提供 OTel Span、三项内部 Baggage 白名单、`crewscope.m6.*` Prometheus 指标与全局结构化日志脱敏；未申明/动态标签失败关闭，观测后端失效时业务无损降级 | [M6-I08 OTel、Prometheus 与日志安全](../testing/M6-I08-OTel-Prometheus与日志安全.md)；55 个专项与联合回归覆盖七类边界、低基数标签扫描、Secret/PII JSON 快照、Operations Health Gauge、Actuator 授权和 Collector/Registry 故障降级 |
| `M6-I09` | FEATURE | S05,I02,I03,I08 | server/infrastructure | 已完成：交付后端与 Web 多阶段不可变镜像、API/Worker 角色分离、七服务 Compose、内部网络、外部 Config Tree Secret、API 独占 Flyway、Readiness 启动依赖、数据卷和一键 Demo；三个应用容器非 Root、只读 RootFS、Drop ALL Capability，Docker Socket 仅属于 Worker；AgentState 共享而执行所有权按 `server/worker` Scope 隔离，Team Beta 配置与空库引导失败关闭 | [M6-I09 生产镜像与 Team Beta 部署](../testing/M6-I09-生产镜像与Team-Beta部署.md)；静态合同、Spring 角色/空库契约、正式镜像七服务实际 Healthy、V1→V30、API/Worker 重启恢复、只读文件系统、UID/GID、Socket 隔离、配置缺失和镜像扫描 |
| `M6-I10` | TASK | D08,D09,I09 | infrastructure/docs | 已完成：实现 Maintenance/零活动、PostgreSQL Custom Dump、完整 Artifact、Redis RDB、Manifest/Envelope、整体加密、Environment Fingerprint、Key ID、Retention、空目标恢复、Artifact URI 重定位、V26–V30 到 V30 边界、RPO/RTO Evidence 和单机 Runbook | [M6-I10 备份恢复与 Runbook](../testing/M6-I10-Team-Beta备份恢复与Runbook.md)；合同门禁、V30→V30、V26→V30、坏包、非空目标、Artifact/Redis/Organization/Principal 与实际 RPO/RTO |

## 9. 应用、API 与 Team Observer

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-A01` | FEATURE | E02,E05,I01 | application/server | 已完成：交付 Team/WorkItem Activity Keyset 历史、当前代际详情、双 Cursor 快照和 Team SSE API；签名 Cursor 绑定 Organization/Team/Projection/Generation/Schema/Filter，SSE 逐帧与心跳复验当前成员及角色，隐藏事件仅推进服务端位置 | [M6-A01 Team 与 WorkItem Activity API](../testing/M6-A01-Team与WorkItem-Activity-API.md)；专项与 M6-E05/M6-I01 联合回归覆盖成员资格、WorkItem 可见性、稳定分页、断线补发、Cursor 篡改/过期/旧 Scope、当前 Generation 与安全 DTO |
| `M6-A02` | FEATURE | E03,I01 | application/server | 已完成：交付“我的 Inbox”五类过滤与稳定 Keyset、OPEN 非归档总数/未读数、当前代际详情、READ/ACTED/ARCHIVED 强 ETag 命令、Idempotency-Key Receipt 回放和服务端固定模板来源跳转；Cursor 绑定 Organization/Team/Generation/Filter，跳转按当前 Member 与 WorkItem 权限复验 | [M6-A02 我的 Inbox API](../testing/M6-A02-我的Inbox-API.md)；12 个应用/HTTP/Spring 专项及 8 个真实 PostgreSQL 联合回归覆盖成员隔离、公开 DTO、强 ETag、回放、分页、计数、来源解析、终结、离队、重建处置保留和旧 Cursor 失效 |
| `M6-A03` | FEATURE | D06,E06,I01 | application/server | 已完成：交付 Team Admin Audit Explorer，支持时间、Category、Outcome、Initiator、Actor、Agent、Subject、ProviderBinding 和 Correlation 组合过滤；HMAC Cursor 绑定 Organization/Team/Filter 并使用稳定 Keyset；治理导出限定 31 天与 10,000 行；公开 DTO 使用白名单；查询与导出追加安全自身 Audit | [M6-A03 Team Admin Audit Explorer](../testing/M6-A03-Team-Admin-Audit-Explorer.md)；16 个新增应用/HTTP/Cursor/Spring/PostgreSQL 测试及 Registry/I01 联合回归覆盖授权、组合过滤、跨 Scope、篡改、轮换、导出上限、脱敏、自身 Audit 和安全错误 |
| `M6-A04` | FEATURE | I03..I06 | application/server | 已完成：交付 Team-scoped Lark Connection/Credential/Grant/Binding 生命周期、Preflight/Health、精确成员验证与映射、固定模板/偏好、通知投递历史与失败再次投递 API；状态命令使用强 ETag、Idempotency-Key 和 Receipt，两类 HMAC Cursor 绑定 Scope/Filter，DTO 使用安全白名单 | [M6-A04 Lark 与 Notification 管理 API](../testing/M6-A04-Lark与Notification管理API.md)；专项测试覆盖 Cursor Round Trip、篡改、跨 Team、Filter Replay 和 Key 轮换，M6-I03 至 I06 回归覆盖授权、映射冲突、DND、模板、投递与恢复内核 |
| `M6-A05` | FEATURE | I07,M2-A03 | application/server | 已完成：提供当前成员绑定的专用 Team Observer Session、TEAM-only AgentScope 模型装配、四类安全 SSE、同 Invocation Resume、显式取消、五段摘要、证据持续授权和五类白名单投影；复用 Conversation Mode 交互语义且不伪造 Personal Conversation | [M6-A05 Team Observer 对话与摘要 API](../testing/M6-A05-Team-Observer对话与摘要API.md)；专项与 I07 回归覆盖当前成员、TEAM 模型连接、Session 隔离、Resume、取消、Structured Output、写命令拒绝、引用持续授权、Prompt 攻击和安全投影 |
| `M6-A06` | FEATURE | E07,I02,I08 | application/server | 已完成：提供成员五组件低基数运行健康摘要、管理员 Projection 强版本诊断和三类恢复坐标；交付 Outbox/Projection DeadLetter 重放、Notification 再次投递以及 Start/Retry/Validate/Switch/Cancel/Fail 固定 Projection 命令 API；危险命令使用精确强确认、稳定 Idempotency Command UUID 和既有原子 Audit | [M6-A06 运行健康与 Projection 管理 API](../testing/M6-A06-运行健康与Projection管理API.md)；专项与 D07/E07/I01/I02/I08 回归覆盖成员/管理员分层、安全 DTO、三类恢复、精确 Generation/版本、旧版本、并发重建、验证失败、命令回放、失败关闭和 Actuator 装配 |
| `M6-A07` | TASK | A01..A06,M5-A08 | application/server | 已完成：交付跨 Conversation、WorkItem、Task、Review、Action、PR、Activity、Inbox、Notification 与 Audit 的 Correlation 安全查询；HMAC Cursor 绑定 Organization/Team/Correlation/Keyset，事件与对象提供双向站内链接；固定两查询预算，Inbox/Notification 限当前成员；Task Timeline 在 SQL 层应用事件类型和 Payload 双白名单 | [M6-A07 Correlation 查询与 Task Timeline 白名单](../testing/M6-A07-Correlation查询与Task-Timeline白名单.md)；专项与真实 PostgreSQL 回归覆盖同链分页、对象双向链接、N+1 上限、持续授权、未知事件不公开、当前 Generation 和敏感字段零泄漏 |

## 10. 前端工作台

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-F01` | TASK | A01..A04,A06 | web | 已完成：建立 Activity、Inbox、Audit、Lark/Notification、Projection Health Gateway、显式公开 DTO、Organization+Team Scope Generation Store、资源级 Cursor 分页缓存、三流恢复存储和稳定错误契约；秘密与危险命令参数只进入单次调用栈 | [M6-F01 团队观测前端数据层](../testing/M6-F01-团队观测前端数据层.md)；13 个专项测试覆盖 DTO 白名单、请求代次、旧 Team 晚到响应、Cursor 恢复/过期、强 ETag、凭证不缓存与生产构建 |
| `M6-F02` | FEATURE | F01,A01,A07 | web | 已完成：交付 Team Activity Stream 独立路由、WorkItem Activity 嵌入入口、公开 Actor/Subject/Outcome/证据链接、筛选分页、事件详情和 Team SSE 耐久恢复；Scope 切换隔离旧流，重复事件去重，格式错误帧不推进 Cursor | [M6-F02 Team 与 WorkItem Activity UI](../testing/M6-F02-Team与WorkItem-Activity-UI.md)；Desktop/Narrow、Loading/Empty/Error/Forbidden/Offline/CursorExpired、重复事件、键盘、Axe、视觉、Vitest、Histoire 与 E2E 通过 |
| `M6-F03` | FEATURE | F01,A02 | web | 已完成：交付“我的 Inbox”独立路由、五类成员视图、服务端计数、优先级、截止时间、授权来源跳转和 READ/ACTED/ARCHIVED；成员由服务端解析，Cursor 按 InboxItem ID 去重，强 ETag 冲突显式回读 | [M6-F03 我的 Inbox UI](../testing/M6-F03-我的Inbox-UI.md)；成员隔离、筛选分页、重建保留处置、离线缓存、冲突重试、响应式、键盘、Axe、视觉、Histoire 与全量 E2E 通过 |
| `M6-F04` | FEATURE | F01,A03,A07 | web | 已完成：交付独立 `/audit` Team Admin Audit Explorer、时间/Category/Outcome/身份/Subject/Provider/Correlation 组合筛选、稳定分页、公开详情、Correlation 去重分页与服务端生成对象跳转、31 天/10,000 行有界 JSON 导出；查询与导出权限分层，原始 Payload 和敏感字段不进入浏览器 | [M6-F04 Team Admin Audit Explorer UI](../testing/M6-F04-Team-Admin-Audit-Explorer-UI.md)；DTO 闭集/脱敏、权限、分页、导出状态、Offline/CursorExpired、窄屏表格降级、键盘、Axe、双视口视觉、Vitest、Histoire 与全量 E2E 通过 |
| `M6-F05` | FEATURE | F01,A04 | web | 已完成：交付独立 Lark/Notification Team 管理页、Connection/Credential、Preflight/Health、精确成员验证与映射、固定模板偏好、DND、通知历史、安全 Receipt 和失败再次投递；Credential/Binding 强版本分离，Secret/open_id 单向输入，公开 DTO 闭集失败关闭 | [M6-F05 Lark 与 Notification 管理 UI](../testing/M6-F05-Lark与Notification管理UI.md)；映射确认、DND、失败重投、旧页冲突、Loading/Empty/Error/Forbidden/Offline/CursorExpired/Conflict、响应式、键盘、Axe、双视口视觉、Vitest、Histoire 与 E2E 通过 |
| `M6-F06` | FEATURE | A05,F01,F02,F03 | web | 已完成：在 Conversation Mode 交付独立 Team Observer 对话入口，在 Control Mode 交付只读团队摘要；共用 Scope 隔离的 Session/Invocation Store 与五段摘要组件；同 Invocation Resume、显式 Cancel、Prompt 纯文本化和持续授权 Evidence API 路由映射失败关闭 | [M6-F06 Team Observer 双入口 UI](../testing/M6-F06-Team-Observer双入口UI.md)；Agent 身份、只读说明、断线 Resume、Scope 晚到隔离、Prompt 攻击、双入口一致、Axe、双视口视觉、Vitest、Histoire 与 E2E 通过 |
| `M6-F07` | FEATURE | A06,A07,F01 | web | 已完成：交付独立 `/operations` 运行健康与 MVP 管理页、五组件低基数摘要、15 秒刷新、权限过滤的 Activity/Inbox/Observer/Audit/Lark 证据入口，以及管理员 Projection Active/Shadow Generation、Lag/Gap/DeadLetter、Start/Validate/Switch/Cancel/Fail 和三类 Recovery 管理 | [M6-F07 运行健康与 MVP 管理 UI](../testing/M6-F07-运行健康与MVP管理UI.md)；成员/管理员分层、DTO/Recovery Target 闭集、强版本/强确认、命令回读不自动重放、离线缓存、键盘焦点、Histoire、Axe、双视口视觉、Vitest 与 E2E 通过 |
| `M6-F08` | HARDENING | F02..F07 | web | 已完成：收口 Activity/Inbox/Audit/Lark/Observer/Operations 全状态、三流离线恢复、响应式、ARIA、Reduced Motion、Histoire、视觉、Axe 和敏感字段门禁；离线续页、缓存刷新、命令并发、跨 Scope 晚到与错误态重试缺口已修复 | [M6-F08 M6 前端全状态与质量门禁](../testing/M6-F08-M6前端全状态与质量门禁.md)；Vitest 426/426，Coverage 四项过线，14 Story/104 Variant，双视口 Playwright/视觉/Axe 180/180，敏感字段扫描通过 |

## 11. 测试、故障、负载与发布

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-Q01` | HARDENING | D01..D09,E01..E07,I01..I08,A01..A07,F01..F07 | all | 已完成：冻结 Activity/Inbox/Audit/Team Observer/Lark/Notification/Operations 110 个固定攻击样本，覆盖六类 Cursor、50 个公开 Projection、Evidence 路由、跨 Scope、Payload、Prompt、映射、模板、凭证、重建和运维命令，并扩展 TeamOps/TeamObserver Web 敏感字段门禁 | [M6-Q01 团队观测固定攻击集与安全加固](../testing/M6-Q01-Security-Hardening.md)；110/110 固定攻击阻断，Java 173/173、Web 83/83，Team Observer 写调用、Secret/PII/原始 Payload 泄漏和普通成员重建/重放命令均为 0 |
| `M6-Q02` | HARDENING | E01..E07,I01..I07,A01..A06 | all | 已完成：冻结 `FI-001` 至 `FI-121`，对 Outbox、Projection、SSE、Redis/Snapshot、Worker、Worktree、Model、GitHub、Lark、Notification 和数据库提交窗口执行协议矩阵及真实所有权边界故障回归 | [M6-Q02 固定故障与恢复攻击集](../testing/M6-Q02-Fault-Recovery.md)；121/121 收敛，自动恢复率 99.17%，重复 Action/Notification、处置状态丢失和旧 Fencing 写入均为 0，最终 UNKNOWN 唯一进入人工队列 |
| `M6-Q03` | HARDENING | S05,I06,I08..I10,A01..A07,F08 | all/performance | 已完成：Fixture、Linux amd64 Canonical 与受保护 Release Candidate 三轨门禁全部关闭；Canonical 完成 120 秒 Warmup、三轮各 600 秒生产负载、新备份空 Target 恢复和真实飞书固定模板投递 | [M6-Q03 固定负载、恢复与完整 MVP E2E](../testing/M6-Q03-Load-Recovery-MVP-E2E.md)；三轮各 `5,960` 请求，Claim P95 `12/11/11ms`、Activity `18/15/13ms`、Inbox `13/13/11ms`、错误率 0；恢复至 V30，RPO `26s`、RTO `71s`；Lark `SUCCEEDED`；macOS/Linux Playwright 均 `180/180` |
| `M6-Q04` | HARDENING | Q01,Q02,Q03 | all/docs/ci | 已完成：本机、Linux amd64 Release Candidate 与 GitHub Actions 权威门禁全部关闭；Canonical 非 root Runner 完成 7 模块 `clean verify`、Q01/Q02、冻结 Judge Pack、前后端镜像、Coverage、生产构建、Histoire 与生产依赖 Audit | [M6-Q04 MVP Release Gate](../testing/M6-Q04-MVP-Release-Gate.md)；Maven、Vitest `450/450`、Playwright `180/180`、14 Story/104 Variant、固定 Digest、OSV 与 Backend/Web Trivy 全部通过，最终 `release-gate=success`，MVP Release 决定为 `PASS` |

## 12. 纵向实施波次

| 波次 | 任务 | 可演示结果 |
|---|---|---|
| W0 契约验证 | S01–S05 | 投影重建、实时游标、通知授权、Lark 和发布协议冻结 |
| W1 团队事实 | D01–D02、D06–D08、E01–E03、E05–E07、I01–I02、A01–A03、F01–F04 | Team Activity、我的 Inbox 和 Audit Explorer 可用，重建与断线恢复闭环 |
| W2 飞书通知 | D03–D04、D08–D09、E04、I03–I06、A04、F05 | 固定模板通知通过 Lark 幂等投递，失败可查询和再次投递 |
| W3 Team Observer | D05、D09、I07、A05、F06 | 成员通过对话和控制台获得只读团队进度、阻塞与风险汇总 |
| W4 观测与发布 | I08–I10、A06–A07、F07–F08、Q01–Q04 | 运维、部署、安全、故障、负载、E2E 和 MVP Release Gate 全部关闭 |

前端不等待后端全部完成。每个波次先冻结 DTO、Cursor、事件、错误、权限与恢复契约，后端提供真实 API 或固定 Contract Fixture，前端在同一波次完成 Store、页面、对话入口、传统管理入口和自动化测试。

## 13. Release Gate

M6 完成需要同时满足：

1. Activity、Inbox、Audit 和 Notification 均由 DomainEvent/Outbox 投影产生，并可从规范事件重建；
2. 投影重建使用影子 Generation，失败或取消不能破坏在线代际；切换后旧 Worker 不能回写；
3. Inbox 来源事实与成员处置状态分离，重建后 READ/ACTED/ARCHIVED 保持不变；
4. Team/WorkItem Activity 和 Inbox 使用 Scope-bound 签名 Cursor，断线恢复不丢失、不重复展示事件；
5. Team Event、Conversation Event 和 AG-UI 各自保持 Cursor，不声称跨流全局事务顺序；
6. 成员只能查询自己的 Inbox 和有权访问的 Activity；Audit Explorer 和恢复命令只向 Team Admin/平台管理员开放；
7. Team Observer 使用每 Team 唯一 Service Principal 和只读 AgentProfile，只访问团队可见摘要，写工具数量为 0；
8. Team Observer 的 PERSONAL/USER Connection 使用数量为 0，运行固定 TEAM/ORGANIZATION Connection；
9. 迁移不会猜测 Team ModelConnection 或 Configuration；未完成 TEAM Binding 与 Preflight 的 Team Observer 保持 `DISABLED`；
10. Lark 成员映射由管理员使用同一 Organization/Team 与当前 Connection/Grant Proof 精确确认，跨 Scope/旧 Proof 和按姓名或模糊邮箱自动映射数量为 0；
11. Lark 只发送版本化固定模板，任意文本、原始 DomainEvent Payload 和 Agent 自由输出不能成为消息正文；
12. 通知策略预授权不能绕过 GitHub Push/Draft PR 原有成员 Gate 和精确 Confirmation；
13. 每次通知绑定精确 Template、Recipient、Binding、Policy、变量 Hash 和 PlannedAction Digest；重复调度只产生一个逻辑 Receipt；
14. Lark/Model/GitHub 长期凭证、Token、Endpoint、原始 Body、Prompt、Audit Payload 和 PII 不进入浏览器、Agent、日志或公开指标；
15. Outbox、Projection、Notification、Team Observer 和 Provider 具备 Trace、低基数指标、健康摘要和安全 Audit；
16. 固定 100 个以上故障样本的自动恢复率达到 ≥99%，重复 Action/Notification Dispatch 为 0；
17. 固定负载下 Team Activity/Inbox 投影和 READY TaskExecution Claim 的 P95 延迟均小于 2 秒；
18. 干净环境可以启动 V1–V30、使用年龄不超过 24 小时的完整备份在 4 小时内恢复，并重复演示首条完整 MVP 纵向闭环；
19. Conversation Mode 与 Control Mode 展示同一 Activity、Inbox、Observer、Notification 和 Audit 事实；
20. M0–M5 全量回归、后端、前端、Docker、AgentScope、GitHub/Lark Fixture、真实 Lark 固定模板烟测、依赖、链接和格式门禁全部通过。

## 14. 开工与提交顺序

推荐按以下节点实施和审查：

1. `M6-S01` 至 `M6-S05`：冻结投影、游标、通知、Lark、观测和发布协议；
2. `M6-D01` 至 `M6-D09`：完成领域边界和 V27–V28；
3. `M6-E01` 至 `M6-I02`、`M6-A01` 至 `M6-A03`、`M6-F01` 至 `M6-F04`：完成 Activity、Inbox、Audit 纵向闭环；
4. `M6-E04`、`M6-I03` 至 `M6-I06`、`M6-A04`、`M6-F05`：完成 Lark 通知闭环；
5. `M6-I07`、`M6-A05`、`M6-F06`：完成 Team Observer 对话与管理双入口；
6. `M6-I08` 至 `M6-I10`、`M6-A06` 至 `M6-A07`、`M6-F07` 至 `M6-Q04`：完成观测、部署、故障、负载和 MVP Release Gate。

每个提交节点先整体 Review，先修正文档与契约，再修正实现并运行相应门禁。任务完成证据保存到 `docs/spikes`、`docs/testing` 或 `docs/evaluations`，文件名以任务 ID 开头。
