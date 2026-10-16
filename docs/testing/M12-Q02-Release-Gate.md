# M12-Q02 Release Gate（单机主线收官）

> 定位：主计划 `docs/plans/M12-企业化与多租户治理.md` §6 Q02 行（M12 最后一批）。验收面 = 该行合同全文：「单机主线端到端及四服务新装/升级/恢复（沿用 M11 独立 Compose 栈分相位 gate 形态）；选入托管、K8s、身份扩展后各自追加真实环境验收」；验收：「报告按 §8 逐条给证据并明确主线/扩展状态；未验证形态不宣称支持」。结果枚举只用：待执行 / 通过 / 失败 / 需授权或环境 / 不适用（说明理由）。
>
> 层级标注原则（与 [Q01 矩阵](M12-Q01-跨包硬化与收口矩阵.md) 一致）：**真实栈 e2e** = 独立 compose 项目 `m12-q02`（端口 18095、RUNTIME_ROOT `var/release/m12-q02`），`up` 相位零附加 env——compose 默认无 OIDC/配额/realtime 键、`security.mode: local`，**默认安装路径本身即 §8-2 验收面**；只经 `sh scripts/m12-q02-release-gate.sh` 运行（`CREWSCOPE_M12Q02_PHASES` 分段 build,up,main，可选 `specs`）；spec 只跑 `--project 'M12 Real Desktop'`（Narrow 孪生不入证据面——M11-Q02 先例）。**Java** = 仓库根 `./mvnw` reactor（单会话纪律，与 Docker 内 Maven 绝不并发）。**Revision**：C1=584d367（gate+spec+config）；C2=本笔（报告+台账回填）；终跑 2026-10-16。
>
> 选入配置（主线默认）：team-beta 四服务、`security.mode: local`、OPEN 注册、OIDC/配额/realtime 零键、无外部监控栈。迁移与角色：gate 栈全新安装跑全链 V1–V70；fresh 栈唯一 ORG_OWNER = bootstrap operator（`crewscope-monitor`，V68 回填只覆盖存量库），A/B/C 三账号全经公开 OPEN 注册各自 onboarding 建队，A/B 由 operator 授予 ORG_ADMIN/ORG_AUDITOR（t1）——「受信绑定可用」以真实授权链承证。
>
> 真实凭据边界（相对 M11 修订）：仍**零真实凭据**——不烧任何模型 key、不招募真人、测试账号全部公开注册。唯一新增：gate 读取 quickstart 生成的 `$RUNTIME/bootstrap_password` 文件取 operator 会话（全仓 gate 首例）——密码只经 `CREWSCOPE_M12Q02_OPERATOR_PASSWORD` env 进入 spec，不 echo、不落日志、不随 trace 入归档，文件随栈 `reset` 销毁、永不入仓库。

## 1. §8 十三条逐行证据表（主轴）

