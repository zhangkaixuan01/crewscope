# M11-S01：实时协作与 WorkGraph 合同冻结

> 日期：2026-10-09；源码基线：`b0a2099`（M10-Q02 收官，CI 全绿）。<br>
> 状态：**S01 已完成**（S01a 合同冻结 + S01b 隔离原型实测全回填，§9 已闭合）。不是 M11 功能完成报告。<br>
> 本轮只交付设计、源码核对与隔离原型：不写产品代码、不写迁移（基线 tip = V66，D01 从 V67 顺延）、不启动产品服务、不调用真实模型。原型验证机制保真度，不冒充 I01/D01 的产品验收。

## 1. 阅读与放行规则

[主计划](../plans/M11-协作规模化与开放生态.md) 管范围与依赖，§10.1 红线、§10.3 落点、§10.7「不要这样做」清单对本轮全部生效。本记录落实 [ADR-032](../adr/ADR-032-实时协作通道与在场模型.md)、[ADR-033](../adr/ADR-033-WorkGraph关系模型.md) 的合同与 [ADR-038](../adr/ADR-038-团队成员生命周期与责任转移.md) 的新通道增补；可选范围以薄登记 ADR-034/040/041 登记，选入前不闭合、不阻塞主线。正式 API 契约（协作信号/WorkGraph）归 A01/A02 交付，本文的协议描述均为**待实现**。

§10.8 十项开放项的闭合状态：

| # | 开放项（原文摘要） | 冻结结论 | 实现责任与不得冒充完成的验证 |
| --- | --- | --- | --- |
| 1 | WebSocket 与 SSE 共存边界与降级 | 已冻结：分工表与降级语义（§3.1、ADR-032）；耐久流零改动 | I01/A01 实现并验收降级端到端；S01b 只验机制保真度 |
| 2 | 在场模型、TTL、键命名、50 人预算 | 已冻结：连接键 + scope ZSET、TTL 45s（§3.2、ADR-032）；预算公式 §4 | I01b 交付清扫与订阅面；S01b 原型实测 TTL/去重/内存 |
| 3 | WorkGraph 规模上限与降级呈现 | 已冻结：交互 500 / 深度 32 / 行 5000 / 邻接 100ms / CTE 500ms（§3.4、ADR-033） | F02 交付降级 UI；S01b 实测 CTE 基线回填 §4 |
| 4 | MCP Client/Server 能力边界 | 已登记（ADR-034 薄登记，含 extensions-protocol 不存在的事实修正） | 选入后闭合；未选入不记为已验证 |
| 5 | MCP 注册持久化 | 已登记（ADR-034 选入后清单第 1 问） | 同上 |
| 6 | D02 撤权事件消费与清理 | 已冻结：三层协议 + 无持久化坐标裁定（§3.3、ADR-038 增补） | D02 交付真实 Outbox 消费者与 M9b 回归；S01b 原型验断连时延 |
| 7 | WS 重连复用 M9b 生命周期鉴权 | 已冻结：握手过 security chain + attributes + Origin 检查（§3.5、ADR-032） | I01a 实现产品级握手；S01b 原型验 401/403/重握手 |
| 8 | i18n 门禁/持久化/格式化归属 | 已登记（ADR-040 薄登记） | 选入后闭合 |
| 9 | 桌面纯包装 vs 业务适配 | 已登记（ADR-041 薄登记，含判定标准） | 选入后闭合 |
| 10 | 大包可领取粒度拆分 | 已冻结：I01a/I01b/I01c（§7）；I02/F04/I03 选入后按薄 ADR 清单细化 | I01a 即刻可领取 |

主线六项（1/2/3/6/7/10）的答案在 §3 详述；可选项（4/5/8/9）只登记不阻塞。

## 2. 源码/依赖事实与风险

