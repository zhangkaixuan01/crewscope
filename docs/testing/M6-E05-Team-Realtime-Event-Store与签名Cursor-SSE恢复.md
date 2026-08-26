# M6-E05 Team Realtime Event Store 与签名 Cursor/SSE 恢复

> 日期：2026-08-26<br>
> 范围：`crewscope-application`、`crewscope-server`<br>
> 结论：通过

## 1. 交付内容

- 建立 `TeamRealtimeEventStore`，统一承载 Team Activity 快照和 Cursor 后缺口读取。PostgreSQL Adapter 需要在同一个读取快照中解析活动 Projection Pointer、快照行和高水位 Cursor。
- 建立 `TeamActivitySnapshot`，完整绑定 Organization、Team、Projection、Generation、Projection Schema、Filter Fingerprint、快照行和高水位位置。
- 建立版本化 Team Cursor 二进制协议，使用 Base64URL 不透明编码和 HMAC-SHA256 签名，绑定 Key ID、签发/过期时间、完整 Cursor Scope、TeamSequence 与 Event ID。
- Key Ring 支持当前签名 Key 和有界历史验证 Key。历史 Key 只验证仍在 Cursor 有效期内的 Token，删除历史 Key 后对应 Cursor 安全失效。
- 建立 `TeamActivityRealtimeStream`，在返回 SSE Session 前完成 Cursor 解析和首批耐久读取，供 M6-A01 的权限型 Controller 复用。
- 每条连接维护独立 Position。首批、批量缺口和后续轮询逐页串行交付，数据库查询与内存事件页均有固定上限。
- 慢消费者只合并空轮询机会。Activity 业务行按 TeamSequence 逐条交付；心跳只在空闲窗口产生，不携带 Cursor，不推进耐久位置。
- 连接断开后使用最后已应用 SSE ID 恢复。Generation 切换、Schema 不兼容和保留期清理由 Store 抛出 `TeamActivityCursorExpiredException`，统一映射为 `410 cursor_expired`。
- Team Cursor 与 Conversation Cursor、AG-UI Invocation Segment 保持独立签名、位置、过期和重放边界。

## 2. 快照与缺口协议

```text
读取 Team 快照
  -> 同一 PostgreSQL Read Snapshot 解析活动 Pointer
  -> 返回有界安全 Activity 行
  -> 返回同 Generation 的 snapshotCursor / hasMore

打开 Team SSE
  -> 验证 Base64URL 规范编码与 HMAC
  -> 验证签发时间、过期时间、Route 和 Filter Fingerprint
  -> Store 复验活动 Generation、Schema 与保留位置
  -> 在提交 HTTP 200 前读取首个耐久页
  -> 串行补齐全部缺口页
  -> 有界轮询新页并在空闲窗口发送心跳
```

快照高水位可以位于最后一条过滤结果之后，也可以在过滤结果为空时存在。该位置关闭“安装快照—建立 SSE”之间的写入竞态。直接打开无 Cursor SSE 时，截断快照从最后一条已发送可见行继续分页，保证剩余可见业务行完整交付。

## 3. Cursor 安全协议

签名 Body 固定包含：

```text
version + keyId + projectionName
+ issuedAt + expiresAt
+ organizationId + teamId
+ projectionGeneration + projectionSchemaVersion
+ filterFingerprint
+ teamSequence + eventId
```

- Token 使用无 Padding 的规范 Base64URL；非规范编码、超长输入和非法长度统一返回 `400 invalid_cursor`。
- 服务端先验证 HMAC，再判断过期状态；无效签名无法探测 Token 时间和 Scope。
- Organization、Team、Projection 或 Filter 变化返回 `400 invalid_cursor`。
- 有效签名 Token 的时间到期，以及 Store 发现 Generation、Schema 或保留位置失效，返回 `410 cursor_expired`。
- HMAC Key 至少 256 bit，通过外部标准 Base64 配置注入。启用 Team Realtime 时缺少有效 Key Ring 将启动失败。

## 4. SSE 背压与连接模型

每个 SSE Session 为单订阅对象，每次 HTTP 连接创建独立 Session 和 Position。批量大小最大 200，默认 100。Reactor 只对空轮询 Tick 使用 `onBackpressureDrop`，当前 Activity 页受下游 Demand 控制，当前页完成后才读取下一页。

心跳使用无 Data、无 ID 的 SSE Comment/Event。心跳用于代理连接保活，客户端只在成功解析并应用 Activity Frame 后保存其签名 SSE ID。

## 5. 配置

```yaml
crewscope:
  team-activity-realtime:
    enabled: false
    poll-interval: 500ms
    heartbeat-interval: 15s
    batch-size: 100
    cursor-maximum-age: 24h
    cursor-future-skew: 30s
    current-key-id: v1
    keys:
      v1: ${CREWSCOPE_TEAM_ACTIVITY_CURSOR_KEY_V1}
```

M6-I01 提供 PostgreSQL `TeamRealtimeEventStore` Adapter 后启用该装配。M6-A01 在此能力上增加成员资格、WorkItem 可见性、公开 DTO、JSON 历史和 Team SSE HTTP API。

## 6. 验证结果

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=TeamActivitySnapshotM6E05Test,TeamActivityCursorCodecM6E05Test,TeamActivityRealtimeStreamM6E05Test,TeamActivityRealtimeConfigurationM6E05Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：14 / 14 通过，其中 Application 3 个、Server 11 个。

覆盖项：

1. 快照 Scope、过滤结果、高水位和空过滤结果不变量；
2. 两条连接从同一 Cursor 独立恢复并获得相同有序事实；
3. 断线后从最后已应用 SSE ID 补发新事件；
4. 多个固定大小缺口页连续排空，查询上限始终有效；
5. 慢消费者逐步请求时业务行零丢失；
6. 同一 PostgreSQL 微秒时间的事件按 TeamSequence 稳定交付；
7. 空闲连接产生无 Cursor 心跳；
8. Projection Generation 切换终止旧连接并返回 Cursor 过期；
9. 快照后新增事件从签名高水位完整补齐；
10. Cursor 完整 Scope 往返、篡改阻断和规范编码；
11. Organization、Team 和 Filter Scope 切换阻断；
12. 有效签名 Cursor 时间过期与无效签名错误隔离；
13. Key 轮换期间历史验证和历史 Key 删除后的安全失效；
14. 实时装配默认关闭，显式启用时缺少耐久 Store 或有效签名 Key 均启动失败。

## 7. 后续边界

M6-E06 扩展 Audit Projector。M6-I01 提供 Activity/Inbox/Audit PostgreSQL 查询 Adapter 与 `TeamRealtimeEventStore` 实现，M6-A01 提供权限感知的 Activity/快照/SSE API，M6-F01/M6-F02 完成浏览器三流协调和 Team Activity 界面。