| # | §8 条款 | 验证层级与证据 | 结果 |
| --- | --- | --- | --- |
| 1 | 存量归属不漂移（升级/恢复前后 Organization、Team、资源归属与授权一致，已有授权不被静默变更或重置） | [M12-D01 演练](M12-D01-组织聚合与归属不漂移验证.md) 三相位（REST 造数→快照 A、重建镜像同卷升级→B≡A、整库恢复→C≡A）+ Q01 时点 gate 复跑（138 绊线基线同步后 exit 0）+ 本包 §4 复跑 | 通过（D01 三相位；§4） |
| 2 | 四服务安装、存量升级与完整恢复通过；企业能力未启用时不要求 IdP、集群、外部 Secret、TLS 或监控栈；`quickstart.sh` 默认路径不变 | 新装=本 gate `build,up`（fresh 卷全链 V1–V70、零附加 env、四服务 Healthy）；升级/恢复=D01 gate 两相位；负证据：compose.yaml 无任何 OIDC/quota/realtime 键，栈在无 IdP/无监控下全链走通（t1–t7） | 通过（§2；D01 见 §4） |
| 3 | 本地/OIDC 账号策略（仅本地、仅 SSO、共存）与受信绑定可用；签名、issuer/audience、state/nonce/PKCE、重放、回跳与会话撤销验证通过；SSO-only 下 IdP 故障不静默退回本地密码 | local 真实栈：t1（受信绑定=operator 授予链收敛进会话投影）+ t6（删除链 security_version 递增踢老会话+重登录 401）+ t7（会话列表/单会话撤销/404 不确认存在性）；OIDC=[M12-I01 自测](M12-I01-OIDC接入与协议攻击自测.md) 九行协议攻击矩阵全拒+撤销延迟钉死（合规禁用双设备下一请求匿名）+三 IT；真实外部 IdP 接入=需授权或环境（裁定 §6.1），不以 EmbeddedIdP 充作外部验收 | 通过（local 真实栈；OIDC 引用 I01 终跑；外部 IdP=需授权或环境） |
| 4 | 配额原子准入与异步计量分开；并发受理不超占、幂等重试不重复占用、崩溃对账、月份切换不杀在途；未配置配额不改变默认使用路径 | Java：`QuotaAdmissionGatewayM12Q01IntegrationTest` 4/4（并发恰预算、事务中止无孤儿预占、两拒绝动作文本逐字——Q01 终跑覆盖，不重跑）；未配置零开销=B 层 verifyNoInteractions（A02）+ 真实栈 t3（beforeAll 造数先于配额配置：project/两 work item/关系全通——结构性证据；usage `materialized:false`）；配置/用量/auditor 分界=t3；拒绝路径不进真实栈（A02 契约偏差 2，裁定 §6.2） | 通过（IT 引用 Q01 终跑；t3 709ms） |
| 5 | 数据治理闭环：授权导出/保留/删除覆盖主库、派生索引、向量、文件、凭证与备份保留（含 M11 WorkGraph 关系表），不宣称删行即全副本清除；高风险操作有确认、幂等与 Audit | 真实栈：t5（导出 create 同键重放 `Idempotency-Replayed: true`、22 族、process 收敛、manifest `pinnedRowCount` 断言 WORK_ITEM≥2/WORK_ITEM_RELATION≥1/TEAM≥1/USER_ACCOUNT≥4、artifacts 200+`export-{id}.json`）；t6（删除 precheck 三零→create 13 items→未确认 process 422→用户名令牌 confirm If-Match→process 13 步全 DONE→备份声明常量英文原文断言——「不宣称删行即全副本清除」逐字承证）；治理审计落点=A01 IT（Q01 终跑覆盖）；D01 恢复相位（§4） | 通过（t5 521ms；t6 692ms） |
| 6 | 基础 SLI 默认四服务可取：六指标有冻结口径/窗口/目标与错误预算；组织下钻走授权查询 | 真实栈：t4（B=ORG_AUDITOR GET /sli 200、六指标固定序逐一断言、`reportingZone:'Asia/Shanghai'`、每指标 windows≥1；operator 亦 200；本栈无 Prometheus——默认四服务取数实证）；Java：[M12-I03 自测](M12-I03-SLI查询与容量报告自测.md)（OrganizationSliHttpIT 4/4+容量报告，Prometheus 414 个 `_bucket` 实证归 I03） | 通过（t4 425ms） |
| 7 | Org 角色与 Team 角色正交：Org Admin 不自动获得 Team 写权限；账号级禁用撤销全部会话（ADR-038 §3.1 事件链）；单 Team 撤权沿用 M11 范围规则 | 真实栈：t1（A 刷新即见 `org:manage-settings` 并列进顶层 permissions 而 `teams[0].permissions` 非空且零 `org:` 前缀——正交并列双向断言；auditor 词汇集恰 `org:audit-read`、铸币 403）+ t6（删除链 12 步 TOMBSTONE_IDENTITY 四面 DISABLED+security_version 递增→老会话匿名——账号级失效真实栈实证；Team 撤权≠账号登出归 M11 t6 面）；Java：`OrganizationOrthogonalityHttpM12Q01IntegrationTest` 3/3（Q01 终跑覆盖）+I01 撤销 IT | 通过（t1 342ms；t6） |
| 8 | F01/F02/F03 前端：组织设置、配额用量、身份源与活跃会话页面覆盖六态、键盘、390px 与 Axe；失败与救援可操作；前端不保存 IdP 密钥 | mock e2e：663 内 m12-f01/f02/f03 三 spec（12+8 用例×双 project：权限矩阵四档、令牌与 hash 不落 DOM、Axe、390px 真实生效——见 [F01](M12-F01-组织设置与治理工作流完成.md)/[F02](M12-F02-配额与用量完成.md)/[F03](M12-F03-企业身份与会话完成.md) 完成记录）；真实栈 UI 锚点：t2（`/settings/organization`「审计保留策略/当前策略：保留 120 天」）+ t3（`/settings/quota`「每月任务受理上限 5；每月 Token 预算 2,000,000」）+ t7（`/auth/sessions` 两行恰一 current）；check:sensitive 门禁绿（§4） | 通过（t2 1.3s；t3 709ms；t7 1.9s） |
| 9 | M0–M11 全量门禁零回归（含实时协作、WorkGraph、成员撤权链与既有攻击/故障集） | 本档 §4：全 reactor clean verify + web 全家桶（unit/quality/q01/build/e2e）+ 四 CI 独有门禁 + D01 gate + **m11-q02 gate 复跑**（含 build 相位重建镜像——M12 后端四笔改动后实时协作/WorkGraph/成员撤权链真实栈面承证）；其余真实栈 gate 不复跑=裁定 §6.4 | 通过（数字见 §4） |
| 10 | 托管隔离（两真实 Organization 全链隔离+RLS 证明清单） | 不适用：属可选范围未选入主线——逐字继承 [Q01 矩阵](M12-Q01-跨包硬化与收口矩阵.md) q01/S16 裁定（不以未运行充作通过） | 不适用 |
| 11 | K8s（I02）真实集群 | 不适用：同上，q01/S17（不以本机 Docker 代替集群验收，未选入即不宣称） | 不适用 |
| 12 | SAML/SCIM/MFA 身份扩展 | 不适用：同上，q01/S18（本期身份面=本地+OIDC，q01/S1/S2 承证） | 不适用 |
| 13 | 外部观测（采集/仪表盘/告警） | 不适用：同上，q01/S19（默认四服务取数由 I03 交付） | 不适用 |

