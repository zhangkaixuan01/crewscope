# M5-S05 ActionBundle 与外部结果对账验证记录

> 任务：`M5-S05`<br>
> 日期：2026-08-22<br>
> 结论：通过<br>
> 长期决策：[ADR-019](../adr/ADR-019-ActionBundle调度与外部结果对账协议.md)

## 1. 验证目标

M5-S05 在 M5-S04 GitHub Push/Draft PR 边界之上验证：

1. 冻结 ActionDigest、BundleDigest、动作顺序、依赖和 Confirmation 失效规则；
2. 证明 Dispatch 只在数据库事务提交后可见，回滚不会触发外部写操作；
3. 冻结至少一次 Worker Claim、Lease、Fencing 与进程退出接管协议；
4. 证明 Push/PR 写入结果不确定时先查询对账并补写唯一 Receipt；
5. 冻结 Webhook 去重、乱序裁决、主动查询合并和 ExternalResult 唯一协议；
6. 冻结长期无法证明结果时的人工队列、证据、Audit 和不可逆终态。

本 Spike 使用测试内 Action、Store、Worker、Provider 和 Webhook Probe，不提前创建 M5-D08/D09 生产领域对象、V21 数据库迁移或 M5-I11/I12 Worker。

## 2. 固定 ActionBundle

测试 Bundle 包含两个动作：

```text
PUSH_BRANCH(push)
  repositoryId=repo-101
  branch=feature/crewscope
  deliveryHead=aaaaaaaa
  expectedRemoteHead=ABSENT

CREATE_DRAFT_PR(pr)
  depends_on=push
  repositoryId=repo-101
  head=feature/crewscope
  base=main
  headSha=aaaaaaaa
  draft=true
```

ActionDigest 同时覆盖参数、依赖、Review、责任、Binding、Grant、Policy、安全覆盖、风险、动作有效期和目标前置版本。BundleDigest 再按顺序覆盖 Action ID 与子 Digest。固定反例分别修改所有安全/执行事实，并调整顺序和依赖，旧 Confirmation 全部失效；Confirmation 自身过期后同样不能授权。相同规范事实重复计算得到相同 SHA-256。

## 3. 事务 Dispatch 与依赖

测试内 Planner 模拟 REQUIRED 事务边界：事务打开后 Store 中 Dispatch 数量为 0，回滚后仍为 0；提交后两个 Dispatch 同时可见。生产实现必须使用同事务 ActionDispatch/Outbox，不依赖非耐久的内存回调。

Worker 第一次只领取 Push。Push 的成功 Receipt 提交后，Draft PR 才转为可领取。两个动作完成后 Provider 写入分别为 1，Receipt 为 2。该样本证明动作依赖控制领取，而不是只控制前端展示。

## 4. Push 成功与 Receipt 丢失

Push Probe 在写入远端 Head 后模拟进程立即退出，首次状态为：

```text
remote branch head = aaaaaaaa
push writes = 1
ActionReceipt = absent
Action state = RUNNING
```

Lease 到期前替代 Worker 无法领取。到期后新 Worker 获得更大的 Fencing Token，先查询远端 Head；发现已经等于 Delivery Head 后补写唯一成功 Receipt，不执行第二次 Push。旧 Worker 随后尝试提交时因 Fencing Token 失效被拒绝。多次 Observation 被保留，逻辑 Receipt 仍只有一个。

## 5. Draft PR 响应丢失与部分成功

Draft PR Probe 先持久化外部 PR，再模拟 HTTP 响应丢失。Worker 将动作置为 `UNKNOWN`，不把异常当作普通失败重试。Reconcile 按 Repository/Head/Base 查询并校验 Head SHA，找到 PR 42 后补写唯一 Receipt 与 ExternalResult。

重复 Reconcile 不增加 Receipt 或 PR 写入。整个序列中 Push 写入为 1、PR 创建为 1。Push 已成功而 PR 结果不确定时只对账 PR，证明部分成功不会重新执行已完成依赖。

## 6. Webhook 与主动查询合并

固定事件顺序为：

