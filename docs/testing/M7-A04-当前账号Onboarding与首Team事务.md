# M7-A04 当前账号 Onboarding 与首 Team 事务

## 1. 交付范围

M7-A04 交付当前登录账号的 Onboarding 状态和首 Team 创建接口：

```text
GET  /api/v1/onboarding
POST /api/v1/onboarding/team
```

GET 从当前 USER Principal 的活动 Team Membership 派生两个闭合状态：没有活动 Team 时为 `TEAM_REQUIRED`，已有任意活动 Team 时为 `COMPLETE`。接口不接受 Account、Organization 或 Principal 坐标，只使用服务端 Session 解析结果。

POST 只接受 Team 名称，要求 `Idempotency-Key`，并返回标准 Command Receipt。相同 Key 和请求可恢复既有成功结果；Onboarding 已完成后使用新 Key 创建返回 `409 onboarding_already_complete`。

## 2. 事务与并发边界

`OnboardingApplicationService` 在最外层 REQUIRED 事务中按 Account ID 执行 `FOR UPDATE`，同时复验 Account 可认证状态和 Session SecurityVersion。`TeamApplicationService.createFirstTeam` 使用独立 `CREATE_FIRST_TEAM` Command Type，在同一事务中先解析该入口的 Command Receipt 重放，再检查当前 Principal 是否已有活动 Team Membership。普通 `CREATE_TEAM` 与首 Team 即使同 Actor、同名称、同 `Idempotency-Key` 也按冲突失败，不跨业务入口重放。

Spring `TransactionTemplate` 固定使用 `PROPAGATION_REQUIRED`，因此 Onboarding 外层与 M1 Team 命令内层共享同一数据库事务。Account 行锁保持到 Team 基础、DomainEvent、Outbox 和 Receipt 一起提交。两条不同幂等键的并发请求只有一条可以创建，另一条在获得锁后看到已提交 Membership 并稳定拒绝。

## 3. 基础完整性

创建路径复用 M1 `TeamCreationService`，在一个原子事务中建立：

- 1 个 Team 和 1 个默认 Workspace；
- 1 个 Owner TeamMember；
- 5 个内置 TeamRole 和 1 个 ACTIVE Owner Grant；
- 1 个默认 Personal Agent Principal/Profile；
- 默认 Provider 基础；
- 唯一 `TEAM_CREATED` DomainEvent、Outbox 与已完成 Command Receipt。

已有任意活动 Team 的账号直接返回 `COMPLETE`，不会为旧成员重复创建 Team。失效 Account、过期 SecurityVersion、非 USER Principal、Team Scope Principal 或被替换的命令 Principal 全部失败关闭。WebFlux Controller 通过 `boundedElastic` 执行阻塞身份与 PostgreSQL 操作，不占用 Event Loop。

## 4. 自动验证

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=OnboardingApplicationServiceM7A04Test,TeamApplicationServiceTest,\
OnboardingControllerM7A04Test,ApplicationCompositionConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl crewscope-infrastructure -am \
  -Dtest=M1JpaPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- Application 与 Server 专项通过，覆盖状态派生、Account/SecurityVersion 复验、上下文防替换、相同 Key 重放、普通/首 Team 跨入口同 Key 冲突、新 Key 冲突、HTTP DTO/错误和 Bean 唯一装配；
- PostgreSQL 17 双线程使用不同 Key 同时请求，最终只提交一套完整 Team 基础与一套发布事实；
- M1 PostgreSQL `23 / 23` 完整回归通过；
- Java 17 Release 编译通过，文档链接和 `git diff --check` 通过。
