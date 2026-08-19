# M4-I05 受控 RepositoryInspectionTool

## 1. 交付结果

M4-I05 在 M4-I04 的独占 external Sandbox 调用窗口上交付只读仓库检查会话：

- `RepositoryInspectionToolFactory` 复验 Sandbox、Workspace、Worktree、Repository、WorkspacePolicy、Lease 与 Fencing 后打开会话；
- `RepositoryInspectionSession` 持有单次 `TaskExecutionSandboxCall`、AgentScope `SandboxBackedFilesystem` 和受控 Tool；
- `RepositoryInspectionTool` 暴露 8 个 `readOnly=true` Tool；
- `RepositoryInspectionPathGuard` 统一执行 canonical path、AllowedPaths、敏感路径和逐段符号链接检查；
- `GitCommandExecutor` 增加仅供检查使用的 pathspec 受限 history、status 与 text diff 模板；
- `RepositoryInspectionConfiguration` 只在 `all/worker` Profile 装配，纯 `server` Profile 不创建宿主检查 Bean。

实现不注册 AgentScope 原生 `FilesystemTool`，因此写入、编辑、删除、移动、上传、下载和 raw Shell 不会随只读能力进入 Toolkit。

## 2. AgentScope 复用边界

```text
RepositoryInspectionToolFactory.open(...)
  -> ManagedTaskExecutionSandbox.openCall(...)
  -> TaskExecutionSandboxCall.requireCurrent()
  -> SandboxBackedFilesystem.setSandbox(guarded external Sandbox)
  -> RepositoryInspectionTool
       -> tree/list/read/grep/glob -> AgentScope AbstractFilesystem
       -> history/status/diff     -> CrewScope typed GitCommandExecutor
```

AgentScope 2.0.0 的 `AbstractFilesystem` 没有独立 tree 方法。`repository_tree` 使用有界广度遍历组合多次 `ls`，仍由 `SandboxBackedFilesystem` 执行每一级读取，并同时受最大深度、后台操作次数、分页和输出字节限制。

## 3. Tool 契约

| Tool | 输入边界 | 实现 |
|---|---|---|
| `repository_tree` | path、depth、offset、limit | 有界组合 AgentScope `ls` |
| `repository_list` | path、offset、limit | AgentScope `ls` |
| `repository_read` | path、line offset、limit | AgentScope `read` |
| `repository_grep` | literal pattern、path、filename glob、offset、limit | AgentScope literal `grep` |
| `repository_glob` | filename glob、path、offset、limit | AgentScope `glob` |
| `repository_git_history` | offset、limit | typed Git log + AllowedPaths pathspec |
| `repository_git_status` | offset、limit | typed NUL porcelain status + AllowedPaths pathspec |
| `repository_git_diff` | line offset、limit | typed text diff + AllowedPaths pathspec |

8 个方法均以 AgentScope `@Tool(readOnly = true)` 注册。AgentScope Plan Mode 可直接使用 Tool 元数据保留这些读取能力；Toolkit 中不存在 `write_file`、`edit_file` 或 `execute`。

## 4. 每次调用复验

`TaskExecutionSandboxCall` 保存打开窗口时的 Workspace 与 ExecutionLease。每个 Tool 方法在访问 AgentScope 文件系统或宿主 Git 前调用 `requireCurrent()`，重新验证：

- 调用窗口尚未关闭；
- 当前句柄仍是 Sandbox 唯一活动调用；
- Workspace ID、TaskExecution、attempt、Workspace Key、Fingerprint 与 Ownership 完整一致；
- Lease ID、Runtime、Worker、TaskExecution、attempt 与 Fencing 仍闭合；
- 权威 Worker 时钟下 Lease 仍有效。

Git history/status/diff 不经过容器内 `Sandbox.exec`，因此同样先执行上述复验。会话关闭后清空 `SandboxBackedFilesystem` 的 external Sandbox 并关闭调用窗口，旧 Tool 引用继续调用会得到 `INVALID_CONTEXT`。

## 5. 路径与内容安全

所有模型输入路径使用仓库相对 canonical 形式。入口拒绝绝对路径、Windows Drive、反斜杠、空段、`.`/`..` 组件、NUL 与控制字符，并要求路径位于 WorkspacePolicy `AllowedPathSet`。宿主 Worktree 从根到目标逐段使用 `NOFOLLOW_LINKS` 检查，任一现有符号链接都拒绝。

