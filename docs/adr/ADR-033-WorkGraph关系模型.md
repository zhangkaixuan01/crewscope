# ADR-033：WorkGraph 关系模型

> 状态：ACCEPTED（设计，待 M11-D01/A02 实现）<br>
> 日期：2026-10-09<br>
> 归属：M11-S01 合同冻结；并发环与递归 CTE 原型见 [M11-S01 冻结记录](../spikes/M11-S01-实时协作与WorkGraph合同冻结.md)<br>
> 关联决策：[ADR-005](ADR-005-事件与投影协议.md)（关系变更走既有事件管线）、[ADR-007](ADR-007-API命令与并发协议.md)、[ADR-038](ADR-038-团队成员生命周期与责任转移.md)（项目行锁沿用其锁序纪律）<br>
> 影响里程碑：M11

## 背景

M11 引入工作项之间的关系图（阻塞、依赖、拆分来源）。当前事实：工作项间关系零现状（`WorkItemResourceLink` 是工作项到外部资源的链接，不是工作项间关系；最近的邻接表先例是 `ConversationWorkItemLink`）；`WITH RECURSIVE` 在全部迁移与源码中零先例；`domain/workitem/` 已经过大（BE-02 方向），关系与图算法不应混入。

## 决策

### 三类关系语义与方向规范化

| 关系 | 语义 | 存储形态 |
|---|---|---|
| `BLOCKS(src, dst)` | src 阻塞 dst：dst 处于被阻塞语义时 src 是其原因 | 唯一 canonical 存储方向 |
| `DEPENDS_ON(X, Y)` | X 依赖 Y（= Y BLOCKS X 的提交视图） | 提交时规范化为 `BLOCKS(Y, X)` 存储；API 同时提供两种视图返回 |
| `SPLIT_FROM(child, source)` | child 由 source 拆分而来（provenance） | 独立存储，`UNIQUE(child)`（每工作项至多一个来源） |

- 自指（src = dst）一律领域层拒绝。
- 方向规范化由构造消灭反向重复存储：库内不存在同时 `(A,B)` 与 `(B,A)` 的 BLOCKS 边；数据库以双唯一索引兜底（`UNIQUE(src, dst)` + 反向 `UNIQUE(dst, src)`）。
- `SPLIT_FROM` 不参与调度环检测（它是溯源不是排序）；`A SPLIT_FROM B` 且 `B BLOCKS A` 合法（拆分项阻塞其来源是正常语义）。

### 环检测：领域算法 + 事务并发控制双层

- 领域层 `RelationCycleDetector` 是纯算法（图遍历、深度上界 32、零框架依赖），服务单线程正确性与快速失败。
- **并发正确性靠事务协议，不能只靠领域算法**（M11 红线）：创建 BLOCKS 边的事务固定为

```text
BEGIN
  → SELECT id FROM work_project WHERE id IN (:p1, :p2) ORDER BY id FOR UPDATE
  → 锁内递归 CTE 检查 EXISTS(dst→src 可达路径)
  → 无环则 INSERT，有环则拒绝（稳定原因码）
COMMIT
```

- **为什么锁两端点工作项行不够（diamond race）**：T1 插 `A→B`、T2 插 `C→D`，两端点集不相交可并行；若既有路径 `B→C` 与 `D→A`，两事务各自的检查都看不到对方未提交的边，双双提交后成环 `A→B→C→D→A`。项目级行锁让同一项目（跨项目时是两个项目行按 id 排序）下的关系创建串行化，后到者在锁内看到先到者已提交的边。S01b 的 cycle-race.sql 以真实 PostgreSQL 两会话同时演示「端点锁方案成环」的否证与「项目锁方案拒绝」的证明。
- 选型理由：项目行 `SELECT FOR UPDATE` 沿用 ADR-038 的 Team 行悲观锁先例与「按 ID 排序加锁」纪律；关系创建是低频人工操作，锁争用可忽略。advisory lock 等价但不进仓库既有悲观锁范式；SERIALIZABLE 无先例、SSI 对递归 CTE 谓词锁行为不透明且需重试逻辑，均不取。
- `SPLIT_FROM` 的祖先链检查在同一事务同一把锁内执行（拒绝自指与祖先环，保证森林）。

### 跨 WorkProject 关系

