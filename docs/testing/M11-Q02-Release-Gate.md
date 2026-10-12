# M11-Q02 Release Gate（三账号同屏协作收口）

> 定位：主计划 `docs/plans/M11-协作规模化与开放生态.md` Q02 行（最后一批，M11 收口）。验收面 = 该行合同全文：「主线：三名成员在四服务环境完成含依赖关系的多工作项协作，验证在场/变更、成员撤权、责任转移、图查询及 SSE 恢复；可选：按所选 MCP、英文、桌面范围分别附端到端报告」；验收：「主线无冲突覆盖、无无主责任链、无越权；断线后权威状态恢复；Web 主线可独立通过。所选扩展只有完成其安全和真实环境验收才能标为通过，未选入不阻塞后续主线」。结果枚举只用：待执行 / 通过 / 失败 / 需授权或环境 / 不适用（说明理由）。
>
> 层级标注原则（与 [Q01 矩阵](M11-Q01-跨包硬化与收口矩阵.md) 一致）：**真实栈 e2e** = 独立 compose 项目 `m11-q02`（端口 18093、RUNTIME_ROOT `var/release/m11-q02`、`COLLABORATION_REALTIME_ENABLED=true` + 1s 心跳——撤权全链演练需要压缩清扫周期），只经 `sh scripts/m11-q02-real-stack-gate.sh` 运行（`CREWSCOPE_M11Q02_PHASES` 分段 build,up,main，可选 `specs`=m11-real 整目录 belt-and-braces）；spec 只跑 `--project 'M11 Real Desktop'`（Narrow 孪生会为同一栈双倍驱动，不入证据面——Q01 先例）。**Java** = 仓库根 `./mvnw` reactor（单会话纪律）。
>
> 真实凭据边界：本 gate **零真实凭据**——三个测试账号全部经公开 OPEN 注册创建（Owner 经 onboarding 建队，两名成员各经一条一次性邀请链接加入），bootstrap operator 密码文件只由 quickstart 生成、gate 不读取；不招募真人（§8.12：自动化/代理浏览器）；不烧任何模型 key。

## 1. §8 十三条逐行证据表（主轴）

