# CrewScope 前端设计规范

> 文档版本：v1.0  
> 对应设计：`CrewScope 团队协作式 AI 工作执行平台设计文档 v4.0`  
> 适用工程：`crewscope-web`  
> 技术基线：Vue 3、TypeScript、Vite

## 1. 设计目标

CrewScope 前端服务于技术团队中的人机协作执行。界面需要让成员快速理解五件事：目标是什么、谁负责任、谁在执行、当前需要什么决策、交付证据在哪里。

设计目标：

1. 对话可直接发起工作，管理页面可稳定维护和检索工作；
2. 成员、Personal Agent、Team Agent 和 Specialist Agent 的身份与动作清晰可辨；
3. 责任、进度、风险、Review、授权和审计信息保持可见；
4. 流式执行具备实时反馈，刷新、断线和恢复后回到一致事实；
5. 高信息密度场景仍具备清晰层级、键盘效率和可访问性；
6. 建立独立、稳定、可扩展的 CrewScope 视觉语言。

## 2. 产品界面模型

### 2.1 Conversation Mode

Conversation Mode 是自然语言工作入口，由以下区域组成：

- 对话流：成员消息、Agent 消息、澄清、计划、协作与确认卡片；
- 上下文栏：Team、WorkProject、WorkItem、仓库、文件、Provider 和参与者；
- 执行画布：Plan、Step、ToolCall、终端、Diff、测试、Artifact 和成本；
- 决策区：Review Gate、Confirmation、Handoff、Takeover、Pause、Resume 和 Cancel；
- 责任区：Owner、Executor、Reviewer、Agent Presence 和当前阻塞方。

成员可以从任意执行对象打开 Control Mode 详情，也可以把 Control Mode 中的对象带回对话继续处理。

### 2.2 Control Mode

Control Mode 是传统 Web 管理入口，覆盖：

- Team、Member、WorkProject、WorkItem 和 Responsibility；
- Inbox、Review、Handoff、Takeover 和 Notification；
- Task、Execution、Artifact、Action、Activity 和 Audit；
- Agent、Skill、Provider、Connection、Runtime 和 Usage；
- Policy、权限、风险、配额和组织设置。

列表、看板、表格、WorkGraph 和时间线是同一份事实的不同投影。视图切换保留筛选、排序与选中对象。

### 2.3 同事实联动

```text
Conversation Mode ── Command / Query ──┐
                                      ├─ Application / Domain ─ Projection
Control Mode ─────── Command / Query ──┘
       ↑                                      │
       └──── AG-UI / Team Event / Cursor ─────┘
```

AG-UI 展示增量输出，领域 Query 展示最终事实。页面在接收 Command Receipt 后等待目标投影追上 `domainEventId/committedVersion`，随后清理 optimistic state。

## 3. 信息架构

### 3.1 全局框架

```text
AppShell
├── ScopeSwitcher：Organization / Team / WorkProject
├── PrimaryNavigation
│   ├── Today：团队首页 / 我的工作 / 待处理
│   ├── Work：WorkGraph / WorkItem / WorkProject
│   ├── Collaborate：Conversation / Inbox / Review
│   ├── Observe：Task / Activity / Audit
│   ├── Capabilities：Agent / Skill / Provider / Connection
│   └── Governance：Member / Policy / Usage / Settings
├── ContextHeader：面包屑 / 状态 / 责任 / 主动作
├── MainWorkspace：页面主内容
├── ContextDrawer：责任 / 执行 / Review / Artifact
└── CommandLauncher：搜索 / 跳转 / 新建 / 快捷动作
```

导航分组随权限裁剪，URL 始终保留当前 Team 和目标对象。对话入口在所有工作页面可见。

### 3.2 页面模板