```text
Webhook delivery-2: MERGED, providerVersion=3
Webhook delivery-2: MERGED, providerVersion=3  （重复）
Webhook delivery-1: OPEN,   providerVersion=1  （乱序旧事件）
Active Query:       CLOSED, providerVersion=2  （旧查询）
```

去重键使用 Connection + Delivery ID；同一 Connection 的重复事件只接收一次，不同 Connection 的相同 Delivery ID 不冲突。ExternalResult 使用 Connection + 外部稳定 ID 定位，最终保持 `MERGED/version=3`；重复、旧 Webhook、同版本冲突状态和旧查询不会覆盖更新状态。它们可以形成 Observation，不会产生第二个 ActionReceipt。

## 7. 人工终结

另一固定样本模拟 PR 请求结果不确定，连续查询仍无法找到可证明结果的外部对象。动作在有界两次查询后进入 `MANUAL_REVIEW`。空证据人工处理被拒绝；具备 Actor、明确结论和证据的人工失败生成唯一 Receipt 和 Audit，进入 `MANUALLY_FAILED`。

之后到达更高 Provider Version 的迟到 Webhook 只能作为外部 Observation，不能让 Action 回到 READY、RUNNING 或 SUCCEEDED。再次人工终结或直接提交不同 Receipt 会被当前状态与 Receipt 唯一约束拒绝。若迟到外部事实与人工裁决冲突，生产实现需要创建运维/安全告警，而不是静默改写人工终态。

## 8. 自动化验证

测试类：

```text
crewscope-infrastructure/src/test/java/io/crewscope/infrastructure/action/
  ActionDeliveryM5S05IntegrationTest.java
```

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure \
  -Dtest=ActionDeliveryM5S05IntegrationTest test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

六个场景分别覆盖摘要/确认、事务/依赖、Push 退出接管、PR UNKNOWN 对账、Webhook/查询合并和人工终结。

M5-S01 至 M5-S05 联合专项：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=AgentScopeDynamicModelM5S01IntegrationTest,AgentOwnershipM5S02CompatibilityTest,ReviewerSpecialistM5S03IntegrationTest,GitHubDeliveryM5S04IntegrationTest,ActionDeliveryM5S05IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：动态模型 `2 / 2`、Agent 所有权兼容 `4 / 4`、Reviewer `5 / 5`、GitHub 交付 `5 / 5`、Action 对账 `6 / 6`，合计 `22 / 22` 通过。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress test
```

```text
Tests run: 1540, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归实际运行 PostgreSQL、Redis、Flyway、Docker Sandbox、本地 Git Process 和 Loopback HTTP 集成测试。根 README 与 `docs` 共 198 份 Markdown 文档链接通过，`git diff --check` 同步通过。

## 9. 后续实现边界

- M5-D08 实现正式 ActionBundle、PlannedAction、Digest、依赖和失效规则；
- M5-D09 实现 Confirmation、ActionDispatch、ActionReceipt、ExternalResult 和人工终结状态机；
- M5-D11 使用 V21 唯一约束、复合 Scope 外键和 Append-only Observation/Audit 落库；
- M5-I08 至 I10 实现 GitHub Adapter、Push、Draft PR、Webhook 和主动查询；
- M5-I11 实现事务 Outbox 后 Worker、Claim、Lease、Fencing、依赖释放与 Receipt 边界；
- M5-I12 已实现 UNKNOWN Reconcile、启动对账、人工队列、Trace、指标和健康摘要，证据见 [M5-I12 UNKNOWN 对账与运行诊断](../testing/M5-I12-UNKNOWN对账与运行诊断.md)。

## 10. 结论

M5-S05 验证通过。精确摘要可以使参数和授权事实漂移立即失效；提交后 Dispatch、动作依赖和 Fencing 可以控制至少一次 Worker；远端查询可以在 Push/PR 响应丢失或进程退出后补写唯一 Receipt；Webhook、主动查询和人工证据可以合并为一个不可逆、可审计的结果链。M5 的五个架构 Spike 已全部关闭，可以进入 M5-D01 领域实现。
