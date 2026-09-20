# M9b：菜单逐操作产品 Review

日期：2026-09-19。承接 [全流程 Review](M9后-全流程使用体验与竞品对照Review.md) 与 [M9b 计划](../plans/M9b-核心流程与使用体验收口.md)。状态：审查记录，不是修复完成或实机验收报告。

## 1. 口径：审菜单里的操作，不只审页面

范围以当前 AppShell 的 **15 个一级菜单**、SettingsShell 的 **9 个二级菜单**为准，另查全局范围/搜索/账号菜单及认证入口。二级菜单对应相同页面时复用下表，不重复计算功能；但二级入口的权限、Scope 和返回路径单独检查。

每个操作都问：用户为什么点 → 前提是否可见 → 填什么/选谁 → 提交的是否是所见内容 → 能否判断结果 → 失败/取消/返回后如何继续。按当前页面、实际子组件、处理函数、store/gateway 及必要后端接口进行源码走查；三个新增问题做了实际函数/表达式的条件模拟。未恢复已停服务、未进行浏览器逐项点击、未调用真实 Provider 或发送外部写请求。

表内“保留”仅表示本次源码审查未发现该操作的新阻断，仍需真实路径验收；“优化”是现有操作有缺陷/产品负担；“缺口”是用户合理需要但当前没有的操作。不能把表中存在一行当作验收 PASS。

本轮新增 **R33–R39 共 7 项**，截至本轮累计 39 项。后续 [Multica 迁移 Review](M9b-Multica用户迁移体验Review.md) 新增 R40–R43 并升级部分要求，当前总计 43 项；其 §5–§6 是本表的增量操作验收。其他发现归并到已有 R 项，避免为相同根因重复造任务。

2026-09-20 补充 [全菜单布局与交互细节 Review](M9b-全菜单布局与交互细节Review.md)：从首屏、摆放位置和用户操作顺序再走查 15+9 菜单及 23 页，将具体反例与改进归并为 L01–L12 子要求。原表的按钮/权限/恢复清单仍保留，新表明确控件放在哪里、窄屏如何退化、返回/焦点如何继续；与主计划及契约共同关闭，不增加 R/父包计数。旧表中“保持登录”按实际能力回归，不要求新增当前不存在的控件。

## 2. 新增问题与证据

### R33（P2）：审计导出不一定导出用户眼前的条件，勾选行没有实际用途

用户先改时间/类别，再点“导出 CSV”，合理预期是导出刚指定的范围。[AuditExplorer.vue](../../crewscope-web/src/components/domain/AuditExplorer.vue) 第 88–97 行用未提交的 `form` 判断导出可用，第 183 行却只发出 `maximumRows`；[AuditPage.vue](../../crewscope-web/src/pages/AuditPage.vue) 第 109–116 行实际使用来自 URL 的 `activeFilter`。如果旧条件为空，会出现按钮允许但服务端拒绝；旧条件有效时则可能导出另一范围。提取实际导出函数模拟，确认接收的仍是旧的 9 月 1–2 日条件，不接收新表单。

同一组件第 87、206 行有行勾选，但选择集没有消费者，既不影响导出也没有批处理；容易让人误认为“导出选中项”。导出结果只提示已生成并下载，没有解释实际条数、上限与是否完整；当前响应只有 rowCount/maximumRows，不能无依据宣称导出全部。

改进：本期已在 [实现契约 §5.2](../plans/M9b-实现边界与验收契约.md) 选定“导出已应用条件”，未应用修改先提示应用，展示范围摘要并冻结到返回；无选中项能力就移除复选框，不为此强建批量导出平台。展示条数/上限，达到上限时提示可能不完整，必要时由 API 返回 hasMore/截断事实。只在生成成功后提示已发起下载，不声称浏览器一定已保存文件。F04 主责，必要导出元数据增量归本包。

验收：未应用条件、旧/新时间范围、选中与未选中、上限边界、失败重试、请求期间改变筛选，导出内容与用户确认一致。

### R34（P2）：审计“找人找事”仍要抄 UUID，详情链接依赖先翻到所在页

[AuditExplorer.vue](../../crewscope-web/src/components/domain/AuditExplorer.vue) 第 169–177 行高级筛选仍含 Initiator、Actor、Agent Principal、Subject、Provider Binding、Correlation 六类 UUID 输入及 Subject Type 文本；用户要查“某人做过什么”仍得先找内部标识。之前只查 input 的 placeholder/aria-label 的静态规则不能发现包在 label 中的 UUID。

[AuditPage.vue](../../crewscope-web/src/pages/AuditPage.vue) 第 53–54 行只从当前已加载列表查 `auditEvent`。第二页的事件链接在新窗口打开、刷新回首屏，详情可能不出现，也没有按目标显示加载/失效说明。不是后端授权失败，而是对象定位绑定了列表加载进度。

改进：成员/Agent/连接/工作项提供受权名称选择；Correlation 保留可选诊断精确输入，不让普通查人依赖 UUID。详情按受权对象定位查询，或通过正式定点查询取得，不能自动穷举所有分页。缺失/无权限安全解释。F04 主责及必要只读 API；复用 R21 选择器、R30 深链接合同。

验收：不用复制 ID 完成按人查记录；首屏之外事件深链接独立打开、刷新、返回仍正确；离职成员的历史记录能按权限检索，不因当前成员列表只含 ACTIVE 而消失。