- 允许，但需双端显式权限：创建者须同时是两端所在项目的有效成员；查询投影同样按双端过滤。
- 双项目行锁按 project id 排序获取（与单项目同一条 `IN (...) ORDER BY id FOR UPDATE` 协议，无第二套锁序）。
- 跨项目边在图查询中正常参与拓扑，但「当前被阻塞」聚合按 Team 维度呈现时显式标注跨项目来源。

### 阻塞链、关键路径与聚合

- `BlockingChain`：递归 CTE 沿 BLOCKS 边向 src 方向步行，深度上界 32、行上限 5000，超出显式截断（稳定错误码），不无限步行。
- **递归 CTE 一律 `UNION`（去重），禁用 `UNION ALL`（S01b 实测红线）**：多父汇聚 DAG 的路径数随深度指数膨胀（60×60 菱形网格、每节点 4 条上游边时理论中间行 4^32），且外层 LIMIT 不阻止递归项物化——实测 UNION ALL 版直接打崩本机 PG backend。`UNION` 把工作集钳制在节点数上界。SPLIT_FROM 是单 parent 森林、路径唯一，两种写法语义等价，仍统一 `UNION` 防复制扩散。环探测 CTE 同理（cycle-race.sql 模板已按此固化）。
- `CriticalPath`：**拓扑意义上的最长阻塞链，不虚构排期**——不引入日期/工时权重，不输出预期完成时间；需要排期判断时由前端基于链与既有 dueAt 字段自行呈现。
- 「当前被阻塞的工作项」是派生查询（存在 BLOCKS 入边且 src 未终态），不复制状态、不落物化表。

### 规模阈值与降级呈现（S01b 实测基线回填冻结记录 §4）

| 阈值 | 冻结值 | 降级行为 |
|---|---|---|
| 交互图节点上限 | 500 | 超限降级为分层列表（按阻塞链/状态分组），并提供聚焦子图（选中节点 + 深度 ≤2 邻域，节点 ≤200）保留图交互 |
| 递归 CTE 深度上界 | 32 | 显式截断错误码，不静默空结果 |
| 递归查询行上限 | 5000 | 同上 |
| 单点邻接查询预算 | P95 ≤ 100ms（S01b 实测 P95=0.069ms，n=33，达标） | 超预算返回显式降级错误码 |
| 5000 节点阻塞链 CTE 预算 | P95 ≤ 500ms（S01b 实测：单向上游链 P95=0.161ms、n=33；多父菱形网格最坏形态 UNION 版 P95=1.292ms、n=12，均达标） | 实测不达则诚实冻结实测值并在此记录 D01 的优化义务，不静默放宽验收 |

### 事件与审计

关系创建/删除作为领域事件（`WorkItemRelationCreated` / `WorkItemRelationRemoved` 级别）走既有同事务 `DomainEventStore.append + Outbox.enqueue` 管线，并登记 audit 注册表；不新建平行事件通道。级联删除语义：删除工作项时其全部关系边同事务删除并出事件。

## 实现约束

- 领域落点：新包 `domain/workgraph/`（`WorkItemRelation`、`WorkItemRelationType`、`RelationCycleDetector`、`BlockingChain`、`CriticalPath`），**不进 `domain/workitem/`**；领域层零框架依赖。
- 应用/持久化落点沿用 M9 §10.5.3 类名族；server 侧 `WorkGraphController`；DTO 平铺 `server/api/`。
- 迁移从 V67 顺延（当前 tip = V66），不预留编号；关系表沿用 work_item 四键复合租户 + 5 列复合 FK 惯例。
- 并发环协议必须在真实数据库以并发测试证明（D01 验收：「以真实数据库并发测试证明相反边不能同时通过」），单线程领域测试不替代。

## 结果

- 三类关系语义、方向规范化与环不变量有单一权威定义；并发成环在事务协议层被证明不可能。
- 图查询全部有界（深度/行/时间），超大图有显式降级路径。
- 关系变更进入既有事件与审计管线，无平行通道。

## 验证

1. M11-S01b SQL 原型（cycle-race.sql 三场景 + cte-depth.sql 基准）证明协议与预算——见冻结记录 §6。
2. 产品级验收归 M11-D01（真实表 + 迁移 + 并发集成测试 + 事件/审计断言）与 M11-A02（越权、空图、超大图截断、查询预算）。

## 重新评估条件

- 单 Team 图规模常态化超过 5000 节点或需要分布式图计算时；
- 出现 BLOCKS 之外的调度语义（如条件阻塞、时效阻塞）需要加权关键路径时；
- 关系创建从低频人工操作变为高频自动化操作（锁争用成为瓶颈）时。
