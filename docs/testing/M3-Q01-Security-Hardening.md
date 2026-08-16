# M3-Q01 安全硬化与固定攻击集

> 状态：已完成<br>
> 日期：2026-08-16<br>
> 范围：Task/Worker API、Task Token、AgentScope Task Runtime、Task Event、Runtime Artifact、日志与依赖供应链

## 1. 安全结论

M3 采用服务端事实闭合、最小范围 Task Token、每请求撤权复验、Prompt 信任分区、有限执行预算和公开数据白名单。Worker 请求只携带一次性 Bearer Task Token；Organization、Team、Task、attempt、Lease、Runtime、Worker、执行 Principal、Policy、Safety、ProviderBinding、Tool 和资源范围均由服务端解析。

本阶段补齐以下安全门禁：

1. Task Token 签发、轮换、认证和 Tool 使用都回查当前 Executor ResponsibilityAssignment；
2. USER Executor 回查当前 TeamMember，Agent Executor 回查当前 Owner Principal 与 Owner TeamMember；
3. 固定攻击集覆盖完整租户、任务、执行、租约、Provider 和资源坐标；
4. Worker Body、进度摘要、失败码、Task Event、日志和 Artifact 元数据保持有限白名单；
5. CI 使用依赖漏洞扫描，High/Critical 漏洞阻断 Release Gate。

## 2. 授权事实链

Task Token 签发、轮换、认证和 Tool 使用按以下顺序复验当前授权事实：

1. 验证 JWT 签名、`issuer`、`audience`、`kid`、`subject`、JTI、签发时间和过期时间；
2. 通过 `OrganizationId + JTI Hash` 加载当前 TaskCredentialGrant；
3. 校验 Grant ID、Environment 和完整 Scope Fingerprint；
4. 回查当前 ExecutionLease，校验 execution、attempt、Runtime、Worker、Claim Hash、Fencing 和有效期；
5. 回查当前 TaskExecution，校验 Organization、Team、Workspace、WorkProject、Task、attempt、Fencing、Policy 和 Safety Overlay；
6. 回查当前 Executor ResponsibilityAssignment，校验 ACTIVE、EXECUTOR、Scope、Principal、Assignment ID 和版本；
7. 回查当前执行 Principal，并只接受 USER/Agent Executor；USER 同时回查 Assignment 指向的 ACTIVE TeamMember；Agent 同时回查当前 Organization 内的 ACTIVE USER Owner Principal 与 Owner 在当前 Team 的 ACTIVE TeamMember；
8. Provider Tool 使用额外回查 ProviderBinding 与可选 ConnectionGrant 的状态、版本、有效期、Capability 和资源。

任一事实缺失、过期、撤销、换版或不一致时统一关闭授权。签发前已失效的责任或成员事实不会产生 Grant；旧 Token 在下一次轮换、认证或 Tool 使用时失效。

## 3. 固定攻击集

固定攻击集使用表驱动测试，所有样本都必须被拒绝。分母保持固定，新增授权坐标时同步扩展攻击集。

| ID | 攻击面 | 变异事实 | 预期 |
|---|---|---|---|
| TK-01 | Organization | 替换 OrganizationId | 拒绝 |
| TK-02 | Team | 替换 TeamId | 拒绝 |
| TK-03 | Workspace | 替换 WorkspaceId | 拒绝 |
| TK-04 | WorkProject | 替换 WorkProjectId | 拒绝 |
| TK-05 | Task | 替换 TaskId | 拒绝 |
| TK-06 | TaskExecution | 替换 TaskExecutionId | 拒绝 |
| TK-07 | attempt | 替换 attempt | 拒绝 |
| TK-08 | Lease | 替换 ExecutionLeaseId | 拒绝 |
| TK-09 | Runtime | 替换 ExecutionRuntimeId | 拒绝 |
| TK-10 | Worker | 替换 RuntimeWorkerId | 拒绝 |
| TK-11 | Claim | 替换 ClaimTokenHash | 拒绝 |
| TK-12 | Fencing | 替换 FencingToken | 拒绝 |
| TK-13 | Principal | 替换 subject 或停用 Principal | 拒绝 |
| TK-14 | Responsibility | 释放、换版或替换 Executor Assignment | 拒绝 |
| TK-15 | TeamMember | 暂停、离开或移除执行成员/Agent Owner | 拒绝 |
| TK-16 | ProviderBinding | 替换、停用或换版 Binding | 拒绝 |
| TK-17 | ConnectionGrant | 撤销、过期或换版 Grant | 拒绝 |
| TK-18 | Tool | 请求 Token 范围外 Tool | 拒绝 |
| TK-19 | Capability | 请求授权范围外 Capability | 拒绝 |
| TK-20 | Resource | 请求授权范围外资源 | 拒绝 |
| WK-01 | Worker Route | 路由 execution 与 Token 不一致 | 拒绝 |
| WK-02 | Worker Header | 注入 Organization/Task/Lease/Runtime/Worker/Claim/Fencing Header | 拒绝 |
| WK-03 | Worker Body | 注入服务端身份字段或未知字段 | 拒绝 |
| WK-04 | Worker Version | 缺失、溢出或伪造强版本 | 拒绝 |
| WK-05 | Worker Credential | Basic、Session、重复 Authorization 或非法 Bearer | 拒绝 |

验收指标：`25 / 25` 样本被阻断，阻断率 `100%`。

## 4. Prompt 信任分区

Task Agent 的 System Prompt 来自版本化 AgentProfile。Task Brief 的 Objective 与 Acceptance Criteria 属于不可信业务数据，使用明确的数据标签并完成 XML 元字符转义。RuntimeContext、Task Token、Policy、Safety、Principal、Lease、ProviderBinding 和 Tool 集合只由服务端构造。

