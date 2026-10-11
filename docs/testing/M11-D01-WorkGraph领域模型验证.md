# M11-D01 WorkGraph 领域模型验证

> 交付日期:2026-10-11。C1 领域+迁移+持久化底座、C2 命令服务+事件审计、C3 并发+基线+文档,三笔提交。
> 设计权威:[ADR-033](../adr/ADR-033-WorkGraph关系模型.md);范围决策:REST/查询 API 归 A02,跨 Team 关系显式拒绝(PolicyDenied)。

## 验收矩阵

| 验收项(主计划原文) | 证据 | 结果 |
|---|---|---|
| 领域层零框架依赖 | `crewscope-domain/.../workgraph/` 全部类型仅 JDK(domain 单测 3 类全绿) | ✅ |
| BLOCKS/DEPENDS_ON 方向规范化 | `WorkItemRelationTest`(领域)+ `normalizesDependsOnSubmissionIntoTheStoredReverseEdge` / `normalizesDependsOnAndStreamsTheCreatedFact`(持久化/命令):`DEPENDS_ON(X,Y)` 入库为 `BLOCKS(Y,X)`,正查反向为空 | ✅ |
| SPLIT_FROM 环规则 | `enforcesTheSplitForestRules`:第二来源拒(SplitSourceConflict)、祖先环拒(CYCLE)、合法链通过;`probesSplitAncestryAlongTheForestOnly`:BLOCKS 边不泄漏进祖先探测 | ✅ |
| 真实数据库并发证明相反边不能同时通过 | `WorkItemRelationCycleConcurrencyM11D01IntegrationTest` 4 场景全绿(下节) | ✅ |
| 跨 WorkProject 关系需显式权限 | `requiresParticipationOnBothEndpointProjects`:单侧项目角色 PolicyDenied,双侧重放行;`WORK_PARTICIPATE` 对 src/dst 项目各裁决一次 | ✅ |
| 深度查询 5000 节点 P95 基线且不超深度上界 | `WorkItemRelationQueryScaleM11D01IntegrationTest`(下节) | ✅ |
| 关系变更产生正确的事件与审计记录 | `normalizesDependsOnAndStreamsTheCreatedFact`(domain_event 行+outbox+receipt)、`removesOneEdgeAndStreamsTheRemovedFact`、`projectsCreatedAndRemovedEventsIntoAuditRows`(audit_event WORK/SUCCEEDED/payload) | ✅ |

## 并发验证(核心验收)

`WorkItemRelationCycleConcurrencyM11D01IntegrationTest`(真实 PostgreSQL,双 latch+2 线程+Outcome):

| 场景 | 断言 | 结果 |
|---|---|---|
| 相反边(T1: A→B,T2: B→A) | 恰一 COMMITTED 另一 CYCLE;库中恰 1 边;receipt 恰 1 行 | ✅ |
| 菱形半环(种子 B→C、D→A,并发 A→B 与 C→D) | 恰一提交;库中 3 边;对每条存活边 `blocksReaches(dst,src)=false`(无环的直接特征) | ✅ |
| SPLIT_FROM 祖先环(种子 C split-from A,并发 B←C 与 A←B) | 恰一提交;库中 2 边 | ✅ |
| 跨项目相反边(CRW×WGS 双行锁) | 恰一提交且两 Future 30s 内返回(升序锁序无死锁) | ✅ |

机制:命令事务先 `lockByIds`(两端项目按 PG 无符号 uuid 升序 FOR UPDATE)再锁内递归 CTE 探测,后到者必见先到者已提交的边;DB 兜底为 `ux_work_item_relation_block_pair` 无序对部分唯一索引(反向命中转译为 2-环 CYCLE 而非重复)。

## 规模基线(P95)

`M11D01GraphScaleFixtures` 直插 5300 节点/4999 边(链 2000 + 300 hub×10 leaf;spike 脚本头注释的 5100/5099 与其 SQL 实际产出不符,以 SQL 为准)。`WorkItemRelationQueryScaleM11D01IntegrationTest`(WARMUP=10/SAMPLES=20/nearest-rank,节点混采链底/链中/hub/leaf):

| 查询 | ADR-033 阈值 | 断言 | 结果 |
|---|---|---|---|
| 单点邻接 `findByEndpoint` | P95 ≤ 100ms | 通过 | ✅ |
| 有界上游步行 `upstreamChain` | P95 ≤ 500ms 且永不报告超深度 32 | 通过 | ✅ |
| 2000 深链截断 | 截断显式呈现 | `truncatedByDepth=true`、恰 32 步、可见最深为 item_33、`truncatedByRows=false` | ✅ |

参考实测(S01b 直连 PG、暖缓存,`scripts/m11-s01/cte-depth.sql` 记录):邻接 P95=0.069ms、步行 CTE P95=0.161ms、菱形网格最坏形态 P95=1.292ms——均远低于阈值;D01 测试口径为阈值断言,环境为 CI 同款容器。

## 递归 CTE 红线遵循

- 探测 CTE(blocksReaches/splitAncestorOf):单列 `UNION`、无深度/行上界(截断=假阴性放环入库,正确性机制不允许);终止性由 UNION 去重保证(工作集 ≤ 节点数)。
- 展示型步行(upstreamChain):`UNION` + 深度 32 + 行 5000,截断经 `truncatedByDepth/truncatedByRows` 显式上抛。

## 其他交付要点

- **V67 兜底修正**:计划原文的两个有序唯一索引(forward/reverse)互不碰撞、拦不住相反边;实现改为 `least/greatest` 无序对部分唯一索引(BLOCKS only),reverse 降为普通链查询索引。
- **异常转译时序**:`flush()` 在方法体内抛 Hibernate 原生 `ConstraintViolationException`,Spring 转译发生在 CGLIB 代理层(方法返回后)——适配器 catch `RuntimeException` 按约束名分流(split_child/block_pair/forward),未知透传。
- **级联删除语义**:`removeAllForWorkItem` 逐边 Removed 事件、信封 `idempotency_key=NULL`(部分唯一索引为不源于幂等命令的系统事实保留 NULL;共享命令键会在第二条边撞约束)、无 receipt(级联是未来删除命令的效应,不是命令)。
- **单线程预判裁剪**:计划中的 RelationCycleDetector+findByEndpoint 预判被裁掉(每边 O(图) 查询且结论被锁内 CTE 覆盖);探测器保留为领域层交付,由域单测证明算法与 kind 视图隔离契约。
- **范围外**:REST/DTO/装配/QueryService、协作扇出(CollaborationResourceChangeConsumer)归 A02;跨 Team 边显式 PolicyDenied(ADR 增补后再开)。

## 门禁

- `./mvnw -pl crewscope-domain,crewscope-application,crewscope-infrastructure -am clean verify`:1194 tests,0 failures(C1/C2 时点)。
- C3 后全量:`./mvnw clean verify` 首跑暴露 server 组合测试缺 WorkItemRelation 两端口 mock(`ApplicationCompositionConfigurationTest` 的 ApplicationContextRunner 上下文起不来),补 mock 后 `./mvnw -pl crewscope-server -am verify` 全 reactor SUCCESS——infrastructure 1194 tests / server 964 tests,0 failures。