| # | §8 条款 | 验证层级与证据 | 结果 |
| --- | --- | --- | --- |
| 1 | 实时通道与 SSE 共存，WebSocket 不参与权威状态恢复，降级路径端到端可用 | 真实栈：I01c `collaboration-ws.spec.ts`（WS 命令面：welcome/订阅/信号）+ `collaboration-degraded.spec.ts`（通道关闭降级端到端）；Q02 `three-member-mainline.spec.ts` t5（只断 Alice 单成员：离线窗内 Carol 经 REST 提交事实，重连后徽标回「实时」+事实计数增长+权威 REST 读到新 item——恢复锚=快照+durable cursor，与 WS 无关；对照 Carol 全程 live） | 通过（t5 8.3s；I01c/F02 复跑见 §4） |
| 2 | 在场数据仅存于 Redis 且有 TTL，丢失不影响业务正确性 | 真实栈：I01c `collaboration-presence.spec.ts`（presenceTtl 到期自清）；`redis-restart-resilience.spec.ts`（Redis 容器重启：在场丢失无错误 UI、roster 经心跳刷新重落）；Q02 t1（多标签同 principal 去重+角标——S01 §101 移交面） | 通过（t1 4.3s；redis 重启演练见 §4 复跑） |
| 3 | 跨 Team 订阅与撤权后连接保持全部被阻断 | 真实栈：Q01 `cross-team-subscription-attack.spec.ts`（合法自有凭据瞄他人 Team：裸 WS `forbidden_scope`+全部图查询面 403 无泄漏）；`revocation-full-chain.spec.ts`（REST suspend→outbox→活 socket 切断→roster 放人→重连拒）；Q02 t6（一次 suspend 同切 REST/WS/presence/SSE 四通道+裸 WS 重入再拒+成员读 403 而账号 session `authenticated=true`——Team 撤权≠账号登出） | 通过（t6 23.6s，徽标「无权限」10s 稳态；Q01 复跑见 §4） |
| 4 | WorkGraph 三类关系语义正确，环检测有效，跨 Project 关系需显式权限 | Java：[M11-D01](M11-D01-WorkGraph领域模型验证.md) 与 [M11-A02](M11-A02-WorkGraph查询与命令验证.md) 全套（三类关系状态机、并发成环检测、跨 Project 显式授权、查询与命令验证）；真实栈：Q02 t4（BLOCKS×2+SPLIT_FROM 真实建链，图聚合 `blocked-work-items` 轮询对账恰为被阻塞两项）+ Q01 并发成环攻击 | 通过（t4 2.4s；Java 面随 §4 全 reactor） |
| 5 | 图视图有非图形的等价可访问表达，大图有降级路径 | 真实栈：F02 `workgraph.spec.ts`（sr-only「依赖图节点清单」+大图超阈值降级为列表）；Q02 t4（role=group「阻塞依赖图，共 3 个工作项、2 条阻塞关系」+节点清单+「被 1 项阻塞」+链尾抽屉两向关系三句式） | 通过（t4；F02 复跑见 §4） |
| 6 | 选做 MCP Client 时：双重治理与沙箱边界 | 不适用：F03（MCP）属可选范围未选入主线——逐字继承 [Q01 矩阵](M11-Q01-跨包硬化与收口矩阵.md) q01/S21 裁定（薄登记 ADR-040，不以未运行充作通过） | 不适用 |
| 7 | 选做 MCP Server 时：外部身份/Scope/撤销/幂等完整 | 不适用：同上，F03 未选入（q01/S21） | 不适用 |
| 8 | 选做 i18n 时：文案完整抽取与双语验证 | 不适用：F04 属可选范围未选入主线（q01/S22，薄登记 ADR-041） | 不适用 |
| 9 | 选做桌面时：认证/同源/深链接/签名更新链 | 不适用：I03 属可选范围未选入主线（q01/S23） | 不适用 |
| 10 | `OperationsController` 等巨型类完成收口且公开合同零差异 | Java：[M11-E01 巨型类收口验证](M11-E01-巨型类收口验证.md)（Characterization Test 先行、公开 API/事件/权限/错误合同前后零差异；不设行数硬门槛）；§4 全 reactor 复跑 | 通过 |
| 11 | 成员角色变更/移除/停用、最后 Owner 保护、无无主责任链、实时订阅与在途执行权限及时失效、跨 Team 会话与账号级失效区分 | Java：M9b-A07（生命周期/最后 Owner/责任交接/SUSPENDED-REMOVED 同分支）；[M11-D02](M11-D02-成员撤权实时通道验证.md)（撤权接实时通道）；真实栈：Q01 `revocation-full-chain.spec.ts` + Q02 t6（账号级区分实证）+ t7（无无主链逐项断言：3 个 item 各恰 1 条 ACTIVE EXECUTOR 且为接手者 principal）；在途执行（TaskToken）=书面裁定 §6.1（消费时全量复检 fail-closed，不以未运行充作通过） | 通过（t6/t7；agent 级 token 消耗需模型凭据，见 §6.1） |
| 12 | 三个隔离测试账号的同屏协作端到端验证通过，由自动化/代理浏览器操作，不招募真人 | 真实栈：Q02 `three-member-mainline.spec.ts` t1–t7 本身——三个隔离浏览器 context（各自独立 cookie/账号），全部经公开 OPEN 注册+邀请链接入队，零真人、零真实凭据 | 通过（7/7，1.1m，§2） |
| 13 | M0–M10 全量固定攻击、故障、恢复、迁移与浏览器门禁零回归 | 本档 §4：全 reactor `./mvnw test` + web 全家桶（unit/quality/q01/build/e2e）+ 四真实栈 gate 串行复跑（i01c→f02→q01→q02）；M10-Q02 gate 不复跑=书面裁定 §6.5 | 通过（数字见 §4） |

## 2. Q02 合同场景逐行表（主线五面 + 验收四要素）

账号命运互不重叠：Alice Q02 Owner（OPEN 注册→onboarding 建队）全程命令者；Bob Q02（邀请链接 #1，MEMBER）三工作项 EXECUTOR、被撤者；Carol Q02（邀请链接 #2，MEMBER）接手者（handover 前从未被指派——无主链断言的干净前提）。造数：共享 TEAM conversation（participants 含两成员）→一个 project→三个链式 work item（链头/链中/链尾；BLOCKS 链头→链中、BLOCKS 链中→链尾、SPLIT_FROM 链尾→链中）→Alice REST 逐项指派 Bob 为 EXECUTOR。图数据唯一源=轮询 `blocked-work-items` 恰为 {链中,链尾}。

