# M5-D08 ActionBundle 与 PlannedAction 领域契约

> 任务：`M5-D08`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-019](../adr/ADR-019-ActionBundle调度与外部结果对账协议.md)

## 1. 交付范围

M5-D08 在 `crewscope-domain` 与 `crewscope-application` 交付：

- `ActionBundle`、`PlannedAction`、`ActionKind`、`ActionDependency`、强类型 ID、风险等级和 15 分钟以内的有效期；
- `PushBranchActionParameters`：固定外部 Repository ID、完整 Branch Ref、Delivery Head、Expected Remote Head 和 Connection ID；
- `CreateDraftPullRequestActionParameters`：固定 Repository、Head、Base、Head SHA、标题、正文、`draft=true` 和 Connection ID；
- `ActionAuthoritySnapshot`：固定 ReviewDecision/ReviewRequest/ReviewSubject/Diff、Owner Responsibility、ProviderBinding/Connection/ConnectionGrant、PolicySnapshot、SafetyEnforcementOverlay、RepositoryBinding/CodingTarget/Baseline/Delivery；
- `ActionDigest` 与 `ActionBundleDigest`：使用带版本、长度前缀的规范编码和 SHA-256，由服务端计算并在重构时复验；
- `ActionBundleRepository` Application Port 与不包含凭证、PR 正文和内部路径的 `ActionBundlePlanned` 安全事件。

Confirmation、ActionDispatch、ActionReceipt、ExternalResult、UNKNOWN/Reconcile、Lease/Fencing 和人工终结属于 M5-D09；V21 表结构属于 M5-D11；GitHub Adapter 与 Worker 属于 M5-I08 至 M5-I12。

## 2. 动作图契约

M5 源码交付 Bundle 固定为：

```text
PUSH_BRANCH                            HIGH_RISK_WRITE
  repositoryId
  refs/heads/<branch>
  deliveryHead
  expectedRemoteHead / ABSENT
  connectionId

CREATE_DRAFT_PR                        LOW_RISK_WRITE
  depends_on=PUSH_BRANCH
  repositoryId
  head / base / headSha
  title / body
  draft=true
  connectionId
```

动作序号从 1 连续递增。依赖只能引用同一 Bundle 内更早的动作；未知引用、前向引用、自引用、重复依赖和重复 Action ID 被拒绝。Draft PR 的 Head 必须区别于 Base，避免向默认分支生成交付动作。动作参数使用封闭类型，不接受浏览器、Agent 或 Provider 提交的 Digest 和权威安全字段。

## 3. 摘要与当前事实

`planned-action-v1` ActionDigest 固定：

1. Action ID、Sequence、Kind、类型化参数和依赖；
2. ReviewDecision ID/Revision/Hash、ReviewRequest ID/Revision/Version/Hash、ReviewSubject Hash、Context Hash 和 DiffArtifact ID/Hash；
3. Owner ResponsibilityAssignment ID/Version/Actor；
4. ProviderBinding、Definition、Implementation、ExecutionIdentity、Connection、ConnectionGrant 的 ID/Version 与 EffectiveAccess Hash；
5. PolicySnapshot ID/Revision/Hash 和 SafetyEnforcementOverlay ID/Version/Hash；
6. RepositoryBinding ID/Version/Key/DefaultBranch、CodingTarget ID/Revision/Hash、Baseline 和 Delivery Commit；
7. 风险与有效期。

`action-bundle-v1` BundleDigest 按动作顺序固定 Action ID、ActionDigest 和依赖。参数、基线、风险、有效期、动作顺序或依赖任一变化都会改变对应 ActionDigest 或 BundleDigest。

生成 Bundle 时必须满足：ReviewRequest 已完成且当前、成员 Gate 为 `APPROVED`、Diff 与 Context 精确一致、Owner Responsibility Active、SourceCode Binding/Connection/Grant 当前可用、Grant 完整覆盖 Binding 固定权限、EffectiveAccess 完整包含目标 Repository 上的 `source.write` 和 `pull-request.create`、Policy/Overlay 允许动作工具、RepositoryBinding 与 CodingTarget 当前且基线一致。仅一个权限或仅一个资源相交不构成授权。

后续确认或派发前再次从当前对象构造快照并调用 `requireCurrent`。责任释放、Binding/Connection/Grant 撤权或版本变化、Policy/Overlay 漂移、Repository 停用、目标变化和有效期到达全部失败关闭。

## 4. 自动化验证

`ActionBundleTest` 新增 7 个场景：

- 固定 Push → Draft PR 类型、风险、依赖、Digest 和安全事件；
- 参数、目标基线、风险、顺序、依赖和有效期摘要变化；
- 未批准 Review、旧 Review/Diff 不能生成或保持当前；
- Owner Responsibility 释放、ConnectionGrant 撤销/权限不足和 Provider 版本漂移失败关闭；
- Bundle 规划要求 GitHub 写入权限和 Draft PR 权限完整包含，单一权限与目标仓库交集不能通过；
- PolicySnapshot、Safety Overlay、RepositoryBinding 和有效期漂移失败关闭；
- Head/Base 相同、未知/前向/重复/自引用依赖、无效重构有效期与篡改 ActionDigest/BundleDigest 被拒绝。

| 验证 | 结果 |
|---|---|
| M5-D08 新增测试 | `7 / 7` |
| Domain 模块回归 | `500 / 500` |
| Application 模块回归 | `331 / 331` |
| 全仓 Maven 回归 | `1651 / 1651` |
| 文档链接与差异格式门禁 | `通过` |

## 5. 结论

M5-D08 已将一次人类批准的代码交付收敛为不可歧义、可精确确认的动作图。所有外部写参数、安全权威、目标前置事实、顺序、依赖、风险和有效期都进入服务端 Digest；旧 Review、撤权身份、过期授权和漂移策略不能继续授权外部副作用。下一任务 M5-D09 在该不可变图之上实现 Confirmation、Dispatch、Receipt 与外部结果状态机。