AgentScope Task Runtime 只注册 `fixture.*` 与受控计划工具。每次 Tool 事件再次校验 Task Token Scope 和当前 PlanStep。外部执行请求以固定安全错误终止。

## 5. 资源与并发预算

| 边界 | 门禁 |
|---|---|
| Task Token | 5 秒至 15 分钟、受当前 Lease 到期时间约束、最大 16 KiB |
| Provider 授权 | 每个 Token 最多 200 个 ProviderBinding |
| PolicyBudget | 最大 Token、模型调用、Tool 调用和总执行时长均为正数并固定在 PolicySnapshot |
| Runtime | 每个模型调用、Tool 调用和累计 Token 都实时计数；总时长使用超时与恢复后的剩余预算 |
| Worker Progress | `safeSummary` 1–1000 个可打印字符，进度为 0–100 |
| Worker Failure | 固定 FailureClass，FailureCode 为 1–64 位大写稳定码 |
| HTTP Decode | 单个请求的内存缓冲上限为 256 KiB |
| Worker 并发 | Team、Runtime、Worker 活动 Lease 配额与数据库原子 Claim |
| Task Event | 有限批次、有限历史页、单订阅流和连续 Cursor |
| Artifact | 声明大小、SHA-256、Scope、可见性、保留期和有界清理批次 |

## 6. 披露规则

| 出口 | 公开内容 | 受保护内容 |
|---|---|---|
| Structured Log | 有界安全字段、Correlation ID、稳定错误码 | Authorization、Cookie、Token、Credential、Prompt、Reasoning、Tool 输入输出、Ciphertext |
| Task Event/SSE | 显式事件类型和字段白名单、安全文本、有限 Usage 与稳定 Failure | Task/Claim/Interrupt Token、AgentState、Reasoning、Tool 参数/结果、Provider 原始请求与错误 |
| AG-UI | 文本消息、匿名化 Tool 生命周期、固定 Run Error | Reasoning、State、Snapshot、Tool Args、Tool 原始 Result、Custom Event |
| Runtime Artifact | Artifact ID、类型、大小、Hash、Scope、保留期 | Artifact 内容、存储凭证、签名 URL、AgentState JSON |
| Web State | Task、Plan、Step、Run、Lease 和恢复安全投影 | Token、Claim Hash、Fencing 内部值、原始 State、Reasoning、Provider 密钥 |

验收指标：固定泄漏探针在日志、Task Event、AG-UI、Artifact 投影和 Web DTO 中的命中数为 `0`。

## 7. 依赖安全门禁

CI 使用 OSV-Scanner `v2.5.0` 递归扫描 Maven 与 pnpm 依赖清单，并使用 pnpm Audit 复核生产 Web 依赖。OSV 已知漏洞与 pnpm High/Critical 漏洞直接阻断 `release-gate`。漏洞库不可用时任务失败。

本地补充检查：

```bash
cd /Users/zhangkaixuan/codes/crewscope-java/crewscope-web
pnpm audit --prod --audit-level=high --registry=https://registry.npmjs.org
```

## 8. 验证命令

```bash
cd /Users/zhangkaixuan/codes/crewscope-java
./mvnw -pl crewscope-domain,crewscope-application,crewscope-agentscope,crewscope-infrastructure,crewscope-server -am test \
  -Dtest='*M3Q01*,DurableTaskToken*Test,TaskTokenWebFilterTest,WorkerTaskCommandControllerM3A03Test,TaskPublicEventMapperM3A05Test,StructuredLogSanitizerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw clean verify
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm audit --prod --audit-level=high --registry=https://registry.npmjs.org
cd ..
node scripts/check-doc-links.mjs
git diff --check
```

## 9. 验收记录

全量回归首次运行发现 `TaskTokenSecurityConfigurationTest` 的精简上下文未提供本阶段新增的
Task、ResponsibilityAssignment 与 TeamMember 仓储替身，导致安全配置装配测试失败。生产配置的
依赖链保持完整；测试夹具必须显式覆盖当前授权复验器的全部依赖，再重新执行全量回归。

实现级审查发现授权复验器曾依赖 ResponsibilityAssignment 聚合的不变式间接排除 SERVICE
Executor，并且没有自行确认 Agent Owner 的 USER 类型与 Organization Scope。安全边界改为显式
拒绝非 USER/Agent Executor，并完整校验 Agent Owner 的类型、状态、Organization 与 TeamMember。
测试身份链使用 SERVICE 签发者、Agent Executor 与 USER Owner 三个独立 Principal。

最终验收结果：

- M3-Q01 专项测试 `50 / 50` 通过；固定 Task Token 与 Worker 攻击样本 `25 / 25`
  被阻断，阻断率 `100%`；
- 责任、成员、Agent Owner、ProviderBinding 与 ConnectionGrant 撤权均在下一次认证或使用时失效；
- Prompt、Task Event、AG-UI、日志与 Artifact 固定泄漏探针命中数为 `0`；
- `./mvnw clean verify` 共 `1082` 项测试通过，失败、错误与跳过均为 `0`；
- Web Vitest `180 / 180`、Playwright 桌面/窄屏 `102 / 102` 通过，应用构建与组件工作台
  `5` 个 Story、`20` 个 Variant 构建通过；
- Web 生产依赖 High/Critical Audit 返回 `No known vulnerabilities found`；OSV-Scanner
  reusable workflow 使用 `v2.5.0` 对应的固定提交 SHA，并与 Web Audit 一同进入 Release Gate；
- `142` 份 Markdown 文档链接、`git diff --check` 均通过。
