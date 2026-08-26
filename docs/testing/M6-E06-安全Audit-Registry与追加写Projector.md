# M6-E06 安全 Audit Registry 与追加写 Projector

> 日期：2026-08-26<br>
> 范围：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`<br>
> 结论：通过

## 1. 交付内容

- 建立 `AuditEventTypeRegistry`，按 `EventType + DomainEvent SchemaVersion` 精确注册已评审 Audit 坐标。
- 当前目录覆盖 M0–M6 已实现及已冻结的 96 个坐标，重点补齐 Runtime、Agent、Model、Review、Action、Projection、Notification 与 Lark 安全事件。
- 每项定义固定 Category、Outcome、Retention、允许的顶层 Payload 字段、低基数摘要字段和可选动态 Outcome 映射。
- `AuditEventProjector` 只保存注册表允许的标量或集合数量，不再把原始 DomainEvent Payload 复制到浏览器可查询的 `audit_event.payload`。
- 写入 Initiator、Actor、AgentPrincipal、Correlation、Causation、DomainEvent、ProviderBinding、Connection 与 ExternalOperationHash 安全坐标。
- 提供 DomainEvent 权威历史和追加写 Audit 当前行的规范 Count/SHA-256 快照，用于一致性验证，不更新、删除或代际替换 Audit 历史。
- 保留 V27 升级前旧 Audit 事实的追加写不可变性，在校验与后续查询中只将其视为 `SYSTEM/STANDARD + {}` 安全摘要。

M6-I01 在此安全写入形状上实现 PostgreSQL Audit Query Adapter，M6-A03 提供 Team Admin Audit Explorer、权限复验和有界导出 API。

## 2. 精确注册与失败关闭

```text
DomainEvent
  -> 按 EventType + SchemaVersion 查找 Audit Definition
  -> 已注册：校验完整顶层字段集合
  -> 提取白名单低基数摘要
  -> 校验摘要字段名、值、长度和总预算
  -> 映射身份、结果、保留级别与 Provider 安全引用
  -> 同一 Projection 事务追加 AuditEvent
```

已注册事件出现未知顶层字段、缺失必填字段、非标量摘要、非法 UUID 或非法 SHA-256 时抛出 `InvalidProjectionEventException`，Audit 写入和 Checkpoint 一起回滚。摘要 Schema 允许零字段；只有内部 UUID、人类文本或其他不适合公开查询的事件仍保留类型化 Audit 事实，摘要为 `{}`。

未注册 EventType 或 SchemaVersion 仍追加一条 `SYSTEM/STANDARD` Audit 事实，`authorization_context.classification` 固定为 `UNREGISTERED`，查询摘要固定为 `{}`。该路径保留审计完整性，同时对原始 Payload 保持零复制。已注册事件使用 `REVIEWED` 分类。

## 3. 脱敏摘要边界

摘要字段使用精确白名单，并执行以下保护：

- 字段名拒绝 Secret、Password、Token、Credential、Authorization、Cookie、Payload、Prompt、Endpoint、Email 和 Phone 等敏感语义；
- 值拒绝 Bearer/API Key/Private Key 等凭证模式，以及邮箱、电话、URL、控制字符和 Format 字符；
- 单值最长 500 字符，全部值总长度最多 4,000 字符，字段最多 24 个；
- 集合仅转换为已评审的 `*Count`，不保存列表元素；
- 原始正文、评论、Prompt、Tool Input、Provider 请求/响应、凭证、外部显示名和错误原文不进入 Audit 查询摘要。

动态结果只接受注册表固定映射。当前 `AGENT_RUN_EVENT_RECORDED` 的 `RUN_ERROR` 和 `ACTION_RECEIPT_RECORDED` 的失败结果映射为 `FAILED`，其余事件使用定义中的固定 Outcome。

## 4. 身份与 Provider 引用

身份链遵循 DomainEvent 的有效 Actor：

- USER Actor 同时写入 `principal_id`、`initiator_id` 和 `actor_id`；
- Personal、Team 或 Specialist Agent Actor 同时写入 `principal_id`、`actor_id` 和 `agent_principal_id`；
- SERVICE Actor 保留服务执行类型，只有存在有效 Actor ID 时写入对应 Principal 引用。

Provider 引用只保存 `provider_binding_id + connection_id`，可选保存 64 位小写 SHA-256 `external_operation_hash`。直接事件引用必须同时提供 Binding 和 Connection；`PROVIDER_BINDING` 聚合事件可以从当前租户内 Binding 精确解析 Connection。Endpoint、Credential、Header、请求/响应 Body 和外部回执原文不落入 Audit 查询形状。

## 5. 追加写与一致性

AuditEvent 继续使用 M0 Checkpoint Runner，并受 V27 追加写触发器保护，不进入 Generation-aware 可替换投影。每个 DomainEvent 最多产生一条有效 AuditEvent，重复消费由 Checkpoint/DomainEvent 唯一索引收敛。

`expectedSnapshot(organizationId)` 从规范 DomainEvent 历史重新执行同一安全映射；`actualSnapshot(organizationId)` 读取当前追加写 Audit 行。两端按字段长度前缀编码、规范 JSON 和稳定排序生成 Count/SHA-256。校验只比较事实，不修改既有 Audit 历史。

V27 升级前已存在的行以空 `authorization_context` 识别为 Legacy Audit。这些行的字节保持不变，但规范校验不解析其旧 Payload，而是将期望与实际两侧都投影为 `SYSTEM/STANDARD`、`SUCCEEDED`、空授权上下文和空摘要。M6-I01 也必须对该类行返回空摘要，不得把历史 `payload` 列映射到 DTO、导出、日志或 Agent Context。

## 6. 验证结果

专项与回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=AuditQueryDomainM6D06Test,AuditEventTypeRegistryM6E06Test,AuditEventProjectorM6E06IntegrationTest,AuditProjectionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：24 / 24 通过，其中 Domain 6 个、Application 4 个、Infrastructure 14 个。

覆盖项：

1. 96 个精确 EventType/SchemaVersion 坐标与 Category；
2. 未知类型、未知版本和未来安全事件保持未注册；
3. Runtime、Agent、Model、Review、Action、Projection、Notification 与 Lark 分类；
4. USER Initiator/Actor 和 Agent Actor/AgentPrincipal 身份链；
5. Correlation、Causation 与 DomainEvent 引用；
6. 动态失败 Outcome 和授权结果分类；
7. ProviderBinding、Connection 与 ExternalOperationHash 安全引用；
8. 低基数白名单摘要和允许为空的安全摘要；
9. 未知字段、Secret、PII、非法摘要和事务回滚；
10. 未注册事件保留 Audit 事实且原始 Payload 零复制；
11. M0 Audit Checkpoint、重复投递、版本顺序和回滚回归；
12. DomainEvent 历史与追加写 Audit 当前行规范 Count/SHA-256 一致；
13. Legacy Audit 行保持不可变，规范校验不解析旧 Payload 并按空摘要收敛。

## 7. 后续边界

- M6-E07 提供 Projection、Outbox、Dead Letter、Cursor 和 Notification 的低基数健康摘要与受审计恢复命令。
- M6-I01 实现 Audit PostgreSQL Keyset Query Adapter，不改变本任务的安全写入边界。
- M6-A03 实现权限型 Audit Explorer 与有界导出；浏览器 DTO 不接收 DomainEvent 原始 Payload。