| 事实入口（仓库相对路径） | 本轮核实结果 | 实施约束 |
| --- | --- | --- |
| `crewscope-server/.../api/TeamActivityController.java`、`ConversationEventController.java`、`TaskEventController.java`、`PersonalAgentInvocationController.java`、`TeamObserverController.java`、`TeamActivityRealtimeStream.java` | 全部实时面 = WebFlux SSE（7 端点）、纯 DB 有界轮询（500ms / batch 100）、心跳 15s + idle-probe 5s、`maximumEventsPerConnection` 有界轮换；零推送基础设施（无 Sinks / Redis pub-sub / LISTEN-NOTIFY） | WebSocket 引入是全新依赖决策，不与既有轮询面耦合；心跳常量与之对齐 |
| `docs/adr/ADR-021-三流恢复与前端合并协议.md` | 权威恢复面 = 版本化 HMAC 签名游标（Team）/耐久补发（Conversation）/Segment 重放（AG-UI） | **WS 不沾任何恢复协议**；多标签每标签独立连接，不触发 ADR-021「多标签共享一条 SSE」重评条件 |
| `crewscope-server/.../config/application/TeamActivityRealtimeProperties.java`、`TaskEventStreamProperties.java` | poll 500ms / heartbeat 15s / idleProbe 5s / batch 100 / maxEvents 10000 | WS 心跳 15s、复验缓存 5s 直接对齐 |
| 根 `pom.xml`、`crewscope-server/pom.xml` | Boot 4.0.6；spring-framework-bom 7.0.9 前置 override；**无任何 websocket 依赖**；reactor 仅 test | 产品引入须用 `org.springframework:spring-websocket`（bom 管版本），**禁用 starter-websocket**（传递 Tomcat/MVC 与 WebFlux 冲突） |
| `crewscope-server/.../security/session/BrowserSessionConfiguration.java`、`BrowserSessionProperties.java`、`SecurityConfiguration.java` | Session=Spring Session Redis（namespace `crewscope:session`，TTL 12h，maxSessions 5）；CSRF cookie + `SameOriginWebFilter` + `TaskTokenWebFilter`（仅 internal 面） | WS 握手复用此链：Origin 检查防 CSWSH，`authenticated()` 挡未认证升级，`getAttributes()` 取 principal（WebFlux 无 HandshakeInterceptor） |
| `crewscope-infrastructure/.../agentscope/RedisAgentStateConfiguration.java`、`CrewScopeRedisKeyspace.java`、`LoginDefenseKeyspace.java` | Redis 三用途键位不相交；键惯例 `crewscope:{env}:{域}:v1:`；TTL 先例 12h/30s/10s/5m；无 pub/sub；Jedis 与 Lettuce 并存 | 在场键落 `crewscope:{env}:collaboration:v1:` 命名空间，不与三用途重叠 |
| `deploy/team-beta/nginx.conf` | `/api/` 已配 `proxy_http_version 1.1` + `buffering off` + `read_timeout 3600s`；**无 Upgrade/Connection 头** | I01c 交付 `map $http_upgrade $connection_upgrade` 与 Upgrade 透传；SSE 配置不动 |
| `crewscope-web/src/domains/teamops/activityRealtimeStore.ts`、`src/api/sse.ts`、`src/domains/realtime/cursorStore.ts` | SSE 面九相状态机 + 指数退避 `min(8000, 500×2^min(a,4))`；fetch 解析（无 EventSource）；游标 localStorage `crewscope:stream:v1:` | 前端新增 collaboration store（五相）复用退避常量；SSE 面原样不动 |
| `crewscope-application/.../team/TeamMemberLifecycleApplicationService.java`、`crewscope-domain/.../team/TeamMember.java`、`team/event/*` | 7 命令固定锁序（Team 行 PESSIMISTIC_WRITE + If-Match + 最后 Owner 校验）；authorizationVersion 状态/角色变更 +1、recordActivity 不 +1（V43 列）；7 个撤权事件带 authorizationVersionAfter；**无消费者主动断连**（撤权=各边界拉取式重读） | D02 的 Outbox 消费者是第一个事件驱动断连实现；WS 层对成员事实只读 |
| `docs/adr/ADR-038-团队成员生命周期与责任转移.md` §3 表末行 | 已留口：「后续 WebSocket/在场 \| M11-D02，当前无此新增实现 \| 订阅/消息/回放按同一 authorizationVersion 接入，不复制生命周期命令/表」 | 本轮增补三层协议与无持久化坐标裁定（见 ADR-038 M11 增补小节），只增不改既有段落 |
| `crewscope-domain/.../workitem/`、全部迁移 SQL | 工作项间关系零现状（无 parentId/blockedBy/depends/splitOf）；`WorkItemResourceLink` 是资源侧链接；`ConversationWorkItemLink` 是最近邻接表先例（不可变 + origin 枚举 + 四键 scope + AuditMetadata）；`WITH RECURSIVE` 零先例 | D01 是库内第一个递归 CTE；关系表沿用四键复合租户 + 5 列复合 FK 惯例 |
| `crewscope-application/.../event/`（DomainEventStore/OutboxRepository）、`crewscope-infrastructure/.../event/` | 同事务双写 → PollingOutboxPublisher → IdempotentEventDispatcher（consumer_receipt 幂等）→ 投影注册表 | 关系事件与撤权消费者都走既有管线，不建平行通道 |
| `~/.m2/repository/io/agentscope/`（11 个 artifact 2.0.0）、`agentscope-bom-2.0.0.pom` | **`agentscope-extensions-protocol` 不存在**（BOM 与本地仓库均无）；MCP 能力在 `agentscope-core` 的 `io.agentscope.core.tool.mcp`（McpClientBuilder：Stdio/Http/Sse/StreamableHttp、McpTool、McpContentConverter）与 `io.agentscope.core.tool.McpClientManager`；BOM 最接近的是 `agentscope-extensions-agent-protocol`（A2A，未下载） | **主计划 §10.3 第 3 条「依赖 agentscope-extensions-protocol（实测该模块存在）」与事实不符，以本行为准修正**；落点结论（agentscope 模块、不进 integration）不变，构件名待 I02 选入时按本行落实 |
| `crewscope-infrastructure/src/main/resources/db/migration/` | tip = V66（`V66__coding_specialist_task_session.sql`，M10-Q02）；vector 目录是独立链 | S01 不写迁移；D01 从 V67 顺延，不预留 |
| `crewscope-web/src/domains/*/labels.ts`（19 域）、`src/app/preference.ts`、`src/composables/useRelativeTime.ts`、`package.json` | labels 形态 `Record<Enum, string>` 中文面值（「后端加枚举必红」契约）；preference 键 `cs.pref.<area>.<name>.v1`；相对时间 = Intl zh-CN；vue-i18n 零痕迹 | ADR-040 已登记；F04 选入时 labels 并入 i18n 且保留类型约束 |

