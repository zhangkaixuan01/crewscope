# M4-Q01：Coding 执行安全硬化与固定攻击集

> 状态：已完成<br>
> 日期：2026-08-21<br>
> 范围：Repository、Workspace、Sandbox、Tool、Artifact、Coding API

## 1. 目标

M4-Q01 使用版本化固定攻击集验证 Coding 执行链路的安全边界。所有攻击在真实校验点失败关闭，并通过外部哨兵、命令副作用、容器事实、Artifact 读取次数和公开响应证明未产生副作用。

安全指标：

- AllowedPaths 外实际修改数为 `0`；
- 禁止命令实际执行数为 `0`；
- 未授权网络连接数为 `0`；
- Docker Socket、宿主 Home、凭证目录和额外 Volume 挂载数为 `0`；
- Task Token、凭证、宿主绝对路径、AgentState 和内部 Reasoning 公开泄漏数为 `0`；
- 固定攻击阻断率为 `100%`。

## 2. 固定攻击矩阵

| 边界 | 固定攻击 | 阻断与副作用证明 |
|---|---|---|
| Repository | RepositoryKey 路径穿越、Option Key、仓库符号链接、错误 Owner、非 bare 仓库、移动 Ref | Resolver 在 Git 与 Worktree 创建前拒绝；安全错误不包含 Managed Root |
| Workspace | 目录残留、冲突 Branch、错误 HEAD、Detached Branch、损坏 `.git`、Worktree 符号链接、跨 execution Policy | 不删除非本次创建的目录或 Branch；外部符号链接目标保持不变 |
| Sandbox | root 用户、可写根层、网络、额外挂载、Docker Socket、宿主凭证挂载、额外环境、旧 Fencing、过期 Lease、并发调用 | 容器契约要求普通用户、只读根层、`network=none`、单一 Worktree 挂载和环境变量白名单；旧句柄不能操作当前容器 |
| Tool | 读取/写入路径穿越、敏感文件、符号链接、大小写别名、TOCTOU 父目录替换、Git Pathspec、模块/测试选择器和 Shell 元字符注入 | Tool 调用前后复验路径与调用窗口；实际外部哨兵不变；命令使用固定类型和单引号 argv 编码 |
| Artifact | 跨 Organization/Team/Task/attempt/Evidence、关系元数据错配、未发布 Artifact、任意 Artifact ID、非法 Range、下载名与并发容量 | 授权与关系闭合先于内容打开；内容读取次数为 `0`；公开文件名和错误保持路径无关 |
| Disclosure | Task Token、Provider 凭证、宿主路径、原始 Tool 内容、AgentState、Reasoning 与日志控制字符 | Sandbox 不继承宿主环境；DTO 使用白名单；结构化日志按字段脱敏并移除注入字符 |

## 3. 本轮审查修复

Sandbox 恢复原有校验确认目标 Worktree 挂载和平台环境变量存在。本轮将完整容器契约收紧为：

- `Mounts` 只能包含一个读写 Bind Mount，Source 必须是当前 canonical Worktree，Destination 必须是 `/workspace/repository`；
- 容器环境变量名称集合必须精确等于受审 Java/Maven 镜像与 CrewScope 运行常量白名单；
- 重复环境变量、Docker Socket、宿主凭证目录、Named Volume、替换 Source/Destination 和宿主坐标环境全部拒绝；
- 恢复与幂等复用在容器启动前执行完整契约复验。

该约束使已存在容器不能通过“保留合法挂载并追加恶意挂载或环境”的方式进入 CrewScope 调用窗口。未来新增 Sandbox 镜像环境变量需要同时更新白名单和固定攻击集。

## 4. 自动化入口

固定安全门禁：

```bash
./scripts/m4-q01-security-gate.sh
```

脚本强制要求 Docker Daemon、固定 Digest 镜像、Node.js 24 和 pnpm 存在，并从 Domain、Application、AgentScope、Infrastructure、Server 与 Web 六层执行版本化攻击语料。缺失 Docker 或镜像直接失败，不将跳过的 Sandbox 攻击计为通过。Web 固定集覆盖 DTO 白名单、非法 Diff 投影、Artifact 关系入口、Range 完整性、跨 attempt 缓存隔离和 Catalog 失败关闭。

## 5. 验证结果

`./scripts/m4-q01-security-gate.sh` 在 M4-Q04 中按当前固定攻击集复验，结果如下：

- Java 专项 `157 / 157`：Domain 25、Application 17、AgentScope 5、Infrastructure 91、Server 19；
- Web 安全投影 `37 / 37`，覆盖 6 个测试文件；
- 其中 40 项使用稳定攻击编号逐坐标替换 Task Token、Artifact、Mount 与 Environment，其余专项测试验证攻击入口及真实副作用；
- 真实 Docker Sandbox `10 / 10`，无跳过；
- AllowedPaths 外实际修改、禁止命令执行、未授权网络连接和敏感挂载均为 `0`；
- 未授权 Artifact 内容读取次数为 `0`；
- Task Token、凭证、宿主绝对路径、AgentState 与 Reasoning 泄漏探针命中数为 `0`；
- 固定攻击阻断率为 `100%`。

总计 `194 / 194` 项安全门禁通过。M4-Q04 全量 `mvn clean verify` 的 7 个 Reactor 模块全部成功，`1517 / 1517` 项测试通过，失败、错误与跳过均为 `0`。M4-Q02 继续验证进程退出、Worktree 损坏、命令挂起、Artifact 写入中断和重复控制下的故障恢复。