| 模板 | 适用页面 | 结构 |
|---|---|---|
| Team Pulse | Today、团队首页 | 责任摘要、待处理、风险、运行中任务和团队动态 |
| Collection | WorkItem、Task、Inbox、Audit | 筛选栏、视图切换、结果区和详情抽屉 |
| Work Detail | WorkItem、Task、Review | 上下文头、主事实、时间线和责任/制品侧栏 |
| Execution Studio | Conversation、Coding Task | 对话、执行画布和上下文抽屉的可调整多面板 |
| Configuration | Agent、Provider、Policy、Settings | 设置导航、表单、权限说明和变更记录 |

### 3.3 多视图策略

- List：默认通用视图，适合快速扫描和移动端；
- Board：按状态、责任人或阶段组织工作；
- Table：适合字段密集、批量比较和治理场景；
- WorkGraph：展示依赖、阻塞和上下游交付；
- Timeline：展示执行、协作、Review、动作和审计顺序。

M1 交付 List 与 Board，Table 随字段体系稳定后交付，WorkGraph 和执行 Timeline 按对应里程碑交付。

## 4. 视觉身份

### 4.1 色彩 Token

初始 Token 作为实现基线，后续只通过 Token 调整主题：

```css
:root {
  --cs-brand-950: #15231d;
  --cs-brand-800: #263a31;
  --cs-brand-600: #3f7257;
  --cs-brand-300: #8ed5a7;
  --cs-brand-200: #b8efca;
  --cs-canvas: #f3f5f2;
  --cs-surface: #ffffff;
  --cs-surface-subtle: #fafbf9;
  --cs-border: #dce2dd;
  --cs-text: #17202a;
  --cs-text-muted: #68766e;
  --cs-info: #3f78b5;
  --cs-agent: #7257b5;
  --cs-warning: #b7792c;
  --cs-danger: #b84c4c;
  --cs-success: #43845e;
}
```

语义状态从上述基色派生文字、背景和边框三档。风险、责任和审批状态同时提供图标与文字。

### 4.2 字体

- UI 与正文：`Inter, ui-sans-serif, system-ui, sans-serif`；
- 品牌标题与关键空状态：`Georgia, ui-serif, serif`；
- ID、代码、Commit、日志和数值：`ui-monospace, SFMono-Regular, monospace`；
- 工作页面正文默认 14px，辅助信息 12px，标题依次为 18/24/32px；
- 数字指标启用 tabular numbers，日志和 Diff 保留等宽对齐。

Serif 只用于低频识别元素，表格、表单、导航和执行信息使用 Sans Serif。

### 4.3 空间、形状与层级

- 间距基数为 4px，常用间距为 4/8/12/16/24/32；
- 控件高度为 32/36/40px，触摸目标不低于 44px；
- 圆角为 8/12/16px，Badge 可使用全圆角；
- 工作区主要依靠边框和 Surface 层级，阴影只用于浮层、拖拽和焦点对象；
- 内容区最大宽度由页面模板决定，执行画布和数据表不设置文章式窄宽度。

### 4.4 动效

- Hover 与 Focus：120ms；
- 抽屉、Popover 和面板切换：160–200ms；
- 执行状态迁移：200–240ms；
- 流式内容使用低干扰增量反馈，避免持续闪烁和大面积骨架动画；
- `prefers-reduced-motion` 下关闭位移、缩放和非必要循环动画。

## 5. CrewScope 核心组件

| 组件 | 必备信息 | 主要使用位置 |
|---|---|---|
| `ResponsibilityChain` | 角色、主体、有效期、来源、冲突和待接手状态 | WorkItem、Task、Review、Handoff |
| `AgentPresence` | Agent 类型、状态、当前步骤、模型/Runtime、接管入口 | 对话、执行画布、团队首页 |
| `WorkItemCard` | Key、目标、状态、Owner、Executor、风险、证据 | 列表、看板、对话引用 |
| `TaskTimeline` | PlanVersion、Step、Tool、等待、恢复、耗时和成本 | Task 详情、执行抽屉 |
| `ReviewGateCard` | Reviewer、资格、检查项、Finding、Decision | Inbox、对话、WorkItem 详情 |
| `ActionReceiptCard` | 动作、风险、确认人、外部回执、对账状态 | 对话、Task、Audit |
| `ArtifactPreview` | 类型、版本、来源、哈希、预览和下载策略 | Diff、报告、测试证据 |
| `TeamActivityItem` | Actor、动作、目标、时间、来源和 Correlation | 首页、Activity、详情时间线 |