上表 `...` 为检索路径提示。冻结针对行为，不依赖行号。

## 3. 主线六项冻结详述

### 3.1 通道共存与降级（开放项 1）

分工表、降级语义、前端双 store 设计见 ADR-032「通道分工与降级」。要点重述：

- 在场/输入**仅 WebSocket**，WS 不可用时功能显式不可用，**不开设 SSE 替代通道**——为易失信号增加一条有游标语义的通道会把「可丢弃」重新变成「需恢复」，违反 Release Gate 第 2 条的精神。
- 变更通知是**增强**：载荷只有稳定坐标 + 新版本，权威更新由既有 SSE Team Activity 流与 REST 覆盖。损失 WS 只损失推送时延，不损失正确性。
- 三条耐久流与 AG-UI 零改动；ADR-021 的恢复边界原样有效。

### 3.2 在场数据模型（开放项 2）

连接级 hash 键（TTL 45s = 3 × 心跳 15s）+ scope ZSET 索引（score = expireAt，惰性清理 + I01b 定期清扫）；多标签每标签一连接、呈现按 principal 去重；预算按原始连接数计。完整键名、字段与 50 人预算公式见 ADR-032 与本文 §4。选「连接级键」而非「成员级键」的理由：成员级单键无法表达多资源多标签的连接粒度；读时按 principal 聚合即可等价获得成员视图。

### 3.3 撤权事件消费与清理（开放项 6）