## 2. Q02 合同场景逐行表（主线七用例 + 安装三形态）

账号命运：operator（bootstrap `crewscope-monitor`，fresh 栈唯一 ORG_OWNER）全程授权者与治理执行者；A（OPEN 注册→onboarding 建队→t1 获 ORG_ADMIN）承设置/配额/SUI 面；B（同路→t1 获 ORG_AUDITOR）承 SLI/审计与能力分界负断言；C（同路、无任何组织角色）=t6 删除靶——三账号均需真实团队 membership：组织 principal 行是会话投影的前提（见 §3 注记）。造数（beforeAll）：A 经 REST 建 project+链头/链中两 work item+BLOCKS 关系（`blocked-work-items` 轮询对账恰为链中），全部发生在配额配置之前（§8-4 结构性证据）。

| 合同面 | spec | 断言要点 | 结果 |
| --- | --- | --- | --- |
| 新装 | gate build,up | fresh 卷全链 V1–V70、零附加 env、四服务 Healthy、`bootstrap_password` 生成 | 通过 |
| 存量升级 / 完整恢复 | D01 gate 两相位（§4 复跑） | 快照 B≡A / C≡A、138 组织作用域表在线绊线三相位成立 | 通过（§4） |
| 角色授予收敛（§8-3/7） | t1 | operator 自有 ACTIVE ORG_OWNER grant 在列；授 A/B 202；A 刷新 `organization.role==='ORG_ADMIN'`+`org:manage-settings` 并列顶层而 `teams[0].permissions` 非空且零 `org:` 前缀；B 恰 `org:audit-read`；B 铸币 grant→403 | 通过（342ms） |
| 设置生命周期+UI 锚点（§8-7/8） | t2 | GET `{null,0}`→operator PUT 90 If-Match `"0"`→`{90,0}`（首写建行 version 停 0）→A PUT 120→`{120,1}`；B GET/PUT 双 403（auditor 词汇集无 SETTINGS_READ——Q01 家族矩阵）；`/settings/organization`「审计保留策略/当前策略：保留 120 天」 | 通过（1.3s） |
| 配额面+UI 锚点（§8-4） | t3 | 未配置 `{null,null,0}`+usage `materialized:false`→A PUT `{5,2000000}`→`{5,2000000,0}`+usage `{limit:5,count:0}`；B GET 403；`/settings/quota`「每月任务受理上限 5；每月 Token 预算 2,000,000」 | 通过（709ms） |
| SLI 读面（§8-6） | t4 | B GET /sli 200：六指标固定序逐一相等+`reportingZone:'Asia/Shanghai'`+每指标 windows≥1；operator 亦 200；栈无 Prometheus（默认四服务取数） | 通过（425ms） |
| 治理导出（§8-5） | t5 | create 同键重放 202+`Idempotency-Replayed: true`；items 22；process 收敛 COMPLETED；manifest `pinnedRowCount`：WORK_ITEM≥2、WORK_ITEM_RELATION≥1（活关系）、TEAM≥1、USER_ACCOUNT≥4；artifacts 200+`Content-Disposition: export-{id}.json` | 通过（521ms） |
| 两步删除+会话撤销（§8-5/7） | t6 | precheck 三零→create 202 `PENDING_CONFIRMATION` 13 items→未确认 process 422→confirm `{confirmationToken:<C 用户名>}` If-Match→process COMPLETED 13 步全 DONE（C 自建团队的真实 TEAM_MEMBER 行入 DELETE_DERIVED_ROWS 清理面）→备份声明英文常量原文（「不宣称删行即全副本清除」）→C 老会话 `authenticated:false`+重登录 401（恒定公共失败契约） | 通过（692ms） |
| 会话列表与撤销（§8-3/8） | t7 | A 第二 context 登录→GET /auth/sessions 2 行恰一 current 降序→DELETE 他属 204→ctx2 匿名而 ctx1 仍认证→重复 DELETE 404 `session_not_found`（不确认存在性） | 通过（1.9s） |

