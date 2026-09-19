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

数据库密码、凭据加密、游标、邀请、Task Token 和登录防护 HMAC 密钥自动生成，重启不轮换。登录防护保持启用，不需要手动提供密钥。保管好 `.runtime/.env`：仅备份数据库而丢失加密密钥，不能恢复模型等已保存凭据。不要将运行目录提交 Git，也不要直接修改已有数据库密码来“重置密码”。

切换注册模式：

```bash
./deploy/team-beta/quickstart.sh set-registration-mode INVITE_ONLY
```

默认 Web 监听所有网卡，数据库和 Redis 不发布宿主端口。不强制 TLS、域名、镜像 Digest、外部 Secret、Prometheus、OTel 或 Socket Proxy。需要 HTTPS 时，在前面配置 Nginx/Caddy，转发至 8080 并传递正确的 Host、X-Forwarded-Proto；应用不会校验部署证书来源。API 具有本机 Docker 管理权限，部署在自己的单机或团队专用执行主机。

HTTP 下密码和会话不加密，公网使用仍建议 HTTPS。内置 Web 会覆盖来访者的 X-Forwarded-For，并清除标准 Forwarded 及端口/前缀等冲突转发头，防止伪造登录防护来源和请求地址；添加上游代理后，默认按代理地址限流。如需区分真实客户端，应由管理员限定可信代理后再配置 Nginx real_ip，不能直接信任公网提供的转发头。

运行目录和执行目录须使用专用绝对路径，不含 `.`/`..` 或重复斜线；脚本按实际物理路径检查，不允许通过符号链接指向主目录、仓库根或系统目录，也不接受符号链接 env 文件。不要同时编辑配置或并发执行初始化/升级/备份；初始化发布不会覆盖另一进程已生成的密钥，发生并发提示后重新运行即可。

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
- 曾使用独立 `local-demo` 项目：显式设置 `CREWSCOPE_LOCAL_DEMO_PROJECT_NAME=crewscope-local-demo` 和原运行目录再调用 `deploy/local-demo.sh`；默认别名现在与 Team Beta 共用项目，切换别名不会自动迁移旧数据。
- 旧七/十服务部署：先完整备份旧数据库、Redis、Artifact 和外部密钥。旧挂载路径与密钥格式不同，不能直接用新随机配置启动原数据库；应单独进行数据和密钥迁移，历史恢复材料见 [M6-I10](../testing/M6-I10-Team-Beta备份恢复与Runbook.md)。
- 若第一次启动曾报 `monitoring_password: unbound variable`：旧脚本可能留下只有两个字段的 `.env`。新版会明确报缺失字段；无业务数据时将该文件移走后重新 `init`，已有数据时从备份恢复原密钥，不能随意重新生成。

## 常见问题

- **端口占用**：修改 env 中的 `CREWSCOPE_WEB_PORT`，执行 `up`。
- **无法连接 Docker**：确认本机 Docker 正常、Unix Socket 路径正确；不支持从远程 Docker Context 直接挂载本机执行目录。
- **API 未就绪**：检查数据库/Redis 健康状态及 API 日志。Worker 与 API 同进程，工作目录和 Docker 连通性也会被检查。
- **模型/仓库不可用**：在 Setup Center 检查模型凭据、项目仓库和构建配置；简化部署保留这些业务授权与就绪检查。
- **配置检查通过但功能报错**：合同检查只验证配置。完整本机运行验证使用 `./scripts/m8-q02-local-runtime-gate.sh`，它用独立数据启动、验证并清理测试服务。