三层协议（事件层 Outbox 消费者 → 复验层每帧 authorizationVersion ≤5s 缓存 → TTL 层 45s 上界）与断连码 4403 见 ADR-038 M11 增补小节。**裁定：D02 不需要持久化投递坐标**。论证：撤权权威事实已耐久（`team_member.authorization_version` 列 + Outbox 事件，均为 M9b 已交付物）；通道状态（内存注册表、Redis 在场）按红线本就是可丢弃可重建的；复验层直接读当前事实，漏事件在下一帧自愈，最坏窗口 = 5s + 一个心跳间隔，与 SSE 已接受的 idle-probe 窗口同量级；为易失通道新增持久化坐标既违反「在场不进 PG/Outbox」红线又买不到一致性增益。

### 3.4 WorkGraph 规模与降级（开放项 3）

阈值三件套（交互 500 / CTE 深度 32 / 行 5000）与超限降级（分层列表 + 聚焦子图 ≤200）见 ADR-033「规模阈值与降级呈现」。500 来自 F02 验收「图在 500 节点下可交互」；32 的依据：深度上界防近环误查并保护分析查询，覆盖 D01 冻结的 5000 节点测试图的现实链深（长链 2000 节点单链深度会超 32——**该场景按设计截断并显式呈现**，见 §4 实测注记）。

### 3.5 WS 重连复用 M9b 鉴权（开放项 7）

握手 = 普通 HTTP upgrade 请求过既有 reactive filter 链（`SameOriginWebFilter` Origin 检查防 CSWSH + security chain `/ws/**` `authenticated()`）→ `WebSessionServerSecurityContextRepository` 解 cookie → handler 内 `getAttributes()` 取 principal。重连 = 完整重新握手 + 重发 subscribe + 逐条重新授权；不回放 WS 状态。CSRF 不适用于 GET 握手（无副作用），CSWSH 防护由 Origin 检查承担。**不新增第二处成员命令入口**：WS 层对成员事实只读。

### 3.6 I01 可领取粒度（开放项 10 主线部分）

见 §7 切片卡。I02/F04/I03 不拆分，选入后按各自薄 ADR 的「选入后须回答清单」细化（主计划 §9 粒度约定原话）。

## 4. 资源预算与阈值（公式冻结；实测数字 S01b 回填）

| 预算项 | 冻结公式 / 阈值 | S01b 实测（2026-10-09，darwin arm64） |
| --- | --- | --- |
| 50 人同屏入站 | ≈ 50 × (1/15 心跳 + typing 5s 窗口 2 帧 + 2/min 迁移) ≈ 22 msg/s 峰值 | 未单独打流；steady 200 连接全程心跳+pong 无积压（framesIn=1608/framesOut=3208 over 120s，无 backpressure 迹象），50 人 22 msg/s 远低于该水位 |
| Redis 在场写 | ≈ 3.3 writes/s 稳态（心跳刷新） | 未单独计数（probe 写路径 fire-and-forget）；steady 200 conn × 1/15s 心跳刷新 ≈ 13.3 writes/s 无异常（Redis 同机 docker，日志无慢查询） |
| 单节点连接 | 软预算 200 / 硬上限 500；每 principal 16；每 Team 300 | storm 200 并发全部建立（connections=200/200，零失败）；堆增量 ≈90KB/conn（含 GC 噪声），500 conn 外推 ≈45MB 单实例可承受 |
| 握手 | 200 并发握手 P95 | **P50=152.7ms / P95=177.4ms / P99=186.1ms**（upgrade→welcome，200 路并发；login 串行前置不计入） |
| 心跳 | ping→pong P95 | **P95=1.7ms**（本机 loopback，应用层 text 帧，n=20） |
| fanout | 1 条变更 → 50 订阅者 P95 | **POST 12.8ms；到达 P50=8.7ms / P95=10.8ms / P99=10.8ms**（50/50 送达） |
| 撤权 | revoke → 全连接关闭 + Redis 清理时延 | **layer1 12ms**（4403 + presence 即删）；layer2 9ms（revoke 即时清缓存；无事件上界 = 5s 缓存 + 一次出站 emit，revoke 场景实测上界内） |
| 在场 TTL | 45s 过期 + ZSET 惰性清理 | 种孤儿键 50s 后 conn 键 `EXISTS`=0（TTL 硬上界成立）；ZSET 残留成员在下一次 presence 读被 `ZREMRANGEBYSCORE` 清除；clean close 与 RST 均 ≤2s 即清（详见 presence-ttl.md §1） |
| 邻接查询 | P95 ≤ 100ms | **P50=0.047 / P95=0.069 / P99=0.083 / max=0.098 ms**（链/hub/leaf 混合，n=33，EXPLAIN ANALYZE；cte-depth.sql） |
| 5000 节点阻塞链 CTE | P95 ≤ 500ms（深度 32 截断；长链截断行为显式呈现） | **单向上游链 P50=0.134 / P95=0.161 / P99=0.162 ms**（链底/链中/hub 混合，n=33，深度截断在 32 行如设计）；**多父菱形网格最坏形态（60×60、4 上游边/节点）UNION 版 P50=0.626 / P95=1.292 / max=11.665 ms**（n=12，max 为冷缓存首跑）。新增实现红线：递归 CTE 一律 UNION 去重（见 §6 第 3 条） |

