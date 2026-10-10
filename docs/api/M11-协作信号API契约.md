# M11 协作信号 API 契约

> 状态：冻结（A01 在场 / 正在输入 / 实时变更通知）<br>
> 版本：V1<br>
> 日期：2026-10-10<br>
> 适用范围：WebSocket 协作通道上的信号帧协议——在场（谁正在查看）、正在输入（Conversation 与评论）、实时变更通知（坐标 + 版本，零业务负载）

## 0. 本轮边界

本契约冻结 `/api/v1/collaboration/ws` 单一端点上的**全部**帧协议：连接与心跳（I01a）、订阅拓扑（I01b）与三类信号（A01）。以下能力不在本契约内：

- **权威数据面**：一切读、写、冲突检测继续走既有 REST——信号只携带坐标与版本，**永不携带业务负载**（ADR-032 冻结）；
- **Team Activity SSE 流**：既有事件流不动，是断线重连期间的权威兜底（见 §4）；
- **事件层撤权扫描**（成员生命周期事件→主动断开）归 D02（ADR-038 通道合同），本契约只定义 A01 承接的复验层（§8）；
- **WorkGraph 变更推送**归 D01/A02；评论 / 参与人 / 责任分配事件不推送（§2.4 白名单排除）；
- **多实例 fanout**：当前单节点内存态拓扑，多实例部署是 ADR-032 的重评条件，不是本契约的承诺。

### 形态与偏差声明

ADR-032 预想过一个 `CollaborationSignalController` HTTP 面。**实现偏差：不存在任何信号 HTTP 端点**——在场、正在输入、变更通知全部经既有 WebSocket 通道的帧扩展交付，在场数据只存 Redis 带 TTL（不进 PostgreSQL / Outbox / Audit，ADR-032 冻结）。这是有意的：信号是有损呈现层，REST/SSE 保持权威。

## 1. 连接与订阅协议

### 1.1 连接与欢迎帧

升级请求携带既有会话 Cookie（与所有 `/api/` 端点同一安全上下文，无第二套认证）。未认证握手返回 HTTP 401，不进入 WebSocket。连接成功后服务端立即发**欢迎帧**：

```jsonc
{
  "type": "welcome",
  "connectionId": "<服务端分配>",
  "principalId": "account:<uuid>",        // 外部身份为 external:<provider>:<subject>
  "heartbeatIntervalSeconds": 15,
  "inboundIdleTimeoutSeconds": 30,
  "presenceTtlSeconds": 45,
  "typingWindowSeconds": 5,
  "maxInboundSignalsPerSecond": 30
}
```

后半四个数字是**本连接的运行参数**（部署可调，见 §10），客户端不得硬编码。心跳契约：服务端每 `heartbeatIntervalSeconds` 发 `{"type":"ping"}`，客户端应答 `{"type":"pong"}`；**pong 不计入信号预算**。任何入站帧（含 pong）刷新活跃时钟，连续 `inboundIdleTimeoutSeconds` 无入站帧则以 1000 关闭。

### 1.2 订阅（I01b 冻结，A01 契约化收录）

```jsonc
// 入站
{"type":"subscribe","scope":{"organization":"<uuid>","team":"<uuid>"}}                          // Team 粒度
{"type":"subscribe","scope":{"organization":"<uuid>","team":"<uuid>","resourceType":"work_project","resourceId":"<uuid>"}}
{"type":"subscribe","scope":{"organization":"<uuid>","team":"<uuid>","resourceType":"work_item","resourceId":"<uuid>"}}
{"type":"unsubscribe","subscriptionId":"<句柄>"}
```

- `resourceType` 严格小写，值域 `work_project` / `work_item` / `conversation`；Team 粒度**不带** `resourceType`/`resourceId`（带 `resourceId` 视为格式错误）；
- 每帧逐次过订阅鉴权（fail-closed，拒绝与"资源不存在"同形，不泄露策略事实）；
- 出站应答：`{"type":"subscribed","subscriptionId":"<uuid>","scope":<原样回显>}`；
- **幂等**：重复 subscribe 同一 scope 返回**原句柄**（不新建订阅，在场继续靠心跳续期）；unsubscribe 未知句柄仍回 `{"type":"unsubscribed",...}`（丢过 ack 的客户端不会被卡死）；
- 每连接订阅上限 32（§10 旋钮），超限回 `subscription_limit`。

