# M1-Q01：Release Gate

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 适用范围：M1 Team、WorkItem、Responsibility、API 与 Web

## 目标

把 M1 的领域、应用、PostgreSQL、API 和 Web 验证收敛为一个可重复执行的 Release Gate。Gate 覆盖 Team 创建、默认 Personal Agent、WorkItem、责任策略、并发、迁移、权限、Cursor、响应式、视觉回归、可访问性和竞品差异。

## 统一入口

```bash
./scripts/m1-release-gate.sh
```

执行顺序：

```text
Docker 与固定 AgentScope Sandbox 镜像
  -> Markdown 链接与工作区 Whitespace
  -> Maven clean verify
  -> pnpm frozen install
  -> Vitest coverage
  -> TypeScript + Vite build
  -> Histoire build
  -> Playwright 交互、视觉与 Axe WCAG 扫描
```

固定 Sandbox 镜像为 `maven@sha256:29a1658b…f939d4`。PostgreSQL 与 Redis 通过 Testcontainers 启动；空库迁移和 V5→V6 升级均为阻断项。前端 Coverage 门槛为 Statements 80%、Branches 70%、Functions 75%、Lines 80%。

## 十二项验收矩阵

| # | 验收结果 | 自动化与证据 |
|---:|---|---|
| 1 | 创建 Team 后创建者成为唯一 Team Owner | `TeamCreationServiceTest.persistsTheCompleteFoundationInsideOneRequiredTransaction`、`M1JpaPersistenceIntegrationTest.persistsTheCompleteTeamFoundationAndReturnsOneDefaultPersonalAgentForRetries` |
| 2 | Team、默认 Workspace、Owner Membership 与默认 Personal Agent 原子创建 | `TeamCreationServiceTest` 事务失败回滚用例、`M1JpaPersistenceIntegrationTest.commitsTeamFoundationEventOutboxAndReceiptAndReplaysAtomically` |
| 3 | 并发初始化只产生一个默认 Personal Agent | `DefaultPersonalAgentServiceTest.concurrentInitializationCommitsOnlyOneDefaultAgent`、`M1JpaPersistenceIntegrationTest.serializesConcurrentDefaultPersonalAgentInitializationByTeamMember`、V6 active 默认 Profile 唯一索引 |
| 4 | Native WorkItem 始终有唯一 ACTIVE Owner | `WorkItemCommandServiceTest`、`M1JpaPersistenceIntegrationTest.serializesConcurrentWorkItemKeysToOneCommittedItemEventAndReceipt`、V6 active Owner 部分唯一索引 |
| 5 | 默认策略阻止 Owner/Executor 兼任 Gate Reviewer | `ReviewerEligibilityPolicyTest`、`GateReviewerAssignmentServiceTest.doesNotPersistWhenStrictDutySeparationRejectsTheReviewer` |
| 6 | 单人团队降级允许本人 Gate Review 并生成审计证据 | `GateReviewerAssignmentServiceTest.returnsAuditEvidenceWhenSingleMemberPolicyAllowsSelfReview`、`ReviewerEligibilityPolicyTest` |
| 7 | 未授权主体无法读取 Team、WorkItem、责任与 Timeline | `TeamApplicationServiceTest` 权限矩阵、`WorkItemQueryServiceTest`、`ResponsibilityCommandAndQueryServiceTest`、`WorkItemTimelineServiceTest.rejectsSuspendedMembershipAndMismatchedUrlScopesBeforeReadingEvents`、`M1JpaPersistenceIntegrationTest.hidesEveryM1LookupBehindTheExplicitOrganizationBoundary` |
| 8 | WorkItem 与 Owner 并发修改只提交一个结果并返回稳定冲突 | `M1JpaPersistenceIntegrationTest.acceptsOneOfTwoConcurrentTransitionsFromTheSameCommittedVersion`、`serializesConcurrentOwnerReplacementsFromTheSameExpectedAssignment`、`WorkItemControllerTest.mapsOptimisticConflictWithTheCurrentVersion` |
| 9 | 刷新和 Cursor 续传后责任与 Timeline 一致 | Playwright `Responsibility and Timeline facts remain consistent after reload and Cursor replay`，并校验 Timeline `eventId` 去重 |
| 10 | 桌面与窄屏的 List、Board、详情和责任链可用 | Playwright 16 个场景在两种视口执行 32 次；详情深链接、Tab 循环、Escape 与 Focus 恢复通过；6 份 M1 视觉基线通过 |
| 11 | 与两个竞品具有可验证的产品和视觉差异 | [前端竞品差异与可访问性审查](../reviews/M1-Q01-前端竞品差异与可访问性审查.md)，固定源码提交并审查导航、布局、Token、组件、任务流与 Runtime 定位 |
| 12 | 全量后端、前端与浏览器 Gate 通过 | `./scripts/m1-release-gate.sh` 全流程通过；GitHub Actions 保持 Backend、Frontend、Quality、Release Gate 四 Job 拓扑 |

