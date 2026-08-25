# M6：团队观测、飞书通知与 MVP 发布执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M6<br>
> 前置条件：M5 Release Gate 通过，M0 Outbox/Projection/Audit、M1 Team/WorkItem、M2 Conversation、M3 Runtime、M4 Coding、M5 Review/Action/GitHub 契约稳定<br>
> 目标周期：3–4 周，按纵向波次推进<br>
> 目标结果：团队成员通过 Activity、Inbox、Audit 和只读 Team Observer 获得共享工作视野；固定模板通知可靠投递到飞书；完整 MVP 闭环具备可观测、可恢复、可部署和可重复验收能力<br>
> 当前进度：`M6-S01` 至 `M6-S05`、`M6-D01` 至 `M6-D09` 已完成，ADR-020 至 ADR-023 已接受；下一任务为 `M6-E01`（2026-08-25）

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

- M6 创建默认 Team Service Principal、`team-observer@1` 和每 Team 唯一 TEAM-owned AgentProfile；已有完整 Team 使用确定性迁移补齐。迁移只创建 `DISABLED` Profile，不猜测 ModelConnection 或 Configuration；管理员完成有效 TEAM Binding 和 Preflight 后显式启用，新 Team 遵循同一规则；
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

M6-I01,I02 + M6-E01..E05 -> M6-A01..A03
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
| `M6-E01` | FEATURE | S01,D07,D08 | infrastructure/server | 加固 Outbox Publisher、消费调度、Checkpoint 和 Projection Registry；实现 Generation-aware Runner、影子重建、验证和原子切换 | 多实例 SKIP LOCKED、Claim 过期、旧 Token、重复 Receipt、版本缺口、构建失败、切换竞态和进程重启 Testcontainers 测试 |
| `M6-E02` | FEATURE | D01,D08,E01 | application/infrastructure | 实现 EventType Registry 与 Activity Projector，把 M0–M5 评审通过的 Team/WorkItem/Task/Review/Action/Provider 事件映射为安全 Activity | 公开 Payload 快照、同事件去重、同 Aggregate 多事件、跨 Aggregate 顺序、未知事件忽略/告警和重建 Hash 测试 |
| `M6-E03` | FEATURE | D02,D08,E01 | application/infrastructure | 实现 Inbox Projector，按当前责任和成员资格生成/关闭五类成员待办，并与独立 Disposition 合并查询 | Owner/Executor/Reviewer/Confirmer/异常矩阵、离队、职责释放、旧 Review/Action、重复重放、重建保留已读状态测试 |
| `M6-E04` | FEATURE | D03,D04,D08,E01,E03 | application/infrastructure | 实现 Notification Intent Projector，按固定策略、成员偏好、DND 和 Lark Mapping 从 Inbox 产生策略授权 PlannedAction；最终结果回写 Delivery 与失败 Inbox | 相同来源零重复、DND 延后、映射缺失、撤权、模板升级、失败闭环和投影回环防护测试 |
| `M6-E05` | FEATURE | S02,D01,E01,E02 | application/server | 实现 Team Realtime Event Store、签名 Cursor、快照/缺口读取、SSE 心跳、背压、过期和断线补发；保留 Conversation/AG-UI 独立 Cursor | 双连接、断线、批量缺口、慢消费者、Cursor 篡改/过期、Scope 切换和同微秒 Event ID 顺序测试 |
| `M6-E06` | TASK | D06,E01,E02..E04 | infrastructure | 扩展 Audit Projector 覆盖 M3–M6 Runtime、Agent、Model、Review、Action、Projection、Notification 与 Lark 安全事件，提供脱敏查询形状 | Correlation 链、Initiator/Actor/Agent、授权结果、Provider 安全码、未知字段、Secret 探针和重建一致性测试 |
| `M6-E07` | TASK | E01..E06 | application/server | 提供投影 Lag、Dead Letter、Generation、重建、Cursor 和 Notification 积压的低基数健康摘要、管理员诊断与受审计恢复命令 | 成员只读安全摘要、管理员详情、重放/重建强确认、命令幂等、并发冲突、指标基数和敏感字段测试 |

