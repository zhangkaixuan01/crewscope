# M3-F07：Task 前端全状态与质量硬化

## 目标

完成 Conversation Mode 与 Control Mode 的 Task 页面状态、键盘、无障碍、桌面/窄屏和视觉回归基线，使异常、终态与恢复过程都能保留已加载的服务端事实，并提供明确的下一步。

## 已交付能力

- Control Mode Task 列表覆盖 Loading、Empty、Error 与显式重试，服务端筛选和已选 Task 使用可访问状态表达；
- Task 列表、详情、创建、控制、Runtime、Fleet、事件和关联 API 返回 403 时进入统一 Access Boundary，页面不把资源级拒绝降级为普通空状态；
- Task 详情在后台刷新时保留已加载事实并提示同步状态，刷新失败时保留旧事实和显式刷新入口；
- Cancelled 形成独立终态提示，说明耐久历史、已产生结果和审计证据继续保留；
- Recovering、continuity gap、SSE projection gap、Connecting、Reconnecting 与实时错误统一使用紧凑状态语义，Timeline 在恢复期间继续展示已有历史；
- Offline 保留 AppShell 全局状态和 Task 控制失败关闭，网络恢复前不提交成员命令；
- Conflict 保留提交版本、服务端版本、权威回读和终态竞态收敛，不执行乐观状态更新；
- WorkItem、Task 和委托/控制确认等叠层对话框只允许最上层 Modal 响应 Escape 和 Tab；打开时设置对象级初始焦点，关闭 Task 后焦点进入仍可见的 WorkItem 抽屉，不落到背景控件；
- Attempt 历史使用语义列表包裹原生按钮，选择状态保持 `aria-pressed`，恢复告警颜色达到 WCAG 2.2 AA 对比度；
- Loading 与 Reconnecting/Recovering 动画继续尊重 Reduced Motion。

## 视觉来源与 CrewScope 差异

- 参考 [vibe-kanban](https://github.com/BloopAI/vibe-kanban) 的执行优先信息层级、列表到详情的快速切换和紧凑状态密度；
- 参考 [multica](https://github.com/multica-ai/multica) 的对话工作区、Agent 执行入口与卡片化上下文组织；
- CrewScope 使用浅色低饱和团队工作台风格，保留品牌浅绿、白色事实卡和克制告警色，不复刻竞品布局、组件比例或视觉资产；
- CrewScope 的主视觉对象是团队耐久 Task、责任快照、attempt、Plan、Step、AgentRun、Lease、Timeline、控制权和审计事实，不是本地 Coding Agent 进程或个人 Kanban Session；
- Conversation 与 Control 两个入口读取同一服务端 Task 事实，实时流只触发权威回读，视觉状态不替代权限、版本和 Runtime 事实。

## 验证

- Vitest：42 个文件、180 项测试通过；
- 覆盖率：Statements 85.55%、Branches 78.25%、Functions 87.03%、Lines 88.19%，全部达到仓库门槛；
- Playwright：102 项桌面与窄屏 E2E 通过，覆盖 Conversation/Control 双入口、全状态、叠层 Modal 键盘和焦点恢复；
- Axe：Conversation Task 卡、Control Task 列表与 Task 详情纳入 WCAG 2.2 A/AA 扫描，严重及以上问题为 0；
- 视觉回归：Conversation Task 卡、Work 列表/看板、WorkItem 详情和 Task 详情的桌面与窄屏基线通过；
- TypeScript 类型检查与 Vite 生产构建通过；
- Reduced Motion、窄屏横向溢出和敏感运行字段缺失断言继续通过。

## 下一项

`M3-Q01` 执行 Task/Worker 安全硬化与固定攻击集验证。
