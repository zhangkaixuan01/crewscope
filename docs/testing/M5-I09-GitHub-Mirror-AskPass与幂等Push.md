# M5-I09 GitHub Mirror、AskPass 与幂等 Push

> 实现模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`
> 完成日期：2026-08-23

## 1. 交付范围

M5-I09 已交付 GitHub Branch 写入边界：

- 新增独立 `GitHubPushPort`，复用已确认动作的 `PushBranchActionParameters`、`ProviderAuthorizationReference` 与 `ActionTargetPrecondition`；
- 每次调用复验 ProviderBinding、有效权限 Hash、RepositoryBinding、Connection、Grant、Credential Secret Version 与 Repository Preflight；
- 按 Organization、固定 `github` Provider Key 和数字 GitHub Repository ID 派生受管 bare Mirror；
- Mirror 创建与重试均复验 Canonical Path、Symlink、Owner 和 bare Repository 形状；
- 从受管本地 Repository 复验 Baseline、Delivery Head 与祖先关系，再把完整 Delivery Object Graph 导入 Mirror；
- 从平台 HTTPS 基址和 Preflight Repository Full Name 构造无用户名、Token、Query 与 Fragment 的规范 Remote；
- 动作窗口创建 `0700` 目录、`0600` Secret 文件和 `0500` 无 Secret AskPass 程序；
- Git 进程使用最小环境，禁用 Terminal Prompt、系统/全局 Config、Pager、Hook、Credential Helper 和 HTTP Redirect；
- 查询远端完整 Branch Ref，同 Head 返回 `ALREADY_PRESENT`，不执行第二次 Push；
- 当前 Head 与 Expected Head 不一致返回 `REMOTE_HEAD_CONFLICT`；
- 当前 Head 不是 Delivery Head 祖先时返回 `NON_FAST_FORWARD`；
- Push 使用完整 SHA RefSpec 和精确 `--force-with-lease=<full-ref>:<expected-or-absent>`；
- Push 超时后重新读取远端 Head，等于 Delivery Head 时返回 `RECOVERED_AFTER_UNKNOWN`，其余结果保持 `UNKNOWN`；
- 远端保护策略拒绝归一化为 `PROTECTED_BRANCH`，公开错误不包含 Git 输出、Remote、路径或 Token。

Draft PR、Webhook 与 PR 外部结果对账由 M5-I10 实现；Dispatch、Receipt 与 Action Worker 编排由 M5-I11 实现。

## 2. 稳定执行坐标

Push 请求固定以下事实：

```text
organization_id
provider_binding_id + version + effective_access_hash
connection_id + version
connection_grant_id + version
repository_binding_id + version
github_repository_id
branch_full_ref
baseline_sha
delivery_head_sha
expected_remote_head_sha | ABSENT
```

浏览器、Agent 和模型不能提交 Mirror 路径、Remote URL、Git argv、AskPass 路径或 Secret 文件路径。Action Worker 后续只能从已确认 PlannedAction 和当前服务端权威对象构造 `PushGitHubBranchRequest`。

## 3. 凭证生命周期

`GitHubCredentialHandle` 在进入动作前重新解析当前 Secret Version。回调内的 Token Byte Buffer 只写入 Owner-only Secret 文件；Git argv、环境值、AskPass 程序、Git Config、异常与结果只包含非 Secret 坐标。动作正常结束、失败、超时和取消均经 `close` 清零文件内容并删除程序、Secret 与动作目录；清理失败返回路径无关的安全错误并要求 Worker 介入。

## 4. Push 协议

```text
Repository/Binding/Connection/Grant/Credential Preflight
  -> 本地 Baseline/Delivery/Ancestor 复验并导入 Mirror
  -> ls-remote 完整 Branch Ref
  -> Remote Head == Delivery Head：ALREADY_PRESENT
  -> Remote Head != Expected Head：REMOTE_HEAD_CONFLICT
  -> Fetch 当前 Branch Object + 再查 Head
  -> 非 Fast-forward：NON_FAST_FORWARD
  -> 完整 SHA RefSpec + 精确 force-with-lease Push
  -> Push Timeout：重新 ls-remote
  -> Head == Delivery Head：RECOVERED_AFTER_UNKNOWN
  -> 仍不可判定：UNKNOWN
```

`--force-with-lease` 只承担查询后到写入时的原子比较。Fast-forward 授权由 Push 前的显式祖先校验完成。

## 5. 自动化验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=GitCommandExecutorM5I09IntegrationTest,GitAskPassSessionM5I09Test,ManagedGitHubMirrorResolverM5I09Test,GitHubPushProtocolM5I09IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=GitHubPushApplicationConfigurationM5I09Test,GitHubProviderApplicationConfigurationM5I08Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖场景包括：

- 首次 Push、同 Branch/Head 重试和精确 Lease 冲突；
- 远端 Head 漂移与 Non-fast-forward 不覆盖；
- 保护分支风格拒绝的稳定分类和敏感输出丢弃；
- Push 已完成但客户端超时后的 Head 查询恢复；
- Git 命令超时、输出限制和固定 argv 模板；
- Mirror 稳定路径、重复解析、数字 Repository ID、Owner 与 bare 校验；
- AskPass 文件权限、程序无 Secret、环境无 Token 和正常清理；
- Worker 存在受管 Repository 边界时装配 Push Port，无 Worker 边界时不暴露写能力；
- 带凭证或非 HTTPS Git Origin 配置失败关闭。

## 6. 结论

M5-I09 已形成从已确认动作到 GitHub Branch 的安全、幂等、可对账写入边界。M5-I10 可以只依赖成功 Push 的精确 Branch/Head 结果实现唯一 Draft PR 和 Webhook 对账。