## 8. 基础设施、Provider 与运行平台

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-I01` | TASK | D08,E02..E06 | infrastructure | 实现 Activity、Inbox、Disposition、Notification、Audit Query、Generation/Rebuild/DeadLetter 的 PostgreSQL Adapter 和 Keyset Query | 真实 PostgreSQL 对象图、稳定 Cursor、固定查询数、并发处置、代际隔离、跨租户和索引执行计划测试 |
| `M6-I02` | TASK | E01,E07,I01 | infrastructure/server | 实现 Projection Supervisor、Startup Recovery、周期调度、Retention/Cleanup 与安全 Operations Port；在线代际和影子代际使用独立 Worker Claim | 多实例接管、关机中断、过期任务、旧 Fencing、清理保护、Actuator/Spring 条件装配和运维摘要测试 |
| `M6-I03` | FEATURE | D03,E04,I01 | application/infrastructure | 实现 Notification Worker、Claim/Lease/Fencing、动作级短期凭证、发送/查询/Receipt、退避、失败终结和人工再次投递 | 事务提交前零调用、重复调度零重复消息、响应丢失查询恢复、旧 Worker 零回写和唯一逻辑 Receipt 测试 |
| `M6-I04` | FEATURE | S04,D04 | integration/infrastructure | 实现 Lark Connector HTTP Client、tenant token 安全缓存、精确 Endpoint、超时、限流、错误归一化、CredentialStore Handle 和日志脱敏 | Loopback HTTP 验证 Token 刷新隔离、401/403/404/429/5xx、超时、取消、撤销、SSRF 和原始 Body 零泄漏 |
| `M6-I05` | FEATURE | D04,I04 | integration/application | 实现 `LarkCollaborationProvider` 成员精确查询、管理员映射验证、Connection/Grant/Binding Preflight 与健康检查 | Tenant/User 精确身份、分页、限流、映射冲突、Owner/Scope、撤权、缓存失效和 Provider 契约测试 |
| `M6-I06` | FEATURE | D03,I03..I05 | integration/application | 实现固定模板消息渲染、Lark 投递、客户端幂等键/查询恢复、外部回执安全投影与重复结果合并 | 模板/变量 Hash、消息转义、响应丢失、重复请求、外部版本乱序、Receipt 唯一和任意文本拒绝测试 |
| `M6-I07` | FEATURE | D05,D09,E02,E03,I01 | agentscope/application | 实现 Team Observer Template Registry、TEAM Model Factory、五类只读 Tool、最小 Context、Structured Output、Session/State 隔离和脱敏证据链接 | Loopback 模型验证进度/阻塞/Review/确认/异常摘要，Tool 写攻击、跨 Team、私有事实、Prompt 注入和超限查询全部拒绝 |
| `M6-I08` | TASK | S05,E07,I02,I03,I07 | infrastructure/server | 完成 OTel Span、Baggage 白名单、Prometheus 指标、日志字段与脱敏 Filter，覆盖 Outbox、Projection、SSE、Inbox、Notification、Lark 和 Team Observer | Trace 链完整、低基数标签扫描、Secret/PII 探针、指标聚合、日志快照、Actuator 授权和 Collector 失效降级测试 |
| `M6-I09` | FEATURE | S05,I02,I03,I08 | server/infrastructure | 完善后端/Web/Worker 非 Root Dockerfile、Compose、健康检查、启动依赖、外部配置/Secret、Flyway、数据卷和一键演示 Profile | 干净主机启动、V1→V28、重启恢复、镜像扫描、只读文件系统、故障退出、配置缺失失败和一键演示测试 |
| `M6-I10` | TASK | D08,D09,I09 | infrastructure/docs | 实现 PostgreSQL/Artifact/Redis-Snapshot 备份恢复、版本升级/回滚边界、数据校验清单和单机 Team Beta Runbook | 备份还原、V26→V28、应用版本回退兼容边界、坏备份失败关闭、恢复校验、RPO/RTO 实测记录和运维演练 |

## 9. 应用、API 与 Team Observer

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-A01` | FEATURE | E02,E05,I01 | application/server | 提供 Team/WorkItem Activity Keyset 查询、事件详情、快照和 Team SSE API；Cursor 与过滤条件、Scope、投影版本绑定 | 成员资格、WorkItem 可见性、稳定分页、断线补发、Cursor 篡改/过期、旧 Scope 和安全 DTO 测试 |
| `M6-A02` | FEATURE | E03,I01 | application/server | 提供“我的 Inbox”五类查询、计数、详情、READ/ACTED/ARCHIVED 命令和来源对象安全跳转 API | 只读自己的 Inbox、强 ETag、幂等回放、重建并发、来源终结、离队、分页和公开 DTO 测试 |
| `M6-A03` | FEATURE | D06,E06,I01 | application/server | 提供 Team Admin Audit Explorer：按时间、Member、WorkItem、Task、Provider、Category、Outcome 和 Correlation 查询及有界导出 | 管理员授权、组合过滤、Keyset、导出上限、脱敏、未知 Payload、跨 Team、审计查询自身 Audit 和安全错误测试 |
| `M6-A04` | FEATURE | I03..I06 | application/server | 提供 Lark Connection、成员映射验证、固定模板/偏好、通知投递历史、失败详情和再次投递 API；所有命令使用 ETag、Idempotency-Key 和 Receipt | Team 管理权限、凭证单向输入、映射冲突、撤销、DND、模板白名单、失败重投、回放和 DTO 泄漏测试 |
| `M6-A05` | FEATURE | I07,M2-A03 | application/server | 提供 Team Observer 对话运行、流式事件、团队摘要与证据链接 API；复用 Conversation 但固定 TEAM Agent/Profile/Configuration 和只读 Runtime | 当前成员授权、TEAM 模型连接、Session 隔离、Resume、取消、Structured Output、写命令拒绝、引用持续授权和安全投影测试 |
| `M6-A06` | FEATURE | E07,I02,I08 | application/server | 提供成员运行健康摘要和管理员 Projection/Outbox/Notification 诊断、重放、影子重建与切换 API；危险命令要求强确认和审计 | 成员/管理员分层、低基数摘要、精确 Generation、旧页、并发重建、命令回放、失败关闭和 Actuator 装配测试 |
| `M6-A07` | TASK | A01..A06,M5-A08 | application/server | 完成跨 Conversation、WorkItem、Task、Review、Action、PR、Activity、Inbox、Notification 与 Audit 的 Correlation 查询和 Task Timeline 白名单 | 同一 Correlation 链、对象双向链接、分页预算、N+1 上限、持续授权、未知事件不公开和敏感内部字段测试 |

