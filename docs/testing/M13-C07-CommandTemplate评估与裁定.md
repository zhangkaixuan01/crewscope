# M13-C07：CommandTemplate 评估与裁定

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C07 工作包（Review §5.2 / §8 P3「慎动批」）。
> 性质：SPIKE——只评估不实施是合法出口；本裁定的量化证据可由 `scripts/analyze-command-duplication.mjs` 复跑。
> 结论：**裁定为「登记，留后续」**——不在本里程碑抽取跨 CommandService 的共享骨架。

## 1. 范围与方法

Review §5.2 的命题：9 个 CommandService 文件内部各自收敛了 `mutatingCommand`+`completed`，但跨文件零共享。本次按 Review 同法量化（10 行窗口 / 步长 5 / 剔注释与空行），并补充两种透镜，避免单一指纹误判：

1. **精确窗口**：字节级相同窗口（下界，标识符差异即失配）。
2. **标识符归一化窗口**：标识符/数字/字面量折叠后比较（结构透镜，捕捉「同骨架不同命名」）。
3. **骨架元素计数**：`findCompleted` / `reserve` / `complete` / `CommandReceipt` / `events.append` / `outbox.enqueue` / `completed` 等逐文件计数 + 私有骨架方法行数（括号配平实测）。

范围：application 层 10 个 `*CommandService`（Review 的「9 个」按命令族计；本表纳入 `ReviewLineCommentCommandService` 作对照组——它不持有 receipt 骨架）。Knowledge/TeamSkill 已由 C03 收敛到 `TeamCommandSupport`，保留在集合内作为「收敛后」的参照。

## 2. 量化结果

### 2.1 滑动窗口指纹（750 窗口）

| 透镜 | 重复哈希 | 出现次数 | 占比 |
| --- | --- | --- | --- |
| 精确窗口 | 4 | 8 | 0.5% 哈希 / 1.1% 出现 |
| 标识符归一化窗口 | 44 | 121 | 5.9% 哈希 / 16.1% 出现 |

精确透镜只有两组：`WorkItemCommandService ↔ ResponsibilityCommandService` 2 窗（reserve 请求体），`KnowledgeCommandService ↔ TeamSkillCommandService` 2 窗（收敛后的共享 import）——字节级重复极低。

归一化透镜的最大一对仍是 `Knowledge ↔ TeamSkill`（21 窗），即已收敛部分；其余 23 个重复哈希分散在 9 对文件之间（单对最多 9 窗，其余 2–6 窗），内容为事务包装、reserve 调用序列与通用 import——**没有一个未收敛的服务对达到「整段 helper 逐行复制」的密度**。

### 2.2 私有骨架方法实测行数

| Service | 骨架方法（行数） | findCompleted 预检 | 事务边界 | 事件信封 | outbox | TaskEvent 追加 | saveResult |
| --- | --- | --- | --- | --- | --- | --- | --- |
| WorkItem | `execute` 25 + `completed` 38 | 无 | helper 内 | 本文件 | 有 | 无 | 有（create） |
| WorkItemRelation | `execute` 25 + `completed` 24 | 无 | helper 内 | 本文件 | 有 | 无 | 无 |
| Responsibility | `execute` 25 + `completed` 33 | 无 | helper 内 | 本文件 | 有 | 无 | 无 |
| MemberTask | `execute` 80 | 无 | 调用方包装 | 本文件（版本派生分支） | 有 | 有 | 无 |
| WorkerTask | `execute` 17 + 86 | 无 | 方法内 | 本文件（聚合/actor 分支） | 有 | 有 | 无 |
| InboxDisposition | `execute` 55 | 有 | 方法内 | 本文件 | **无（契约要求）** | 无 | 无 |
| LarkAdministration | `execute` 37 | 有 | 方法内 | **不建（action 提供 fact）** | 无 | 无 | 无 |
| Knowledge | `mutatingCommand` 30 | 有（support） | support | support | support | 无 | 无 |
| TeamSkill | `mutatingCommand` 33 | 有（support） | support | support | support | 无 | 无 |
| ReviewLineComment | — | 无 receipt 骨架 | — | — | — | — | — |

## 3. 成本/风险评估

共享骨架必须同时参数化的维度（实测至少 6 条正交轴）：

1. **重放策略**：4 个服务先 `findCompleted` 再 reserve（Inbox/Lark/Knowledge/Skill），5 个仅依赖 `reserve` 的 `acquired` 分支。
2. **事务归属**：helper 内、公开方法内、调用方包装三种摆放。
3. **事件信封**：7 个服务自建信封且聚合类型/作用域/版本派生各不相同；Lark 由 action 返回 fact；Inbox 显式不排队 outbox。
4. **附加副作用**：MemberTask/WorkerTask 追加 TaskEvent；WorkItem create 追加 saveResult。
5. **上下文族**：`TeamCommandContext` 与 Worker 的 `WorkerCommandContext`（TaskTokenGrantScope、worker actor 类型）不共享结构。
6. **请求哈希派生**：各服务字段列表不同（每文件 3–12 处 `CommandRequestHash`）。

回归面：10 个服务被 application/server 25 个测试文件引用，命令面是全仓最高 blast radius；任何骨架抽取都要重验 receipt replay、幂等键、事件形状与乐观锁。对照已付成本：C03 为重叠最高的 Knowledge↔Skill 一对建 `TeamCommandSupport`（343 行），整体净 **+46** 行——证明参数化骨架的固定成本高于其删除收益，且那一对是重复密度最高的一对。

## 4. 裁定：登记，留后续

**依据**：字节级重复仅 0.5%；结构重复的最大一对已经收敛；剩余重复是薄而分散的事务/收据样板，而参数面有 6 条正交轴、两种上下文族与 25 个测试文件的回归面。在零行为变化铁律下抽取 CommandTemplate，风险显著高于收益，符合 Review 对 P3 的「慎动批」定位与本计划「允许裁定为登记留后续」的出口。

**保留资产**：`TeamCommandSupport` 继续作为新命令服务与未来合并的母本；各服务文件内 helper 保持现状。

**重估触发条件**（任一满足时重新立项）：

1. command 收据/事件信封出现第三次同形状缺陷双份修复（与 distiller 的立项形状同构）；
2. 新增命令族第三次复制同一骨架；
3. 出现可机械验证的命令契约测试套件，能把 10 个服务的 replay/幂等语义前置锁定——届时抽取的回归成本可被自动化吸收。

## 5. 复跑入口

```sh
node scripts/analyze-command-duplication.mjs
```

输出两段：精确窗口与标识符归一化窗口的逐文件计数、跨文件对排名、汇总。脚本只做分析、不进 CI（是否固化进 CI 留给 Q01 低优先裁定）。