### R35（P1）：飞书“验证身份→确认映射”没有固定用户当时选中的人

[LarkNotificationAdmin.vue](../../crewscope-web/src/components/domain/LarkNotificationAdmin.vue) 第 134–140 行成功 watcher 在响应到达时读取当前 `mappingMemberId` 和 `activeBinding`；第 334 行成员选择在请求期间仍可变。模拟在 A 的验证请求返回前改为 B，生成的 verified 变成 `{memberId: B, proofId: A请求的回执}`。已验证后再切成员，旧 verified 也未随选择作废；第 200–204 行“确认映射”发出命令便立即清除 verified，失败后缺少直接继续路径。

这里确认的是前端目标与用户意图不一致，**未验证服务端会接受错误绑定，不推断发生越权或实际误投递**。轮换凭证弹窗同样需固定打开时的连接：提交读取当前 `selectedConnection`，不能将跨连接/Scope 的输入交给新目标。

改进：验证/轮换/确认固定成员、连接、绑定版本和请求代次；切换使确认失效或保留明确原目标，显示“将把谁与哪个已验证身份对应”，不只显示 Proof ID。成功才清除，未知结果查原命令；失败可按原意图继续。F01 主责目标固定/恢复，F04 接交互；服务端证据与目标约束同步复核。

验收：验证中切人/连接/团队、验证后切人、确认丢响应、轮换时切连接都不误改目标；秘密不入持久化草稿。

### R36（P2）：仓库导入取消后还显示“正在导入”，状态读取失败没有对应恢复操作

[GitHubSettingsPage.vue](../../crewscope-web/src/pages/GitHubSettingsPage.vue) 第 412 行只有 READY、FAILED、其他三种标题分支；实际表达式对 CANCELLED 输出“正在导入”。第 204–211 行轮询异常后停止轮询、提示“稍后刷新”；页上刷新/错误重试调用 `loadConnections`，不是重新查询原 import job。`closeImport` 清掉 job，重新打开仓库又从空任务开始，页面没有持久恢复原导入目标的入口。

改进：区分等待/执行/取消请求/已取消/失败/完成/状态未知；“重新读取导入进度”对原 job 查询而非再创建，关闭只离开面板不代表取消；返回/刷新后可恢复授权范围内的原任务。完成后给“打开仓库设置/继续原任务”而非只给 binding ID。A02 主责，F02 接返回流程。

验收：各终态标签、取消与执行竞争、轮询断网、刷新/离开后返回、原任务恢复与重复创建防护。

### R37（P2）：模型连接能一键停用，却没有重新启用的完整路径

[ModelConnectionDetail.vue](../../crewscope-web/src/components/domain/ModelConnectionDetail.vue) 的“停用连接”直接提交，SUSPENDED 后告知 API 尚无恢复命令；[ModelConnectionController.java](../../crewscope-server/src/main/java/io/crewscope/server/api/ModelConnectionController.java) 仅有 verify/rotate/suspend/revoke。领域 [ModelConnection.java](../../crewscope-domain/src/main/java/io/crewscope/domain/model/ModelConnection.java) 第 201–252 行已有 activate 且要求当前凭据 HEALTHY，rotate 保持原 status，因此轮换不等于启用。

改进：在 A03 补“检查健康→重新启用”受权命令和 UI，复用既有领域规则、强版本、幂等；停用前说明影响哪些新执行/默认配置，永久撤销仍独立确认。不可恢复的旧授权不能被启用操作复活。不要建议用户删除重建全部 Agent 配置来恢复误停用。

验收：ACTIVE→SUSPENDED→验证→ACTIVE 全链；不健康/已撤销拒绝恢复；并发变更、引用配置、在途与新执行影响符合冻结合同。

### R38（P2）：团队观测的“刷新”只是重读旧摘要，首次失败后可能没有当前页继续入口

[TeamObserverWorkspace.vue](../../crewscope-web/src/components/domain/TeamObserverWorkspace.vue) 的摘要页只在 idle/cancelled 显示生成按钮；error 仅在 retryable 时提供恢复。首次创建 session/调用失败尚无 invocationId，[teamobserver/store.ts](../../crewscope-web/src/domains/teamobserver/store.ts) 的 fail 会令 retryable=false，摘要页没有直接重新生成的动作，需绕去对话入口。

完成后的“刷新事实”调用 `refreshSummary`，读取同一 invocation；后端 [TeamObserverInvocationService.java](../../crewscope-application/src/main/java/io/crewscope/application/teamobserver/TeamObserverInvocationService.java) 第 155–165 行返回该 invocation 已存储的 summary，不会重新生成反映新进展的摘要。

改进：区分“重读本次结果”“恢复同一次调用”“按最新团队情况重新生成”；首次失败给检查配置/重新尝试，已完成给重新生成并说明时间/可能的模型调用成本。证据打不开是这条证据的错误，不应一概暗示整个 Agent 调用失败或必须重跑。F04 主责，复用现有 invoke/retry/summary 能力。

验收：首次 session 失败、无 invocationId、流中断、业务终态失败、生成后团队事实变化、证据过期/撤权，均有对应安全下一步。

### R39（P2）：工作项创建后缺少修改基本内容的日常操作

