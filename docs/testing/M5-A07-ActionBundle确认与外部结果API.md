# M5-A07 ActionBundle 确认与外部结果 API

> 实现模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`
> 完成日期：2026-08-24

## 1. 交付范围

M5-A07 将 M5-D08/D09 和 M5-I11/I12 的动作执行能力收口为成员可用的安全应用边界：

- 根据当前 Review、责任、Provider、Policy、Safety、CodingTarget、Repository 与 ExecutionWorkspace 事实规划不可变 ActionBundle；
- 由服务端使用 ExecutionWorkspace `managedBranch` 生成 Push Branch 和 Create Draft PR 依赖图；
- 将 GitHub Catalog 的稳定 Repository ID 映射到规范 Grant Resource Key，规划与确认均重新验证；
- Owner 使用精确 Bundle Version 和 Digest 创建 Confirmation；
- Confirmation 与两个 READY ActionDispatch 在一个事务中可见；
- 取消 Confirmation 时只取消仍为 READY 的动作，每个动作只写入一条无副作取消 Receipt；
- 查询 ActionBundle、Confirmation、Dispatch、ActionReceipt 和 ExternalResult 的统一安全投影；
- 公开 UNKNOWN/MANUAL_REVIEW 状态和当前 Owner 强版本人工终结入口；
- 写入 Action 命令 DomainEvent、TaskEvent、Outbox 和 CommandReceipt；
- 通过显式组合根和构造器注入装配生产实现。

## 2. HTTP 边界

根路径：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions
```

公开能力：

```text
POST /bundles                                      规划 ActionBundle
GET  /bundles                                      列表与当前/失效状态
GET  /bundles/{bundleId}                           完整安全投影
POST /bundles/{bundleId}/confirmations             精确确认
POST /confirmations/{confirmationId}/cancel        撤回未使用确认
POST /dispatches/{dispatchId}/manual-resolution    人工终结对账
```

规划、确认、取消和人工终结要求 `Idempotency-Key`；确认、取消和人工终结要求强 `If-Match`。四类命令统一返回 `202 CommandReceipt`，人工终结响应丢失后使用相同 Key 和请求精确回放，不重复写入 ActionReceipt 或 Dispatch 终态。浏览器不存在 ActionDispatch 创建、Claim、Heartbeat、Lease、Fencing 或 Worker 执行 API。

## 3. 精确确认与当前事实

规划时由服务端重建：

```text
当前 Task / TaskExecution / WorkItem 可见性
  -> 当前活动 USER OWNER
  -> 当前 ReviewRequest / ContextPackage / 最新 APPROVED ReviewDecision
  -> ProviderBinding / Connection / ConnectionGrant / ExecutionIdentity
  -> 当前 DELIVERABLE GitHub Repository Catalog / Grant Resource
  -> PolicySnapshot / SafetyEnforcementOverlay
  -> CodingTargetSnapshot / RepositoryBinding
  -> 已完成的 ExecutionWorkspace / 受管交付分支
```

确认时重复整条解析链，并校验 Bundle 仍为同 ReviewDecision 的最新预览、Version 和小写 SHA-256 Digest 完全一致。Owner、Review、Binding、Connection、Grant、Policy、Safety、CodingTarget、Repository 或 Catalog 变化后，旧页面不能产生新的 GitHub 写操作。

## 4. 取消与对账

取消是对未使用人工授权的撤回：

- `READY` 动作转为 `CANCELLED`，并写入唯一 `NO_SIDE_EFFECT_CONFIRMATION_CANCELLED` Receipt；
- 已 Claim、运行中、`UNKNOWN` 或已终结动作不执行盲目取消；
- 已经产生外部副作用的动作继续由 GitHub 只查询 Reconcile 和 Webhook Observation 收敛；
- `MANUAL_REVIEW` 只能由当前有效 USER OWNER 使用 Dispatch 强版本终结；
- ActionReceipt 的逻辑唯一性防止 Worker、Reconcile、取消或人工路径产生第二个结果。
- 人工终结在 Receipt 回放前重新验证当前 Owner；请求 Hash 固定结果、外部身份安全 Hash、目标版本、原因和说明，同键异参冲突。
- ActionReceipt、Dispatch、DomainEvent、TaskEvent、Outbox 与 CommandReceipt 在同一事务提交，并使用本次 HTTP Correlation ID。

## 5. 安全投影

公开 DTO 只包含 Bundle Version/Digest/失效原因、Review 和 Repository 展示坐标、动作参数/风险/依赖、Dispatch 状态与尝试计数、Receipt 结果与 ExternalResult 单调状态。

以下坐标不进入响应、错误或 Receipt：

- Connection ID、Credential、ConnectionGrant 与 Provider Endpoint；
- Worker ID、Lease、Fencing Token 和内部幂等键；
- 原始外部 ID、Business Key、Observation Key 和 Provider 原始响应；
- AskPass、Token、Remote URL、内部异常与执行身份。

外部对象只返回类型和安全 Hash。详情与列表使用 `Cache-Control: no-store`，详情返回 Bundle ETag。

## 6. 自动化验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application,crewscope-server -am \
  -Dtest=ActionDeliveryApplicationServiceM5A07Test,CurrentActionDeliveryPlanningResolverM5A07Test,ActionDeliveryControllerM5A07Test,ActionDeliveryApplicationConfigurationM5A07Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项结果：`11 / 11` 通过。

覆盖：

- 规划回放重新验证当前 Owner，不依赖已过期 Catalog 快照；
- 精确最新 Bundle Version/Digest 确认与两动作 READY 依赖图；
- 同键确认回放不重复创建 Dispatch；
- 旧 Digest 在任何 Confirmation/Dispatch 持久化前拒绝；
- 取消为每个 READY 动作写入唯一 Receipt，回放不重复写入；
- 人工终结要求 `Idempotency-Key + If-Match`，首次返回 `202 CommandReceipt`，同键回放不重复终结；
- 稳定 Repository ID 到 GitHub Grant Resource Key 的当前 Catalog 映射；
- 过期 Catalog、Execution Identity 或 Grant 不匹配失败关闭；
- Controller 的 Idempotency-Key、If-Match、`no-store`、路由闭合和安全字段白名单；
- 完整 Action 依赖与自定义覆盖下的条件 Spring 装配。

## 7. 结论

M5-A07 已将 Review Gate、GitHub 授权、受管交付分支、精确人工确认、动作 Worker 和外部结果对账连接为可审计的应用闭环。M5-A08 可在该投影上扩展 Task Timeline、Conversation 卡片与运维摘要。
