# M7-I07 Bootstrap Operator 账号升级

## 1. 完成范围

M7-I07 将旧 Bootstrap 管理身份升级为开放用户体系中的持久化 Operator：

- 启动引导只精确识别目标 Organization 内的 `bootstrap/crewscope-monitor` Principal；
- 既有 Principal 原位复用，Principal、TeamMember 和 Audit 历史 ID 保持不变；
- 无候选 Principal 时创建 ACTIVE、Organization Scope、ORGANIZATION 可见的 USER Principal；
- Account、Local LoginIdentity、LocalCredential 与 AccountOrganizationBinding 在同一 REQUIRED 事务内创建；
- `UserAccount.bootstrapOperator(...)` 是唯一创建 `PlatformRole.OPERATOR` 的领域入口，自助注册仍固定为 USER；
- API 进程可显式启用启动引导，Worker 进程保持关闭。

非 Secret 坐标使用 `crewscope.security.operator-bootstrap.*` 配置。密码继续来自外部 `crewscope.security.bootstrap.password`，不复制到另一套配置、环境变量或持久化明文列。显式启用但缺少 Organization 或密码时，应用启动失败关闭。

## 2. 幂等、并发与密码轮换

引导事务首先对目标 Organization 行执行 `FOR UPDATE`，使多个 API 实例的并发启动串行收敛。首次启动创建一条完整身份链；重复启动复用同一 Account、LoginIdentity、Binding 和 Principal，不追加重复记录。

密码处理分为三种结果：

- Secret 与当前编码均匹配：`UNCHANGED`，不写库；
- Secret 匹配但编码需要升级：`REHASHED`，只推进 Credential Version，不撤销已有 Session；
- Secret 改变：`ROTATED`，以 Credential 乐观版本和 Credential Version 执行 CAS 轮换，同时推进 Account SecurityVersion，使旧 Session 失效。

既有 Binding 指向普通 USER Account、Account/Identity/Binding 被禁用、Principal 类型或 Scope 错误、外部身份不精确匹配、用户名或邮箱冲突时，整笔事务回滚。已建立的 Operator Account 必须与当前配置的用户名、规范邮箱和展示名一致；这些坐标发生漂移时启动失败关闭，不把部署配置当作账号资料修改通道，也不旋转 Credential。并发 Credential 修改导致 CAS 失败时不覆盖较新的密码。

## 3. 授权与浏览器边界

请求身份解析不再把旧 Bootstrap Basic 的 `ROLE_ADMIN` 映射为平台管理员。旧 Bootstrap/OIDC Principal 只保留无 Provision 的只读兼容解析；平台 Operator 权限仅来自本次读取的持久化 `PlatformRole.OPERATOR`。

Bootstrap Basic 仍接受客户端显式提交的 Authorization Header，便于迁移期运维 API 调用。未认证 Web 请求统一返回 401，响应不携带 `WWW-Authenticate`，因此浏览器不会弹出原生 Basic 登录框。

Bootstrap 命令、结果和异常的字符串表示不含密码。密码编码、验证和持久化异常均移除 Secret-bearing cause，对外只暴露固定失败信息 `Bootstrap Operator provisioning could not safely converge`。

## 4. 验证结果

专项测试执行：

```bash
./mvnw -pl crewscope-infrastructure -am \
  -Dtest=BootstrapOperatorProvisioningM7I07IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl crewscope-server -am \
  -Dtest=BootstrapOperatorConfigurationTest,SecurityConfigurationTest,RepositoryTeamRequestIdentityResolverTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项结果：

- infrastructure：9 / 9，使用真实 PostgreSQL 17 与 Redis 7.4 Testcontainers；
- server：17 / 17；
- 合计：26 / 26。

覆盖真实 V1→V30 fixture 再升级 V30→V32、Principal/TeamMember/Audit ID 保持、既有与缺失 Principal、重复启动、Secret 轮换、SecurityVersion、非 Secret 配置漂移零写入、8 路并发收敛、错误 Principal、错误 Account Role、缺失启动配置、密码零回显、旧 `ROLE_ADMIN` 无提权和无 Basic Challenge。

随后联合回归 M7-I01、M7-I03、M7-I05 及 Team、Task、Provider 既有授权入口。计入准入执行器关闭/Provider 故障与 Rehash 场景后共 109 / 109 通过：application 35 / 35、infrastructure 28 / 28、server 46 / 46。

## 5. 后续

M7-I08 已分离 Operator 与 Prometheus 凭证及 SecurityWebFilterChain，并完成 Team Beta Session、Registration Mode、Cookie 安全 Guard、备份恢复、结构化认证日志和低基数指标。下一任务为 M7-A01。
