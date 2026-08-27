# M6-Q02 固定故障与恢复攻击集

> 任务：`M6-Q02`<br>
> 状态：已完成<br>
> 日期：2026-08-27<br>
> 范围：Outbox、Projection、SSE、Redis/Snapshot、Worker、Worktree、Model、GitHub、Lark、Notification、数据库提交窗口

## 1. 目标

M6-Q02 把 Team Beta 的中断恢复、幂等、Fencing 和人工处置边界固化为可重复运行的故障门禁。固定验收指标为：

- 固定故障样本不少于 100，自动恢复率不低于 `99%`；
- 重复 Action Dispatch 与 Notification Dispatch 为 `0`；
- Inbox 独立处置状态丢失为 `0`；
- 旧 Worker、旧 Projection Generation 和过期 Lease 的终态写入为 `0`；
- 无法自动证明外部结果的最终失败进入人工队列；
- 响应丢失、事务提交结果不确定和进程退出不能直接重放可能已发生的外部写。

## 2. 固定矩阵

固定矩阵冻结 `FI-001` 至 `FI-121`，11 个故障面各保留 11 个稳定样本。测试代码校验样本总数、编号唯一性、每组数量、自动恢复率和全部零副作用指标，删除或重复样本会直接使门禁失败。

| 编号 | 故障面 | 数量 | 主要注入窗口 | 收敛证据 |
|---|---|---:|---|---|
| `FI-001`–`FI-011` | Outbox | 11 | Claim、Publish、Ack、Receipt、Lease、Restart、Dead Letter Replay | Claim/Receipt 幂等，已提交事实不重复发布 |
| `FI-012`–`FI-022` | Projection | 11 | Apply/Receipt/Checkpoint、Gap、Retry、Switch、旧 Generation | Receipt/Checkpoint 收敛，旧 Fencing 写入拒绝 |
| `FI-023`–`FI-033` | SSE | 11 | Disconnect、Reconnect、Snapshot Race、Gap、Switch、Slow Subscriber | 最后已应用 Cursor 恢复，缺口 Reset，不丢业务事件 |
| `FI-034`–`FI-044` | Redis/Snapshot | 11 | Load/Save、响应丢失、损坏、版本漂移、Restart、Lease | PostgreSQL/Workspace 权威恢复，旧状态不覆盖新状态 |
| `FI-045`–`FI-055` | Worker | 11 | Claim、Prepare、Run、Checkpoint、Heartbeat、Pause/Cancel | Lease Sweep 与更大 Fencing Token 重新入队或安全终止 |
| `FI-056`–`FI-066` | Worktree | 11 | Create、Git Add、Metadata、HEAD/Branch、Finalize、Cleanup、Lock | 普通失败全补偿，中断由启动对账恢复同一 Delivery Tree |
| `FI-067`–`FI-077` | Model | 11 | 429、Timeout、Credential/Provider、Fallback、Output、Budget、Restart | 当前授权与版本复验，受控 Fallback 或稳定安全失败 |
| `FI-078`–`FI-088` | GitHub | 11 | Push/PR 响应丢失、Webhook、Lease、Fencing、Query、Receipt、Restart | Query-only 对账；唯一 Push/PR/Receipt；最终 UNKNOWN 进入人工队列 |
| `FI-089`–`FI-099` | Lark | 11 | Token、Mapping、Connection/Grant、Template、Write/Query、Restart | 当前授权复验，同 Provider UUID 查询恢复，写调用不重复 |
| `FI-100`–`FI-110` | Notification | 11 | Claim、Provider、Query、Receipt、Lease、Poll、Redelivery | 唯一 Delivery/Receipt，UNKNOWN 查询恢复，最终失败可受审计重投 |
| `FI-111`–`FI-121` | 数据库提交窗口 | 11 | Command Receipt、DomainEvent、Outbox、Audit、Projection/Notification Receipt、Rollback | 同事务全有或全无；提交响应丢失按 Command ID/事实查询恢复 |

`FI-088` 表示 GitHub 写结果在有界查询次数内仍无法证明的最终 UNKNOWN。该样本不自动重放外部写，进入 `MANUAL_REVIEW`；其余 120 个样本自动恢复或自动安全收敛。因此自动恢复率为 `120 / 121 = 99.17%`。

## 3. 恢复协议

```text
内部事务或 Worker 中断
  -> 按 Command/Operation/Delivery/Projection 身份查询权威事实
  -> 未提交则由更大 Fencing Token 重新 Claim
  -> 已提交则复用 Receipt、Checkpoint、Artifact 或 Delivery
  -> 旧 Lease 与旧 Generation 写入失败关闭

外部写响应丢失
  -> 保持 UNKNOWN，不直接重放写调用
  -> 使用同一 Idempotency Key / Provider UUID 执行 query-only 对账
  -> 找到结果则写入唯一 Receipt 并闭合终态
  -> 明确未产生副作用才允许延迟重试
  -> 有界期限内仍不可证明则进入人工队列

浏览器断线或投影切换
  -> 使用最后已应用的签名 Cursor 恢复
  -> Retention Gap 或 Generation 变化触发权威 Snapshot/Reset
  -> Inbox Disposition 独立保存，不被来源 Projection 重放覆盖
```

## 4. 自动化入口

```bash
./scripts/m6-q02-fault-gate.sh
```

门禁不调用真实模型、GitHub 或 Lark 账户。它使用：

- PostgreSQL、Redis Testcontainers 验证迁移、事务、Receipt、Checkpoint、Lease 与 Fencing；
- 真实本地 Git 验证 Worktree 创建、Finalize、清理和启动恢复；
- AgentScope 可控模型验证限流、超时、Fallback 和运行时恢复；
- Loopback GitHub/Lark Provider 验证外部写响应丢失、查询恢复和幂等身份；
- Vitest 验证三流 Cursor、离线恢复、Scope Generation、命令重放和缓存隔离。

Docker、Node.js 24 或 pnpm 不可用时门禁直接失败。数据库、Redis、Git 或 Provider Fixture 测试跳过时不能满足验收。

## 5. 验收结果

| 指标 | 结果 |
|---|---|
| 固定故障收敛 | `121 / 121` |
| 自动恢复 | `120 / 121`，`99.17%` |
| 人工队列 | `1 / 121`，最终 UNKNOWN 唯一入队 |
| 重复 Action Dispatch | `0` |
| 重复 Notification Dispatch | `0` |
| Inbox Disposition 丢失 | `0` |
| 旧 Fencing 终态写入 | `0` |
| Java 专项 | `304 / 304`，零失败、零跳过 |
| Web 专项 | `67 / 67` |
| 专项自动化总计 | `371 / 371` |
| Maven 全量回归 | `2552 / 2552`，零失败、零跳过，7 个 Reactor 模块全部成功 |
| Web 全量回归 | `450 / 450`，TypeScript 检查与 Vite 生产构建通过 |

M6-Q02 未发现需要放宽生产恢复或幂等规则的问题。M6-Q01 全量回归发现的随机 Audit 夹具已在进入本门禁前改为确定性名称，因此 Operations 数据库提交窗口测试保持稳定。后续 M6-Q03 本机协议与生产 Queue/Activity/Inbox Fixture 已完成固定样本负载、重启恢复、备份恢复合同与完整 MVP E2E，仍待 Canonical Nightly 空目标恢复和受保护真实 Lark 证据。