### 1.3 帧序契约（重要）

`subscribed` ack 是 subscribe 帧的**直接回答，先于** fanout 副作用到达：同 scope 既有订阅者看到的 enter delta、以及订阅者自己的在场快照（§2.1）都在 ack **之后**送达（快照源于一次异步 Redis 读，必然晚于 ack）。客户端应按帧类型驱动，不得假定"ack 之后下一个帧就是快照"（中间可能插入 ping / delta / typing）。

## 2. 信号帧协议

四类信号帧共享形状骨架：`type` + `subscriptionId`（**接收者自己的订阅句柄**——订阅粒度可以粗于资源粒度，帧始终能定位到本连接的订阅上下文）+ `scope`（坐标，形状与入站 subscribe 的 scope 逐字节兼容）。信号帧的入站侧只有 `typing` 一类（§2.3）。

### 2.1 presence_snapshot（在场快照）

```jsonc
{"type":"presence_snapshot","subscriptionId":"<我的句柄>","scope":{...},
 "present":[{"principalId":"account:<uuid>","displayName":"Alice","connections":2}]}
```

- **仅资源与项目粒度**在订阅完成时自动发一帧；Team 粒度**不发**（整团队快照可能超出单帧预算，Team 订阅者运行在纯 delta 模式）；
- `present` 按 principal 去重，每项含该 principal 的**总连接数**（多标签页合并计数）；
- **快照含订阅者自己**（自己也是在场者）；
- 快照是**尽力而为**：presence 只存 Redis，Redis 读失败时订阅照常成立、快照缺席——丢失在场只降级呈现，永不影响业务（有损合同）。

### 2.2 presence_delta（进出通知）

```jsonc
{"type":"presence_delta","subscriptionId":"<我的句柄>","scope":{...},
 "action":"enter","principalId":"account:<uuid>","displayName":"Alice","connections":2}
```

- `action` ∈ `enter` / `leave`；同一 principal 开第二个连接也发 enter（`connections` 变 2），关掉一个发 leave（`connections` 回 1）——**leave 不代表离场，`connections:0` 才是**；
- **订阅者收不到自己的 enter/leave**（自己进出自己的视图没有信息量）；他人的进出全员可见；
- unsubscribe 与断连同样触发 leave delta；撤权强断（4403）也走同一条清理路径，剩余订阅者会看到该 principal 的 leave。

### 2.3 typing（正在输入）

```jsonc
// 入站（发送者自己的句柄）
{"type":"typing","subscriptionId":"<我的句柄>","state":"started"}
{"type":"typing","subscriptionId":"<我的句柄>","state":"stopped"}
// 出站（scope 内其他订阅者收到）
{"type":"typing","subscriptionId":"<接收者自己的句柄>","scope":{...},
 "principalId":"account:<uuid>","displayName":"Alice","state":"started"}
```

- 状态只有 `started` / `stopped` 两值（ADR-032 冻结，无中间态无文本）；
- **服务端锚定窗口**（非滑动，防饿死）：`started` 仅当无锚点或距锚点 ≥ `typingWindowSeconds` 才转发并移动锚点；窗口内重复 `started` 丢弃且**锚点不动**；`stopped` 总是转发并清锚——停了立刻再打字不会被旧窗口饿死；
- 未知句柄回 `unknown_subscription`；重订阅拿到新句柄 = 新窗口；
- **不落任何存储**（无状态转发）：客户端拿到 `started` 后应在 `typingWindowSeconds` 内无后续信号时自行清除输入指示器（自清是客户端义务，服务端不补发 stopped）；
- 评论框的输入信号挂在对应 **WORK_ITEM** 粒度订阅上（`resourceType` 枚举不为评论扩值）。

### 2.4 resource_changed（实时变更通知）

