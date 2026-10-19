# M13-C05：CursorCodec 基类收敛 — 完成记录

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C05 工作包（Review §3.2 CursorCodec 四胞胎）。
> 性质：纯结构收敛（零行为变化、零 API 契约变更）；wire 字节与错误语义逐字保留。

## 1. 交付：共享 framing + 三份字段声明

新增 `crewscope-server/.../api/CursorCodec<T>`（包私有抽象类，224 行），承载三个游标共享的 framing：

| 共享内容 | 说明 |
| --- | --- |
| token 编码管线 | `encode` 组装 body + 独立 HMAC-SHA256 签名 → base64url（无 padding） |
| 解码管线 | 规范 base64url 校验（字符集、长度上限、往返相等）→ 切分签名 → 读键 ID → 键环取键 → 常量时间比较 → 解析 → 时间校验 |
| 签名域 | `signingDomain()` 钩子：WorkItem/WorkDesk 在 HMAC 前喂入各自域前缀，TeamActivity 的 v1 格式无前缀 |
| 字段原语 | UUID、Instant（秒+纳秒）、可选 Instant/Int（0/1 标志）、长度前缀文本（UTF-8 往返校验）、固定 32 字节指纹 |
| 时间策略骨架 | `clock()`/`maximumAge()` 与默认 `maximumAge ≥ 1 分钟` 校验（文案含 Codec 类名，逐字不变）；`validateTime(T, byte[])` 由子类声明 |

三个 Codec 变为声明各自的 version 字节、签名域、payload 字段序、expected-scope 比较与过期异常：

- `WorkItemCursorCodec`（167 行）：v2、四个 UUID 作用域、排序 ordinal、指纹、签发时间、可选时间/排名、WorkItem id。
- `WorkDeskSectionCursorCodec`（196 行）：v1、三个 UUID + section key、指纹、签发时间、可选排名/截止时间、排序时间与 id；保留两个嵌套 record。
- `TeamActivityCursorCodec`（211 行）：v1、projection 文本、签发/过期双时间戳、Generation/Schema 版本、指纹、序列与事件 id；保留原有 2 空格缩进。

## 2. 行为等价性

- **wire 格式不变**：三份字段序、字段宽度、可选字段标志字节、时间戳纳秒精度、签名拼接顺序逐字段保留；TeamActivity 仍只写秒级签发/过期时间，WorkItem/WorkDesk 仍写秒+纳秒。
- **签名域不变**：`crewscope:work-item-cursor:v2` 与 `crewscope:work-desk-section-cursor:v1` 仍在 HMAC 前喂入；TeamActivity 仍无前缀。旧 token 继续可解。
- **错误语义不变**：三个 Codec 各自的 `invalid_cursor` 文案逐字保留（`CursorCodec.readText` 委托子类 `invalidCursor()`，不引入通用文案）；过期仍抛各自 `*CursorExpiredException`；未来时间偏差、token 长度上限（512/512/768）、键环轮换接受/移除拒绝的判定顺序不变。
- **校验顺序不变**：`decodeVerified` 保持「规范编码 → 长度 → 键 ID → 签名 → 解析 → 断言无剩余字节 → 时间」原序；expectedScope 的 null 检查仍在解析与时间校验之后；TeamActivity 的 expectedFilter → organization → team → projection 检查顺序逐字保留。
- **构造校验顺序不变**：keyRing → clock → maximumAge；TeamActivity 追加 futureSkew；null 消息与 IllegalArgumentException 文案逐字保留。
- **登记一处内部冗余删除（非行为）**：原 `parseVerified` 内重复的「键 ID 是否在键环」检查在基类取键验签后不可达，按同一 `readText` 规则重新读取必然得到相同结果；删除后不存在可观测差异。逐字等价性由现行游标测试与固定攻击集承证。

## 3. 行数盘点（如实入档）

| 口径 | 数值 |
| --- | --- |
| 三 Codec 基线 | 872 行（315+285+272） |
| 三 Codec 现况 | 574 行（167+196+211） |
| 共享基类 | 224 行 |
| 合计 | 798 行，**净 −74** |
| 差异 | 计划估计净删 ~300 未达成 |

未达成原因：三份 payload 字段布局本身不同（作用域维度、文本前缀宽度、可选字段集合、时间戳精度均不一致），属于 wire 格式的固有声明面；可共享的 encode/decode/base64/键 ID/签名/字段原语约 300 行已全部上提。剩余重复仅为「同形状的字段读写语句」，未引入通用字段 DSL——游标是安全边界，保留显式 ByteBuffer 读写以保证 wire 正确性可读可审，符合本期「零行为变化」优先于行数的纪律。

## 4. 测试证据

| 面 | 结果 |
| --- | --- |
| WorkItemCursorCodecTest | 6/6 |
| WorkDeskSectionCursorCodecTest | 6/6 |
| TeamActivityCursorCodecM6E05Test | 4/4 |
| M6CursorFixedAttackSetM6Q01Test（六游标 × 六畸形 token 固定分母 36） | 36/36 |
| server 模块 verify（`-pl crewscope-server -am`） | BUILD SUCCESS：1096 用例，0 失败 / 0 错误 / 2 跳过（2026-10-19 23:10；上述游标报告时间戳 23:05–23:06，系新源码编译后执行） |
| OpenAPI 漂移 | `node scripts/check-openapi-drift.mjs` exit 0——Base/Codec 均非 `*Controller.java`，指纹不触发 |

## 5. 复跑入口

```sh
./mvnw -pl crewscope-server -am test -Dtest='WorkItemCursorCodecTest,WorkDeskSectionCursorCodecTest,TeamActivityCursorCodecM6E05Test,M6CursorFixedAttackSetM6Q01Test' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl crewscope-server -am verify
node scripts/check-openapi-drift.mjs
```

全 reactor 与真实栈由 Q01 收口统一背书。