度量口径：darwin arm64 本机、单 API 进程（记录 JVM 参数与堆）、Redis 本机 docker、直连不过 Nginx。产品级（过 Nginx、真实权限过滤、进程重启重连潮、Prometheus 指标）归 I01/Q01，本文不冒充。

## 5. 验收七场景 → 证据形态映射

| 场景 | S01 证据 | 形态 | 产品验收归属 |
| --- | --- | --- | --- |
| 多标签 | 同 cookie 3 连接：presence 按 principal 去重、不新建 Session、各自独立复验 | 原型实测（multitab） | F01/Q02 真实浏览器 |
| 多成员编辑 | 变更通知合同（零负载 + 坐标版本）+ fanout 50 订阅者 P95；冲突仍走强 ETag，不做 CRDT | 合同 + 原型实测 | A01/F01/Q02 |
| 断线 | 断连 → 退避重连 → 重握手重订阅；presence 清理时延 | 原型实测 | I01c/F01（过 Nginx） |
| 过期 | 停心跳 → 45s TTL 过期 + 惰性清理；session 过期 → 握手 401 | 原型实测 | I01b |
| 跨 Team | B 队 principal 订阅 A 队资源被拒 + 计数留痕；可见性规则（不泄漏资源标题） | 原型实测 + ADR-032 | A01/Q01 攻击集第 3 项 |
| 并发成环 | cycle-race.sql 三场景（相反边 / diamond race / SPLIT_FROM 祖先环）真实 PG 两会话拒绝证明 | SQL 原型实测 | D01（真实表 + 并发集成测试） |
| 撤权 | revoke → 4403 断连 + Redis 清理时延；三层协议 + 无持久化坐标裁定 | 原型实测 + ADR-038 增补 | D02（真实 Outbox 链 + M9b 回归） |

**证据不冒充声明**：ws-probe 用独立 Spring Session namespace（`crewscope:probe:m11s01:session`）验证机制保真度（cookie → 握手 → attributes → 复验），不是产品 session 存储的字节级一致；产品级一致归 I01 集成测试。SQL 原型在独立 schema `m11s01` 上验证事务协议，不是 D01 迁移与聚合的验收。

## 6. S01b 原型清单与结果（2026-10-09 实测，WS 八场景 + SQL 三场景全通过）

