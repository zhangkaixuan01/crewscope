# M11-A02 WorkGraph 查询与命令验证

> 交付日期:2026-10-11。C1 派生查询核心(domain/application/infrastructure)、C2 命令 REST+归档守卫+协作扇出、C3 契约文档+全量门禁,三笔提交。
> 设计权威:[ADR-033](../adr/ADR-033-WorkGraph关系模型.md);API 契约:[M11-WorkGraphAPI契约](../api/M11-WorkGraphAPI契约.md);底座:D01(领域/命令协议/CTE 适配器/V67)。

## 验收矩阵(主计划 :121 原文)

| 验收项 | 证据 | 结果 |
|---|---|---|
| 越权覆盖 | `WorkItemRelationCommandIntegrationTest.requiresParticipationOnBothEndpointProjects` / `refusesCrossTeamEdgesExplicitly`(D01,A02 复用);`WorkGraphQueryControllerTest`(mock 可见性策略不掩字段);契约 §6:跨 Team 403 不掩 404、单侧角色 403、撤权下一请求生效 | ✅ |
| 空图覆盖 | `anEmptyGraphStillAnswersOkWithExplicitTruncationFlags`:空邻接 200 + `edges=[]` + `truncatedByRows=false` | ✅ |
| 超大图截断 | 集成 `theDeepChainClosureReportsDepthTruncationAndFeedsTheCriticalPath`(2000 深链→closure `truncatedByDepth=true` 恰 32 层);`WorkGraphQueryServiceTest.relationsTruncateExplicitlyAtTheRowBound`(5001 行→5000+flag);契约 §3 | ✅ |
| 环创建拒绝 | D01 `rejectsEdgesThatWouldCloseACycle`(锁内 CTE);REST 冒烟 `conflictCycleAndSplitGuardMapOntoConflict`(409 三码) | ✅ |
| 并发关系变更 | D01 `WorkItemRelationCycleConcurrencyM11D01IntegrationTest` 4 场景(A02 不重证,主计划边界) | ✅(承 D01) |
| 查询预算有上界 | 集成 `derivedReadsHoldTheLatencyBudgetsAtScale`(D01 规模图:邻接 p95≤100ms;链/闭包/阻塞页 p95≤500ms,nearest-rank WARMUP=10/SAMPLES=20)+ 硬上界 32/5000/20 全部显式上抛 | ✅ |
| 结果通过公开字段白名单 | 响应=内嵌 response record 组件(§5 白名单);`relationsProjectAnchorRelativeWhitelistedFields` / `blockedWorkItemsAnswerWhitelistedRowsAndASignedCursor` 逐字段断言;无 Map 透传 | ✅ |

## 派生查询(真实 PostgreSQL,`WorkGraphDerivedQueryM11A02IntegrationTest` 6 用例)

| 用例 | 断言要点 |
|---|---|
| `theDeepChainClosureReportsDepthTruncationAndFeedsTheCriticalPath` | 2000 链闭包恰 32 对、每层 blocker 唯一;CriticalPath 链 33 节点、链头 item_33、链尾 seed |
| `diamondClosureDeduplicatesPairsAndYieldsTheLongestBranch` | 菱形 (blocked,blocker) 对去重(ROOT 直连+经 BRANCH 各一,邻接表恰一对);最长支 [root,branch,seed] |
| `terminalBlockersDropOutOfTheCurrentlyBlockedAggregation` | DONE/ARCHIVED blocker 从行/计数/窗口三者一致剔除;`blockedSince` 取仍活跃边 MAX(更新的终态边不顶替);SPLIT_FROM 不算阻塞 |
| `crossProjectBlockersFlagTheRowAndTheProjectFilterRestrictsTheBlockedSide` | `crossesProject` 标注;`projectId` 过滤只限被阻塞侧,blocker 仍可跨项目 |
| `keysetPagesAreDisjointOrderedAndTieBrokenByWorkItemId` | (blockedSince DESC, workItemId DESC) 排序;tie-break 生效;游标=上页最后一行位置,排他重启,两页不相交 |
| `derivedReadsHoldTheLatencyBudgetsAtScale` | 四读 p95 预算(上表);页大小 20、`nextPosition` 存在 |