| 合同面 | spec | 断言要点 | 结果 |
| --- | --- | --- | --- |
| 在场/变更 | t1/t2/t3 | t1 三页 pill「实时协作在线」+双端 roster 互认+Bob 双标签去重（aria-label 中 /Bob Q02/g 计数恰 1+title「Bob Q02（2 个标签页）」）；t2 typing 一人形/两人形「和…正在输入…」+5s 窗客户端自清归零；t3 Carol REST PATCH(If-Match)→202→Alice 脏抽屉 `.change-banner`「该工作项已被更新」且本地标题原样（无冲突覆盖）→点「点击刷新」→横幅消失+版本 v+1 | 通过（4.3s/5.4s/3.7s） |
| 成员撤权 | t6 | 先负断言：MEMBER 发 suspend→403 `policy_denied`、行仍 ACTIVE（无越权）；Owner 真实 suspend→202→四通道同断：REST 权威行 SUSPENDED(20s)、Bob 主页 pill 离开 live(20s)、双幸存者 roster 放人(20s)、第二标签页 /activity 徽标「无权限」且 10s 稳态（SSE 断流→退避重连→新握手 403→forbidden，见 §3 缺陷 #1 修复链）；残留：裸 WS 重入 `forbidden_scope`、Bob 读 members 403 而 `auth/session` `authenticated=true` | 通过（23.6s） |
| 责任转移 | t7 | preview 恰 3 项 EXECUTOR；create handover{target=Carol}→202；process→200（控制器 okView——只有 create 应答是 202，spec 初版误读已按契约修正）；轮询 job COMPLETED 且逐项 DONE(30s)；无无主链逐项断言：3 个 item 各恰 1 条 ACTIVE EXECUTOR 且 actorPrincipalId=Carol；UI 收口：链中抽屉含 Carol Q02 不含 Bob Q02 | 通过（2.3s） |
| 图查询 | t4 | role=group「阻塞依赖图，共 3 个工作项、2 条阻塞关系」+sr-only 节点清单三项全中+「被 1 项阻塞」；链尾抽屉 `.relations-list` 两向句式：「「Q02 链中…」阻塞本项」「本项拆分自「Q02 链中…」」（顺序契约：t4 先于 t5——t5 会铸造第四个 item 污染计数，spec 注释钉死） | 通过（2.4s） |
| SSE 恢复 | t5 | Alice 徽标「实时」→`setOffline(true)`（仅单成员断连，栈与双对照者不动）→「离线」→离线窗内 Carol REST 新建「Q02 恢复探针」→`setOffline(false)`→徽标回「实时」(30s)+「已加载事实」计数增长+权威 REST 读到新 item；Carol pill 全程 live | 通过（8.3s） |
| 验收四要素 | 全部 | 无冲突覆盖=t3 脏抽屉原样；无无主责任链=t7 逐项恰 1；无越权=t6 负断言+全 403；断线后权威状态恢复=t5 快照+cursor 对账；Web 主线独立通过=gate exit 0、零可选包、零真实凭据 | 通过（7/7，1.1m） |

## 3. 真实栈抓到的产品缺陷（Q02 存在的直接证据）

