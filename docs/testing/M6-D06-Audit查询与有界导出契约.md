# M6-D06 Audit 查询与有界导出契约

> 任务：`M6-D06`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 前置契约：M6-S01、M0-D06、M0-E03

## 1. 交付目标

M6-D06 为追加写 AuditEvent 建立 Team 管理查询契约：

- 14 类稳定 `AuditEventCategory` 与 `SUCCEEDED/DENIED/FAILED` 结果；
- Initiator、Actor 和 Agent 类型化身份链；
- Subject、ProviderBinding/Connection 和 Correlation/Causation/DomainEvent 安全引用；
- `STANDARD/EXTENDED/LEGAL_HOLD` 保留级别；
- 按已注册 EventType/Payload Schema 生成的脱敏摘要；
- 绑定 Organization、Team 和过滤指纹的时间/UUID Keyset Cursor；
- 当前 Team 权限复验与有界导出请求。

本任务交付 domain/application 契约。Audit Projector 扩展由 M6-E06 实现，PostgreSQL Keyset Adapter 由 M6-I01 实现，HTTP API 与导出 Artifact 由 M6-A03 实现。

## 2. 脱敏查询形状

`AuditQueryEvent` 只包含：

```text
AuditEventId
OrganizationId + TeamId
EventCategory + Outcome + RetentionLevel
InitiatorId + EventActor + AgentPrincipalId
Subject AggregateReference
ProviderBindingId + ConnectionId + ExternalOperationHash
CorrelationId + CausationId + DomainEventId
OccurredAt
AuditRedactedSummary
```

`AuditIdentityChain` 要求 USER Actor 和 Initiator 一致，Agent Actor 和 AgentPrincipalId 一致。Provider 引用不保存 Endpoint、Credential、Header、Request/Response Body 或外部回执原文。

`AuditSummarySchemaRegistry` 以 `EventType + SchemaVersion` 精确定位 Schema。未注册事件、未知 Payload 版本、未知字段、缺失必填字段全部失败关闭。字段名拒绝 Secret、Token、Credential、Authorization、Cookie、Payload、Prompt、Endpoint、Email 和 Phone 等敏感语义；值拒绝凭证模式、邮箱、电话、URL、控制字符和 Format 字符。查询对象不接收原始 Payload。

## 3. 查询与 Cursor

`AuditQueryFilter` 支持组合筛选：

```text
occurredFrom / occurredBefore
categories / outcomes
initiatorIds / actorIds / agentPrincipalIds
subject
providerBindingId
correlationId
```

每类集合条件最多 50 个值。规范化条件按排序后内容生成 SHA-256 指纹，Cursor 绑定 Organization、Team 和指纹，跨 Team 或改变筛选条件后无法重放。

查询按 `occurredAt DESC, eventId DESC` 排序，单页上限 200。UUID 次排序使用与 PostgreSQL UUID 一致的 16 字节无符号顺序，避免 Java 有符号 `UUID.compareTo` 在高位 UUID 上产生不同分页边界。`AuditPage` 验证 Scope、Filter、数量、唯一 Event ID 和严格排序。

## 4. 授权与导出

`DefaultAuditAuthorization` 每次请求复验：

1. Actor 是当前 Organization 的 ACTIVE USER；
2. 非平台管理员具有当前 ACTIVE Team Membership；
3. Role 仍可授权，MemberRole 仍为 ACTIVE、TEAM Scope 且在有效时间内；
4. 查询要求 `AUDIT_READ`；
5. 导出同时要求 `AUDIT_READ + GOVERNANCE_EXPORT`。

Team Admin 可使用 Audit Explorer。Team Owner、Auditor 和其他同时持有 `GOVERNANCE_EXPORT` 的授权可导出。平台管理员可在当前 Organization 范围内查询和导出。撤权后新请求在 Repository 读取前失败。

`AuditExportRequest` 必须同时具有显式时间起点和排他上界，时间范围最多 31 天，行数最多 10,000。`AuditExportBatch` 再次校验 Scope、Filter、唯一性、排序和行数。

## 5. 追加写边界

M6-D06 只定义 Query/Export 读 Port，不定义 AuditEvent 更新、归档、软删除或物理删除命令。`AuditRetentionLevel` 是保留策略标签，不改变历史 Audit 事实。

## 6. 验证

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=AuditQueryDomainM6D06Test,AuditQueryApplicationM6D06Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：14 个测试通过，0 Failure，0 Error，0 Skip。

覆盖：

- 已注册 Schema 投影与未知 EventType/Payload Version 失败关闭；
- 敏感字段、凭证模式、PII、URL 和控制字符拒绝；
- Initiator/Actor/Agent、Category、Subject、Provider 和 Correlation 不变量；
- 组合 Filter 指纹、跨 Team Cursor 拒绝、PostgreSQL UUID 次排序和严格 Keyset；
- Team Admin 读取、Owner 导出、平台管理员读取与撤权后零 Repository 读取；
- 显式时间范围、31 天上限和 10,000 行上限。
