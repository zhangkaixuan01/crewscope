# M2-A04 Conversation Event 与断线补发

> 状态：已完成
> 日期：2026-08-11

## 交付目标

M2-A04 交付 Conversation Event 的耐久流位置、历史 JSON API、SSE Cursor 补发、历史追平后实时轮询和三类实时流的去重契约。AG-UI 继续承载当前运行的瞬时交互；Conversation Event 承载已提交业务事实。

## 数据与事务契约

- `conversation_event.position` 是 Conversation Event 的单调恢复位置；
- `conversation_event.event_id` 是稳定的 Conversation Stream Event ID；
- `conversation_event.domain_event_id` 指向唯一业务事实；
- Conversation 状态、DomainEvent、Conversation Event 索引和 Outbox 在同一事务提交；
- Conversation 聚合事件按 Aggregate ID 归属，Participant 聚合事件通过类型化 Payload 显式归属；
- V8 迁移为 V7 已有 Conversation DomainEvent 建立投影索引；
- 历史 API 只读取调用者当前可见且不晚于 Participant 历史截止时间的事件。

## HTTP 契约

资源路径：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/events
```

`Accept: application/json` 使用 `after` 与 `limit` 返回升序历史、`hasMore` 和 `nextCursor`。`Accept: text/event-stream` 使用 `Last-Event-ID` 或 `after` 补发；两者同时提供时必须一致。SSE `id` 保存 Cursor，SSE `event` 保存业务 `eventType`，SSE `data` 保存统一实时事件信封。所有响应使用 `Cache-Control: no-store`。

Cursor 是规范 Base64URL 二进制值，包含版本、Organization、Team、Conversation、Position 和 Stream Event ID。跨 Conversation/Scope、非法编码和未知版本返回 `400 invalid_cursor`；已被投影保留策略清理的位置返回 `410 cursor_expired`。

## 历史转实时与背压

SSE 在返回 200 前完成身份、Membership、Conversation 可见性和首个历史页校验。首个历史页发送完成后，服务端只从最后成功发送的 Cursor 串行读取下一页；追平后使用配置化间隔轮询 PostgreSQL。每轮读取都通过耐久身份映射重新解析 Principal，再复验 Membership、Participant 和 Conversation 可见性；Principal 停用、成员离开或 Participant 变更会在下一轮询终止或收紧该订阅。慢消费者会延迟下一页查询，空轮询 tick 可以合并，业务事件不能丢弃。HTTP 断开只停止该订阅，不影响已提交事实或 Agent 执行。

## 去重与顺序

- Conversation 流按 `position` 排序，按 `eventId` 幂等；
- Conversation 与 Team 对同一业务事实使用不同 `eventId`、相同 `domainEventId`；
- 跨持久流的客户端投影按 `domainEventId` 合并；
- AG-UI 无 `domainEventId` 的瞬时事件按自己的 `eventId` 去重；
- Aggregate Version 用于投影缺口检测，Cursor 不声明跨 Aggregate 或跨流全局顺序。

## 验证矩阵

| 场景 | 期望 |
|---|---|
| 历史分页 | Position 升序、边界无重复、`hasMore` 正确 |
| SSE 断开重连 | `Last-Event-ID` 后第一条恰为下一位置 |
| 历史后实时 | 追平期间新提交事件不遗漏、不重复 |
| 重复与乱序输入 | 唯一索引吸收重复，输出保持 Position 顺序 |
| Cursor 过期 | 返回 `410 cursor_expired` 与安全错误信封 |
| 跨 Conversation Cursor | 返回 `400 invalid_cursor` |
| 慢消费者 | 空 tick 可合并，业务事件完整且有界查询 |
| Participant 退出 | 事件截止于当前可见历史边界 |
| 长连接期间授权变更 | 每轮重新解析 Principal 并复验可见性，不复用建连时身份快照 |
| 跨流去重 | Event ID 跨流不同，DomainEvent ID 相同 |
| PostgreSQL 事务 | 领域写入失败时不留下孤立流索引 |

## 验证命令

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```

## 验证结果

- 新增 9 项测试或测试方法，覆盖稳定流 Event ID、完整路由 Cursor、JSON 历史 API、SSE `Last-Event-ID`、长连接 Principal 持续复验、Cursor 过期、PostgreSQL Position 分页、V7→V8 回填、事务回滚与 Participant 历史截止；
- Java 与 PostgreSQL 对 `MD5("CREWSCOPE:REALTIME:CONVERSATION:" + domainEventId)` 的稳定 Event ID 派生结果一致；
- AG-UI 与 Conversation SSE 使用相同的扁平实时响应 DTO；
- 全仓 `clean verify` 通过 693 项测试，0 failure、0 error、0 skipped；
- 文档链接与差异格式检查通过。
