# M6-S02 三流 Cursor 与 Scope 恢复验证记录

> 任务：`M6-S02`<br>
> 日期：2026-08-25<br>
> 结论：通过<br>
> 长期决策：[ADR-021](../adr/ADR-021-三流恢复与前端合并协议.md)

## 1. 验证目标

M6-S02 在现有 Conversation Event SSE、Conversation Cursor、Personal Agent AG-UI Segment 和前端 Scope Store 之上验证：

1. Team Event 和 Conversation Event 使用独立 Cursor 断线补发；
2. AG-UI 使用同一 Idempotency Key 重放 Invocation Segment；
3. Team Projection Generation 切换后只刷新 Team 快照；
4. Scope Epoch 与 Abort 共同阻止旧 Team 迟到帧；
5. 持久事实在合并工作面按 DomainEvent ID 去重；
6. 展示顺序保持确定性，不生成跨流全局顺序；
7. 非法 Stream/Scope 帧不推进 Cursor。

本 Spike 使用 test-only TypeScript 协调器和可控 SSE Source，不提前实现 M6-D01 Team Cursor 领域对象、M6-E05 Team Event Store/SSE 或 M6-F01/F02 生产前端协调器。

## 2. 现有能力与差距

已具备的基线：

- `ConversationEventController` 在 SSE `200` 前完成身份、可见性和首页历史读取；
- Conversation JSON 历史与 SSE 共享 Organization/Team/Conversation/Position/Event ID Cursor；
- `Last-Event-ID` 与 `after` 同时使用时要求相同；
- `PersonalAgentInvocationController` 为 AG-UI 返回稳定 Event ID 和 Invocation ID；
- `ConversationRealtimeStore` 使用原 Idempotency Key 重放调用，并将 AG-UI 与 Conversation Event ID 集合分离；
- Invocation SSE 断开只中止客户端消费，不替代显式 Cancel 命令；
- Conversation/Task/Scope Store 已使用 Generation/Epoch、AbortController 和完整 Scope Key 拒绝迟到异步结果。

M6 需要补齐：

- Generation-aware Team Event 耐久投影、快照与签名 Cursor；
- Team/Conversation/AG-UI 统一协调中的独立恢复坐标；
- Team 与 Conversation 中同 DomainEvent 的合并展示去重；
- Team Generation 过期后的快照安装与新 Cursor 恢复；
- Cursor 只在帧验证和应用完成后推进的统一前端边界。

## 3. 冻结的恢复坐标

```text
Team:
  Organization + Team + Projection + Generation
  + Schema Version + Filter Fingerprint + Team Sequence + Event ID

Conversation:
  Organization + Team + Conversation + Position + Event ID

AG-UI:
  Organization + Team + Conversation + Invocation + Segment
  + Idempotency Key + stream-local Event ID
```

AG-UI SSE ID 只是 Segment 内的 Event ID。它不作为 Team/Conversation Cursor，不用于通用 GET Resume。断线恢复重放同一 POST/Resume 幂等调用。

## 4. 可控协调器

`ThreeStreamCoordinator` 只用于 M6-S02 协议验证。它保存两个耐久流 Cursor、三个独立 Event ID 集合、当前 Scope Epoch、流内历史和按 DomainEvent ID 去重的合并视图。

耐久帧处理顺序为：

```text
校验 Stream/Scope/Cursor
  -> 判定新 Event ID 或已验证重放
  -> 更新流内状态和合并视图
  -> 保存 Cursor
```

Scripted Source 可以在指定帧后抛出断线、抛出 Cursor 过期、返回新 Generation 快照，也可以通过 Deferred Gate 延迟旧 Team 帧。

## 5. Team 与 Conversation 独立断线

Team 首次连接交付 `team-1` 后断开，重连从 `team:g7:s1` 继续，并容忍 `team-1` 重放后交付 `team-2/team-3`。Conversation 同时在 `conversation-1` 后断开，从 `conversation:p1:e1` 继续并交付 `conversation-2`。