[WorkItemCreateDialog.vue](../../crewscope-web/src/components/domain/WorkItemCreateDialog.vue) 创建时接受标题、描述、优先级、标签、到期时间；[WorkItemDetailDrawer.vue](../../crewscope-web/src/components/domain/WorkItemDetailDrawer.vue) 仅展示这些信息并提供状态/责任/评论/资源操作。[WorkItemController.java](../../crewscope-server/src/main/java/io/crewscope/server/api/WorkItemController.java) 及相关公开入口未发现基本字段编辑命令。用户写错标题、补验收说明、调整截止时间，不能通过普通界面就地修改。

改进：A01 增加最小基本字段编辑（标题、描述、优先级、标签、到期时间），带授权、版本冲突、幂等、变更记录和保留输入；编号只读，类型变更/跨项目移动/删除不顺带扩大范围。工作项当前说明与已经启动的执行快照分开；改目标不能静默重写在途任务或历史审批，需明确提示已有执行仍按原快照，重新执行/审查按既有流程进行。

验收：修改/取消、冲突不覆盖他人、归档只读规则、详情/列表/Today 同步、修改后历史执行/Review 证据不被篡改。

## 3. 15 个一级菜单逐操作清单

每行是一个可验证操作或共享同一状态合同的一组操作；“刷新/关闭”等按页面实际处理函数检查，不把某个通用组件通过等同于全部入口通过。表中任务省略 `M9b-` 前缀。

### 3.1 今日

