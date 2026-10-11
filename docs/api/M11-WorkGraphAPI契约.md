# M11 WorkGraph API 契约

> 状态：冻结（A02 关系命令与派生查询）<br>
> 版本：V1<br>
> 日期：2026-10-11<br>
> 适用范围：WorkItemRelation 的创建/删除命令，与四个派生读——锚点邻接、阻塞链、关键路径、「当前被阻塞」聚合分页（ADR-033）

## 0. 本轮边界与偏差声明

本契约冻结 WorkGraph 的 REST 面。以下不在本轮内：

- **前端图视图 / 降级呈现**——归 F02；本 API 只保证**有界预算与显式截断标志**；
- **审计/事件形状**——D01 已冻结 `WORK_ITEM_RELATION_CREATED` / `WORK_ITEM_RELATION_REMOVED` 两条事件，本轮零新增；
- **协作 WS 推送帧**——本轮只让既有 resource_changed 信号覆盖关系事件（§8），订阅协议见《M11-协作信号API契约》；
- **迁移**——V67 足够，本轮零 DDL。

### 偏差声明（相对 ADR-033 与主计划）

1. **controller 拆两个**：ADR 预想单个 WorkGraphController。实现按仓库命令/查询分离惯例拆为 `WorkGraphController`（命令）与 `WorkGraphQueryController`（查询），与 WorkItem 两个 controller 完全同构。
2. **链/关键路径挂项目路径段**：计划草图中 blocking-chain / critical-path 直接挂在 `work-items/{id}` 下。实现挂 `work-projects/{projectId}/work-items/{workItemId}/…`——可见性协议（`requireVisibleWorkItem`）需要 project 坐标，且仓库所有 work-item 读都带 project 路径段，保持一致。
3. **协作扇出双端、版本取端点当前聚合**：关系事件对两个端点各发一帧 WORK_ITEM `resource_changed`，`version` = 该端点 WorkItem 的**当前聚合 version**（consumer 读 `WorkItemRepository.findById`）；关系信封的 `aggregateVersion=0` 是边的事实，不能当 WorkItem 帧版本。CREATED 与 REMOVED 都扇出。
4. **归档守卫不加新错误码**：ARCHIVED 端点拒绝建新边走既有 `DomainValidationException`（422 `invalid_value`，`details.field = workItemRelation.endpoints`），与 WorkItemComment.create 先例一致。

## 1. 端点总览（6 操作，OpenAPI 277→283）

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | `/api/v1/organizations/{orgId}/teams/{teamId}/work-item-relations` | 建边（202） |
| DELETE | `/api/v1/organizations/{orgId}/teams/{teamId}/work-item-relations/{relationId}` | 删边（202） |
| GET | `…/work-projects/{projectId}/work-items/{workItemId}/relations` | 锚点邻接 |
| GET | `…/work-projects/{projectId}/work-items/{workItemId}/blocking-chain` | 阻塞链（32/5000 截断） |
| GET | `…/work-projects/{projectId}/work-items/{workItemId}/critical-path` | 纯拓扑最长链 |
| GET | `…/teams/{teamId}/work-graph/blocked-work-items` | 当前被阻塞聚合分页 |

边跨两个 WorkProject，因此命令资源挂在 **Team** 级路径下，不在任何项目下。

## 2. 命令

### 2.1 创建

```jsonc
POST …/work-item-relations
Idempotency-Key: <必填>
{ "type": "BLOCKS" | "DEPENDS_ON" | "SPLIT_FROM",
  "fromWorkItemId": "<uuid>", "toWorkItemId": "<uuid>" }
```

- `DEPENDS_ON(X, Y)` 由聚合在构造期规范化为存储边 `BLOCKS(Y, X)`（D01 冻结），API 接受全部三值；
- 响应 **202** + CommandReceipt（`domainEventId` / `committedVersion`=0 / `correlationId`）；
- **无 If-Match**：关系是不可变 version-0 事实，没有乐观锁版本可言。

### 2.2 删除

```jsonc
DELETE …/work-item-relations/{relationId}
Idempotency-Key: <必填>
```

- 202 + CommandReceipt；幂等重放返回原 receipt。

### 2.3 归档守卫（A02 冻结）

创建时双端任一 WorkItem 处于 `ARCHIVED` 即拒绝（422，`details.field=workItemRelation.endpoints`）。守卫在**权限协议之后**执行——非参与者先撞 403，不能借 422 探测端点状态。**只约束创建**：既有边在端点归档后保留可见、允许删除（归档不是删库）。

### 2.4 错误码

| 状态 | `code` | 触发 |
|---|---|---|
| 400 | `invalid_request` | 缺 Idempotency-Key；标识非 UUID；body 校验失败 |
| 403 | `policy_denied` | 非活跃成员 / 单侧项目无 WORK_PARTICIPATE / 跨 Team 边（显式 403，不掩 404） |
| 404 | `aggregate_not_found` | Team / WorkItem / WorkItemRelation 不存在 |
| 409 | `work_item_relation_duplicate` | 同向重复边 |
| 409 | `work_item_relation_cycle` | 建边闭环（锁内 CTE 探测） |
| 409 | `work_item_relation_split_source_conflict` | 第二个 SPLIT_FROM 源 |
| 422 | `invalid_value` | 自指（`workItemRelation.endpoints`）；归档端点（同 field） |