终态集唯一权威:`WorkItemStatus.isTerminal()`(DONE‖CANCELLED‖ARCHIVED,`WorkItemStatusTest`);适配器 SQL 绑定列表从它运行时派生——域与聚合永不漂移。

## 命令 REST 与归档守卫

- `WorkGraphControllerTest` 8 用例:202 receipt 契约、缺 Idempotency-Key 400、重放回原 receipt、409 三码映射、403、422 归档(`details.field=workItemRelation.endpoints`)与自指、非 UUID 逐字段 400、DELETE 202;
- 集成 `rejectsNewEdgesTouchingArchivedEndpoints`:双端 ARCHIVED 各向拒绝且库中零边;守卫在双端 requirePermission 之后(requireEdgeAbsent 之前)——非参与者先撞 403,不能借 422 探测端点状态;
- 集成 `anArchivedEndpointsExistingEdgeMayStillBeRemoved`:既有边在端点归档后仍可删除(归档不是删库);
- `DEPENDS_ON` 经 REST 全链路规范化(D01 命令路径复验)。

## 游标(`WorkGraphBlockedCursorCodecTest` 5 用例)

v1 HMAC-SHA256、签名域 `crewscope:work-graph-blocked-cursor:v1`(与 Team Activity / WorkItem 游标同 key ring 不同域,token 永不互通);org+team+projectFilter+位置全绑定;往返无损(micro 精度);篡改/错域/超 30 天全 400 `invalid_cursor`;future skew 5 分钟。

## 协作扇出(拍板决策记录)

用户拍板(A02 计划期):**扇出=双端 WORK_ITEM 资源 + 各端点当前聚合 version**;CREATED 与 REMOVED 都扇出。理由:关系信封 `aggregateVersion=0` 是边的事实,不是任一端点 WorkItem 的帧版本——拿 0 当 WorkItem 版本会让订阅方误判"回到初始态"。实现:consumer 注入 `WorkItemRepository.findById` 逐端点取当前 version,audience=资源∪项目∪Team 三级(与 WorkItem 自身事件同形);端点消失防御性静默跳过(信号有损合同,权威读在 REST)。

证据:`CollaborationResourceChangeConsumerTest.relationEventsFanOutToBothEndpointsAtTheirCurrentVersions`(CREATED/REMOVED 双事件类型:2 帧、各端各自 version 11/4、三级 audience)、`aVanishedRelationEndpointIsSkippedSilently`。

## 递归 CTE 红线遵循(承 D01)

新增两条展示型 CTE(upstreamClosure 三元组闭包、blocked 聚合页+窗口)全部 `UNION`(禁 UNION ALL)、深度 32 上界 JOIN 条件、LIMIT 5001 探测、截断显式上抛;探测 CTE(blocksReaches/splitAncestorOf)保持无上界不变。

## 偏差记录

见契约 §0:controller 拆两个(命令/查询惯例);链/关键路径挂 `work-projects/{projectId}` 路径段(可见性协议需 project 坐标,与全部既有 work-item 读一致);归档守卫无新错误码(422 `workItemRelation.endpoints`,WorkItemComment.create 先例)。

## 门禁

- C1 时点:`./mvnw -pl crewscope-domain,crewscope-application,crewscope-infrastructure -am clean verify` BUILD SUCCESS(A02 集成 6/6)。
- C3 全 reactor(合并 C2 server 门禁,按用户指示统一构建):`./mvnw clean verify` BUILD SUCCESS——domain / application / agentscope-adapter / integration / infrastructure(26:58 min)/ server(02:23 min)全绿。首跑暴露 consumer 扇出单测 UnfinishedStubbing(thenReturn 参数内现场 mock,修复为先建对象再 stub——A01 同文件注释先例的同类坑),修后二跑全绿。C2 新测试类计数:WorkGraphControllerTest 8、WorkGraphQueryControllerTest 8、WorkGraphBlockedCursorCodecTest 5、CollaborationResourceChangeConsumerTest 12(含 2 扇出用例)、WorkItemRelationCommandIntegrationTest 12(含 2 归档用例),全部 0 failures / 0 errors。