| # | 模块 | 根因 | 修复与回归 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | crewscope-web（teamops `activityRealtimeStore`） | t6 两轮同形失败：Owner suspend 后 /activity 徽标钉死「实时异常」45s+10s 不动。根因链：冻结契约（`TeamActivityStreamRevocationTest` :232「idle probe denial must terminate the SSE stream」=`isOnError()`）的撤权断流形态是——idle probe 复检抛 PolicyDenied→Flux error→**已提交 200 的连接直接中止，无任何错误 envelope data 行**；浏览器侧 body reader 因此抛裸 TypeError。而 `consume` 的 for-await 把「迭代器 rejection（传输层断流）」与「循环体抛错（帧级畸形 JSON）」灌进同一个 catch，`teamOpsError` 对 TypeError 一律归 `invalid-response`——terminal error 相位、`return` 不重连，退避梯子永远到不了新握手门口的权威 403/forbidden。单测层从未暴露：既有用例只造过「帧级畸形」（error 合同正确），没造过「reader 中途 rejection」。 | 手动迭代区分两维：迭代器 rejection=传输层断流→退避重连（新鲜握手的 403 落 forbidden 并停住）；循环体抛错维持「malformed 帧→error 相位+durable cursor 不推进」既有合同与用例不变；手工迭代在早退路径显式 `frames.return()`（对齐 for-await 的关闭语义，防孤儿 generator 持续缓冲 HTTP body）。同步删除第一版误诊引入的 `isErrorEnvelopeFrame` 死分支——envelope 以 SSE data 行形态只出现在**新握手被拒**（同测试 :297-305，真 403），`client.open` 在 `!response.ok` 即抛 `CrewScopeApiError(403)`，永不进帧解析循环。回归=`activityRealtimeStore.spec.ts` 新用例「severed stream（reader rejection）→reconnect→新握手 403→forbidden、openings=2」（5/5 绿，既有 malformed 用例原样通过）；真实栈复跑 t6 徽标「无权限」+10s 稳定。 | 通过（commit 017e7e5；gate run4 7/7） |

> 测试侧注记（非产品缺陷，一并入档）：t7 初版把 `POST /{jobId}/process` 断言为 202——实际契约只有 create 是 202（`createdResponse`+控制器注释「The 202 acknowledgement」），process/get/cancel 全走 `okView`=200；run3 首跑 t7 暴露后按契约修正 spec（断言从契约推导不从记忆推导）。另 t7 曾有 response body 双读（先 `text()` 后 `json()`）与循环变量遮蔽两处自查修正，均在首跑前清除。
>
> harness 注记一（i01c gate 首跑 401=已知坑复现，非产品缺陷）：陈旧 `var/release/m11-i01c/load-principals.json` 运行时缓存在 `main reset` 换新库后仍持旧凭据——load 阶段登录 401 `invalid_credentials`。Q01 零回归期已发生过一次（清理后复跑通过）；本次重置后缓存又被上一轮播种重新变陈旧。处置同 Q01：删除缓存文件后全相位复跑。每次 `reset` 后该文件必然陈旧，后续凡 i01c gate 全相位重跑前都先删。
>
> harness 注记二（i01c gate 自身缺陷，已随本包修复——`scripts/m11-i01c-real-stack-gate.sh` 降级相位显式钉 `CREWSCOPE_COLLABORATION_REALTIME_ENABLED=false`）：macOS `/bin/sh` 下函数调用的前缀赋值（`V=true main up`）在函数返回后**保持已导出状态**并传播给后续子进程——bash/zsh 按规范隔离，POSIX sh 不隔离。泄漏路径：up 相位 `...=true main up` → 降级相位裸 `degraded up` → quickstart `compose()` 透传 shell 环境 → compose.yaml `${VAR:-false}` 插值读到泄漏的 true → 降级栈以热通道起 → 降级 spec 的 404 断言收到 opened=true 而失败。定位链：手动按 gate 原语拉起降级栈（env=false）其上 spec 3/3 过（产品全对）→ 最小复现实测三 shell 对照确证泄漏。修复=降级相位显式钉 false（泄漏 false 无害，degraded 是最后相位）+ 脚本内注释钉死根因。修复后全相位复跑 exit 0（degraded 3 passed）。首跑日志里 f02/q01/q02 三 gate 当时已 exit 0、不受影响，均无需重跑。

