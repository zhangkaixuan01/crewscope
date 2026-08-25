# M5-Q02 模型、Review 与 GitHub 交付故障恢复

> 状态：已完成<br>
> 日期：2026-08-25<br>
> 范围：Model Provider、Credential、Agent/Member、Review、Diff、GitHub、ActionReceipt、Webhook 与 Action Worker

## 1. 验收目标

M5-Q02 使用确定性时钟、受控 Provider 异常、Loopback GitHub、真实本地 Git、真实 PostgreSQL/Testcontainers、事务回滚和前端命令重放验证 M5 交付链路。固定不变量如下：

1. Provider 停用、模型限流或凭证撤销在下一模型边界失败关闭，TEAM 执行不回退到 USER Connection；
2. 成员或 Reviewer 离队后，既有配置、Receipt 和页面缓存不能绕过当前责任与资格复验；
3. Diff、测试证据或 Review ETag 变化后，旧 Finding 和 Decision 只保留历史，不能满足当前 Gate；
4. Push 或 Draft PR 写结果不确定时进入查询恢复，禁止直接重放外部写；
5. ActionReceipt 与 Dispatch/Event 在一个事务内提交，回执窗口故障不能产生第二个逻辑 Receipt；
6. Webhook、主动查询、重复事件和乱序事件收敛到一个单调 ExternalResult；
7. Worker 退出或 Lease 过期后只以更大 Fencing Token 进入只读对账；
8. 无法自动证明的 UNKNOWN 在有界次数或时间内进入 MANUAL_REVIEW。

## 2. 恢复协议

```text
模型或凭证故障
  -> 当前 Provider/Connection/Credential Version 复验
  -> PERSONAL 仅使用配置中明确且独立授权的 Fallback
  -> TEAM 仅使用 TEAM/ORGANIZATION Connection
  -> 无可用选择时返回稳定失败

Review 事实变化
  -> 当前成员、责任、Reviewer Eligibility、Diff/Test/Context Hash 复验
  -> 旧 Review INVALIDATED
  -> 新 Diff 创建新 ContextPackage 与 ReviewRequest

外部写结果不确定或 Worker 退出
  -> UNKNOWN 或过期 RUNNING
  -> 新 RECONCILE Claim + 更大 Fencing Token
  -> 合并已提交 Webhook
  -> GitHub query-only Head/PR 查询
  -> 唯一 ActionReceipt + 终态 Dispatch 原子提交
  -> 有界不确定后进入 MANUAL_REVIEW
```

明确证明未产生副作用的限流或 Provider 暂时不可用可以回到 READY 延迟重试。已经进入外部写窗口的超时、连接中断和未分类异常必须进入 UNKNOWN。Push 的成功 Receipt 是 Draft PR 的依赖；Draft PR 不确定或失败不会重新执行 Push。

## 3. 固定故障矩阵