## 3. 真实栈抓到的产品缺陷

**零产品缺陷。** 九轮迭代（run1–run9：登录入口→principal 前提→权限形状→首写版本→auditor 分界→manifest 计数→投影前提→备份声明→全绿）全部失败均收敛为测试侧契约推导修正或 harness 事实钉死，无一触及产品行为偏差：

| # | 类别 | 事实 | 处置 |
| --- | --- | --- | --- |
| 1 | 契约笔误（顺手修正） | A01 契约表 settings 行括注「SETTINGS_READ（AUDITOR+）」——auditor 词汇集从不携带 SETTINGS_READ，t2 真实栈 403 与 Q01 家族矩阵 IT 双向钉死 | `docs/api/M12-Organization管理API契约.md` 括注改为「ADMIN+；auditor 词汇集无 SETTINGS_READ——Q01 家族矩阵裁定」，随 C2 笔入档 |
| 2 | harness 注记 | **未建队 OPEN 账号的会话是整投影匿名而非「principal 缺失」**：`AuthenticatedAccountOrganizationResolver.resolveSession` 链上 `principalResolver.resolveExisting` 对无组织 principal 账号返回 empty→session 落回匿名分支（account=null）。run7 t6 初版 C 不建队即踩中，且「删除后老会话匿名」断言会真空通过 | C 同走 onboarding 建队+beforeAll 三正向断言（authenticated/account/principal）防真空；事实入档供后续 spec 复用 |
| 3 | harness 注记 | **F01 mock `sessionFor` 把团队词汇并入顶层 permissions——mock 比真实宽**：真实后端顶层 permissions=账号级+`org:*`，团队词汇只在 `teams[].permissions`（t1 断言据此收敛） | 不改产品/mock（F01 e2e 契约自洽）；偏差入档，真实形状以本 spec 为准 |
| 4 | 契约语义钉死 | **设置/配额行首写建行 version 停 0**：首写 PUT（If-Match `"0"`）insert 建行 version 仍 0，第二次写才是第一个版本化变更（V68 settings 与 V70 quota 同语义，契约 §2.1 未明说，t2/t3 实测钉死） | spec 断言按实测语义+注释；语义随本报告入档 |
| 5 | 测试侧修正 | 导出 manifest `itemCount`=批次数、`pinnedRowCount`=行数（两个 work item 同批→itemCount 1 而 pinnedRowCount 2）；备份声明是**英文常量**（契约 §4.4 逐字），初版误断中文「保留」 | 断言改 `pinnedRowCount` 与英文原文片段——从契约推导不从记忆推导 |

