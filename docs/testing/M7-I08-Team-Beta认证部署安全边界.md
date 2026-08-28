# M7-I08 Team Beta 认证部署安全边界

## 1. 完成范围

M7-I08 将 M7 的账号、Session、登录防护、邀请与 Operator 升级能力纳入 Team Beta 正式部署：

- 生产 API 启用 Browser Session、登录防护、邀请 Token 和 Bootstrap Operator 引导，Worker 精确关闭四项浏览器认证能力；
- 正式部署默认 `INVITE_ONLY`、HTTPS 与 Secure Cookie，Demo 使用 `OPEN`、受控本地入口与显式非 Secure Cookie；
- 正式 8C16G Profile 使用 ADR-025 冻结的 4 个 Hash Permit，Demo 使用 2 个；
- Cookie 固定为 `CREWSCOPE_SESSION / HttpOnly / Path=/ / SameSite=Lax`；
- 登录防护与邀请 Token 使用两个独立的 256-bit Base64 HMAC Secret，只有 API 挂载；
- `RegistrationMode` 只接受 `OPEN / INVITE_ONLY / DISABLED`；
- Team Beta 启动 Guard 在 Readiness 前复核角色、传输、可信代理、Cookie、认证开关、Secret 强度和注册模式。

本地开发配置仍可显式关闭浏览器 Session 与防护；`team-beta` Profile 使用完整的失败关闭约束。

## 2. Operator 与监控隔离

Spring Security 使用两条有序过滤链：

```text
Order 0  /actuator/prometheus  -> 独立 Monitoring User/Encoder/AuthenticationManager
Order 1  其余路径              -> Bootstrap/OIDC 应用认证链
```

Prometheus 只挂载 `monitoring_password`，用户名固定为 `crewscope-prometheus`。Operator 的 `bootstrap_password` 不能抓取 Prometheus；Monitoring 凭证不能访问业务 API。监控链无 Session、无 Request Cache、无 CSRF，只在精确抓取路径返回 Basic Challenge。业务 Bootstrap 链继续对普通匿名 Web 请求返回无 `WWW-Authenticate` 的 401。

启动 Guard 使用常量时间字节比较拒绝相同的 Operator/Monitoring 用户名和密码，并要求 Monitoring 密码至少 24 字符。Demo 首次准备时分别生成随机 Secret，正式 Secret 权限脚本将文件设为 `root:10001/0440`；Prometheus 仅获得固定只读组和自己的挂载项。

## 3. 可观测性与敏感字段

认证防护指标 `crewscope.authentication.defense.operations` 由独立 `AuthenticationMetricPolicy` 管理，坐标闭合为：

```text
flow       2: login | registration
operation  4: resource_admission | account_observe | account_failure | account_success
outcome    8: allowed | identifier_limited | network_limited | both_limited |
              unlocked | locked | cleared | unavailable
```

理论上限为 `2 × 4 × 8 = 64` 条序列。未知认证指标、未知枚举值、额外标签，以及用户名、邮箱、网络地址、Session ID、Redis Key 等身份维度均被 MeterFilter 拒绝。该预算独立于 M6 的 2,000 条自定义指标预算。

结构化日志全局过滤器新增 `username`、`loginIdentifier`、`networkAddress`、`sessionId` 和 `passwordHash` 精确脱敏；CSRF Token、邀请 Token 与其他 `*token` 字段继续由通用后缀规则脱敏。认证 Secret、身份、网络桶和异常正文不会进入日志值。

## 4. Compose、恢复与配置合同

生产与 Demo Compose 均解析为七服务拓扑。Web 在固定 `172.30.0.10/32` 后端地址代理 API，登录防护只信任该直接代理；Nginx 仅接受显式 `https` 的 `X-Forwarded-Proto`，其余值回退到入口 Scheme。Actuator 不通过 Web 公开。Compose 同时冻结正式/Demo 的 Hash Permit 为 4/2，并由应用启动校验其不超过容器可用 CPU。

备份兼容范围升级为 `V26..V32 -> V32`。恢复继续在 Worker/Web 关闭时启动 API 执行 Flyway，再验证 Readiness、System Info、Artifact 与零活动执行。V30 真实数据夹具已实际执行 V31/V32，确认 Flyway 收敛到 32，并保留既有 Principal、TeamMember 与 Audit 身份，同时建立 `user_account`、`login_identity`、`account_organization_binding` 和 `team_invitation` 表。

M6-I10 的 V26/V30 历史恢复结果作为当时 V30 镜像的发布证据保持不变；当前运维手册和自动化合同使用 V32 边界。

## 5. 验证结果

执行：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=RegistrationConfigurationTest,AuthenticationMetricPolicyM7I08Test,LoginDefenseBoundaryM7I04Test,TeamBetaOperationalTelemetryM6I08Test,SecurityConfigurationTest,ActuatorAuthorizationM6I08Test,TeamBetaDeploymentGuardM6I09Test \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl crewscope-infrastructure -am \
  -Dtest=M7I03LocalCredentialSecurityIntegrationTest,BootstrapOperatorProvisioningM7I07IntegrationTest,V32TeamInvitationMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

node scripts/check-team-beta-deployment.mjs
node scripts/check-team-beta-recovery.mjs
sh -n deploy/team-beta/demo.sh deploy/team-beta/operations/*.sh
```

结果：

- server 注册、安全、指标与日志专项：27 / 27；
- infrastructure 密码准入、Bootstrap Operator 与 V30→V32/V32 迁移专项：27 / 27；
- 正式/Demo Compose 七服务、认证角色、健康检查、Secret 挂载和未跟踪 Secret 扫描：通过；
- 加密包、Artifact、V26–V32、保留策略和 Runbook 恢复合同：通过；
- Demo 与运维 Shell 语法：通过。

## 6. 后续

M7-I01 至 M7-I08 已完成。下一任务为 `M7-A01`，实现按 Registration Mode 裁决的本地账号注册事务与邀请注册闭环。