```jsonc
{"type":"resource_changed","subscriptionId":"<我的句柄>","scope":{...},"version":12}
```

- `scope` 是**变更资源的具体坐标**（WorkProject 变更→`resourceType:"work_project"`），不是接收者的订阅粒度；
- `version` = 领域事件的 `aggregateVersion`（该聚合的权威版本号，§5）；
- **帧里没有别的东西**：无标题、无内容、无变更人、无变更类型。客户端的义务是"该坐标可能变了，重新拉取权威读面"。

**三级展开**（fanout 受众 = 资源 ∪ 所属项目 ∪ Team 订阅者）：

| 领域事件 | 受众 |
|---|---|
| `WORK_ITEM_CREATED` / `WORK_ITEM_CONTENT_UPDATED` / `WORK_ITEM_STATUS_CHANGED` | 资源(work_item) ∪ 所属项目(payload.projectId) ∪ Team |
| `WORK_PROJECT_CREATED` | 项目 ∪ Team |
| `CONVERSATION_CREATED`（payload.visibility=`TEAM`） | 会话资源 ∪ Team |
| `CONVERSATION_CREATED`（`PRIVATE`） | **仅**会话资源 |
| `CONVERSATION_MESSAGE_POSTED`（仓库读可见性） | 同上两行规则；会话已删 / 不可解析 → 不推 |
| participant / comment / responsibility / 未知聚合 | **不推**（评论不可变无版本语义） |

PRIVATE 会话可见性门是**安全红线**：PRIVATE 会话坐标绝不出现在 TeamScope 的任何帧里——否则 Team 粒度订阅者可凭空推断 PRIVATE 会话的存在性（§7）。payload 缺 teamId 时防御性去掉 TeamScope 级（不静默推错受众）。

**乱序与重复是契约**：同一资源可能先收 v12 再收 v7（多事件并发提交），也可能重复收同版本。客户端去重规则：按 `(resourceType, resourceId)` 维护已见版本，应用**单调 max(version)**——旧版本到达直接丢弃。服务端保证的是"每个已提交事件都会尝试送达每个在册订阅者"，不保证跨资源的全序。

## 3. 错误码与 close 码表

| 形态 | code / close | reason 字符串 | 触发 |
|---|---|---|---|
| error 帧 | `invalid_json` | — | 入站帧不是合法 JSON 对象 |
| error 帧 | `unsupported-frame` | — | 未知 `type`（回显 `frameType`） |
| error 帧 | `invalid_scope` | — | scope 缺字段 / 坏 UUID / Team 粒度带 resourceId / 未知 resourceType |
| error 帧 | `forbidden_scope` | — | 鉴权拒绝（跨 Team、跨 Org、非成员——与"资源不存在"同形） |
| error 帧 | `subscription_limit` | — | 该连接订阅数达上限 |
| error 帧 | `rate_limited` | — | 信号预算超限（连接保持，见 §9） |
| error 帧 | `unknown_subscription` | — | typing 引用本连接未持有的句柄（回显句柄） |
| HTTP | 401 | `authentication_required` | 升级请求无有效会话 |
| close | 1000 | `inbound idle` | 连续 30s 无任何入站帧 |
| close | 1008 | `no authenticated principal` | 会话安全上下文缺失 / 重复连接 id |
| close | 1013 | `node limit` | 节点连接数达硬上限（500） |
| close | 1013 | `outbound budget exceeded` | 出站缓冲积压 256 帧（慢客户端，I01a） |
| close | 1013 | `signal budget exceeded` | 10s 窗口内第 3 次入站超限（§9） |
| close | 4000 | `principal limit` | 该账号连接数达上限（16，多标签页合计） |
| close | 4403 | `authorization revoked` | 复验层判定成员资格失效（§8） |

三个 1013 共享码位、靠 reason 字符串区分；客户端对 1013 的一律动作是指数退避重连。

## 4. 分页与重连

WebSocket 无分页——**N/A**。断线重连语义：