> **收官后整体 review 补录（2026-10-16，随修复笔提交）**：上表「零产品缺陷」仅指 gate 九轮迭代内的测试面事实。收官后对 M12 实现的整体代码 review 另发现一处 gate 与既有 IT 均未覆盖的并发缺陷，登记待修：**last-owner 保护存在并发双撤窗口**——`OrganizationRoleApplicationService` revoke 先 `findByIdForUpdate` 锁目标 grant 行、再以无锁 `countActiveOwners`（普通 COUNT）判定最后一张保护；并发撤两张**不同** owner grant 时行锁互不阻塞，双方 count 均读到 2→都通过→组织被撤空为 0 ACTIVE OWNER，绕过契约 §2.2 `last_owner_protection`。触发需两个 ROLE_REVOKE_OWNER 会话毫秒级窗口对齐（授权用户并发自伤，非越权）。修法=executeRevoke 在任何行锁之前先取 per-organization advisory 事务锁（`pg_advisory_xact_lock`，ClaimQuota 同款形态；不用 FOR UPDATE 聚合——它锁全部 owner 行，双撤互持对方目标行仍有互等死锁窗口，advisory 单锁零死锁）+并发双撤回归 IT。其余七面（正交性/导出白名单/配额台账并发/OIDC 首登收敛/ensureInitialOwner 幂等/SLI 合并数学/前端 tokenHash 白名单）人工核验为可靠。