最终 Team 流保存 3 个唯一事件，Conversation 流保存 2 个唯一事件。两条流分别推进至 `team:g7:s3` 和 `conversation:p2:e2`。共享 `domain-2` 的两个流内表示都被保留，合并 Conversation 工作面只保留更具体的 Conversation 表示。

## 6. AG-UI Segment 重放

AG-UI 首次交付“第一段”后断开，第二次使用原 `invoke-key-1` 返回相同 Invocation/Segment，并重放“第一段”后追加“第二段”。

AG-UI Event ID 去重后文本为“第一段第二段”。Team 和 Conversation Cursor 都保持空，证明 AG-UI 重放不推进耐久业务流。

## 7. Generation 过期与 Scope 切换

Team Cursor 过期时，协调器安装 Generation 8 的两行快照和 `team:g8:s2` Snapshot Cursor，再从该位置交付 `team:g8:s3`。Conversation 与 AG-UI 状态不参与 Team 快照替换。

另一个场景将 Platform Team 帧阻塞在 Deferred Gate，然后切换至 Security Team 并交付新 Team 事件。释放旧 Gate 后，旧帧因 Scope Epoch 不一致被丢弃，Security Store 只包含新 Team 事件。

## 8. 展示顺序与失败关闭

样本使用 Team Cursor `team:g2:s99` 和 Conversation Cursor `conversation:p500:e1`。合并视图按 `occurredAt + eventId` 展示 Conversation 早于 Team 的事件，对象不存在 `globalSequence`，两个 Cursor 各自保留。

最后的反例在 Team 连接中注入 Conversation Frame。协调器拒绝该帧，Team Cursor 保持空，Team Store 不产生部分写入。

## 9. 自动化验证

测试文件：

```text
crewscope-web/src/domains/realtime/ThreeStreamRecoveryM6S02.spec.ts
```

专项命令：

```bash
pnpm --dir crewscope-web exec vitest run \
  src/domains/realtime/ThreeStreamRecoveryM6S02.spec.ts
```

结果：

```text
Test Files  1 passed (1)
Tests       6 passed (6)
```

六个场景分别覆盖两条耐久流独立断线补发、AG-UI Segment 重放、Team Generation 过期快照、Scope Epoch 隔离、无全局顺序合并和非法跨流帧失败关闭。

前端全量回归与生产构建：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
```

```text
Test Files  71 passed (71)
Tests       317 passed (317)
vue-tsc --noEmit SUCCESS
vite build SUCCESS
```

Conversation Cursor、Conversation SSE、AG-UI Controller 和跨流 Event ID 回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=ConversationEventControllerTest,ConversationEventCursorCodecTest,PersonalAgentInvocationControllerTest,RealtimeStreamEventIdsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

根 README 与 `docs` 共 247 份 Markdown 文档链接通过，tracked/untracked whitespace 检查通过。

## 10. 后续实现边界

- M6-D01 实现 TeamSequence、ProjectionGeneration 和 Team Cursor Scope；
- M6-D07 实现 Generation 状态机与 Cursor 过期关联；
- M6-E05 实现 Team Event Store、快照、签名 Cursor、SSE 和断线补发；
- M6-A01 提供权限感知的 Team/WorkItem Activity 历史与 SSE API；
- M6-F01 实现 Team/Conversation/AG-UI 三流协调、有界去重与 Scope Epoch；
- M6-F02 在 Activity 与 Conversation 视图实现快照恢复、状态提示和权威资源回读。

## 11. 结论

M6-S02 验证通过。Team、Conversation 和 AG-UI 使用独立恢复坐标可以在至少一次交付下收敛，持久事实通过 DomainEvent ID 在合并工作面去重。Team Generation 过期只替换 Team 快照，Scope Epoch 拒绝旧 Team 迟到帧，各流 Cursor 保持独立并不声明跨流全局顺序。
