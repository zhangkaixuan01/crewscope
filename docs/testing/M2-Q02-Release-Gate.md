# M2-Q02 Release Gate

> 日期：2026-08-12<br>
> 状态：已完成<br>
> 适用范围：M2 Conversation、Personal Agent、TaskIntent、ProviderBinding、Native WorkItem 与 Web

## 1. 结论

M2 Release Gate 全部通过。Conversation、Personal Agent、结构化澄清、TaskIntent 确认、Native WorkItem 建档、双向跳转、安全边界、断线恢复和响应式界面达到可重复验证的发布基线。

M2 使用可控 Model、PostgreSQL/Redis Testcontainers 和浏览器 API Contract Fixture，发布验证不依赖外部模型和真实 Provider 网络状态。

## 2. 统一入口

```bash
./scripts/m2-release-gate.sh
```

执行顺序：

```text
Docker、Node 24 与固定 AgentScope Sandbox 镜像
  -> Markdown 链接与工作区 Whitespace
  -> Maven clean verify
  -> pnpm frozen install
  -> Vitest coverage
  -> TypeScript 与 Vite build
  -> Histoire build
  -> Playwright Chromium install
  -> Playwright 交互、恢复、视觉与 Axe WCAG 扫描
```

固定 Sandbox 镜像为 `maven@sha256:29a1658b…f939d4`。Maven 使用 Java 17 Release 编译；PostgreSQL、Redis 和 Docker Sandbox 均在 Gate 中真实运行。

## 3. 十四项验收矩阵

| # | 验收结果 | 自动化证据 |
|---:|---|---|
| 1 | PRIVATE Conversation 自动创建 Owner 与默认 Personal Agent Participant | `ConversationApplicationServiceTest.createsOwnerAndAgentParticipantsFromCurrentServerFacts`、`M2JpaPersistenceIntegrationTest.roundTripsConversationMessagesAndStableKeysetPages` |
| 2 | TEAM Conversation 发现与写入分别遵循 Membership 和 Participant | `ConversationApplicationServiceTest.listsOnlyPolicyDiscoverableRowsAndBindsTheQueryToTheActor`、`requiresAnActiveUserParticipantEvenWhenTeamConversationIsDiscoverable` |
| 3 | 同 Session FIFO，不同 Session 并行 | `HarnessAgentM2S02ConcurrencyIntegrationTest.sameSessionCallsEnterModelInSubscriptionFifoOrder`、`differentSessionsEnterModelBeforeEitherTurnCompletes` |
| 4 | RuntimeContext 和身份控制面全部由服务端生成 | `PlatformExecutionContextResolverTest`、`PlatformMiddlewareM2I04Test`、`PersonalAgentInvocationControllerTest.rejectsClientRuntimeControlFieldsBeforeServiceInvocation` |
| 5 | 多轮澄清生成通过 Schema、Bean 与业务校验的 TaskIntent | `HarnessAgentM2S03StructuredConversationIntegrationTest`、`AgentScopeNativeRuntimeIntegrationTest.interruptsForClarificationAndResumesTheSameStructuredInvocation` |
| 6 | TaskIntent 确认原子创建 WorkItem、责任与 ConversationWorkItemLink | `TaskIntentConfirmationServiceTest.confirmsAndCreatesTheCompleteNativeWorkItemGraphOnce`、`M2JpaPersistenceIntegrationTest.confirmsTaskIntentAndCreatesNativeWorkItemFactsInOnePostgresTransaction` |
| 7 | 确认失败不遗留 WorkItem、责任和关联孤儿 | `TaskIntentConfirmationServiceTest.stopsBeforeConfirmationEventsWhenAnyResponsibilityCannotBeCreated`、`M2JpaPersistenceIntegrationTest.rollsBackTheEntireConfirmationGraphWhenPublicationFails` |
| 8 | SSE 中断、重放、刷新和 Cursor 恢复无重复遗漏 | `ReplayableExecutionSegmentTest`、Playwright 的断线同键重放、刷新恢复和耐久 Cursor 恢复场景 |
| 9 | 模型失败、Retry、Fallback、Usage、取消与恢复关联到可信 Trace 上下文 | `AgentCallObservabilityM2I07Test`、`AgentScopeNativeRuntimeIntegrationTest`、`TracePropagationIntegrationTest` |
| 10 | ProviderBinding 歧义、撤销和越权均失败关闭 | `ProviderBindingResolverTest`、`ProviderBindingCandidateTest.closesActiveCandidateAndFailsAfterGrantRevocation`、`M2JpaPersistenceIntegrationTest.resolvesProjectCandidateAndFailsClosedThroughJpaFacts` |
| 11 | Conversation Mode 与 Control Mode 使用同一关联事实双向跳转 | `ConversationWorkItemQueryServiceTest`、Playwright 的 TaskIntent 确认、查看工作项、返回对话和刷新恢复场景 |
| 12 | 桌面与窄屏的消息、澄清、TaskIntent、离线、键盘、视觉和可访问性通过 | Playwright 36 个场景在两个视口执行 72 次；Conversation 视觉基线与 5 页 Axe WCAG 2.2 AA 扫描通过 |
| 13 | 页面不展示虚构 TaskExecution、AgentRun、进度、Tool、Diff 或 Artifact | Playwright `Agent execution entry remains an explicit non-executing placeholder`、公开 AG-UI 白名单测试 |
| 14 | M2 数据迁移覆盖空库、V6→V7 和非默认 `search_path` | `V7ConversationAgentProviderMigrationIntegrationTest.createsAllM2TablesAuditColumnsConstraintsAndIndexesFromEmptyDatabase`、`upgradesPopulatedV6DatabaseWithoutChangingExistingFacts`、`migratesV7IntoCrewscopeWhenConnectionUsesNonDefaultSearchPath` |

