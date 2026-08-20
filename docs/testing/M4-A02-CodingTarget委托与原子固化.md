# M4-A02 CodingTarget 委托与原子固化

## 1. 交付范围

M4-A02 扩展 M3-A01 的统一 Task 委托命令。Control Mode 的 WorkItem 表单与 Conversation Mode 的确认动作继续调用同一个 `POST .../work-items/{workItemId}/tasks`，两种入口不建立各自的 Coding Task 创建流程。

`codingTarget` 是可选请求对象。省略时创建兼容的非 Coding Task；提交时包含：

- `repositoryBindingId`；
- 用户确认的短 `baselineRef`；
- 1–200 个仓库相对 canonical `allowedPaths`；
- `BuildProfile` 的 Key、Version 与 canonical SHA-256；
- TaskBrief 中的目标与有序验收条件。

Conversation 来源仍通过 `conversationSource` 指向一个已授权 Message。它只改变 `TaskSource` 和 `ConversationTaskLink`，不改变 CodingTargetSnapshot 的事实模型。

WorkItem 可见成员还可以调用：

- `GET .../work-items/{workItemId}/coding-target/build-profiles` 获取部署批准的 Profile Key、Version、Hash、BuildTool、Java Release 和 CommandKind；
- `POST .../work-items/{workItemId}/coding-target/preflight` 对选中的 ACTIVE Binding 与任意安全短 Ref 执行创建前检查。

公开 Profile DTO 不包含 Sandbox Image、CommandCatalog、typed argv、工作目录或环境事实。

## 2. 服务端顺序

应用服务在 WorkItem 责任锁和同一个数据库事务内执行：

```text
Idempotency Reservation
  -> WorkItem Scope、Version、Owner/Executor 责任复验
  -> AgentProfile、Agent Principal、ProviderBinding 复验
  -> 可选 Conversation Message 读取授权复验
  -> RepositoryBinding Organization/Team/Workspace/WorkProject 与 ACTIVE 复验
  -> BuildProfile Key/Version/Hash 精确匹配
  -> Baseline Preflight 解析完整 Commit，并复验 RepositoryKey 与 Ref 回显
  -> Task
  -> 可选 CodingTargetSnapshot revision 1
  -> TaskExecution、PolicySnapshot、SafetyOverlay
  -> READY、Task ACTIVE、可选 ConversationTaskLink
  -> DomainEvent、Task/Conversation Event、Outbox、CommandReceipt
```

Snapshot 在首个 TaskExecution 创建前固化，因此领域模型可证明 Task 仍为 `CREATED` 且尚未关联 attempt。后续 Ref 移动、Binding 默认分支变化或停用不会改写已保存的 Binding Version 和完整 Commit。任一步骤失败都回滚 Task、Snapshot、attempt、策略、事件、Outbox 与 Receipt。

## 3. 安全与幂等

- Repository 查询显式携带 Organization、Team 和 WorkProject，并再次闭合 Workspace；跨 Team、Workspace 或 WorkProject 的 ID 按无效目标拒绝；
- DISABLED Binding 不能创建新 Snapshot；
- Preflight 不可用返回可重试的安全错误，仓库、Ref 或 Git 失败只返回稳定分类，不携带宿主路径和原始 Git 输出；
- AllowedPaths 拒绝绝对路径、反斜杠、空段、`.`/`..` 组件、Windows Drive、NUL 和控制字符，并折叠重复父子根；
- BuildProfile 只接受部署冻结 Catalog 中完全匹配的 Key、Version、Hash，不能自动前移版本；
- CommandRequestHash 纳入 Coding/非 Coding 标识、Binding ID、Ref、canonical AllowedPaths 和完整 BuildProfile Reference；同一个 `Idempotency-Key` 改变任何目标事实都会冲突，不会复用旧 Receipt；
- DTO、异常、日志和 Snapshot 都不包含 Managed Repository Root 或宿主绝对路径。

## 4. M4 初始 BuildProfile

服务端注册冻结的 `maven-java-17` version 1，使用摘要固定的 M4 Maven/Java 17 Sandbox 镜像以及 `COMPILE`、`TEST`、`VERIFY` typed-argv 命令槽。Profile 内容改变时新增版本，历史 Snapshot 继续按精确引用恢复执行语义。

M4-F03 将以服务端可选项公开 Profile Reference，浏览器不自行计算 Profile Hash，也不能提交镜像、argv、工作目录或环境变量。

## 5. 自动化验证

专项测试覆盖：

- 非 Coding M3 请求保持兼容；
- 表单来源与 Conversation Message 来源固化相同结构的 CodingTargetSnapshot；
- Snapshot 先于首个 TaskExecution、PolicySnapshot 和 READY 发布；
- Binding 完整 Scope、ACTIVE 状态与精确 BuildProfile；
- Ref Preflight 失败、AllowedPaths traversal 和不完整 HTTP DTO；
- CodingTarget 全字段进入幂等请求 Hash；
- BuildProfile 相同 Key/Version 不能对应两份不同内容。
- BuildProfile 选项和 Ref Preflight 先复验当前 WorkItem 可见性，并保持公开 DTO 白名单。

验证命令：

```bash
./mvnw -pl crewscope-application,crewscope-server -am \
  -Dtest=AgentTaskCreationServiceM3A01Test,ImmutableBuildProfileCatalogM4A02Test,CodingTargetSelectionServiceM4A02Test,TaskControllerTest,CodingTargetControllerM4A02Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw clean verify
```

最终验证结果：

- M4-A02 专项测试 `27 / 27` 通过；
- 全仓 `clean verify` 共执行 `1376` 项测试，Failures、Errors 和 Skipped 均为 `0`；
- `169` 份 Markdown 文档链接检查通过，`git diff --check` 通过；
- Testcontainers 与 `io.crewscope.sandbox.managed=true` 受管 Sandbox 容器均无残留。
