# M1-D06：ReviewerEligibilityPolicy

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立 Gate Reviewer 的主体资格和职责分离规则。默认策略要求 Gate Reviewer 与同一 WorkItem 的 Active Owner、Executor 分离。单人团队只能通过显式 PolicyPack 覆盖进行本人 Gate Review。

## Reviewer 效力

| 主体 | 效力 | 规则 |
|---|---|---|
| Active TeamMember 对应的 USER Principal | `GATE` | 可以控制后续状态迁移，必须通过 ReviewerEligibilityPolicy |
| Active SPECIALIST_AGENT Principal | `ADVISORY` | 只产生 Finding 和建议，不产生 Gate Decision |

`ResponsibilityAssignment.role=REVIEWER` 保存统一责任事实。M1 中的决策效力由 Actor 主体类型确定，USER Reviewer 是 Gate Reviewer，SPECIALIST_AGENT Reviewer 是 Advisory Reviewer。Personal Agent 和 Team Agent 不能担任 Reviewer。

## 默认职责分离

Gate Reviewer 必须满足：

- Reviewer Principal 为同 Organization 的 Active USER；
- Reviewer TeamMember 与 Principal 匹配，属于 WorkItem Team 且为 Active；
- Reviewer 不是当前 Active Owner；
- Reviewer 不是任何当前 Active Executor；
- RELEASED 的 Owner 和 Executor 不影响当前资格；
- Active ResponsibilityAssignment 和 TeamMember 查询结果必须属于当前 WorkItem Scope。

当 USER 直接担任 Owner/Executor 时，使用 Principal ID 和 TeamMember ID 识别职责重合。Agent Executor 是独立执行主体；Agent 所有者、委托链和实际控制人的扩展职责分离由后续 Policy Engine 基于 PolicySnapshot 判定。

## 单人团队覆盖

`ReviewerEligibilityPolicy` 默认使用 `STRICT_SEPARATION`。显式覆盖必须携带：

```text
PolicyPackId
PolicyPack Version
Override Reason
```

覆盖只在以下条件同时满足时生效：

1. Team 当前恰好有一个 Active TeamMember；
2. Gate Reviewer 就是该唯一 Active TeamMember；
3. 存在 Owner 或 Executor 职责冲突；
4. PolicyPack 引用、版本和原因完整。

团队有两个或更多 Active TeamMember 时，覆盖不生效。没有职责冲突时按严格策略通过，不产生降级审计事实。

## 决策证据

策略通过后返回 `ReviewerEligibilityDecision`：

```text
mode                 STRICT_SEPARATION / SINGLE_MEMBER_OVERRIDE
conflicting_roles    OWNER / EXECUTOR
policy_pack          降级时必填
override_reason      降级时必填
```

Application Service 将决策与 Reviewer Assignment 一起返回。M1-A06 在同一 Command 事务中将 Assignment 创建、PolicyPack 引用、冲突角色和降级原因写入 DomainEvent/AuditEvent。

## 应用边界

- `assignGateReviewer` 必须获取 Team 成员和 WorkItem Active Assignment 的服务端事实，不接受客户端声明的冲突结果；
- `assignAdvisoryReviewer` 只接受 Specialist Agent，不使用 Gate 降级规则；
- Owner、Executor 和 Gate Reviewer 变更在事务内先获取 WorkItem 责任链锁；Owner/Executor 入口反向阻止 Active Gate Reviewer，避免通过变更其他角色绕过职责分离；
- 同一 WorkItem、Role 和 Actor 的重复 Active Assignment 继续由 D05 规则阻止；
- D06 不创建完整 PolicyPack Aggregate，只建立稳定 `PolicyPackReference` 和 Reviewer 策略值对象；
- D06 不修改 Flyway 迁移和 JPA Repository Adapter。D08 使用 WorkItem 行锁实现责任链锁 Port，使并发 Owner、Executor 和 Gate Reviewer 变更共享同一串行化边界。

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-application -am clean test
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

测试覆盖独立 Gate Reviewer、Owner 冲突、Executor 冲突、已释放责任、停用成员、跨 Scope 事实、Agent Gate 拒绝、Specialist Advisory Reviewer、单人团队显式覆盖、多人团队覆盖拒绝、PolicyPack 证据和应用层查询边界。

M1-D06 新增 19 个测试：

- Domain 12 个，覆盖严格分离、Owner/Executor 冲突、已释放历史、停用成员、Agent Gate 拒绝、Scope 失配、单人团队覆盖、多人团队拒绝和 PolicyPack 证据校验；
- Application 7 个，覆盖服务端事实查询、严格 Gate 分配、降级决策返回、停用/跨 Team 防护、重复 Reviewer、责任链锁和 Owner/Executor 反向绕过防护。

专项结果：Domain 124 个测试、Application 56 个测试通过，0 失败、0 错误、0 跳过。

全仓回归：7 个 Maven 模块全部构建成功，后端共 296 个测试通过，0 失败、0 错误、0 跳过。

## 后续

M1-D07 新增 `V6__team_work_and_responsibility.sql`，落地 Team、Workspace、AgentProfile、WorkProject、WorkItem 扩展和 ResponsibilityAssignment 表，增加唯一 Active Owner、唯一 Active Role/Actor、版本和查询索引。M1-D08 实现 Repository Adapter 及 WorkItem 责任链行锁。
