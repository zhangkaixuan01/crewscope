# M5-Q01 安全硬化与固定攻击集

> 状态：已完成<br>
> 日期：2026-08-25<br>
> 范围：模型与凭证、AgentTemplate 与 Agent 配置、Reviewer、成员 Gate、Confirmation、GitHub Provider/Webhook、Coding Artifact、服务端公开 DTO 与 Web 状态

## 1. 安全结论

M5 使用服务端 Owner/Scope 事实、不可变 Template/Configuration/Policy Snapshot、精确 Review 证据、双层人工 Gate、同源 GitHub 访问、HMAC Webhook 和公开投影白名单闭合授权。固定攻击集共 84 项，全部被阻断，阻断率 100%。

本阶段审查发现并修正两项边界缺口：

1. 成员补充指令原样追加到 System Prompt，虽然不能改变实际 Tool/Skill/Schema，但可以伪造 Prompt 分区标签；现对 XML 元字符编码，并放入明确的 `member-supplied-instructions` 不可信分区；
2. 从数据库恢复的 Confirmation 在授权时校验 Bundle 与 Action Digest，但没有再次比较自身 Scope、确认人和 Audit 创建人；现恢复和每次授权同时闭合当前 Scope、人类 Owner、Audit、Bundle ID/Digest 与全部有序 Action Digest。

## 2. 固定攻击矩阵

| 前缀 | 数量 | 攻击面 | 固定变异 | 预期 |
|---|---:|---|---|---|
| `AS` | 8 | Agent Owner/Scope 与模型连接 | 外部 USER 主/Fallback、USER Key 注入 TEAM 主/Fallback、外部 Team、未批准 Skill、Tool/配置 Hash 伪造 | 全部拒绝 |
| `PT` | 6 | Prompt、Tool、Skill 与 Runtime Identity | Prompt 闭合标签、指令请求 Tool、Tool/Skill 扩权、晚绑定 Toolkit、外部 Agent Session | 全部拒绝或保持原权限 |
| `RV` | 6 | Reviewer Finding | Diff Artifact、Manifest、TestEvidence、Acceptance、Hunk 和 Reviewer Actor 伪造 | 全部拒绝 |
| `GD` | 7 | Human Gate Decision | Agent Gate、缺失 Assignment、Owner 自审批、陈旧 ETag、未完成 Review、陈旧 Context、覆盖终态 | 全部拒绝 |
| `CF` | 7 | Action Confirmation | 非 Owner、外部 Scope、外部确认人、Bundle/Action Digest、过期与撤销确认 | 全部拒绝 |
| `SS` | 8 | GitHub SSRF | Metadata、未授权 Loopback、IPv6 Loopback、file Scheme、嵌入凭证、Path、Query、Fragment | 全部拒绝 |
| `WH` | 8 | GitHub Webhook | 零签名、缺失前缀、大小写、Body 替换、事件/动作、Repository/PR 身份漂移 | 全部拒绝 |
| `AR` | 9 | Coding Artifact | Organization、Team、Workspace、Project、Task、Execution 和请求 attempt 坐标替换 | 全部拒绝且不读取 Blob |
| `LK` | 25 | 公开 DTO 泄漏 | Model、Agent、Review、GitHub、Action/Receipt/ExternalResult 响应字段探针 | 敏感字段命中数 0 |
| **合计** | **84** |  |  | **84 / 84 阻断** |

## 3. 授权与信任边界

### 3.1 模型与 Agent

- USER Connection 只允许 Owner 的 PERSONAL 执行使用；TEAM 执行只接受 TEAM/ORGANIZATION Connection 或 Team Default；
- AgentConfiguration 恢复校验精确 Template、Ownership、Configuration Hash、Tool、Skill、Schema 和模型 Binding；
- 成员补充指令位于平台 System Prompt 基线之后，XML 元字符编码防止关闭或伪造不可信分区；
- AgentScope 构建后再次比较启用 Tool 与 Toolkit，禁止晚绑定 Tool 扩权。

### 3.2 Review 与外部动作

- Finding 只能由当前 Reviewer Specialist 对当前 ContextPackage 写入，证据必须落在当前 Diff Hunk、TestEvidence 和 Acceptance；
- Agent Finding 永远是 `ADVISORY`，Gate Decision 只接受当前 Team 的 ACTIVE USER Reviewer，并复验职责分离和强 ETag；
- Review Approval 与 Action Confirmation 是两层独立权限，Confirmation 只授权一个当前 Bundle 和全部有序 Action Digest；
- GitHub 读取只接受配置的原点，分页必须同源；Webhook 先验证 HMAC，再验证事件类型、Connection、Repository 和 Pull Request 身份。

### 3.3 Artifact 与披露

- Artifact 访问先闭合 Organization、Team、Workspace、WorkProject、Task、Execution 与业务用途，再解析有界 Range 和读取 Blob；
- 模型凭证只在 CredentialStore 安全回调和瞬时命令输入中存在；公开 DTO 泄漏探针同时拒绝标准化后的精确敏感字段名与组合敏感别名，异常、Web Store、Story 和快照不包含 Secret、Endpoint、Token、Host Path、Lease/Fencing 或原始 Provider Body；
- Action Receipt 只公开外部身份 Hash、稳定结果和证据码，不公开原始外部对象身份或观察载荷。

## 4. 验证命令

```bash
cd /Users/zhangkaixuan/codes/crewscope-java
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application,crewscope-agentscope,crewscope-infrastructure,crewscope-server \
  -am test \
  -Dtest='AgentConfigurationVersionTest,AgentManagementApplicationServiceM5A02Test,AgentConfigurationApplicationServiceM5A03Test,AgentExecutionConfigurationResolverTest,ModelConnectionApplicationServiceM5A01Test,ModelConnectionCredentialBoundaryTest,AgentTemplateRuntimeRegistryM5I05Test,ReviewFindingTest,ReviewDecisionTest,ReviewRepositoryTenantBoundaryTest,ReviewFindingBatchRecorderM5I06Test,ReviewGateApplicationServiceM5A05Test,ActionBundleTest,ActionDeliveryTest,ActionDeliveryApplicationServiceM5A07Test,CodingArtifactAccessServiceM4Q01Test,GitHubProviderAdapterM5I08Test,GitHubPullRequestWebhookAdapterM5I10Test,GitAskPassSessionM5I09Test,M5PublicProjectionFixedAttackSetM5Q01Test,AgentManagementControllerM5A02Test,AgentConfigurationControllerM5A03Test,ModelManagementControllerM5A01Test,ReviewControllerM5A05Test,GitHubConnectionControllerM5A06Test,ActionDeliveryControllerM5A07Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw --batch-mode --no-transfer-progress clean verify
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm run check:sensitive
cd ..
node scripts/check-doc-links.mjs
git diff --check
```

## 5. 验收记录

- 固定攻击集 `84 / 84` 被阻断，阻断率 100%；
- M5-Q01 关联安全回归 `197 / 197` 通过，Failures、Errors 和 Skipped 均为 0；
- M5-Q04 干净 Maven 全量回归 `1862 / 1862` 通过，Failures、Errors 和 Skipped 均为 0；
- 服务端公开投影 25 项固定泄漏探针命中数为 0；
- Web 敏感字段门禁覆盖 20 个生产文件和 8 个 Story，命中数为 0；
- 后端全量、前端 Coverage/Build、文档链接与格式门禁通过。

下一任务为 `M5-Q02`，执行模型、凭证、成员、Reviewer、Diff、GitHub、Receipt、Webhook 和 Worker 固定故障恢复与对账矩阵。