| ID | 故障面 | 固定样本 | 收敛结果 | 自动化证据 |
|---|---|---:|---|---|
| `FR-01` | Provider 停用、模型 429、健康探测限流、TEAM 默认不可用 | 5 | 当前调用安全失败或有界重试；TEAM 不查询 USER Connection | `AgentScopeNativeRuntimeIntegrationTest`、`OpenAiCompatibleModelProviderHealthProbeTest`、`AgentExecutionConfigurationResolverTest` |
| `FR-02` | Credential 轮换、挂起、撤销、版本漂移与旧 Handle | 5 | 旧 Handle 失效，精确版本失败关闭，不泄漏或复用旧 Secret | `ModelConnectionCredentialServiceTest`、`ResolvedAgentScopeModelFactoryM5I05Test`、`CachedModelConnectionAvailabilityVerifierTest` |
| `FR-03` | Agent Owner/成员离队、任务读权限撤销、Connection Grant 撤销 | 4 | 委托、读取或对账复验当前事实；外部写保持关闭 | `TaskAgentSelectionServiceM5A04Test`、`ReviewerExecutionApplicationServiceM5A05Test`、`ActionReconciliationWorkerM5I12Test` |
| `FR-04` | Reviewer Assignment 释放、Reviewer 退出、旧命令重放与旧页面 | 4 | Replay 仍复验资格；Gate 命令拒绝，页面关闭交互 | `ReviewGateApplicationServiceM5A05Test`、`ReviewerExecutionApplicationServiceM5A05Test`、`ReviewWorkbench.spec.ts` |
| `FR-05` | 最终 Diff、Context、Evidence、ETag 与 Review Revision 变化 | 5 | 旧 Review 失效，旧 Finding/Decision 不能控制新 Gate | `JdbcReviewPersistenceM5I07IntegrationTest`、`ReviewFindingBatchRecorderM5I06Test`、`ReviewerExecutionApplicationServiceM5A05Test`、`review/store.spec.ts` |
| `FR-06` | Push 超时、PR 响应丢失、PR UNKNOWN 与部分成功 | 6 | 远端 Head/PR 查询恢复；Push/PR 创建各最多一次 | `GitHubPushProtocolM5I09IntegrationTest`、`GitHubDraftPullRequestProtocolM5I10IntegrationTest`、`ActionWorkerM5I11Test`、`GitHubQueryOnlyProtocolM5I12Test` |
| `FR-07` | Receipt/Event 提交失败、终态更新冲突、重复 Receipt 与命令重放 | 6 | 事务整体回滚或复用首个 Receipt；逻辑 Receipt 仍唯一 | `JdbcActionWorkerPersistenceM5I11IntegrationTest`、`ActionReconciliationWorkerM5I12Test`、`ActionDeliveryApplicationServiceM5A07Test` |
| `FR-08` | Webhook 重复、伪重复、关闭/重开/合并乱序与主动查询竞争 | 6 | Connection-scoped 去重，Provider Version/时间单调合并 | `GitHubPullRequestWebhookAdapterM5I10Test`、`ActionDeliveryTest`、`ActionReconciliationWorkerM5I12Test` |
| `FR-09` | Worker 在 Claim/Provider/Receipt 窗口退出、Lease 过期、旧 Fencing 回写与长期 UNKNOWN | 7 | 新 Worker 只读对账；旧 Worker 写入为 0；最终确定或人工队列 | `ActionWorkerM5I11Test`、`ActionReconciliationWorkerM5I12Test`、`JdbcActionWorkerPersistenceM5I11IntegrationTest`、`ActionDeliveryTest` |

固定矩阵共 `48` 个故障样本，全部收敛。每个样本由版本化门禁脚本显式引用其真实所有权边界，避免用控制层 Mock 代替数据库、Git 或 Provider 协议验证。

## 4. 重复副作用与 UNKNOWN 判定

专项测试固定记录：

```text
TEAM -> USER Connection fallback attempts     0
Duplicate Git Push                            0
Duplicate Draft Pull Request create           0
Duplicate logical ActionReceipt               0
Late old-Fencing terminal writes               0
UNKNOWN without terminal/manual convergence    0
```

Push Fixture 在客户端超时后查询远端 Head，结果为 `RECOVERED_AFTER_UNKNOWN`，同 Branch/Head 不执行第二次 Push。Draft PR Fixture 丢失第一次创建响应后按 Head/Base 查询，首次恢复与再次调用的 Create 计数始终为 `1`。数据库使用 `(organization_id, planned_action_id)` 唯一约束和 `insertIfAbsent`，Receipt、Dispatch、DomainEvent、TaskEvent 与 Outbox 通过同一事务收敛。

当前执行 Authority 已撤销时，对账 Worker 不尝试替换 Connection，不调用 Push/Create，也不依赖 USER Key；已提交且足以证明结果的 Webhook 仍可先行闭合历史外部事实。没有充分证据时保留 UNKNOWN，并按次数或最大年龄进入 MANUAL_REVIEW。

## 5. 自动化门禁

执行命令：

```bash
nvm use 24
./scripts/m5-q02-fault-gate.sh
```

门禁不调用真实模型账户或真实 GitHub 账户。模型故障使用 AgentScope 可控 Model，GitHub HTTP 使用 Loopback Stub，Push 使用真实本地 bare Git，持久化使用真实 PostgreSQL/Testcontainers。Docker、Node.js 24 或 pnpm 不可用时门禁直接失败。

## 6. 验收结果

- 固定故障恢复：`48 / 48`，恢复率 `100%`；
- TEAM 执行回退 USER Connection：`0`；
- 重复外部 Push、Draft PR Create 和逻辑 Receipt：`0`；
- UNKNOWN 未进入确定状态或人工队列：`0`；
- Java 专项：`149 / 149`；
- Web 专项：`39 / 39`；
- 专项自动化总计：`188 / 188`；
- PostgreSQL/Testcontainers 与真实 Git 测试跳过：`0`。

M5-Q02 未发现需要修改生产状态机的缺口。新增自动化直证了 TEAM 模型故障不回退 USER Connection、Push 限流只进入有“无副作用”证据的重试，以及 Push 成功后 Draft PR 不确定只对账后继动作。下一任务为 `M5-Q03`，冻结多模型兼容和 Reviewer 质量评测集。
