# M11-S01 presence TTL 与压测观察（S01b 证据）

日期：2026-10-09。环境：macOS（Darwin 25.3.0），Java 21.0.12，Node v24.13.1，
Redis 7（docker compose `crewscope-java-redis-1`，127.0.0.1:6379），ws-probe 单实例
（Netty，端口 18095）。Redis 键空间独立前缀 `crewscope:probe:m11s01:collaboration:v1:`
与独立 session namespace `crewscope:probe:m11s01:session`，全程未触碰产品键。

所有数字来自 `load-ws.mjs` 八场景实跑输出（本机 loopback、单进程、无 Nginx），
绝对值仅作量级参考；I01c 在四服务真实栈+浏览器下重测。

## 1. TTL 45s 的三个退出路径（ttl 场景实测）

| 退出路径 | 触发 | 实测结果 |
|---|---|---|
| 正常关闭 | 客户端发 WS close 帧 | cleanup 即刻 `DEL conn` + `ZREM` 成员：close 后 2s 内 ZSET=0 |
| 传输层断开 | `socket.destroy()`（RST，无 close 帧） | 服务器经 transport close 走同一 cleanup：2s 内 ZSET=0 |
| 服务器失联（模拟半开/kill -9） | 手工直写 Redis 种入孤儿键（conn hash TTL 45s + ZSET 成员 score=now+45s） | 50s 后：conn 键 `EXISTS`=0（**TTL 是硬上界**），ZSET 成员仍残留；下一次 `presence` 读触发 `ZREMRANGEBYSCORE` 惰性清理，ZSET 只剩活连接成员 |

结论：在场数据的三条退出路径闭合——正常路径立即清，异常路径由 45s TTL 兜底，
索引残留由读路径惰性清扫。**score=expireAtMs 的 ZSET 索引不自行过期**，
产品实现（I01b）必须保留同样的读路径清扫，不能只依赖 TTL。

诚实声明：真·半开连接（网线拔出、NAT 静默丢弃，无 FIN/RST）本机无法模拟——
本机所有 TCP 断开都会产生 FIN 或 RST，走 cleanup 路径。种孤儿键是等效替代：
它精确复现「服务器不再刷新/删除键」的终态，TTL 语义（`EXPIRE 45`）与
真实心跳停止后的键状态完全一致。

## 2. 多标签按 principal 去重（multitab 场景实测）

同一 cookie（同一 Redis session）开 3 条连接，全部订阅同一 scope：

- 3 个 connectionId 互不相同（每标签一条连接，Session 不新建）
- presence 视图返回**单条**：`{"principalId":"tab-user","connections":3}`

呈现层合同成立：连接预算按原始连接数计（200 含多标签），呈现按 principal 去重。
Session 数恒为 1（握手复用既有 session，无第二个 token）。

## 3. 200 连接规模实测（storm + steady）

| 指标 | 实测 | 冻结阈值（ADR-032） | 余量 |
|---|---|---|---|
| 200 并发握手（upgrade→welcome） | p50=152.7ms p95=177.4ms p99=186.1ms | 单节点软上限 200 | 无积压、无失败 |
| 稳态心跳维持（200 conn × 120s） | CPU +1.5s/120s（≈1.2% 单核）；framesIn=1608/framesOut=3208 | — | 充足 |
| 稳态 RTT（应用层 ping→pong，n=20） | p50=1.1ms p95=1.7ms | — | — |
| 堆增量 | 峰值后稳态 ~90KB/conn（storm 前后差含 GC 噪声） | 节点软 200/硬 500 | 500 conn 外推 ≈45MB，单实例可承受 |

framesIn/Out 符合模型：200 连接 × 8 个心跳周期 ×（1 ping + 1 pong）≈ 1600/1600，
另有 RTT 段 20 ping。

## 4. 慢客户端与本机环境的边界（slow 场景，重要诚实记录）

原设计「暂停读取 → 出站缓冲填满 → 1013 断开」在本机**不可复现**，两层原因：

1. macOS loopback 的 socket 缓冲被 autotune 到 MB 级，200~20000 帧（26KB~2.6MB）
   突发全部被 OS 缓冲吸收，Netty channel 始终可写；
2. 实测（framesOut 计数）表明 Reactor Netty 的 WebSocket `send` 路径**不将
   channel writability 反馈为对上游 sink 的消费停滞**——bounded sink（64 帧）
   被即时抽干，`tryEmitNext` 溢出路径在本机永不触发。

实测可验证的慢客户端行为：入站静默 30s → 关闭（close code 1000，与 CLOSE_IDLE
一致），且慢客户端存在期间健康订阅者 pong 往返正常（故障隔离）。30.01s 的实测
断开时刻与 15s 心跳 × 2 的设计值吻合。

**移交 I01a 的义务**（spike §9 已登记）：慢客户端防护不能只依赖「库行为 +
sink 容量」；产品实现须在真实网络（带宽延迟积自然停滞 channel）下验证 1013
路径，或改为应用层出站配额（未确认出站字节/帧数上限，超限即关）。

## 5. 复现方式

```bash
docker compose up -d redis                      # 127.0.0.1:6379
./mvnw -f scripts/m11-s01/ws-probe/pom.xml verify
java -jar scripts/m11-s01/ws-probe/target/crewscope-m11-s01-ws-probe-0.1.0-SNAPSHOT.jar &
cd scripts/m11-s01
node load-ws.mjs --scenario ttl        # §1，含 50s 等待
node load-ws.mjs --scenario multitab   # §2
node load-ws.mjs --scenario storm      # §3
node load-ws.mjs --scenario steady --duration 120   # §3
node load-ws.mjs --scenario slow       # §4
```

Redis 侧观察（本机无 redis-cli，经容器）：

```bash
docker exec crewscope-java-redis-1 redis-cli --scan \
  --pattern 'crewscope:probe:m11s01:collaboration:v1:presence:*'
docker exec crewscope-java-redis-1 redis-cli ZRANGE \
  'crewscope:probe:m11s01:collaboration:v1:presence:scope:{org}:{team}:{rt}:{rid}' \
  0 -1 WITHSCORES   # score = expireAtMs（epoch 毫秒）
```