| 入口 | 场景 | 结果摘要 |
| --- | --- | --- |
| `scripts/m11-s01/ws-probe/` | 独立 Maven 小应用（Boot 4.0.6 + WebFlux + spring-websocket 7.0.9 + reactive Redis + Spring Session），端口 18095 | `./mvnw verify` BUILD SUCCESS；Netty 启动 1.3s；未认证 upgrade→401、Origin 校验、login→session→握手链路全通 |
| `scripts/m11-s01/load-ws.mjs` | storm / steady / slow / fanout / revoke / ttl / multitab / crossteam 八场景 | **全部通过**：storm 200 并发握手 P95=177ms 零失败；steady 200×120s 心跳维持 CPU 1.2% 单核、RTT P95=1.7ms；slow 静默 30s 断（code 1000）+ 健康连接隔离；fanout 50/50 P95=10.8ms；revoke layer1 12ms/layer2 9ms（4403）；ttl 三退出路径 + 惰性清理；multitab 3 连接去重 1 principal；crossteam 拒绝 + 计数 |
| `scripts/m11-s01/presence-ttl.md` | TTL 到期、close vs 自然过期、惰性清理、去重示例、内存占用 | 已交付（含半开连接本机不可模拟的诚实注记与 I01a 义务移交） |
| `scripts/m11-s01/cycle-race.sql` | S1 相反边 / S2 diamond race（含端点锁否证）/ S3 SPLIT_FROM 祖先环 | **三场景全部按预期**（真实 PG 容器两会话交错，断言输出留档 /tmp 会话记录）：S1 T2 锁等待 2.68s → 读到已提交 A→B → 探测 true 拒绝；无锁对照命中 uq_dep_reverse；S2a 端点锁方案 T1/T2 双双提交且 cycle_present=t（否证成立——端点不相交互不阻塞、双唯一索引对 diamond 组合不兜底）；S2b 协议版 T2 锁等待 1.66s → 探测 true 拒绝 → 图无环；S3 祖先链 X 的祖先={Y,Z}，Z 在其中 → 拒绝成祖先环 |
| `scripts/m11-s01/cte-depth.sql` | 5000 节点阻塞链 CTE P50/P95/P99 + 邻接基准 | 邻接 P95=0.069ms（阈值 100ms）；CTE P95=0.161ms（阈值 500ms）；多父菱形网格最坏形态 UNION 版 P95=1.292ms；长链深度截断在 32 行如设计。**过程中实证 UNION ALL 红线**（见下第 3 条） |

S01b 过程中发现并修正的三个**承重事实**（产品实现必须带走）：

1. **Spring Framework 7 的 `WebSocketSession.getAttributes()` 不再是 6.x 的 exchange attributes**。`HandshakeWebSocketService.initAttributes` 只有在设置了 `sessionAttributePredicate` 时才从 `WebSession` attributes 过滤拷贝（默认空 map）——握手鉴权链（filter 校验 401 + handler 读 principal）必须显式配置该 predicate（probe 见 `WebSocketConfiguration`）。ADR-032 §握手鉴权的机制描述以此为准。
2. **Reactor Netty 的 WebSocket `send` 路径不把 channel writability 反馈为上游消费停滞**（framesOut 实测全量成功）。慢客户端防护不能依赖「bounded sink + 库背压」隐式生效：真实网络的带宽延迟积会让 channel 不可写、sink 溢出、1013 生效，但应用层出站配额（未确认出站字节/帧上限，超限即关）是显式可测的等价物——已登记为 I01a 义务（presence-ttl.md §4）。
3. **BLOCKS 图上的递归 CTE 必须用 `UNION`（去重），`UNION ALL` 会指数爆炸**。多父汇聚 DAG 的路径数随深度指数膨胀（60×60 菱形网格、每节点 4 条上游边 = 理论 4^32 中间行），且外层 LIMIT 不阻止递归项物化——实测 UNION ALL 版把本机 PG backend 直接打崩（连带 Docker Desktop VM 重启）。UNION 把工作集钳制在节点数上界，实测最坏形态 P95=1.292ms 达标。SPLIT_FROM 是单 parent 森林、路径唯一，两种写法语义等价，仍统一 UNION 防复制扩散。红线已固化进 cte-depth.sql 文件头、cycle-race.sql 模板与 ADR-033「阻塞链」小节。

## 7. I01 切片卡与可选包指引