证据：[TodayPage](../../crewscope-web/src/pages/TodayPage.vue)、[WorkDeskBoardCard](../../crewscope-web/src/components/domain/WorkDeskBoardCard.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 新建项目、空态创建项目 | 优化：名称先行、自动短代号，成功进入明确项目；原先过滤条件不影响创建定位 | R07，A01/F02 |
| 打开 Work、点击待办/执行/今日变化 | 优化：目标工作/执行一致，返回保留合理范围，不只进入空工作台 | R11/R30，F03/F04 |
| 项目、责任角色、仅需行动筛选 | 优化：明确当前团队跨项目例外，组合条件/清空可预测 | R30，A06/F04 |
| 按状态/责任分组 | 保留：分组不修改业务；显示数量与数据范围，不能将样本误说成全部 | R16，A06/F04 |
| 卡片状态动作、拖拽/键盘移动、撤销 | 保留服务端动作可用性和不可逆确认；跨项目、冲突、不可拖分组给原因，不将拖动失败当成功 | R25/R27，F01/F04 |
| 刷新/失败重试、跨日停留 | 优化：保留卡片、时间更新；“刚刚”不替代最近观测事实 | R25/R32，F04 |

### 3.2 对话

证据：[ConversationPage](../../crewscope-web/src/pages/ConversationPage.vue)、[ConversationComposer](../../crewscope-web/src/components/domain/ConversationComposer.vue)、[TaskIntentCard](../../crewscope-web/src/components/domain/TaskIntentCard.vue)、[ClarificationCard](../../crewscope-web/src/components/domain/ClarificationCard.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 新建、取消创建、选择对话、加载更多 | 优化：创建准确打开本次对象，草稿离开不丢；同名/分页可辨别 | R05/R24，A01/F05 |
| 发送、失败重发 | 优化：同一意图不重复发送、失败保留内容；正常中文 Enter/换行各自明确 | R06/R14/R27，F01/F05 |
| 附件、Slash | 优化：已有入口但能力不足；实现最小真实行为或移除，不保留假承诺 | R14，F05 |
| 取消 Agent 调用、恢复调用 | 保留取消不回滚已发生事实；说明取消请求中/已取消/可恢复，与重新发起区别 | R25，F03/F04 |
| 回答澄清、修订/拒绝/确认任务提案 | 优化：提案可读业务信息，确认后定位工作；拒绝/修订不丢草稿，旧版本不可继续确认 | R02/R11/R24，A01/A05/F03/F05 |
| 打开关联工作项/执行、发起 Coding 委托 | 优化：一次带出目标和默认配置，配完返回，不重新输入内部配置 | R11，A05/F02/F03 |
| 加载历史、跳最新、滚动恢复 | 优化：长代码/表格不跳空，正在读历史时不强拉底部 | R19，F03 |
| 折叠/调整两侧面板、移动返回列表 | 优化：边界跟手、返回选中会话与草稿，窄屏不露无效参与者控制 | R28，F04/F05 |

### 3.3 工作项

证据：[WorkPage](../../crewscope-web/src/pages/WorkPage.vue)、[WorkItemDetailDrawer](../../crewscope-web/src/components/domain/WorkItemDetailDrawer.vue)、[TaskControlPanel](../../crewscope-web/src/components/domain/TaskControlPanel.vue)、[CodingDiffExplorer](../../crewscope-web/src/components/domain/CodingDiffExplorer.vue)、[ReviewWorkbench](../../crewscope-web/src/components/domain/ReviewWorkbench.vue)、[ActionDeliveryWorkbench](../../crewscope-web/src/components/domain/ActionDeliveryWorkbench.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 创建工作项、取消、创建失败重试 | 优化：去手填 Key、并发编号、确定定位、响应丢失恢复 | R01/R02/R06，A01/F01 |
| 修改标题/说明/优先级/标签/到期时间 | 缺口：最小编辑闭环，保留历史与执行快照 | R39，A01 |
| 列表/看板、状态/类型/优先级筛选、排序、清筛选、加载更多 | 优化：全量排序分页，清条件不误留；筛选空与无数据不同 | R16/R25，A06/F04 |
| 勾选、选当前页、清选择、批量状态/责任、查看/关闭结果 | 保留 Work 已有逐项结果；补隐藏选择范围、失败/未知项安全恢复，不照搬 Inbox 的缺陷结论 | R06/R26，F01/F04 |
| 卡片/详情状态动作、拖拽、撤销 | 保留服务端 availability、反向边撤销和不可逆确认；用户看见作用于哪个工作项 | R25/R27，F04 |
| 打开/关闭详情、重试详情/时间线、加载更多历史 | 优化：深链接独立定位、关闭后回原位置，详情错误不影响列表 | R25/R30，F03/F04 |
| 更换 Owner、分配执行者/Gate/建议审查者、释放责任 | 优化：名字选择/停用状态/冲突影响清楚，不能无意留无主工作 | R15/R21，A07/F04 |
| 写评论、关联资源、打开链接 | 优化：草稿不丢；普通站内资源选名称/对象而非“稳定标识”；外链类型校验 | R07/R24，F04/F05 |
| 带到对话、打开关联对话、交给 Agent | 优化：保持同一个工作上下文；已有责任不静默覆盖，失败可继续 | R11，A05/F03 |
| 任务状态/Owner 筛选、选任务、选当前/历史尝试 | 优化：默认当前执行，历史明确只读；不能让用户猜哪一次代表当前工作 | R11/R12，A06/F03 |
| 暂停、恢复、取消执行、创建新尝试、重试原命令 | 保留不同操作语义与影响说明；可选配置 Revision 改为具名选择/沿用默认，网络重试与新尝试分开 | R06/R07/R25，F01/F03 |
| 查看日志/测试报告、加载更多、失败重读 | 保留分资源加载；说明截断/范围，长输出可读，单资源失败不清空工作区 | R12/R25，F03 |
| Diff 搜文件/切文件/单双栏/折叠/全屏/标已看/键盘跳变更 | 优化：窄屏单栏、版本化已看，截断明确，焦点定位可用 | R16/R22/R27，F03/F04 |
| 打开/提交/取消行评、查看历史评论、定位证据 | 优化：固定目标，未保存不假成功，过期锚点可理解 | R23/R24，F03/F05 |
| 选审查轮次、执行 Reviewer、评论/批准/要求修改/拒绝、取消/重试 | 保留版本/人审；明确决定影响、保存意见、修改后再审路径 | R13/R24，F03/F05 |
| 选连接/仓库、同步/预检、规划 PR、确认外部写入 | 优化：默认带出上下文，确认仓库/分支/差异，不手审 Digest | R13，A02/F03 |
| 撤回未执行确认、刷新交付结果、人工终结失败、打开 PR | 保留不可撤回已执行副作用、UNKNOWN 对账与人工说明；不得把刷新变成再次推送 | R13/R25，F03 |

### 3.4 动态

证据：[ActivityPage](../../crewscope-web/src/pages/ActivityPage.vue)、[ActivityStream](../../crewscope-web/src/components/domain/ActivityStream.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 按类别/成员筛选 | 优化：名称可读；现仅 ACTIVE 成员选项不足以查离职成员/Agent 的历史，受权目录覆盖历史身份 | R21/R34，F04 |
| 最早/最新排序、加载更早活动 | 优化：不能只翻转已加载页冒充全量最早优先 | R16，A06/F04 |
| 刷新、恢复实时流/过期 cursor | 保留续传去重；失败保留可读内容，筛选切换不接回旧流 | R04/R25，F01/F04 |
| 打开事件详情、详情重试/关闭 | 优化：技术 payload 下沉，事件讲清谁对哪个工作做了什么；筛选后详情范围明确 | R12/R30，F04 |
| 打开证据链接 | 保留站内目标导航；落点和返回范围验收，不可用目标说明原因 | R25/R30，F04 |

### 3.5 我的 Inbox

证据：[InboxPage](../../crewscope-web/src/pages/InboxPage.vue)、[InboxWorkspace](../../crewscope-web/src/components/domain/InboxWorkspace.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 全部/负责/执行/Review/确认/异常分类 | 优化：类型标签面向工作，计数说明未读/来源未关闭口径 | R12/R26，A06/F04 |
| 来源/处置筛选、清筛选、排序、加载更多 | 优化：一次清条件，排序全量，过滤后隐藏选择规则明确 | R16/R26，A06/F04 |
| 查看/关闭详情、详情重试、刷新计数 | 优化：任务名称/下一步先于版本和 ID；计数失败不能呈现为真实零 | R12/R25，F04 |
| 打开来源/来源失败恢复 | 优化：命名“去审查/去确认”等；连接实际重试动作 | R25/R26，F04 |
| 标已读、标已处理、归档、恢复/标未读 | 优化：仅个人收纳、不完成源业务；当前无恢复，过渡说明不可撤回；迁移轮已升级为补受权恢复/未读领域与 UI 闭环，不以提示代替最终交付 | R25/R26，F04 |
| 勾选、清选择、批量已读 | 优化：批次锁定、进度/逐项结果、部分失败/未知项继续 | R26，F01/F04 |

### 3.6 运行与发布

证据：[OperationsPage](../../crewscope-web/src/pages/OperationsPage.vue)、[OperationsWorkspace](../../crewscope-web/src/components/domain/OperationsWorkspace.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 查看健康/管理员诊断、跳 Inbox/审计 | 优化：菜单应说明是运维，不让人误以为从这里发 PR；普通成员只见授权摘要 | R12/R30，F04 |
| 手动刷新、开关15秒自动刷新、后台返回 | 保留后台停计时；`refresh` 无论查询是否成功都写 lastRefreshedAt，改成区分尝试时间/成功观测，离线不假装更新 | R25/R32，F04 |
| 查看恢复候选、执行恢复 | 保留影响确认与原命令键；诊断陈旧/并发冲突给刷新后再确认 | R06/R25，F01/F04 |
| 启动影子重建、验证、切换代际 | 保留有风险的运维确认，不为“简单”取消；用进度/影响摘要解释技术步骤 | R12/R25，F04 |
| 取消重建、标记失败、关闭/提交确认 | 保留不可逆区别；失败代码可给默认原因选项，高级诊断才看 code；关闭不冒充服务端取消 | R07/R25/R27，F04 |

### 3.7 团队观测

证据：[TeamObserverPage](../../crewscope-web/src/pages/TeamObserverPage.vue)、[TeamObserverWorkspace](../../crewscope-web/src/components/domain/TeamObserverWorkspace.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 生成团队摘要、对话式提问 | 保留只读边界；说明需要可用模型、生成范围/时间 | R10/R38，F02/F04 |
| 取消、恢复调用、首次失败重试 | 优化：无 invocationId 时也有检查配置/重新尝试入口 | R38，F04 |
| 刷新事实、按最新进展重新生成 | 优化：旧结果回读与重新生成分开，不以刷新名义重复模型调用 | R38，F04 |
| 打开证据 | 保留重新授权；单条证据失败就地处理，不全部改成 Agent 失败 | R25/R38，F04 |
| 前往工作项/Today、Scope 错误重试 | 保留只读页出口；目前 Scope 错误同时有默认重试与插槽重试，合并重复动作 | R25/R30，F04 |

### 3.8 团队成员

证据：[TeamMembersPage](../../crewscope-web/src/pages/TeamMembersPage.vue)、[TeamInvitationManager](../../crewscope-web/src/components/team/TeamInvitationManager.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 添加成员/添加已有用户 | 优化：当前候选只有非 ACTIVE 的旧成员，主按钮却叫添加成员；改为“邀请成员”优先，“重新加入”按条件出现；去过时“输入 Principal ID”说明 | R07/R12/R15，A07/F04 |
| 查看成员、复制标识、深链定位/清除、加载失败重试 | 保留显示名/角色与定位；ID 复制作为诊断次操作，重名需辅助识别，名单支持查找 | R12/R21，F04 |
| 创建邀请：邮箱限制/角色/期限、取消 | 保留最小默认角色；明确邮箱是限制而不是自动发邮件，展示角色实际权限 | R15/R25，A07/F04 |
| 复制一次性链接、复制失败手选、离开 | 保留只显示一次及 HTTP 下手动复制；避免尚未复制就误关闭，链接不持久化 | R01/R24，F01/F05 |
| 看邀请状态、加载更多、撤销/取消撤销 | 保留撤销邀请不移除已加入成员的明确说明；并发已接受给新事实，原重试不重复建邀请 | R06/R29，F01/A07 |
| 变角色、停用/恢复、移除、责任交接 | 缺口由 A07 完整补齐，最后 Owner 与移除影响可见；不只补按钮 | R15，A07 |

### 3.9 审计中心

证据：[AuditPage](../../crewscope-web/src/pages/AuditPage.vue)、[AuditExplorer](../../crewscope-web/src/components/domain/AuditExplorer.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 时间/类别/结果筛选、应用、重置 | 优化：可读枚举、时区与已应用条件明确，输入未应用有提示 | R33/R34，F04 |
| 展开高级筛选、按人/Agent/对象/连接/关联链查询 | 优化：普通路径用受权选择器，精确诊断 ID 可保留高级入口 | R34，F04 |
| 勾选审计行 | 优化：当前无后续动作，移除假选择能力或明确用途，不暗示影响 CSV | R33，F04 |
| 设置导出上限、导出 CSV/失败重试 | 优化：实际参数与所见一致、条数/上限/可能截断可见 | R33，F04 |
| 列表续页、刷新、详情/关闭、分享详情 URL | 优化：非首屏详情独立恢复，不能静默不展示目标 | R34，F04 |
| 打开/关闭关联链、链续页/重试、跳受权对象 | 保留关联链证据路径；清晰保留来源筛选，部分链数据/失效对象可解释 | R25/R30，F04 |

### 3.10 配置中心

证据：[SetupPage](../../crewscope-web/src/pages/SetupPage.vue)、[SettingsShell](../../crewscope-web/src/components/settings/SettingsShell.vue)、[SettingsFieldSearch](../../crewscope-web/src/components/settings/SettingsFieldSearch.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 下一步、配置模型/Agent/项目/仓库/集成 | 优化：按只对话/Coding 目标给前提和具名责任人；准确落点、不循环配置 | R10，F02 |
| 刷新就绪度/健康、失败重试 | 优化：两类结果独立，错误/离线保留现有事实，不用“保存成功”冒充可执行 | R10/R25，F02/F04 |
| 搜索配置菜单/字段、点击命中、重试搜索 | 保留字段实际检索；明确菜单匹配与字段匹配，命中回到授权配置对象 | R20/R25/R30，F04 |
| 九项二级导航 | 优化：Agent/模型/仓库/GitHub/飞书/配置健康/账号/成员/运维与一级权限规则一致；目前二级列表未过滤权限，链接不携 Scope query，需明确恢复规则 | R25/R30，F04 |
| 返回 Today/返回原任务 | 优化：返回原目标与草稿优先，不把所有配置结束都送首页 | R10/R24，F02/F05 |

### 3.11 Agent 中心

证据：[AgentSettingsPage](../../crewscope-web/src/pages/AgentSettingsPage.vue)、[AgentConfigurationPanel](../../crewscope-web/src/components/domain/AgentConfigurationPanel.vue) 及模型绑定、偏好、历史、生命周期子组件。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 创建个人/团队 Agent、选模板、模板失败重试、取消 | 优化：先讲用途/所属范围，默认足够，不要求理解 runtime role | R08/R12，A03/F04 |
| 分组找 Agent、选中/关闭、加载更多、从团队 Agent 去工作 | 优化：对象优先；ACTIVE 标签当前写“运行中”，应为“已启用/可用”，不能等同正在执行任务 | R12/R32，F04 |
| 个人/团队模型绑定、主/备模型、继承默认 | 优化：显示有效来源与能力，不让用户猜落到哪个模型；完整名称选择 | R08/R21，A03/F04 |
| 指令、技能、生成参数、保存配置 | 保留按 payload 复用命令键及版本；普通/高级分层，校验失败不丢输入 | R06/R12/R24，A03/F05 |
| Preflight、刷新配置/目录 | 优化：保存提交、后续预检、最新事实刷新分别反馈，不能把部分刷新失败写成全部通过 | R10/R25，A03/F04 |
| 查看/续页历史、选版本、对比、复制 Hash | 保留只读历史和对比；Hash 为诊断，当前版本/生效范围明显；不假装支持一键回滚 | R12，F04 |
| 恢复草稿、关闭脏表单、切团队/Agent | 保留已有脏态保护并补账号/Scope 隔离；切换时不能将草稿留给另一对象 | R18/R24，F05 |
| 启用、禁用、归档及确认 | 保留可恢复禁用与不可逆归档；确认对象/影响、版本变更后重新确认，不以同位置双击替代理解影响 | R15/R25，F04 |

### 3.12 模型与凭证

证据：[ModelSettingsPage](../../crewscope-web/src/pages/ModelSettingsPage.vue)、[ModelConnectionDetail](../../crewscope-web/src/components/domain/ModelConnectionDetail.vue)、[ModelCredentialDialog](../../crewscope-web/src/components/domain/ModelCredentialDialog.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 选 Provider/看模型目录、刷新/失败重试 | 优化：能力/价格未知明示；自定义兼容服务由 A03 补真实管理 | R08，A03 |
| 个人/团队/组织切换、选连接、开关详情 | 优化：范围/管理权明确，旧响应不写入新选择 | R04/R25，F01/F04 |
| 创建连接、取消、失败重试 | 优化：必要字段、凭据即时清理与结果定位；禁止未说明原因的灰按钮 | R06/R08/R25，A03/F01 |
| 验证健康、轮换凭据 | 保留版本/秘密隔离；验证不等于启用，轮换前说明影响并固定目标 | R25/R37，A03 |
| 停用、重新启用 | 缺口：停用影响与受权恢复闭环 | R37，A03 |
| 永久撤销、取消/确认 | 保留不可逆确认与原因；提交中关闭不丢原结果，失败可恢复查询 | R06/R25，A03/F01 |
| 查看命令审计证据 | 优化：已有审计中心，不再写“未来 API 交付后接入”；提供受权追溯而非只显示回执 ID | R12/R34，F04 |

### 3.13 仓库设置

证据：[RepositorySettingsPage](../../crewscope-web/src/pages/RepositorySettingsPage.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 无项目时创建、去 GitHub 导入 | 优化：补实际授权绑定前置并能回原设置 | R03/R10，A02/F02 |
| 绑定仓库、选择目录项/默认分支、取消 | 优化：展示 owner/repo 与分支建议，不靠内部 Key；草稿离开规则明确 | R07/R09/R24，A02/A04/F05 |
| 草稿预检、创建绑定、冲突/失败重试 | 保留预检门槛；原因可见、修改输入令旧预检失效；未知提交不能盲重发 | R06/R25，A02/F01 |
| 已有仓库预检、启用/停用、刷新列表 | 保留强版本；停用前说明对新执行/默认仓库影响，不靠缩写判断健康 | R09/R25，A04/F04 |
| 定位绑定/清除定位、查看创建与更新信息 | 保留深链定位；不在当前页时明确继续查找/失效，不将空列表冒充不存在 | R30，F04 |

### 3.14 飞书与通知

证据：[LarkSettingsPage](../../crewscope-web/src/pages/LarkSettingsPage.vue)、[LarkNotificationAdmin](../../crewscope-web/src/components/domain/LarkNotificationAdmin.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 切连接/映射/通知页签、刷新、选连接 | 优化：保留授权范围与已选对象，非当前页状态不相互污染 | R04/R30，F01/F04 |
| 创建连接、轮换/取消凭据输入 | 优化：解释外部必要参数来源，轮换固定原连接，秘密不保存草稿 | R07/R35，F01/F04 |
| Preflight、健康检查、失败重试 | 优化：区分连通/身份/绑定缺失，直接给可用恢复入口 | R25，F04 |
| 撤销连接、撤销成员映射 | 优化：当前直接提交，应展示对象与通知影响并确认；失败保留原意图，不替用户自动重投 | R25/R35，F01/F04 |
| 选成员、输入 open_id、验证、确认映射 | 优化：固定人/连接/版本、展示映射双方，切换重新验证；失败不清除可恢复坐标 | R35，F01/F04 |
| 筛选/清空/续页成员映射 | 优化：名字而非短 ID，返回参数与表单同步；同条件重试应实际加载 | R21/R25/R30，F04 |
| 通知开关、类型、勿扰、保存 | 优化：本人设置不藏在管理员页，时区/生效/关闭限制明确 | R31，A07/F04 |
| 按收件人/状态/类型筛选投递、续页、看详情/关闭 | 优化：投递状态与源业务状态分开，跳页不带错收件人筛选 | R25/R30，F04 |
| 失败投递重投、冲突刷新 | 保留只对受支持终态操作；提示会再次发送哪条通知，原请求未知先查结果 | R06/R25，F01/F04 |

### 3.15 GitHub 集成

证据：[GitHubSettingsPage](../../crewscope-web/src/pages/GitHubSettingsPage.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 创建个人/团队连接、选择认证方式、取消/重试 | 优化：真实凭据接入与 OAuth 安装不混称；身份自动识别，不让用户抄 Account ID | R03/R06/R07，A02 |
| 选连接、刷新、验证、同步仓库目录 | 优化：请求绑定所选连接，失败不清空另一连接的结果；验证/同步是不同结果 | R04/R25，F01/A02 |
| 撤销、确认/取消撤销 | 保留明确目标确认和新执行影响；Scope 在确认期间变化应作废而非改用新 Scope | R04/R25，F01/A02 |
| 选仓库、打开导入、开始导入 | 优化：连续授权/自动 Key/明确项目；不要求用户查 ProviderBinding | R02/R03/R07，A02 |
| 查看进度、取消、失败重试、重新读状态 | 优化：取消不再写正在导入，网络恢复定位原 job，不另建任务 | R36，A02 |
| 关闭导入、刷新/离开返回、完成后继续 | 优化：明确后台是否继续，原任务可恢复，完成后直接到仓库/原 Coding 任务 | R10/R36，A02/F02 |

## 4. 二级菜单映射与全局可达操作

九个二级菜单逐项对应如下；页面操作复用 §3/§4.3，但从二级入口进入仍要单独检查可见权限、当前团队/项目、未保存输入和返回位置。不能用一级菜单通过替代二级入口验收。

| 二级菜单 | 当前路由 | 页面操作清单 |
| --- | --- | --- |
| Agent 配置 | `/settings/agents` | §3.11 Agent 中心 |
| 模型与凭证 | `/settings/models` | §3.12 模型与凭证 |
| 受管仓库 | `/settings/repositories` | §3.13 仓库设置 |
| GitHub | `/settings/integrations/github` | §3.15 GitHub 集成 |
| 飞书 | `/settings/integrations/lark` | §3.14 飞书与通知 |
| 配置健康 | `/setup` | §3.10 配置中心 |
| 账号 | `/account` | §4.3 账号设置 |
| 团队成员 | `/team/members` | §3.8 团队成员 |
| 运维 | `/operations` | §3.6 运行与发布 |

### 4.1 全局壳、团队/项目与账号菜单

证据：[AppShell](../../crewscope-web/src/components/layout/AppShell.vue)、[ScopeSwitcher](../../crewscope-web/src/components/domain/ScopeSwitcher.vue)、[UserAccountMenu](../../crewscope-web/src/components/layout/UserAccountMenu.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| Logo、一级/二级导航、对话/工作模式、移动底栏 | 优化：首页一致、权限一致、目标参数白名单；九个配置二级入口全部验收，不只测侧栏 | R30，F04 |
| 开关移动菜单、侧栏收起/展开、滑动/外部点击/退出 | 保留可达入口；键盘焦点/叠层/窄屏返回位置一致 | R27/R28，F04 |
| 打开范围、切团队/项目、刷新范围、新建项目 | 优化：切换前处理未保存输入、清旧对象坐标，当前范围可读，创建后精确进入 | R04/R07/R24/R30，A01/F01/F05 |
| 主题轮换、密度切换 | 保留即时效果；坏偏好可重置、系统主题变化同步，不影响业务权限或内容缓存 | R28，F04 |
| 通知铃铛 | 保留去 Inbox；补真实未读/待处理提示及加载失败说明，不用假计数 | R12/R26，A06/F04 |
| 账号菜单、账号设置、退出当前设备 | 保留服务端退出及失败反馈；外点/键盘关闭统一，成功后清理业务缓存，和退出全部设备区分 | R18/R27，F04/F05 |

### 4.2 搜索与命令菜单

证据：[SearchPage](../../crewscope-web/src/pages/SearchPage.vue)、[CommandPalette](../../crewscope-web/src/components/action/CommandPalette.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 提交查询、切类型/清类型、续页、同条件重试 | 优化：使用已提交条件、真重试、续页错误保留已有结果 | R20，F04 |
| 点击结果、浏览器返回、深链查询 | 优化：授权目标精确、保留查询位置，范围文案准确 | R20/R30，F04 |
| 打开/关闭命令面板、查动作/对象 | 优化：不清空背后的搜索页，离线动作与对象查询状态区分 | R20，F04 |
| 最近访问、箭头/Enter 选项、中文输入 | 优化：账号隔离、稳定 ID、可见与选择顺序一致、不误触 IME | R18/R27，F04/F05 |
| 导航/创建/视图/执行控制命令、快捷键帮助 | 保留 action registry 的权限/availability；命令与页面同一影响确认，帮助退出恢复焦点 | R25/R27，F04 |

### 4.3 账号设置

证据：[AccountPage](../../crewscope-web/src/pages/AccountPage.vue)、[AccountWorkspace](../../crewscope-web/src/components/account/AccountWorkspace.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 资料/安全/会话子导航 | 保留分区；键盘和窄屏能到实际区域，固定栏不挡标题 | R27，F04 |
| 编辑资料、保存、取消 | 保留改用户名/邮箱验证当前密码；冲突保留非敏感输入，具体错误可读 | R24/R25，F04/F05 |
| 修改密码、取消、重新登录 | 保留现有“修改密码并重新登录”及全会话失效说明，不重复登记成静默退出缺陷 | R25，F04 |
| 退出全部设备、密码确认/取消 | 保留作用范围与确认；失败留可操作说明、密码不落盘 | R25/R27，F04 |
| 主题/密度、本人的通知偏好 | 主题密度保留；通知入口缺口与管理权限分离 | R28/R31，A07/F04 |
| 账号加载失败重试 | 优化：修正错误参数接线，区分会话失效与普通读取失败 | R25，F04 |

### 4.4 登录、注册、邀请、初始化与异常返回

证据：对应 [Login](../../crewscope-web/src/pages/LoginPage.vue)、[Register](../../crewscope-web/src/pages/RegisterPage.vue)、[Invite](../../crewscope-web/src/pages/InvitePage.vue)、[Onboarding](../../crewscope-web/src/pages/OnboardingPage.vue)、[AccessDenied](../../crewscope-web/src/pages/AccessDeniedPage.vue)、[NotFound](../../crewscope-web/src/pages/NotFoundPage.vue)。

| 操作 | 产品结论/需要达到的结果 | 归属 |
| --- | --- | --- |
| 登录、密码显示/隐藏、保持登录、去注册 | 保留实际能力与字段语义；失效会话返回原任务，HTTP 条件可用，不增加不存在的找回密码承诺 | R01/R10/R27，F01/F02 |
| 注册提交/重试、返回登录 | 优化：远端 HTTP 不抛 UUID 错误，关闭注册/邀请限制说明明确，重复提交可恢复 | R01/R06，F01/F02 |
| 邀请预览、登录/注册中转、接受/重试 | 优化：显示团队/角色/当前身份，精确进入受邀团队，过期/撤销/已接受各有下一步 | R29，A07/F02 |
| 初始化团队、创建项目、继续配置/开始工作 | 优化：业务名称优先，按只对话/Coding 目标前进，刷新能继续 | R07/R10，A01/F02 |
| 无权限返回 Today/切团队、404 返回/搜索 | 保留安全说明，不泄漏不可见对象；保留有效 Scope/原目标，不能陷入返回后再拒绝循环 | R25/R30，F04 |

## 5. 如何验收，避免再把“菜单存在”当成“操作可用”

- S01 将表中操作落到测试用例，保留菜单/角色/前提/输入/目标/预期/证据字段；变体复用同一合同，但不得跳过实际页面接线。新增/隐藏菜单需更新清单。
- 每个菜单至少验证普通成员与管理员可见性；每个写动作至少覆盖成功、校验失败/无权限、网络失败或结果未知、重复提交、切 Scope/对象、取消/返回。只读动作覆盖空/分页/失效目标/失败后继续。
- 高风险动作包括成员撤权、连接撤销、映射撤销、任务取消、审查拒绝、GitHub 写入、运维代际切换：逐个验影响说明、确认目标固定与结果查询，不能把“全局有确认弹窗”当作接线完成。
- 未实现的基本编辑、模型重启用、成员生命周期等写侧有独立 API/版本/授权测试；不只新增按钮，不降低后端边界。
- 本次三个条件模拟的观察：审计导出用已应用旧条件；飞书验证回执与当前 B 成员组合；GitHub CANCELLED 显示“正在导入”。其余新增问题为源码/接口路径确认，均待修复后固定回归与真实浏览器验证。
- Q02 由自动化或编码代理在浏览器逐操作执行，记录目标/参数/结果、失败步骤与输入保留，附 trace/截图，不要求真人测评；需要外部凭据/写权限的行单独申请授权，未执行保持待执行，不自动启动服务或发送真实通知/PR。

本轮文档校验：`node scripts/check-doc-links.mjs` 通过（401 个 Markdown 文件），`git diff --check` 通过。现有相关回归执行 `npx vitest run src/components/domain/AuditExplorer.spec.ts src/components/domain/LarkNotificationAdmin.spec.ts src/components/domain/TeamObserverWorkspace.spec.ts src/domains/teamobserver/store.spec.ts src/components/domain/ModelConnectionDetail.spec.ts`，5 个文件、31 个测试通过。这是既有行为基线，不代表新增反例已经修复；M9b 仍未开始。

本文件没有声称每个按钮已经实机通过；它把本轮审查从页面级细化为菜单内操作级，并把缺口交给 M9b，而不是另开一轮无边界的美化项目。