组件 Props 使用领域 DTO，不在视图内部重建责任、权限和状态机规则。组件在完整页、抽屉和对话卡片中共享状态语义。

## 6. 关键交互

### 6.1 对话、任务与 Diff 联动

1. 对话消息创建或引用 WorkItem；
2. 确认 TaskIntent 后打开 Execution Studio；
3. Plan 与 Step 在执行画布持续更新；
4. 文件变化进入 Diff 索引，选中文件定位到对应 Step 和 ToolCall；
5. 行内评论生成 Review Context 或后续 WorkItem；
6. 测试证据与验收标准并列展示；
7. Review Gate 通过后显示 PlannedAction 和 Action Receipt。

工作区默认突出当前需要人处理的决策，运行日志和低层工具输出按需展开。

### 6.2 责任与协作

- 任何 WorkItem 和 Task 都可在一屏内确认 Owner、Executor 和 Gate Reviewer；
- Responsibility 变化先预览影响，再提交 Command；
- 请求协助、Handoff 和 Takeover 使用结构化卡片，展示范围、证据、权限和截止时间；
- 当前阻塞方显示在页面头、卡片和时间线中；
- Agent 执行与人工责任使用不同视觉标识，人工责任主体始终可追溯。

### 6.3 反馈与错误

- Command 立即返回进行中反馈，并通过 Receipt 跟踪最终状态；
- 乐观锁冲突展示当前事实与用户草稿，支持重新应用有效变更；
- 网络断开显示最后同步时间、各事件流 Cursor 和恢复状态；
- 长任务提供 Pause、Resume、Cancel 和 Takeover，按钮按权限与状态显示；
- 危险动作展示目标、范围、身份、风险、回滚/补偿方式和确认有效期。

## 7. 响应式与可访问性

### 7.1 响应式

| 宽度 | 布局策略 |
|---|---|
| `>= 1440px` | 展开导航、主工作区和上下文抽屉，可用三面板 Execution Studio |
| `1024–1439px` | 折叠导航，上下文抽屉按需覆盖，保留双面板执行区 |
| `768–1023px` | 单主栏，详情与执行区使用页签，表格降级为列表 |
| `< 768px` | 聚焦查看、评论、审批、确认和接管；复杂图与 Diff 提供摘要和跳转 |

桌面端优先交付，所有新组件从 M0 起保留窄屏行为，不使用全局固定最小宽度阻断页面访问。

### 7.2 可访问性

- 键盘可到达所有交互控件，焦点顺序与视觉顺序一致；
- Focus Ring 清晰可见，Dialog/Drawer 正确管理焦点与 Escape；
- 语义 HTML 和 ARIA 描述覆盖状态、进度、错误与流式区域；
- 正文和关键控件达到 WCAG 2.2 AA 对比度；
- 状态不单独依赖颜色，图表提供文本摘要；
- 新消息、任务状态变化和错误使用可控的 Live Region，避免重复播报。

## 8. 竞品参考与独立性

### 8.1 吸收范围

从 `vibe-kanban` 研究：

- 对话、执行、Diff 和 Git 上下文的联动关系；
- 工具状态、进程状态和执行反馈的可见性；
- 高信息密度研发工作台与快捷操作。

从 `multica` 研究：

- 同一工作数据的列表、看板、表格和 Swimlane 表达；
- Agent、Runtime、Usage、Member 和 Project 的管理控制面；
- 个人工作与团队治理信息的层次组织。

### 8.2 CrewScope 差异

CrewScope 的页面围绕以下对象形成独立体验：

- 人与 Agent 的 Responsibility Chain；
- 团队协作请求与 Contribution；
- 可暂停、恢复、接管的 Task Timeline；
- 人员资格与职责分离约束下的 Review Gate；
- 外部副作用的 PlannedAction、Confirmation 和 Action Receipt；
- 面向团队负责人的 Activity、Audit、Risk 和 Cost 观测面。