## 纵向与数据验证

`M1JpaPersistenceIntegrationTest.commitsAndQueriesTheResponsibilityChainWithIdempotentEventsAndRelease` 在真实 PostgreSQL 中完成 Team Foundation、WorkProject、Native WorkItem、Owner、Executor、Gate Reviewer、Release、责任查询、DomainEvent、Outbox 和 CommandReceipt 纵向链路。

同一集成测试类继续覆盖：

- Team Foundation 与默认 Personal Agent 的事务写入和幂等重放；
- 遗留 Team 并发补全、成员并发加入和身份并发映射；
- WorkProject Key、WorkItem Key、WorkItem Version 与 Owner 责任链并发；
- Comment、ResourceLink、Responsibility 和 Timeline 的持久化、分页、排序与去重；
- Organization、Team、Workspace、WorkProject 和 WorkItem Scope 隔离。

`V6TeamWorkResponsibilityMigrationIntegrationTest` 从空库和 V5 两条路径验证表、列、延后外键、唯一索引、遗留数据保留、主体资格和 active 责任唯一性。

## 前端加固

M1-Q01 新增：

- `@axe-core/playwright` 自动可访问性门禁；
- Playwright 固定为 2 个浏览器 Worker，避免 Docker 集成测试后的浏览器启动资源竞争；
- Today、Work、Team Members、WorkItem Detail 的 WCAG 2.2 AA 扫描；
- 责任命令后的刷新、页面 Reload、Timeline Cursor 重放与事件去重场景；
- Work List、Board、Detail 在桌面和窄屏的 6 份截图基线；
- ARIA Table 行列语义、导航与次级文字对比度修复；
- StatusBadge 语义颜色不再被详情容器选择器覆盖。

提交前整体 Review 继续收紧产品和交互边界：

- Conversation 的 M2 能力统一改为“原型预览/交互蓝图”，移除虚假执行状态、运行 ID、进度、耗时和变更统计；
- Work 空结果一次性更新类型与优先级 Query，避免连续路由替换互相覆盖；
- ScopeSwitcher 使用与非模态展开区域一致的 ARIA 语义；
- Team Member 目录移除无行为的复制图标，避免把装饰误认为可操作按钮；
- Work 空状态动作使用 `StatePanel` 的命名 Action Slot，恢复首项创建和清除筛选按钮；
- Playwright 固定浏览器业务时钟，避免 Today 日期跨日导致无业务变化的视觉基线漂移；
- WorkItem Key 明确为当前已加载集合上的可编辑建议，服务端唯一约束仍是最终边界。

## 验证结果

### 后端

```text
Maven modules       7 / 7 SUCCESS
Tests               405
Failures            0
Errors              0
Skipped             0
Migrations          empty -> V6 and V5 -> V6
Docker Sandbox      executed
PostgreSQL/Redis    executed through Testcontainers
```

### 前端

```text
Vitest files        13
Vitest tests        55 passed
Statement coverage 86.46%
Branch coverage    80.11%
Function coverage  85.71%
Line coverage      88.00%
Histoire            4 stories / 12 variants
Playwright          16 scenarios x 2 viewports = 32 passed
Axe WCAG scan       4 pages x 2 viewports, 0 violations
Visual baselines    6 M1 Work screenshots passed
Vite build          passed
```

### 文档与 CI

- 63 份 Markdown 本地链接通过；
- `git diff --check` 通过；
- CI 使用 macOS 14 固定视觉光栅化环境，并上传 Coverage、Vite、Histoire、Playwright Report 与失败 Trace；
- Release Gate Job 强制 Backend、Frontend 与 Quality 全部成功。

## M1 验收结论

M1 十二项出口条件全部通过。Team、WorkItem、责任和观察闭环达到可重复验证的发布基线。下一里程碑为 M2 Conversation 与 Personal Agent：实现真实 Conversation、Message、Agent Session、Provider Binding 与 TaskIntent。