以下内容不向检查 Tool 返回：

- `.git`；
- `.env` 与 `.env.*`；
- `.ssh`、`.aws`、`.gnupg`、`.docker`；
- `.npmrc`、`.pypirc`；
- `id_rsa`、`id_ed25519`、`credentials`、`credentials.json`；
- `.pem`、`.key`、`.p12`、`.pfx`、`.jks`、`.keystore`。

list/tree/grep/glob 在投影结果时再次执行路径检查。Git 三项把 AllowedPaths 编码为 `top,literal` pathspec，仓库根 `.` 使用平台固定的全仓 glob，再与固定敏感路径 exclude pathspec 一起传给参数数组；以 `:` 开头的合法仓库路径不会被 Git 重新解释为 pathspec magic。异常只返回稳定分类和安全摘要，不包含 canonical Host Path 或原始 Git 输出。

`repository_read` 只接受 AgentScope 返回的 `utf-8` 编码，并对文本再次探测 NUL 和非法控制字符。扩展名已被 AgentScope 识别为二进制的文件以及隐藏二进制文本都返回 `BINARY_FILE`。Git diff 不使用 `--binary`，因此不会返回 binary patch payload。

## 6. 分页与预算

默认配置为：

```yaml
crewscope:
  coding:
    inspection:
      max-page-size: 200
      max-read-lines: 500
      max-tree-depth: 6
      max-backend-operations: 64
      max-pattern-length: 256
      max-result-bytes: 65536
```

list、grep、glob、history、status 和 diff 使用 `offset + limit`；read 使用行 offset；tree 同时使用 depth、offset 和 limit。返回头固定包含 `returned`、`offset`、`limit`、`hasMore` 与 `nextOffset`。实际输出上限取部署配置与 `WorkspacePolicy.sandboxBudget.maxCommandOutputBytes` 的较小值，并在 UTF-8 字符边界内收口。tree 的后台 `ls` 次数独立受 `max-backend-operations` 限制；预算不足以生成当前完整页面时返回 `TRAVERSAL_LIMIT`，调用方改为检查更窄的路径或深度，不返回无法推进的分页游标。

## 7. 验证范围

自动化验证覆盖：

- 8 个 Tool 的名称集合与 `readOnly=true` 元数据；
- 原生 write/edit/raw execute Tool 不存在；
- AgentScope Docker 中真实 list/tree/read/grep/glob；
- 行分页、集合分页、`hasMore/nextOffset` 与 UTF-8 字节上限；
- tree 后台操作预算耗尽时稳定失败，不产生无法推进的 `nextOffset`；
- AllowedPaths、绝对/遍历路径、敏感文件和符号链接；
- 扩展名二进制与隐藏 NUL 二进制；
- Git history 分页、status/diff pathspec 隔离和敏感 pathspec 排除；
- 以 `:` 开头的 AllowedPath 按真实文件名匹配，不能扩大为 Git pathspec magic；
- diff 不产生 `GIT binary patch`；
- 会话关闭后的旧 Tool 失效；
- Worker 装配、Server 退让和非法配置失败关闭。

专项测试类：

- `RepositoryInspectionToolM4I05Test`
- `RepositoryInspectionConfigurationTest`
- `GitCommandExecutorM4I01IntegrationTest` 的 M4-I05 检查场景
- `TaskExecutionSandboxFactoryM4I04DockerIntegrationTest` 的真实 AgentScope 文件系统场景

修正后验收命令与结果：

- `./mvnw test`：全 Reactor 7 个模块通过，`1275` 个测试，Failures/Errors/Skipped 均为 `0`；
- `node scripts/check-doc-links.mjs`：`160` 份 Markdown 文档链接检查通过；
- `git diff --check`：通过；
- M4-I05 Docker 测试结束后无 `agentscope-sandbox-crewscope-*` 容器残留。

## 8. 后续边界

M4-I06 已在独立 Session 中交付 [受控 CodingFilesystemTool](M4-I06-受控CodingFilesystemTool.md)。M4-I05 的只读 Tool 元数据和 Toolkit 边界保持不变；最终由 M4-I11 `CodingSpecialistFactory` 按固定 Tool 白名单分别装配只读、写入和结构化命令能力。