- 重连 = 重新握手 + 重新 subscribe（句柄全新，旧句柄随之全部失效）；
- **不回放**：断线窗口内的 delta / typing / 变更通知不补发、不可补发（typing 天然瞬态，presence 靠新快照重建，变更通知靠版本号收敛）；
- 权威兜底始终是 REST 读面与 Team Activity SSE——信号通道丢的任何东西都能从权威面拉回，这是 ADR-032「信号=有损提示层」的合同本质；
- 重连风暴防线：1013 统一退避重连；单次突发限流不触发断连（§9），不会因一次超限雪崩。

## 5. 版本语义（version / Idempotency）

- 帧中 `version` 一律是领域事件信封的 `aggregateVersion`——与 REST 写命令回执里的 `committedVersion` 同源，客户端可直接比对；
- **消费幂等 = 收据**：事件进 outbox 后由幂等收据消费器派发（同一事件至多一次展开），信号层再叠加 §2.4 的客户端 max(version) 去重，两层各挡各的；
- **写冲突不走本通道**：检测到版本落后后的写竞争仍走既有 REST 强 ETag `If-Match` 协议（409/412 语义不变），信号只是让冲突**在发生前被看见**；
- 在场 / typing 无版本概念（瞬态，Redis TTL 或内存锚点即生命周期）。

## 6. 公开字段白名单

| 帧 | 字段 | 说明 |
|---|---|---|
| presence_snapshot / presence_delta | `principalId` / `displayName` / `connections` | 全部公开字段 |
| typing | `principalId` / `displayName` / `state` | state ∈ started/stopped |
| resource_changed | `version` | 坐标之外唯一业务信号 |

**显式不含**：资源标题、内容、描述、状态名、变更人身份（resource_changed 里没有作者）、任何凭据。displayName 取账号 displayName（ADR-024 边界），解析失败降级为空串——呈现标签损失无害。`principalId` 是稳定公开标识（`account:<uuid>`），不含账号名 / 邮箱。

## 7. 在场可见性规则（专节）

1. **同 scopeKey 互见**：订阅了同一 (organization, team, 资源坐标) 的连接互相可见——包括不同 principal 的多连接合并计数；
2. **Team 粒度仅 delta**：Team 订阅者收不到快照（§2.1 帧预算），靠增量维护在场视图，乱序容忍同 §2.4；
3. **PRIVATE 会话不出 TeamScope**：可见性门（§2.4 表）保证 PRIVATE 会话的任何信号只达会话资源粒度订阅者——Team 粒度订阅者既看不到它存在，也看不到谁在它里面打字；
4. **快照含自己**（§2.1）；**自己收不到自己的 delta**（§2.2）；
5. **撤权窗口有界**：复验层缓存 + 心跳保证被撤成员**最坏几秒内**被切断（机制细节与最坏值见 §8），窗口内其已建立订阅的帧仍会送达——坐标 + 版本的零负载合同使"先发后验"不泄露任何业务事实；
6. **在场即订阅**：没有"纯围观"——不在该 scope 的订阅者集合里，就不出现在任何快照 / delta / typing 里。

## 8. 越权与撤权

**越权**（订阅时刻）：每帧逐次鉴权，跨 Team / 跨 Org / 非成员一律 `forbidden_scope`，与"资源不存在"同形（不泄露策略事实）；scope 键含 organization+team 坐标，**不同 Team 的 scope 键是隔离的订阅空间**——他队资源坐标在自己的键空间里永远匹配不到 fanout 受众。

**撤权三层分工**（ADR-032，连接已建立后成员资格被撤销）：

| 层 | 归属 | 机制 | 时窗 |
|---|---|---|---|
| 事件层 | **D02**（ADR-038 通道合同） | 成员生命周期事件→主动扫描断开 | 秒级（事件驱动） |
| 复验层 | **A01**（本契约） | fanout 发射路径成员缓存（默认 5s）+ 异步探针 + 心跳强制刷新 | 最坏 ≈ 心跳间隔 + 缓存窗 |
| TTL 层 | 存储固有 | 在场数据 45s TTL 自然过期 | ≤45s（兜底） |

复验层细节（`CollaborationRevocationRevalidator`）：

