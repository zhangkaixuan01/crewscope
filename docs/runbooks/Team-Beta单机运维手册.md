# CrewScope Team Beta 单机运维手册

当前部署从源码构建，长期运行 PostgreSQL、Redis、API、Web 四个服务；API 使用 `all` 模式，同时承担 Worker。旧七/十服务方案仅作为历史验收资料。

## 启动与更新

宿主机需要 Git、Docker Engine / Compose v2、OpenSSL，以及下载基础镜像和构建依赖的网络。API 通过本机 Unix Docker Socket 管理 Coding Sandbox；启动脚本会自动准备执行目录、目录所有者和 Socket 组，无需手工配置 Worker 身份。

在仓库根目录执行：

```bash
./deploy/team-beta/quickstart.sh up
```

访问 `http://<服务器地址>:8080`。首次启动自动构建本地镜像，后续启动复用已有镜像。默认开放注册，Operator 用户名为 `crewscope-monitor`，初始密码位于 `deploy/team-beta/.runtime/bootstrap_password`。模型、GitHub 和飞书的真实凭据需自行配置；当前只内置 Maven/Java 17 构建方案，尚无完整的自定义构建方案管理页面。远端 HTTP 提交兼容、GitHub 首次导入及自定义模型目录等已知限制见 [README 当前说明](../../README.md#部署本机与服务器通用) 和 M9b 计划，服务就绪不代表这些业务路径已通过验收。

更新时先完成下文备份，再执行：

```bash
git pull --ff-only
./deploy/team-beta/quickstart.sh build
./deploy/team-beta/quickstart.sh up
```

`build` 成功后才执行 `up`。构建失败时原运行容器不受影响。数据库迁移由应用启动时执行；不要把新 Schema 直接交给不兼容的旧代码，回退需要同时恢复更新前的数据和配置。

## 从启动到第一条回复、第一个任务、审查与 PR

新环境的最小可用主线按下面五步走完；每步的真实栈自动化验证与失败证据见 [M9b-Q02 Release Gate](../testing/M9b-Q02-Release-Gate.md)。任何一步卡住先查「常见问题」，再对照该文档对应场景行。

1. **启动与首用**：`quickstart.sh up` → 打开 Web 注册账号 → 创建团队。未配置任何 Provider 时对话、工作项、责任链照常可用（发送时有配置引导，不是报错页）。
2. **第一条真实回复**：右上角进入设置 → 模型连接，新建 DeepSeek 连接（API Key 只写不读，保存后服务端真实探测 `{endpoint}/models`，状态须为 HEALTHY）→ Agent 中心为默认 Personal Agent 选择该连接作为模型 Binding → 回到对话发送消息，收到 Agent 回复。无效 Key 会在验证一步被拒绝并留在表单内，可原地更正。
3. **第一个 Coding 任务**：设置 → GitHub 连接，粘贴 PAT（同样只写不读），验证执行身份发现并回填账号 → 同步仓库目录 → 把测试仓库绑定到团队 → 在项目的 GitHub 导入里发起导入（Worker 构建 bare mirror，完成后 preflight 可答）→ 项目执行默认值选仓库/分支/构建方案 → 工作项里「交给 Agent 处理」，确认目标与验收标准后「分配并启动」。预检在提交前评估完整执行图（含将要创建的 EXECUTOR 分配），不通过会给出可操作的原因。
4. **审查与 PR**：执行产生尝试与 Diff 后，从工作项进入审查：逐行评论、要求修改会驱动新一轮执行；确认后从交付计划创建 **Draft PR**（系统不自动合并，PR 边界见 Release Gate §凭据说明）。
5. **失败后的下一步**：命令超时/崩溃后系统不重复执行——通过恢复入口按幂等键找回结果坐标；仓库导入失败可取消后重读原 job（未知 job 读回专用错误码）；PR 结果未知时先只读对账再决定重试。成员被停用后下一次请求即被切断，重新激活不复活旧授权。

### 模型（DeepSeek）

平台目录只内置 DeepSeek（`https://api.deepseek.com`），连接端点固定取目录默认值，不能指向自定义地址。Key 经页面提交后加密落库（`CREWSCOPE_CREDENTIAL_KEYS`），任何 DTO 都不回传凭据；团队执行只用 TEAM/ORGANIZATION 连接，USER Key 在服务端被拒。健康探测失败置 UNHEALTHY，可重新启用。

### GitHub

PAT 需要 target 仓库的读写权限（验证一步会做真实身份发现）。连接 → 仓库目录同步 → 团队绑定 → 项目导入的链路每步都可单独重试；导入 Worker 通过 ask-pass 使用已保存凭据拉取 bare mirror，执行 Sandbox 在 `CREWSCOPE_EXECUTION_ROOT` 下受管。凭据同样只写不读。

## 配置

`quickstart.sh init` 只生成配置，不启动服务。编辑 `deploy/team-beta/.runtime/.env` 后执行 `up` 应用变更，值按生成文件的格式直接填写，不需要 shell 引号。

| 配置 | 默认值 / 用途 |
| --- | --- |
| `CREWSCOPE_WEB_PORT` | `8080`，宿主机 Web 端口 |
| `CREWSCOPE_REGISTRATION_MODE` | `OPEN`；可选 `INVITE_ONLY`、`DISABLED` |
| `CREWSCOPE_DEMO_ORGANIZATION_NAME` | 首次初始化的组织名称，已有组织请通过产品功能修改 |
| `CREWSCOPE_EXECUTION_ROOT` | 自动生成的绝对路径，保存受管仓库和 Worktree，使用专用目录 |
| `CREWSCOPE_DOCKER_SOCKET` | `/var/run/docker.sock`，可改为本机 Docker 的 Unix Socket |
| `CREWSCOPE_SESSION_COOKIE_SECURE` | `false`；使用 HTTPS 时可选 `true` |
| `CREWSCOPE_HSTS_ENABLED` | `false`；确认长期使用 HTTPS 后可选 `true` |
| `CREWSCOPE_PGVECTOR_ENABLED` | `false`；启用可选知识向量存储（pgvector），见下方小节 |
| `CREWSCOPE_KNOWLEDGE_INDEX_ENABLED` | `false`；知识索引控制面（读恒 200，关态触发命令回 `enqueued:0`） |
| `CREWSCOPE_KNOWLEDGE_INDEX_WORKER_ENABLED` | `false`；索引 Worker。关态作业停在 QUEUED，便于演练取消 |
| `CREWSCOPE_KNOWLEDGE_RETRIEVAL_ENABLED` | `false`；统一检索。关态检索以显式降级码应答（200，非错误） |
| `CREWSCOPE_KNOWLEDGE_INJECTION_ENABLED` | `false`；Prompt 注入组装。关态不组装、不封存清单、不占预算 |
| `CREWSCOPE_SKILL_ENABLED` | `false`；Team Skill 目录写闸。读 200，五条写命令回 422 `skill_disabled` |
| `CREWSCOPE_MEMORY_ENABLED` | `false`；辅助记忆。关态不再写模型可见记忆，既有条目仍可查看/清除 |
| `CREWSCOPE_BUDGET_ALERT_ENABLED` | `false`；软预算提醒（提醒不配额），配合下两行使用 |
| `CREWSCOPE_BUDGET_MONTHLY_TOKENS` | `0`（不启用）；月 token 预算阈值 |
| `CREWSCOPE_OBSERVABILITY_REPORTING_ZONE` | `Asia/Shanghai`；用量事实归组的月份时区 |
| `CREWSCOPE_COLLABORATION_REALTIME_ENABLED` | `false`；实时协作 WebSocket 通道（在场/正在输入），见下方小节 |
| `CREWSCOPE_COLLABORATION_REALTIME_HEARTBEAT_INTERVAL` | `15s`；通道心跳周期，仅慢客户端演练需要压缩 |

数据库密码、凭据加密、游标、邀请、Task Token 和登录防护 HMAC 密钥自动生成，重启不轮换。登录防护保持启用，不需要手动提供密钥。保管好 `.runtime/.env`：仅备份数据库而丢失加密密钥，不能恢复模型等已保存凭据。不要将运行目录提交 Git，也不要直接修改已有数据库密码来“重置密码”。

切换注册模式：

```bash
./deploy/team-beta/quickstart.sh set-registration-mode INVITE_ONLY
```

默认 Web 监听所有网卡，数据库和 Redis 不发布宿主端口。不强制 TLS、域名、镜像 Digest、外部 Secret、Prometheus、OTel 或 Socket Proxy。需要 HTTPS 时，在前面配置 Nginx/Caddy，转发至 8080 并传递正确的 Host、X-Forwarded-Proto；应用不会校验部署证书来源。API 具有本机 Docker 管理权限，部署在自己的单机或团队专用执行主机。

HTTP 下密码和会话不加密，公网使用仍建议 HTTPS。内置 Web 会覆盖来访者的 X-Forwarded-For，并清除标准 Forwarded 及端口/前缀等冲突转发头，防止伪造登录防护来源和请求地址；添加上游代理后，默认按代理地址限流。如需区分真实客户端，应由管理员限定可信代理后再配置 Nginx real_ip，不能直接信任公网提供的转发头。

运行目录和执行目录须使用专用绝对路径，不含 `.`/`..` 或重复斜线；脚本按实际物理路径检查，不允许通过符号链接指向主目录、仓库根或系统目录，也不接受符号链接 env 文件。不要同时编辑配置或并发执行初始化/升级/备份；初始化发布不会覆盖另一进程已生成的密钥，发生并发提示后重新运行即可。

### 可选向量存储（pgvector）

M10-I01a 起知识条目嵌入支持可选 pgvector 存储，默认关闭；关闭时数据库结构与升级前完全一致。启用条件有二，缺一不可：

1. 在 `.runtime/.env` 写入 `CREWSCOPE_PGVECTOR_ENABLED=true`（quickstart 会自动叠加 `deploy/team-beta/compose.pgvector.yaml`，把 postgres 镜像换成 `pgvector/pgvector:pg17`）；
2. Postgres 数据卷在 pgvector 镜像下重建或迁移（见下方警告）。

启用流程（先备份再切换）：

```bash
# 1. 停机快照，保管好备份目录
./deploy/team-beta/quickstart.sh down
./deploy/team-beta/snapshot.sh backup /absolute/path/to/pre-pgvector-backup

# 2. 持久化开关（编辑 .runtime/.env 增加：CREWSCOPE_PGVECTOR_ENABLED=true）

# 3. 启动：首次会在独立迁移链 flyway_vector_history 上安装 vector 扩展与知识向量表
./deploy/team-beta/quickstart.sh up
```

**镜像基底不兼容警告**：基础镜像是 Alpine（`postgres:17-alpine`），pgvector 镜像是 Debian 基底（`pgvector/pgvector:pg17`），两者 locale/ICU 布局不同。已有数据卷直接换镜像可能因 locale 不一致导致索引损坏或启动失败；切换前必须按上述流程做快照，失败时用快照恢复，不要在未备份的数据卷上来回切换镜像。PostgreSQL 主版本保持 17 不变。

关闭流程：把 env 改回 `CREWSCOPE_PGVECTOR_ENABLED=false` 后 `up`。关闭只停用检索，**不换回镜像**：quickstart 一旦叠加过 pgvector 叠层就会在 `.runtime/pgvector-installed` 留下标记并持续叠加（安装状态与检索开关分离，ADR-030 §5）——数据卷上已安装的扩展引用的 .so 只在 Debian 基底镜像里存在，换回 Alpine 会使向量链在启动校验时失败。已安装的向量表与 `flyway_vector_history` 会保留，应用每次启动仍会校验并升级该链（这是有意设计：装过就不静默漂移）；要彻底移除需恢复到启用前的快照，或 `reset`（连卷一起删）后重新初始化。嵌入向量可随时由知识条目内容重建，属可再生数据；`knowledge_entry`/`knowledge_entry_version` 是权威数据，不可丢。

健康检查：启用后 Actuator `/actuator/health` 出现 `knowledgeVector` 组件——迁移已应用为 UP；若为 DOWN 会带原因（例如镜像未随开关更换），按原因排查后再重启。

### 开关组合与降级

M10 的九个功能开关（上表 pgvector 至 reporting-zone）相互独立、默认全关；每种组合都有定义好的行为，不存在「开关半开导致隐式报错」的形态。组合语义速查（M10 计划 §10.7 必测组合的运行手册面）：

| 组合 | 行为 |
| --- | --- |
| 全部关闭 | 纯 PostgreSQL 栈：知识条目 CRUD 照常可用；检索/注入以显式降级码应答；Skill 读 200、写 422；记忆只读可清除；观测空结构 200 |
| 索引开、检索关 | 索引作业照常构建与治理；检索预览返回 200 + 降级码（`RETRIEVAL_DISABLED` 类），不是错误 |
| 索引关、检索开（有历史有效代） | 既有 ACTIVE 代继续服务检索；无代则降级为「无活跃索引」 |
| 记忆关、知识开 | 检索与知识库正常；辅助记忆停止新写入，既有条目可查看/清除 |
| 检索关、Team Skill 开 | Skill 目录完整可用（发布/审批/回滚），不依赖检索 |
| 已安装 pgvector 后关闭增强再升级 | pgvector 镜像与 `flyway_vector_history` 保留（粘性标记），仅功能关闭；在其上执行 `git pull → build → up` 升级安全 |
| 索引开、pgvector 关 | **唯一非法组合**：health `knowledgeVector`/`knowledgeIndex` DOWN，先启用 pgvector 再开索引 |

降级与失败的边界：检索类降级（开关关、无代、embedding 失败）一律 200 + 显式降级码，绝不让执行失败；知识作业失败 fail-closed 落 FAILED 带失败码（见「作业故障恢复」）；权限拒绝永远是 403/404，不与降级混同。注入预算（总 8K/知识 3K/仓库 4K/记忆 1K，硬上限 32K）在任何组合下不会被降级绕过。

### 实时协作通道（WebSocket 在场）

M11-I01c 起内置 Web 支持 WebSocket 升级（Upgrade/Connection 透传随 web 镜像交付，无需额外 Nginx 配置）。通道默认关闭：关闭时 `/api/v1/collaboration/ws` 返回 404，在场/正在输入显式不可用，而权威更新与恢复继续走既有的 Team Activity SSE 流——这不是故障，是 ADR-032 定义的降级形态。

启用：在 `.runtime/.env` 写入 `CREWSCOPE_COLLABORATION_REALTIME_ENABLED=true` 后 `up`。容量上限（超过即拒绝新连接）：单节点软预算 200 连接、硬上限 500；每账号 16 连接（多标签页合计）；每连接 32 个订阅。在场数据只存 Redis 且带 TTL（45s，心跳续期），断连与进程重启都会自然清理，不影响业务数据。

演练与基线复跑：`scripts/m11-i01c-real-stack-gate.sh` 会起一套隔离的验证栈（不碰本部署），跑真实浏览器 e2e、200 连接风暴/稳态/重连/慢客户端（1013）与订阅泄漏断言；基线数字与解释见 `docs/testing/M11-I01c-实时通道部署与降级e2e.md`。慢客户端演练需要把 `CREWSCOPE_COLLABORATION_REALTIME_HEARTBEAT_INTERVAL` 压到 `1s` 加速观察，演练完改回 `15s`。

## 状态、日志与停止

```bash
./deploy/team-beta/quickstart.sh status
./deploy/team-beta/quickstart.sh logs
./deploy/team-beta/quickstart.sh config
./deploy/team-beta/quickstart.sh compose logs --tail 100 api
./deploy/team-beta/quickstart.sh down
```

`config` 校验配置且不输出密钥。原生 Compose 命令统一通过 `quickstart.sh compose …` 执行，自动带入正确的 env 文件、项目名和 Compose 路径。`compose config` 的完整输出包含密钥，排查时优先使用 `config`。

`down` 保留所有数据。仅清空测试环境时使用 `reset`：它删除当前项目的 PostgreSQL、Redis、Artifact 和四类 Agent 数据卷，保留配置及执行仓库/Worktree。它不是完整的数据擦除命令。

## 作业故障恢复

知识索引作业仅在同时启用 pgvector 与知识索引（`crewscope.knowledge.vector.enabled=true` + `crewscope.knowledge.index.enabled=true`）后产生；作业查询面恒可用（含关闭态的历史数据）。以下 curl 以 `$TOKEN`（API Token）与 `{org}`/`{team}`（组织/Team id）占位。

1. **先看健康与作业列表**：`/actuator/health` 的 `knowledgeIndex`/`knowledgeVector` 组件（DOWN 会带 reason）；`GET /api/v1/organizations/{org}/teams/{team}/knowledge/index/jobs?status=FAILED`（可加 `&source=REPOSITORY` 只看仓库作业）；`GET …/jobs/{jobId}` 看详情的 `failureCode`/`attempt`/`chunksDone`。

2. **FAILED 重试**：条目类→`POST …/knowledge/index/rebuilds`（幂等坍缩到活作业，可安全重复）；仓库类→修复根因后 `POST …/knowledge/index/repository-builds`（体 `{projectId, bindingId, commit}`）。失败码速查：`REPOSITORY_UNAVAILABLE`/`REPOSITORY_READ_FAILED`→查受管 mirror 与 GitHub 导入状态；`MODEL_DRIFT`/模型连接类→查模型连接健康与 embedding 治理链；`CHUNK_TOO_LARGE`/`CHUNK_LIMIT_EXCEEDED`→修内容或调 `max-file-bytes`（注意改分片值即换策略哈希=新 Generation，属预期行为）。

3. **卡死作业**：租约默认 30 分钟（`CREWSCOPE_KNOWLEDGE_INDEX_WORKER_LEASE`，5s–1h），过期由 claim 自动重领，`claimToken` 单调递增使旧 Worker 写显式失败（日志/遥测可见 FENCED）——**不要重启数据库或手改作业行**；checkpoint 保证重领后从批尾续传，不重复嵌入。

4. **取消排队作业**：`POST …/knowledge/index/jobs/{jobId}/cancel`；200=CANCELLED（重复调用幂等）；409=已被领取或已终态（分批短事务设计使中断安全）；404=不存在或跨租户同形。

5. **开关矩阵排障**：触发命令返回 `enqueued:0` 是 refresh 闸关闭的跳过，不是故障；`index=true` 而 `vector=false` 是唯一非法组合（health DOWN），先启用 pgvector 再开索引。

6. **升级/模型/策略变更=新 Generation**：换 embedding 模型或改分片旋钮→索引键变→新 Generation 构建成功后单事务原子激活、保留 2 代、失败绝不影响 ACTIVE 代。向量数据可再生（重新触发构建即可），权威数据随数据库备份（见下节「备份与恢复」）。

## 备份与恢复

建议在无活动任务时做停机备份。完整恢复需要 **全部项目数据卷、执行目录和同一份 env 密钥**。旧版 `operations/` 和 systemd 脚本针对旧七/十服务合同，不能直接用于本版。

以下命令在仓库根目录执行；备份目录应受保护，完成后复制到其他存储。先停止容器，再归档数据，避免 PostgreSQL、Redis 和文件状态不一致：

```bash
./deploy/team-beta/snapshot.sh backup /absolute/path/to/new-backup
```

恢复到同一主机、相同仓库路径的空项目：

```bash
./deploy/team-beta/snapshot.sh restore /absolute/path/to/new-backup
./deploy/team-beta/quickstart.sh up
```

恢复脚本拒绝覆盖已有 env、执行数据或项目卷。不要直接换个目录恢复：跨路径迁移涉及 Artifact URI、Git Worktree 绝对路径，当前简单快照限定同路径恢复；应先保留原环境的独立完整备份，另行确认清空目标的操作，或在具有相同路径的空主机恢复。跨主机时还需保持 Docker 项目名、PostgreSQL 主版本及兼容应用版本。快照是未加密的冷备份，必须用受保护的存储保管。

备份和恢复结束后均保持停机，不自动重启；失败时也保持当前停止状态并保留现场。只有已生成且校验通过的 `SHA256SUMS` 才表示快照完整。恢复中途失败会留下部分目标数据，脚本会拒绝直接覆盖重跑；保留快照后先核对并清理本次失败恢复创建的目标，再重试。校验和仅用于检测损坏，不验证备份来源，勿恢复不可信归档。修改配置时先将实际值持久化到 env，备份不收录临时 shell 覆盖值。

自定义项目时，通过 `CREWSCOPE_QUICKSTART_PROJECT_NAME` 和 `CREWSCOPE_QUICKSTART_RUNTIME_ROOT` 指定同一组值调用所有命令，避免误操作别的项目。

## 从上一版迁移

- 上一版四服务部署：保留原 `.runtime/.env` 和数据卷；新版初始化补充 Task Token、登录防护 HMAC 和执行目录配置，原有加密密钥不会改变。启动时修复之前创建的 root-owned Agent 卷根目录。
- 升级到 M10（含 Q01 收口）：迁移链自动前移到当前 tip（普通 PostgreSQL 链与向量链分别校验）。曾启用过 pgvector 的栈在关闭全部增强开关后升级也是安全的——pgvector 镜像与 `flyway_vector_history` 随粘性标记保留，只有功能关闭；升级后按需在 `.runtime/.env` 重新打开各开关。M10 新增的九个功能开关（见「配置」表）在旧 env 中缺失时按关闭默认值生效，`init` 不会替你打开它们。
- 曾使用独立 `local-demo` 项目：显式设置 `CREWSCOPE_LOCAL_DEMO_PROJECT_NAME=crewscope-local-demo` 和原运行目录再调用 `deploy/local-demo.sh`；默认别名现在与 Team Beta 共用项目，切换别名不会自动迁移旧数据。
- 旧七/十服务部署：先完整备份旧数据库、Redis、Artifact 和外部密钥。旧挂载路径与密钥格式不同，不能直接用新随机配置启动原数据库；应单独进行数据和密钥迁移，历史恢复材料见 [M6-I10](../testing/M6-I10-Team-Beta备份恢复与Runbook.md)。
- 若第一次启动曾报 `monitoring_password: unbound variable`：旧脚本可能留下只有两个字段的 `.env`。新版会明确报缺失字段；无业务数据时将该文件移走后重新 `init`，已有数据时从备份恢复原密钥，不能随意重新生成。

## 常见问题

- **端口占用**：修改 env 中的 `CREWSCOPE_WEB_PORT`，执行 `up`。
- **无法连接 Docker**：确认本机 Docker 正常、Unix Socket 路径正确；不支持从远程 Docker Context 直接挂载本机执行目录。
- **API 未就绪**：检查数据库/Redis 健康状态及 API 日志。Worker 与 API 同进程，工作目录和 Docker 连通性也会被检查。
- **模型/仓库不可用**：在 Setup Center 检查模型凭据、项目仓库和构建配置；简化部署保留这些业务授权与就绪检查。
- **配置检查通过但功能报错**：合同检查只验证配置。完整本机运行验证使用 `./scripts/m8-q02-local-runtime-gate.sh`，它用独立数据启动、验证并清理测试服务。
