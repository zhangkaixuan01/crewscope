# M6-D01 Activity 领域与 Cursor Scope 契约

> 任务：`M6-D01`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 关联决策：[ADR-020](../adr/ADR-020-投影代际重建与游标协议.md)、[ADR-021](../adr/ADR-021-三流恢复与前端合并协议.md)

## 1. 交付目标

M6-D01 建立 Team Activity、WorkItem Activity、历史查询和后续 Team Realtime Event 共用的公开领域契约：

- Activity 由规范 DomainEvent 派生稳定身份；
- Team 与 WorkItem 查询直接返回同一份 `ActivityEvent`；
- 公开 Payload 只能通过版本化 Schema 白名单进入投影；
- Subject、Actor 和 Reference 使用类型化身份，不携带原始 Payload、凭证或 Provider Body；
- TeamSequence 在 Team、Projection 和 Generation 内表达单调位置；
- Cursor Scope 闭合 Organization、Team、Projection、Generation、Projection Schema 和 Filter Fingerprint；
- ActivityVisibility 使用当前成员、管理员和 WorkItem 可见事实裁决。

## 2. 领域契约

### 2.1 Activity 身份

`ActivityEventId` 使用以下稳定输入确定性派生：

```text
crewscope:activity-event:v1 + domainEventId
```

Projection Generation 不进入派生输入。实时投影、历史重建、新旧 Generation、Team 查询和 WorkItem 查询使用同一 Activity 身份。`ActivityEvent` 构造时复验 `ActivityEventId` 与 `domainEventId`，持久化恢复无法注入不匹配身份。

### 2.2 投影与序号

Activity 行完整保存：

```text
organizationId
teamId
projectionName
projectionGeneration
projectionSchemaVersion
teamSequence
eventId
domainEventId
```

`ProjectionGeneration` 和 `TeamSequence` 均为正数值对象，提供溢出保护的 `next()`。`ActivityPage` 要求结果严格按 TeamSequence 升序、全部属于同一个 Cursor Scope、全部符合规范过滤条件且 Event ID 不重复。

### 2.3 公开 Payload Schema

`ActivityPayloadSchema` 维护必填字段和可选字段白名单。Payload 创建时执行：

1. 必填字段完整；
2. 未知字段拒绝；
3. Secret、Password、Token、Credential、Authorization、Cookie、Raw Payload、Provider Body、Tool Input 和 System Prompt 类字段名拒绝；
4. 单字段长度、总长度、控制字符、双向覆盖字符和孤立 Unicode 代理项检查；
5. 校验后的规范值不可变保存；
6. Event 保存精确 Schema Name 和 Version。

Schema 兼容升级采用追加规则：保留既有字段和必填集合，新字段以可选字段加入。历史 Event 保留创建时的 Schema Version，新版本发布不会改写旧 Payload。

### 2.4 Subject、Actor 与 Reference

- `ActivitySubject` 表达主要业务对象；
- `ActivityActor` 只保存 Actor Type 和可选 Principal ID；
- `ActivityReference` 使用固定公开资源类型和内部稳定 ID；
- TEAM Subject 与 TEAM Reference 必须匹配 Event 的 Team Scope；
- `WORK_ITEM_PARTICIPANTS` Event 必须精确关联一个 WorkItem。

Actor Display Name、URL、Idempotency Key、Credential Subject、内部 Runtime ID、原始 DomainEvent Payload 和外部响应不进入该契约。应用查询需要展示名称或链接时，从当前授权的权威资源读取。

### 2.5 可见性

`ActivityVisibilityPolicy` 先比较 Organization 和 Team，再要求 Active Team Membership：

| Visibility | 可见条件 |
|---|---|
| `TEAM_MEMBERS` | 当前 Team Active Member |
| `WORK_ITEM_PARTICIPANTS` | Team Admin，或成员当前可访问精确 WorkItem |
| `TEAM_ADMINS` | 当前 Team Admin |

Organization、Team、Membership 或 WorkItem 可见事实不匹配时返回不可见。

## 3. 应用查询与 Cursor Scope

`ActivityFilter` 覆盖 WorkItem、Category、EventType 和 Actor Principal，使用稳定排序后的规范字段计算 SHA-256 Filter Fingerprint。集合输入顺序不影响指纹。

`ActivityCursorScope` 保存：

```text
organizationId
teamId
projectionName
projectionGeneration
projectionSchemaVersion
filterFingerprint
```

`TeamActivityCursor` 在 Scope 之外保存 TeamSequence 和 ActivityEventId。解码后的 Cursor 必须与请求 Scope 完全相等，跨 Organization、跨 Team、跨 Generation、跨 Schema 和过滤条件变化均失败关闭。

`ActivityQuery.team(...)` 与 `ActivityQuery.workItem(...)` 共用 `ActivityQueryPort` 和 `ActivityPage`。WorkItem 查询通过过滤同一 Canonical Activity Event 完成，不创建视图专属 Event ID。

M6-E05 实现带 Key ID 的签名 Cursor Codec、过期错误、快照和 SSE。M6-D01 提供签名载荷所需的完整 Scope 与值对象。

## 4. 验证

专项测试：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=ActivityEventM6D01Test,ActivityCursorM6D01Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：12 个场景通过，0 Failure，0 Error，0 Skip。

覆盖范围：

- Payload 白名单、敏感字段、未知字段、缺失字段、不可变性和 Unicode 安全；
- Schema 追加演进与历史版本保留；
- Team Member、WorkItem Participant、Team Admin 和跨租户可见性矩阵；
- TEAM Subject/Reference Scope 和受限 WorkItem 唯一性；
- ProjectionGeneration 与 TeamSequence 正数、推进和溢出；
- Filter Fingerprint 规范化；
- Cursor 跨 Organization、Team、Generation、Schema、Filter 复用阻断；
- 严格 TeamSequence、重复身份、Scope 和过滤结果校验；
- Team/WorkItem 查询共享 Activity 身份；
- 历史重建跨 Generation 保持稳定 Event ID；
- 从最后已应用 Cursor 继续读取。

## 5. 后续任务边界

- `M6-D07` 使用 `ProjectionName` 和 `ProjectionGeneration` 实现代际状态机、Pointer 与管理命令；
- `M6-D08` 将 Activity、Generation 和 Cursor 所需索引落地到 V27；
- `M6-E02` 注册公开 EventType/Payload Schema 并实现 Activity Projector；
- `M6-E05` 实现 Team Event Store、签名 Cursor、快照、缺口读取和 SSE；
- `M6-A01` 组合 Membership、WorkItem Access 和 ActivityVisibilityPolicy 提供安全 API。
