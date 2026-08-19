# M4-I06 受控 CodingFilesystemTool

## 1. 交付结果

M4-I06 在 M4-I04 的独占 external Sandbox 调用窗口上交付受控代码文件变更会话：

- `CodingFilesystemToolFactory` 复验 Sandbox、Workspace、Worktree、WorkspacePolicy、Lease 与 Fencing 后打开 Session；
- `CodingFilesystemSession` 持有单次 `TaskExecutionSandboxCall`、AgentScope `SandboxBackedFilesystem` 和受控 Tool；
- `CodingFilesystemTool` 暴露 `coding_create/edit/patch/move/delete` 五个显式写 Tool；
- `CodingFilesystemPathPolicy` 统一执行仓库相对 canonical path、AllowedPaths、敏感路径、文件类型、UTF-8、符号链接和大小写碰撞检查；
- `CodingFilesystemUsageRegistry` 以 `ExecutionWorkspaceKey` 共享同 Worker 进程内的累计写入预算；
- `CodingFilesystemConfiguration` 只在 `all/worker` Profile 装配，纯 `server` Profile 不创建宿主写入 Bean。

原生 `FilesystemTool`、raw Shell、目录递归移动和目录递归删除均未进入 Toolkit。

## 2. AgentScope 复用边界

```text
CodingFilesystemToolFactory.open(...)
  -> ManagedTaskExecutionSandbox.openCall(...)
  -> SandboxBackedFilesystem.setSandbox(guarded external Sandbox)
  -> CodingFilesystemTool
       create -> AbstractFilesystem.write
       edit   -> CrewScope 精确替换 + AbstractFilesystem.uploadFiles
       patch  -> CrewScope 单文件 unified hunk + AbstractFilesystem.uploadFiles
       move   -> AbstractFilesystem.move
       delete -> AbstractFilesystem.delete
```

CrewScope 负责 Workspace 身份、路径授权、内容语义、预算、TOCTOU 和安全错误；AgentScope 负责将固定文件操作委托给当前 Docker Sandbox。AgentScope 2.0.0 的 Sandbox `edit` 依赖镜像内 `python3`，固定 Maven Sandbox 镜像没有该依赖，因此 edit/patch 在宿主完成确定性文本计算后使用 AgentScope 原生 `uploadFiles` 写入。Agent 不接触 upload 接口，也不能提供容器路径或命令文本。

## 3. Tool 契约

| Tool | 输入 | 语义 |
|---|---|---|
| `coding_create` | `path/content` | 只创建不存在的 UTF-8 普通文件 |
| `coding_edit` | `path/old_text/new_text/replace_all` | 对现有文本执行唯一或全量精确替换 |
| `coding_patch` | `path/patch` | 对显式目标应用有界、无文件头的单文件 unified hunks |
| `coding_move` | `source/destination` | 在 AllowedPaths 内移动一个 UTF-8 普通文件 |
| `coding_delete` | `path` | 删除一个普通文件 |

Patch 内容不能选择目标文件，不接受 `---/+++` 文件头。所有 Tool 都是非只读能力；M4-I11 只按固定名称白名单装配。

## 4. 路径与内容边界

每次调用按以下顺序失败关闭：

1. 复验 Session、Workspace、Lease 和 Fencing；
2. 拒绝绝对路径、反斜杠、空段、`.`、`..`、NUL、控制字符和非 canonical 表达；
3. 复验 AllowedPaths，并拒绝 `.git`、环境文件、凭证目录与密钥扩展名；
4. 逐段使用 `NOFOLLOW_LINKS` 拒绝符号链接；
5. 检查同目录大小写歧义 sibling；
6. create 要求目标不存在，edit/patch/move/delete 要求源为普通文件；
7. edit/patch/move 只接受严格 UTF-8 文本并拒绝 NUL；
8. 变更前再次核对既有 path component 的文件身份与首个缺失组件；
9. AgentScope 操作后从宿主 Worktree 复验结果。

异常只包含稳定 `CodingFilesystemError` 和安全摘要，不包含宿主路径、原始文件内容或 Sandbox 输出。

## 5. Workspace 预算

有效单文件上限取 `WorkspaceOperationBudget.maxSingleFileBytes` 与部署 `max-tool-content-bytes` 的较小值。每次成功进入写窗口前保守预留：

- `maxChangedFiles`：Workspace 内出现过的不同路径数；move 同时计算源与目标；
- `maxWriteOperations`：create/edit/patch/move/delete 每次计一项；
- `maxWrittenBytes`：create/edit/patch 按完整结果 UTF-8 字节数累计；
- `maxPatchHunks`：单次 patch 的 parser ceiling。

同一 Worker 中重复打开 Session 不重置预算。首次打开时通过类型化 Git status 将已有未提交路径及普通文件大小保守纳入初始用量。预算预留后发生的 TOCTOU 或底层失败仍占用额度，避免失败重试绕过上限。Git 状态只能重建已变更路径和当前普通文件字节，无法还原历史写操作次数与多次覆盖产生的累计字节；M4-A03 在生产 Tool 主链路启用前持久化并恢复精确计数。

## 6. 验证覆盖

- `CodingFilesystemToolM4I06Test`
  - 精确五个 Tool 名称与非只读元数据；
  - create/edit/patch/move/delete 完整链路；
  - traversal、绝对路径、反斜杠、AllowedPaths、敏感路径、符号链接与大小写碰撞；
  - 单文件、累计字节、写操作数和变更文件数预算；
  - stale/malformed patch、目录操作与失效上下文；
  - parent swap TOCTOU，AllowedPaths 外实际写入为 0。
- `CodingFilesystemConfigurationTest`
  - Worker 装配、Server 退让与非法 ceiling 启动失败。
- `TaskExecutionSandboxFactoryM4I04DockerIntegrationTest`
  - 在真实 AgentScope Docker Sandbox 中执行五种操作；
  - 宿主 Worktree 结果一致；
  - Session 关闭后旧 Tool 返回 `INVALID_CONTEXT`；
  - 重开 Session 后累计操作数从 5 延续到 6；
  - 测试结束后精确销毁容器。

专项命令：

```bash
./mvnw -pl crewscope-infrastructure \
  -Dtest=CodingFilesystemToolM4I06Test,CodingFilesystemConfigurationTest,TaskExecutionSandboxFactoryM4I04DockerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

收口结果：

- `./mvnw test`：全 Reactor 7 个模块通过，`1286` 个测试，Failures/Errors/Skipped 均为 `0`；
- `node scripts/check-doc-links.mjs`：`161` 份 Markdown 文档链接检查通过；
- `node evaluation/m4/coding-v1/scripts/evaluate.mjs validate`：12 个任务、2 个 Track 的冻结评测清单有效；
- `git diff --check`：通过；
- M4-I06 Docker 测试结束后无 `agentscope-sandbox-crewscope-*` 容器残留。

## 7. 后续边界

M4-I07 在同一 Sandbox、WorkspacePolicy 与 Lease/Fencing 边界上实现结构化命令执行和 CommandEvidence。文件 Tool 不获得命令执行能力；命令 Tool 不获得任意 argv 或 raw Shell。M4-I10 完成物理资源与 Diff 的启动恢复，M4-I11 将只读仓库检查、受控写入和结构化命令能力组合进固定 Coding Specialist Toolkit，M4-A03 在正式接通 Worker 主链路时补齐文件写预算的耐久精确计数。