### 8.3 实现边界

1. 不复制竞品源码、DOM 层次和组件切分；
2. 不复制 CSS Token、Tailwind Class 组合、主题、图标组合和动效参数；
3. 不复制侧栏分组、页面命名、路由、文案、空状态和品牌资产；
4. 参考模式先写成用户目标，再映射到 CrewScope 领域模型和设计 Token；
5. Vue 组件在 CrewScope 工程中独立实现，并保留设计决策记录；
6. 评审时并排检查参考截图与 CrewScope 截图，确认布局、视觉和任务流具备明显差异。

## 9. 工程规范

### 9.1 目录建议

```text
src/
├── app/                 # AppShell、Router、全局 Provider
├── design/              # Token、图标适配、基础样式
├── components/          # 通用 UI 组件
├── domains/             # Work、Conversation、Task、Review 等领域 UI
├── pages/               # 路由页面与页面编排
├── stores/              # 客户端状态与服务端投影缓存
├── api/                 # Client、DTO、错误与 Cursor
└── test/                # Fixture、Mock Server 与测试工具
```

### 9.2 状态规则

- 服务端事实进入 Query Cache/Store，临时面板、选择和草稿进入本地状态；
- URL 保存可分享的范围、筛选、视图和选中对象；
- AG-UI 增量态与领域投影态分开保存，通过 ID 和版本关联；
- 枚举显示文案、图标和语义色集中映射；
- 权限隐藏提升可用性，服务端授权仍是安全边界。

### 9.3 组件工作台

M0 建立 Histoire 组件工作台，至少覆盖 Token、基础控件与 CrewScope 核心卡片。每个领域组件提供正常、加载、空、错误、无权限、冲突和长内容状态。

## 10. 测试与验收

### 10.1 自动化

- Vitest：Token 映射、状态格式化、组件行为和可访问性断言；
- Histoire：组件状态目录与人工视觉检查；
- Playwright：Conversation/Control 互跳、视图恢复、责任分配和核心纵向流程；
- 截图测试：AppShell、Team Pulse、Collection、Work Detail 和 Execution Studio 关键尺寸；
- 构建检查：TypeScript、Vite Build、路由懒加载和包体预算。

### 10.2 页面完成定义

一个页面完成需要满足：

1. 对应领域事实、Command、Query、权限和错误状态已接入；
2. Loading、Empty、Error、Forbidden、Conflict 和 Offline 状态可用；
3. Conversation Mode 与 Control Mode 之间具有对象级跳转；
4. 键盘、Focus、对比度、Live Region 和 Reduced Motion 通过检查；
5. 桌面、平板和移动降级策略经过验证；
6. 核心路径具备 Vitest 和 Playwright 覆盖；
7. 截图已完成视觉回归；
8. 与参考产品的布局、视觉、组件和任务流差异已在 PR 中说明。

## 11. 分阶段落地

| 里程碑 | 前端重点 |
|---|---|
| M0 | Design Token、基础组件、AppShell、Router、API Client、Histoire、Vitest、Playwright 和截图基线 |
| M1 | Team/WorkProject、WorkItem List/Board、责任链、详情与传统管理闭环 |
| M2 | Conversation Mode、TaskIntent、对话卡片及与 WorkItem 的双向跳转 |
| M3 | Task 列表、Task Timeline、Pause/Resume/Cancel、断线与 Cursor 恢复 |
| M4 | Execution Studio、Diff、终端、测试证据、Artifact 和 Agent Presence |
| M5 | Review Gate、Confirmation、Action Receipt、GitHub Draft PR 交付链 |
| M6 | Team Pulse、Inbox、Activity、Audit、Usage、风险与飞书通知状态 |

每个里程碑同步交付 Conversation Mode 与 Control Mode 中与该能力相关的入口，避免形成只能对话或只能管理的孤立功能。
