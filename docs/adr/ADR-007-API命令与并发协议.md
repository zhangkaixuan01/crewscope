# ADR-007：API 命令与并发协议

> 状态：ACCEPTED<br>
> 日期：2026-08-07<br>
> 更新：2026-08-09（M2-S03 固化 Agent Invocation Resume 幂等与过期边界）<br>
> 影响里程碑：M0–M6

## 背景

CrewScope 的 Web、Agent Tool 和 Provider Worker 会重试创建与状态变更命令。页面同时使用乐观界面、持久化投影和断线续传。API 需要统一处理重复提交、并发更新、分页位置、错误语义和投影确认。

## 决策

### 错误信封

`/api/v1` 错误统一返回：

```json
{
  "code": "optimistic_lock_conflict",
  "message": "WorkItem version conflict",
  "correlationId": "01989ee2-f6b0-7cda-97c4-1b337043d401",
  "retryable": false,
  "currentVersion": 12,
  "details": {
    "expectedVersion": "11",
    "actualVersion": "12"
  }
}
```

- 机器错误码使用小写 `snake_case`；
- 请求解码、Header 和 Bean Validation 失败返回 `400 invalid_request`；
- Domain Validation 返回 `422`，不存在返回 `404`，状态、幂等和版本冲突返回 `409`，Policy 拒绝返回 `403`；
- 未知异常返回 `500 internal_error`，不回显异常、SQL、请求体和凭证；
- `currentVersion` 只在已知当前版本时返回；
- Correlation ID 的全请求 Filter、Trace 和结构化日志上下文按 [ADR-008](ADR-008-可观测性与日志安全协议.md) 执行。

### Idempotency-Key 与 Command Receipt

所有公开创建和状态变更命令必须携带 `Idempotency-Key`。Key 长度为 1–200，只允许字母、数字、`.`、`_`、`:`、`/` 和 `-`，不是跨 Organization 的全局标识。

命令执行使用 V5 `command_receipt`：

```text
BEGIN
  -> INSERT organization_id + idempotency_key 的 PENDING 占位
     ON CONFLICT DO NOTHING
  -> 占位成功：写业务事实、DomainEvent 和 Outbox
  -> 将占位更新为 COMPLETED Command Receipt
COMMIT
```

整个流程使用一个 REQUIRED 数据库事务。并发重试在唯一约束上等待首个事务：

- Command Type 和规范 Request SHA-256 相同时返回原 Receipt，不重复写业务事实和事件；
- 同 Key 对应不同 Command Type 或 Request Hash 时返回 `409 idempotency_conflict`；
- 业务、事件或 Outbox 失败时占位随事务回滚，后续请求可安全重试；
- 公开响应为 `202 Accepted`，重放响应增加 `Idempotency-Replayed: true`。

Command Receipt 固定包含：

```text
commandId
domainEventId
committedVersion
correlationId
```

Request Hash 使用可影响副作用的可信 Scope、Actor、Causation 与应用命令规范值生成，不保存请求体、Secret 和非规范原文。Correlation ID 用于调用链而不改变命令语义，重试时可使用新 Correlation ID 并返回首次提交的 Receipt。

### Agent Invocation Resume

Conversation 下的 Agent Invocation Resume 是状态变更命令，必须携带 `Idempotency-Key`。服务端以当前 Organization、Conversation、Invocation、认证成员和规范化回答计算 Request Hash，并在进入 AgentScope 前完成以下裁决：

```text
认证与 Conversation Scope
  -> Pending Invocation 与 AgentRuntimeSession
  -> Pending Clarification 的 replyId/toolCallId/schemaVersion
  -> expiresAt 与当前服务端事实
  -> Idempotency-Key / Request Hash
  -> AgentScope Resume
```

- 首次有效 Resume 取得执行权；相同 Key 和 Request Hash 的重放返回首次结果，不再次进入 Model 或执行 Tool；
- 相同 Key 对应不同回答、Invocation 或 Clarification 时返回 `409 idempotency_conflict`；
- 已过期的 Clarification 在进入 AgentScope 前转为 `EXPIRED`，返回稳定业务错误，不执行 Tool 和 Model；
- 非当前 Pending Invocation、错误 Conversation/Session、错误 `replyId`、错误 `toolCallId` 或错误 SchemaVersion 失败关闭；
- 客户端只提交受约束的回答，不提交 AgentScope `ConfirmResult`、ToolUseBlock、PermissionRule 或 Session 标识；
- CrewScope Bridge 从持久化 Pending Tool 重建 `ConfirmResult`，保持原 `toolCallId` 和 Tool Name，只把已验证回答写入允许修改的 `answers` 字段；
- 完成、拒绝和过期结果均可稳定重放；Receipt 和可见结果的持久化由 M2 Application Service 与 PostgreSQL 事务实现。

AgentScope 2.0.0 原生重复 Resume 不会重复执行已经完成的 Tool，但会把重复确认当成新一轮输入并再次调用 Model。因此 Resume 幂等不能由 AgentStateStore 或 AgentScope 内存状态代替。

### If-Match

更新和状态迁移必须携带强 ETag 形式的 `If-Match: "<version>"`。

- 缺失时返回 `428 precondition_required`；
- Weak ETag、`*`、多值和非负版本以外的格式返回 `400 invalid_if_match`；
- 预期版本与已提交版本不同时返回 `409 optimistic_lock_conflict` 和 `currentVersion`；
- 事实查询响应使用同格式 `ETag` 返回当前版本。

### Cursor

列表接口统一使用 `after` 和 `limit`。`limit` 默认 50，范围为 1–100。Cursor 是资源类型专用、版本化的 Base64 URL 无填充 Token，客户端只透传，不组装内部字段。

M0 WorkItem Cursor 固化 `updatedAt DESC, id DESC` 的位置。Cursor 不承载 Organization 或授权，服务端始终用已认证的 Organization/Team 重新执行范围查询。非法版本、长度或二进制形状返回 `400 invalid_cursor`。

## 结果

- 同一命令可以在网络超时、浏览器重试和多实例并发下只提交一次；
- 前端通过 Receipt 与 DomainEvent/投影确认建立确定的 optimistic state 协议；
- 更新不覆盖已提交的并发修改；
- 所有 API 错误保持稳定机器码和安全详情。

## 验证

1. 首次命令返回 Receipt，业务事实、DomainEvent、Outbox 和 Receipt 同时提交；
2. 相同 Key 与请求返回同一 Receipt，数据库只有一组副作用；
3. 相同 Key 与不同请求返回稳定冲突；
4. 业务失败不留下 PENDING 占位；
5. WebTestClient 覆盖成功、重放、版本冲突、Header 和 Bean Validation 失败；
6. Cursor 往返与非法 Token 拒绝经过单元测试。
7. Agent Resume 的首次请求只执行一次，相同重放返回首次结果，不同 Payload 冲突；
8. 过期、错误 Invocation/Session/replyId/toolCallId 在 AgentScope 调用前拒绝，Model 和 Tool 计数不增加。

## 重新评估条件

- Command 跨越多个事实数据库；
- 需要保存完整 HTTP 响应快照；
- Cursor 开始承载安全敏感或可修改查询范围的字段；
- 引入跨区域主动-主动写入。
