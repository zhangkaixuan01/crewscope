# M0-A01：API 命令协议基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

建立 `/api/v1` 的统一错误信封、命令幂等、乐观并发、Command Receipt 和 Cursor 基线，使 Web、Agent Tool 与 Provider Worker 可以安全重试命令，并使用稳定协议处理校验、冲突和分页。

## 命令协议

公开创建和状态变更命令必须携带 `Idempotency-Key`：

```text
[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}
```

- Key 在 Organization 内全局唯一；
- Request Hash 使用命令类型、可信 Scope、Actor、Causation 和规范化命令字段计算 SHA-256；
- 相同 Key、命令类型和 Request Hash 返回首次提交的 Receipt，不重复写入业务事实和事件；
- 相同 Key 对应不同命令或内容时返回 `409 idempotency_conflict`；
- 首次执行和重放统一返回 `202 Accepted`；
- 重放响应增加 `Idempotency-Replayed: true`。

Command Receipt 固定返回：

```text
commandId
domainEventId
committedVersion
correlationId
```

V5 `command_receipt` 使用 `organization_id + idempotency_key` 唯一约束和 `INSERT ... ON CONFLICT DO NOTHING` 完成数据库级占位。WorkItem、DomainEvent、Outbox 和 Receipt 在同一个 REQUIRED 事务内提交；任一步失败时 PENDING 占位同时回滚。

## 并发与分页协议

更新和状态迁移使用强 ETag：

```http
If-Match: "12"
```

- Header 缺失返回 `428 precondition_required`；
- Weak ETag、`*`、多值和非法版本返回 `400 invalid_if_match`；
- 版本冲突返回 `409 optimistic_lock_conflict`，已知时携带 `currentVersion`；
- 事实查询使用相同格式的 `ETag` 返回当前版本。

列表接口使用 `after` 和 `limit`。`limit` 默认 50，合法范围为 1–100。WorkItem Cursor 使用版本化、资源专用的 Base64 URL 无填充编码，固定保存 `updatedAt DESC, id DESC` 的位置，不承载 Organization 和授权范围。

## 错误信封

`/api/v1` 错误统一包含：

```text
code
message
correlationId
retryable
currentVersion（可选）
details（可选）
```

- 请求解码、Header 和 Bean Validation 返回 `400 invalid_request`；
- 领域校验返回 `422`；
- 不存在返回 `404`；
- 状态、幂等和乐观锁冲突返回 `409`；
- Policy 拒绝返回 `403`；
- 未知异常返回 `500 internal_error`；
- 未知异常、校验详情和字符串表示不回显请求原值、底层异常、SQL 或凭证。

M0-A01 在 API 边界生成或复用合法 Correlation ID。M0-A02 将其扩展到全请求 Filter、Trace、结构化日志与基础指标。

## 验证

`CommandContractTest` 的 3 个单元测试覆盖 Idempotency Key 规范化、边界安全的确定性 Request Hash 和首次执行/重放结果模型。

`WorkItemApplicationServiceTest` 的 5 个单元测试覆盖 Receipt 生成、相同命令重放、不同命令冲突、缺失 Key 无副作用和领域校验前置。

`V5CommandReceiptMigrationIntegrationTest` 的 2 个 PostgreSQL 集成测试覆盖空库 V5 结构、V4→V5 升级、Organization 内唯一键、完成态约束和索引。

`DomainEventTransactionIntegrationTest` 的 6 个 PostgreSQL 集成测试覆盖业务事实、DomainEvent、Outbox 与 Receipt 原子提交，失败回滚、顺序重放、请求冲突、双线程相同命令串行化和事务边界。

`ApiContractWebTest` 的 8 个 WebTestClient 测试覆盖稳定 Receipt、重放 Header、幂等冲突、乐观锁、`If-Match`、Bean/Header/领域校验和未知异常脱敏。

`WorkItemCursorCodecTest` 的 3 个单元测试覆盖精确往返、非法 Base64、填充、长度、版本、二进制结构和分页上限。

全仓验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块成功，162 个测试全部通过，0 失败、0 错误、0 跳过。

## 后续

M0-A02 建立 Correlation ID、`traceparent`、结构化日志、统一脱敏和基础指标。