## 4. 纵向验证

M2 使用三条确定性纵向链路：

- AgentScope 可控 Model 完成多轮消息、结构化 TaskIntent、澄清 Interrupt/Resume、失败、取消与恢复；
- Spring 事务与真实 PostgreSQL 完成 Message、TaskIntent、Native WorkItem、责任、关联、DomainEvent、Outbox 和 CommandReceipt 原子提交；
- 浏览器从 Conversation 输入开始，完成公开 AG-UI 流、结构化澄清、TaskIntent 预检确认、Control Mode 查看、返回对话、刷新和 Cursor 恢复。

外部模型、GitHub 和飞书真实网络调用属于 Provider 上线验收，不进入 M2 Release Gate。

## 5. 验证结果

### 后端

```text
Maven modules       7 / 7 SUCCESS
Tests               698
Failures            0
Errors              0
Skipped             0
PostgreSQL/Redis    Testcontainers executed
Docker Sandbox      executed
Migrations          empty -> V7, V6 -> V7, V7 -> V8, V8 -> V9
```

### 前端

```text
Vitest files        30 passed
Vitest tests        119 passed
Statement coverage 87.51%
Branch coverage    83.01%
Function coverage  85.82%
Line coverage      88.86%
Histoire            5 stories / 20 variants
Playwright          36 scenarios x 2 viewports = 72 passed
Axe WCAG scan       5 pages x 2 viewports, 0 violations
Visual baselines    10 screenshots passed
Vite build          passed
```

### 文档与 CI

- Markdown 本地链接检查通过；
- `git diff --check` 通过；
- CI 保持 Backend、Frontend、Quality、Release Gate 四个阻断 Job；
- CI 上传后端测试、Coverage、Vite、Histoire、Playwright Report 和失败 Trace。

## 6. M2 验收结论

M2 的十四项出口条件全部通过。下一阶段进入 M3 耐久 Task Runtime 执行清单拆分。