## 10. 前端工作台

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-F01` | TASK | A01..A04,A06 | web | 建立 Activity、Inbox、Audit、Lark/Notification、Projection Health Gateway、公开 DTO、Store、Scope 缓存、三流 Cursor 和错误契约 | DTO 白名单、请求代次、旧 Team 晚到响应、Cursor 恢复、强 ETag、凭证不缓存和生产构建测试 |
| `M6-F02` | FEATURE | F01,A01,A07 | web | 交付 Team Activity Stream 和 WorkItem Activity 嵌入入口，展示 Actor、Subject、Outcome、证据链接、实时状态和断线补发 | Desktop/Narrow、Loading/Empty/Error/Forbidden/Offline/CursorExpired、重复事件、键盘、Axe、视觉和 E2E 测试 |
| `M6-F03` | FEATURE | F01,A02 | web | 交付“我的 Inbox”，提供五类视图、计数、优先级、截止时间、来源跳转及 READ/ACTED/ARCHIVED 操作 | 成员隔离、筛选分页、强 ETag、冲突回读、重建不丢已读、响应式、键盘、Axe 和视觉测试 |
| `M6-F04` | FEATURE | F01,A03,A07 | web | 交付 Team Admin Audit Explorer，支持组合筛选、Correlation 链、对象跳转和有界导出，不展示原始 Payload | 权限、过滤/分页、脱敏、导出状态、窄屏表格降级、键盘、Axe、视觉和 E2E 测试 |
| `M6-F05` | FEATURE | F01,A04 | web | 交付 Lark Connection、成员映射、固定模板偏好和通知投递页，显示安全健康、Receipt、失败 Inbox 与再次投递 | Secret 单向输入、映射确认、DND、失败重投、旧页冲突、全状态、响应式、Axe 和视觉测试 |
| `M6-F06` | FEATURE | A05,F01,F02,F03 | web | 在 Conversation Mode 交付 Team Observer 对话入口，在 Control Mode 交付只读团队摘要；展示进度、阻塞、Review、待确认、异常和证据链接 | Agent 身份、只读说明、流式 Resume、Scope 切换、Prompt 攻击安全结果、双入口一致、Axe、视觉和 E2E 测试 |
| `M6-F07` | FEATURE | A06,A07,F01 | web | 交付运行健康与 MVP 管理页：Projection/Outbox/Notification Lag、Dead Letter、Generation、影子重建、切换和一键演示证据入口 | 成员/管理员分层、强确认模态、命令回放、实时刷新、低基数摘要、键盘焦点、Axe 和视觉测试 |
| `M6-F08` | HARDENING | F02..F07 | web | 收口 Activity/Inbox/Audit/Lark/Observer/Operations 全状态、三流离线恢复、响应式、ARIA、Reduced Motion、Histoire、视觉、Axe 和敏感字段门禁 | Coverage 不低于既有门槛；Story/Variant、双视口 Playwright、视觉、Axe、离线恢复和公开字段扫描全部通过 |

## 11. 测试、故障、负载与发布

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M6-Q01` | HARDENING | D01..D09,E01..E07,I01..I08,A01..A07,F01..F07 | all | 建立 Activity/Inbox/Audit/Team Observer/Lark/Notification/Operations 固定攻击集，覆盖跨 Scope、Cursor、Payload、Prompt、映射、模板、凭证、重建和运维命令 | 越权工具与资源访问阻断率 100%；Team Observer 写调用 0；Secret/PII/原始 Payload 泄漏 0；普通成员重建/重放命令 0 |
| `M6-Q02` | HARDENING | E01..E07,I01..I07,A01..A06 | all | 注入 Outbox、Projection、SSE、Redis/Snapshot、Worker、Worktree、Model、GitHub、Lark、Notification 和数据库提交窗口故障，冻结至少 100 个样本 | 自动恢复率 ≥99%；重复 Action/Notification Dispatch 为 0；处置状态不丢失；最终失败进入人工队列；旧 Fencing 写入 0 |
| `M6-Q03` | HARDENING | S05,I06,I08..I10,A01..A07,F08 | all/performance | 执行固定负载、重启恢复、备份还原、完整 MVP E2E 和显式凭证真实 Lark 烟测；真实轨道只向专用测试接收者发送固定模板并追加归档脱敏报告，普通 CI 不读取凭证 | Team Activity/Inbox 投影 P95 <2s；READY Claim P95 <2s；Worktree 回滚 100%；干净环境完整闭环可重复运行；真实 Lark 固定模板取得安全 Receipt |
| `M6-Q04` | HARDENING | Q01,Q02,Q03 | all/docs/ci | 执行 M6 与 MVP Release Gate，审查 V27–V28、M0–M5 回归、后端、前端、AgentScope、Docker、Provider、依赖、文档、部署和演示证据 | 所有自动化零失败/零跳过；安全/故障/负载门槛通过；镜像与依赖零阻断漏洞；形成版本化 M6 Release Gate 报告 |

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
18. 干净环境可以启动 V1–V28、使用年龄不超过 24 小时的完整备份在 4 小时内恢复，并重复演示首条完整 MVP 纵向闭环；
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