## 4. 零回归复跑记录（§8-9）

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| Java 全 reactor | `./mvnw --batch-mode --no-transfer-progress clean verify` | 通过：BUILD SUCCESS 48:36min，七模块全 SUCCESS；Tests run 4691、Failures 0、Errors 0、Skipped 19（已知条件跳过，与 Q01 基线 4691 持平） |
| web 单测 | `pnpm test` | 通过：261 文件 / 1827 用例全绿（与 Q01 基线持平，零新单测——本包交付面为真实栈 gate） |
| web 质量门禁 | `pnpm check:quality` | 通过（Web quality: PASS） |
| 三矩阵+M12 矩阵结构校验 | `pnpm check:q01` | 通过：M9b-Q01 40 行、M10-Q01 26 行、M11-Q01 24 行、M12-Q01 19 行，证据路径全部存在 |
| web 构建 | `pnpm build` | 通过（vue-tsc + vite，6.28s） |
| web 浏览器 e2e | `pnpm test:e2e` | 通过：663 passed + 7 skipped（既有条件跳过），16.8m，exit 0（663 基线维持；m12-real 目录被默认 e2e testIgnore 排除，本包主线由 §5 gate 承证） |
| 四 CI 独有门禁 | `check-design-tokens` / `check:enum-labels` / `check:sensitive` / `check:bundle` | 通过（design-tokens 183 文件 PASS 含对比度核算；enum-labels/sensitive/bundle 均 PASS；另 `pnpm lint` 0 errors） |
| D01 gate（§8-1/2 直接证据） | `sh scripts/m12-d01-tenancy-drift-gate.sh` | 通过：三相位（seed,upgrade,restore）exit 0——ownership anchors hold（organization=1 行、drilled org bound、scoped tables=138 绊线三相位成立） |
| m11-q02 gate（§8-9 真实栈面） | `sh scripts/m11-q02-real-stack-gate.sh`（全相位含 build——M12 后端四笔改动后重建镜像） | 通过：exit 0，`three-member-mainline` 7 passed（1.5m，18093）——实时协作/WorkGraph/成员撤权链当前 HEAD 承证 |

## 5. 复跑入口

```sh
# Q02 主线 gate（build,up,main；可选 specs=m12-real 整目录；KEEP_STACKS 保留栈供检查）
sh scripts/m12-q02-release-gate.sh
CREWSCOPE_M12Q02_PHASES=main CREWSCOPE_M12Q02_KEEP_STACKS=1 sh scripts/m12-q02-release-gate.sh

# 零回归（严格串行；Docker 内 Maven 与仓内 reactor 不并发）
sh scripts/m11-q02-real-stack-gate.sh
sh scripts/m12-d01-tenancy-drift-gate.sh
```

## 6. 书面裁定（随本包入档）

1. **OIDC 不进 gate 真实栈主线**：compose 默认无任何 OIDC 键——默认安装路径（§8-2「企业能力未启用时不要求 IdP」）与 OIDC 协议面（§8-3 后半）分属两层；后者由 [M12-I01 自测](M12-I01-OIDC接入与协议攻击自测.md) 九行矩阵+三 IT+EmbeddedOidcIdp 真签名 IT 承证（I01 终跑全量覆盖，Q01 已裁定不重跑）。真实外部 IdP 接入=需授权或环境，不以嵌入式 IdP 充作外部验收。
2. **配额拒绝路径不进真实栈**：逐字继承 A02 契约偏差 2——创建链路前置（agent profile/provider binding）过重且无既有真实先例，仲裁由 Q01 `QuotaAdmissionGatewayM12Q01IntegrationTest` 4/4+既有 403 映射通道替代覆盖；未配置零开销另由 t3 结构性证据（造数先于配置+`materialized:false`）承证。
3. **bootstrap 密码边界修订**：gate 读 quickstart 生成的 `bootstrap_password` 是全仓首例——密码只经 env、随栈销毁、不入仓库/报告/归档（头部凭据边界段）；spec 无该 env 即拒跑（`throw` 明确报错）。
4. **m11-i01c/f02/q01 与 m10-q02 gate 不复跑**（M11-Q02 §6.5 先例）：其确定性面已由 §4 全 reactor+web 全家桶覆盖；**m11-q02 例外复跑**（全相位含 build）——它承证 §8-9 点名的实时协作/WorkGraph/成员撤权链三真实栈面，且 M12 后端（V68–V70+I01 会话底座+A02 挂点）有实质改动，必须当前 HEAD 镜像承证。
5. **扩展 10–13 不适用**：逐字继承 [Q01 矩阵](M12-Q01-跨包硬化与收口矩阵.md) q01/S16–S19 裁定，不以未运行充作通过。
6. **未验证形态不宣称支持**：本档只称**单机主线通过**；托管、K8s、身份扩展（SAML/SCIM/MFA）、外部观测四形态未验收，不称双形态或全形态完成。