| 切片 | 内容 | 验收要点 |
| --- | --- | --- |
| I01a 传输与连接管理 | spring-websocket 接入（WebFlux + Reactor Netty）、握手鉴权复用（security chain + attributes + Origin）、心跳 / 空闲断开、连接注册表、per-principal/Team/节点上限、有界出站缓冲与慢客户端断开 | 未认证 / 跨 Origin 握手被拒；心跳与断开常量符合 ADR-032；慢客户端不拖垮节点 |
| I01b 订阅拓扑与在场存储 | subscribe/unsubscribe 协议、Team/WorkProject/资源三粒度、逐订阅授权求交、Redis 在场写路径 + TTL + scope 索引 + 定期清扫、订阅数上限 | 跨 Team 订阅拒绝且记录；在场丢失不影响业务；清扫覆盖无人访问的 scope |
| I01c 部署与降级端到端 | Nginx Upgrade/Connection（map）配置、team-beta compose、四服务真实浏览器验证、WS 不可用时 SSE 降级 e2e、连接风暴 / 重连潮演练产品化 | 降级路径端到端可用；200 连接产品级基线（过 Nginx） |

依赖：I01a → I01b → I01c（a/b 可并行，c 收口）；A01 消费 I01b 订阅面。I02/F04/I03 见各自薄 ADR。

## 8. 验证台账

| 验证项 | S01a | S01b | 后续责任 |
| --- | --- | --- | --- |
| §10.8 十项合同闭合 | 本文档 §1/§3 | — | 主计划 §10.8 |
| 通道降级语义 | ADR-032 分工表 | 原型不覆盖（无产品 SSE 面） | I01c e2e |
| 在场 TTL 与去重 | ADR-032 模型 | multitab/ttl 实测 | I01b |
| 撤权三层 + 4403 | ADR-038 增补 | revoke 实测 | D02 + M9b 回归 |
| 并发无环（真实 PG） | ADR-033 协议 + diamond-race 论证 | cycle-race 三场景 | D01 集成测试 |
| 图查询阈值 | ADR-033 阈值表 | cte-depth 回填 | A02 查询预算 |
| 200 连接预算 | ADR-032 上限 + §4 公式 | storm/steady 实测 | I01 产品级基线 |
| ADR/索引同步 | 本轮 C1/C2 提交 | — | — |
| 领域零框架依赖 | ADR-033 实现约束 | — | D01 评审 |
| 迁移基线（V67 顺延） | §2 事实行 | — | D01 |
| 可选 ADR 登记 | ADR-034/040/041 | — | 选入时细化 |

## 9. 决定闭合（2026-10-09 S01b 实测后）

主线六项逐项闭合声明：

| # | 开放项 | 闭合声明 | 证据 |
| --- | --- | --- | --- |
| 1 | 通道共存与降级 | 冻结生效（ADR-032 分工表）；WS 降级=功能显式不可用，耐久流零改动 | §3.1；产品验收归 I01c |
| 2 | 在场模型/TTL/预算 | 冻结生效（连接键 TTL 45s + scope ZSET）；八场景实测 TTL 三退出路径、去重、200 连接预算 | §3.2/§4/presence-ttl.md |
| 3 | WorkGraph 规模与降级 | 冻结生效（500/32/5000/100ms/500ms）；实测全部达标且余量两个数量级 | §3.4/§4；UNION 红线见下 |
| 6 | D02 撤权事件消费 | 冻结生效（三层协议 + 无持久化坐标裁定）；revoke 实测 layer1 12ms/layer2 9ms | §3.3；ADR-038 增补 |
| 7 | WS 重连复用 M9b 鉴权 | 冻结生效（握手过 filter 链 + sessionAttributePredicate）；401/403/重握手实测 | §3.5；承重事实 §6-1 |
| 10 | I01 拆分 | 冻结生效（I01a/I01b/I01c 切片卡，§7） | — |

**实测迫使冻结值调整的显式记录：无**。全部阈值（邻接 100ms、CTE 500ms、连接 200、握手/心跳/fanout/撤权/TTL 各预算）实测达标，未发生被迫改值。**新增实现红线一条**（非阈值调整）：递归 CTE 一律 UNION 去重，UNION ALL 在多父汇聚 DAG 上指数爆炸打崩 PG（§6-3，已固化进 cte-depth.sql / cycle-race.sql / ADR-033）。

可选项（4/5/8/9）维持薄登记不闭合，主线未等待。

**S01 关闭，I01a 即刻可领取**（切片卡 §7）；D01 迁移从 V67 顺延不受影响。M11 主计划状态行已回填「S01 已完成」。