## 4. 零回归复跑记录（§8.13）

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| Java 全 reactor | `./mvnw test`（单会话） | 通过：BUILD SUCCESS 37:24min，七模块全 SUCCESS；Tests run 4458、Failures 0、Errors 0、Skipped 19（已知条件跳过：key 门控质量门等；分模块 domain 873/application 1099/agentscope 243+2skip/integration 18/infrastructure 1222+15skip/server 1003+2skip） |
| web 单测 | `pnpm test` | 通过：245 文件 / 1687 用例（+1=§3 缺陷 #1 回归用例；断言基线 1686→1687 如实入档） |
| web 质量门禁 | `pnpm check:quality` | 通过（stylelint+eslint+check-web-quality PASS） |
| 三矩阵结构校验 | `pnpm check:q01` | 通过：M9b-Q01 40 行、M10-Q01 26 行、M11-Q01 24 行，证据路径全部存在 |
| web 构建 | `pnpm build` | 通过（vue-tsc + vite，7.82s） |
| web 浏览器 e2e | `pnpm test:e2e` | 通过：619 passed + 7 skipped（既有条件跳过），10.8m，exit 0（m11-real 目录被默认 e2e testIgnore 排除，不占此数——Q02 主线由 §5 gate 承证） |
| 真实栈 gate ×4（串行） | `sh scripts/m11-i01c-real-stack-gate.sh` → `m11-f02` → `m11-q01` → `sh scripts/m11-q02-real-stack-gate.sh` | 通过：四 gate 各 exit 0——i01c 全相位（specs 1 passed + load 四演练 + slow1013 + degraded 3 passed，首跑 401=Q01 已知陈旧缓存坑、复跑首败=gate 自身 sh 泄漏 harness 修复见 §3 注记，修复后全相位 exit 0）；f02 5 passed (16.1s)；q01 3 passed (18.8s) + 1 passed (8.4s)（redis-restart 演练独立批次）；q02 7 passed (1.0m)。i01c gate 相位含 build（Docker 内 Maven 缓存隔离），期间与主栈无并发 |

## 5. 复跑入口

```sh
# Q02 主线 gate（build,up,main；可选 specs=m11-real 整目录；KEEP_STACKS 保留栈供检查）
sh scripts/m11-q02-real-stack-gate.sh
CREWSCOPE_M11Q02_PHASES=main CREWSCOPE_M11Q02_KEEP_STACKS=1 sh scripts/m11-q02-real-stack-gate.sh

# 零回归四 gate（严格串行；各脚本自带 build 相位，Docker 内 Maven 缓存隔离）
sh scripts/m11-i01c-real-stack-gate.sh
sh scripts/m11-f02-real-stack-gate.sh
sh scripts/m11-q01-real-stack-gate.sh
sh scripts/m11-q02-real-stack-gate.sh
```

## 6. 书面裁定（随本包入档）

1. **TaskToken 在途执行归属裁定**：suspendMember 不调 TaskTokenService.revoke **不是缺口**——权威防线在消费端全量复检：`DurableTaskTokenAuthenticator.authenticate`→`TaskTokenCurrentAuthorization.requireCurrent`（requireCurrentMembership→currentExecutionMember→`canParticipate()` 过滤，status≠ACTIVE 即 fail-closed），签发快照从不作为授权依据；revoke 只是旁路加固。归属测试：`TaskTokenMemberDimensionM9bA07Test`、`PersonalAgentRevocationGuardTest`、`ActionAuthorityMemberRevocationTest`。真实 agent 级「suspend→在途 token 拒」需模型凭据，不属 Web 主线，不以未运行充作通过。
2. **typing ≥3 人形不可达**：三账号合同形态上限=2 名远端输入者（观察者是第三账号但同屏合同不要求其输入），「{N} 人正在输入…」的 ≥3 形不写 spec 断言。
3. **t5 注入选型=setOffline**：精确单成员断连（栈不动、双对照者不受扰、零 docker 权限、确定性重连），恢复路径本地驱动（store.setOnline→closeTransport+durable cursor）不依赖对端行为；回退预案 restartApi（重启 api 注入）未触发，不以备而未用为缺陷。
4. **Q02 文档不挂校验脚本**：Q01 矩阵由 `check:q01`→`check-m11-q01-matrix.mjs` 托底已含 M11 面校验；Q02 为 Release Gate 记录体（M10-Q02 先例不挂），结构由 §1–§6 固定。
5. **M10-Q02 gate（18090/18091 真实模型对照）不复跑**：其独有价值面=真实模型对照（烧真实 key、单轮 9–13h），确定性面（Java/web/浏览器门禁）已由本档 §4 全量复跑覆盖，与 Q01 零回归先例一致；如需另行授权可单独执行。
6. **⑥–⑨ 可选范围不适用**：MCP/英文/桌面均未选入主线（逐字继承 Q01 矩阵 q01/S21–S23 裁定，ADR-040/041 薄登记），不以未运行充作通过，不阻塞主线。