## 3. 查询（邻接 / 链 / 关键路径）

三者都以可见 WorkItem 为锚（`requireVisibleWorkItem`），响应 `Cache-Control: no-store`，均带**显式截断标志**：

- **relations**：`{anchor, edges[], truncatedByRows}`；每条 edge 是锚点相对投影——`direction ∈ OUTGOING|INCOMING`（锚点在存储边 src/dst 哪一侧）、`otherWorkItemId/otherProjectId`、`crossProject`（双端项目比对）、`views[]`（存储边 `A BLOCKS B` 同时呈现 `A BLOCKS B` 与 `B DEPENDS_ON A` 两个方向视图；SPLIT_FROM 单视图）、`createdAt`。上限 5000 行。
- **blocking-chain**：`{anchor, steps[{workItemId, depth}], truncatedByDepth, truncatedByRows}`；深度 32 / 行 5000（ADR-033 冻结值）。
- **critical-path**：`{chain[], truncatedByDepth, truncatedByRows}`；**纯拓扑**最长阻塞链（链头→…→锚点），不虚构排期；截断时链是**下界**。

## 4. 「当前被阻塞」聚合分页

```jsonc
GET …/teams/{teamId}/work-graph/blocked-work-items?projectId=<可选>&after=<可选>&limit=<1..100>
```

- 语义：Team 内「至少有一条 BLOCKS 入边、且 blocker 仍执行中（src 状态非终态：DONE/CANCELLED/ARCHIVED）」的 WorkItem，按 `(blockedSince DESC, workItemId DESC)` 排序；
- `blockedSince` = 最新的**仍阻塞中**入边时间（终态 blocker 的边从行、计数、窗口三者中一致剔除）；
- `projectId` 过滤**被阻塞侧**；blocker 可来自 Team 任意项目；`crossesProject` 标注该行是否存在跨项目 blocker；
- `blockerCount` 是权威计数；`blockers[]` 是每行最新 ≤20 个 blocker 摘要（窗口），两者同一数据库快照、同一过滤条件；
- `limit` 默认 20、上限 100（`invalid_request` 越界拒绝）。

**游标**：`nextCursor` 为 HMAC-SHA256 签名的 v1 不透明 token（key ring 轮转、签名域 `crewscope:work-graph-blocked-cursor:v1`，与 Team Activity / WorkItem 游标同 key 不同域、永不互通）。token 绑定 org+team+projectFilter+位置，重启位置是**上一页最后一行**，排他比较保证页不相交。换 Team / 换 filter / 篡改 → 400 `invalid_cursor`；签发超 30 天 → 400 `invalid_cursor`（重启查询，非 410）。

## 5. 公开字段白名单

响应 record 组件即白名单（无反射面、无 Map 透传）：

- 邻接 edge：`relationId, direction, otherWorkItemId, otherProjectId, crossProject, views[type, fromWorkItemId, toWorkItemId], createdAt`
- 链步：`workItemId, depth`
- 关键路径：`chain, truncatedByDepth, truncatedByRows`
- 被阻塞行：`workItemId, itemKey, title, status, projectId, blockedSince, blockerCount, crossesProject, blockers[]`
- blocker：`workItemId, title, status, projectId`

## 6. 越权与撤权

- **跨 Team**：命令路径显式 403（`relate WorkItems across Teams` / `remove relations across Teams`），不掩成 404；查询锚点跨 Team 同样 403；
- **单侧角色**：建边要求**双端项目** WORK_PARTICIPATE（D01 权限协议不变）；
- **撤权下一请求生效**：全部命令逐请求重验成员态与角色授予，无缓存授权；
- **非参与者探测**：归档守卫排在权限后，403 先于 422。

## 7. 性能预算（ADR-033，D01 规模图实测）

| 读 | 预算（p95, nearest-rank） |
|---|---|
| 邻接 relations | ≤ 100ms |
| blocking-chain | ≤ 500ms |
| critical-path（闭包+域内行走） | ≤ 500ms |
| blocked-work-items 一页（页+窗口） | ≤ 500ms |

全部读带硬上界：深度 32 / 行 5000 / blocker 窗口 20；探测类 CTE（环/森林，D01）保持无上界——截断的探测会放进环。

## 8. 协作扇出（承《M11-协作信号API契约》）

`WORK_ITEM_RELATION_CREATED` / `WORK_ITEM_RELATION_REMOVED` 进入 consumer 白名单后，对 payload 的 `srcWorkItemId` / `dstWorkItemId` 各发一帧 WORK_ITEM `resource_changed`：audience = 资源 ∪ 所属项目 ∪ Team（三级，与 WorkItem 自身事件同形），`version` = 端点当前聚合 version（§0.3）。端点消失（防御路径，当前产品不删 WorkItem）静默跳过——信号有损合同不变，权威读在 REST。