- 缓存 fresh → 帧放行零开销；缓存 stale → **帧照发** + 异步探针一次（同键并发合并为单探针）；
- 探针复用订阅鉴权**同一规则书**（无第二套成员判定），fail-closed：DENIED 与基础设施故障不可区分，一律按失效处理；
- 探针判失效 → close 4403 + 撤销全部订阅 + 广播 leave delta + 清理 Redis 在场（与正常断连同一清理路径，幂等）；
- **fail-closed 的副作用（运维须知）**：PostgreSQL / 鉴权链抖动也可能触发 4403。这是有意取舍——连接是廉价可重建的（客户端退避重连即恢复），而"撤权后多活几秒"是安全债。若实测过激，探针异常可改为保持 stale（D02/Q01 时再评）。

## 9. 性能预算

**入站信号预算**（每连接令牌桶）：容量 = 速率（默认 30/s，全新连接满桶）。ADR-032 实测入站峰值 22/s（10 活跃用户突发场景），30/s 桶容量在吸收合法突发之余留 36% 余量。超限处理分两级：

- **LIMITED**（10s 非滑动窗口内第 1–2 次违规）：回 `rate_limited` error 帧，连接保持——单次突发不该引发断连重连风暴；
- **ESCALATE**（同窗口第 3 次）：close 1013 `signal budget exceeded`——持续超限者必须被切断；
- 计费口径：除 pong 外**一切入站帧**（含 invalid_json 垃圾帧）都花令牌——垃圾洪水与合法洪水同价。

**fanout 预算**：单资源变更 → 50 订阅者扇出 P95 ≤ 50ms（S01b 原型实测 10.8ms，单节点内存拓扑）；出站慢客户端由 256 帧缓冲 + 1013 兜底（I01a），不占他人预算。

**收据写放大**：变更事件消费器在 outbox **收据事务内、4 线程固定池**上执行——sink 实现为非阻塞、绝不抛异常、可自由丢弃；通道关闭（`enabled=false`）时消费器 bean 不装配 = 零收据行，无写放大。

## 10. 运维旋钮与指标

| 环境变量 | 默认 | 范围 | 语义 |
|---|---|---|---|
| `CREWSCOPE_COLLABORATION_REALTIME_ENABLED` | `false` | — | 通道总开关；false = 端点 404、在场显式不可用（SSE 兜底，ADR-032 降级形态） |
| `CREWSCOPE_COLLABORATION_REALTIME_MAX_INBOUND_SIGNALS_PER_SECOND` | `30` | 5–120 | §9 令牌桶速率=容量 |
| `CREWSCOPE_COLLABORATION_REALTIME_REVALIDATION_INTERVAL` | `5s` | 1s–60s | §8 复验缓存窗 |
| `CREWSCOPE_COLLABORATION_REALTIME_TYPING_WINDOW` | `5s` | 1s–30s | §2.3 锚定窗口（ADR-032 冻结 5s，调窄仅为测试加速） |
| （既有 I01 旋钮） | | | 心跳 15s / 空闲 30s / 每账号 16 连接 / 节点 200 软 500 硬 / 每连接 32 订阅 / 出站缓冲 256 帧 / 入站帧 8KB / 在场 TTL 45s |

指标（Micrometer，runbook 见《Team-Beta 单机运维手册》实时协作通道小节）：

- `crewscope.collaboration.signal.emitted`（tag `frame_type` ∈ presence_snapshot / presence_delta / typing / resource_changed，枚举低基数）；
- `crewscope.collaboration.signal.rate-limited`（LIMITED 计数）；
- `crewscope.collaboration.connection.closed.revoked`（4403 计数——**突增 + 无成员变更 = 疑似鉴权链抖动**，对照 §8 副作用声明）；
- `crewscope.collaboration.connection.closed.rate-limited`（ESCALATE 计数）。

**信号降级语义**：Redis 故障 → presence 缺席（订阅照常）、复验退化为探针直查；PG 抖动 → 4403 短暂误伤（重连即愈）；事件消费停摆 → 变更通知静默缺席（SSE/REST 权威面不受影响）——所有降级都不产生错误形态，只产生缺席。
