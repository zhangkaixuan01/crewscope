# M7-I02 Spring Session Redis 会话边界

> 任务：`M7-I02`<br>
> 日期：2026-08-28<br>
> 状态：完成<br>
> 运行基线：Spring Boot 4.0.6、Spring Session 4.0.3、Redis 7.4

## 1. 交付目标

M7-I02 将浏览器认证 Session 接入生产依赖，并提供注册与登录共用的会话生命周期边界：

- `BrowserSessionLifecycle.establish` 建立 Session、旋转 ID、执行账号级并发 Session 裁决并保存 SecurityContext；
- `BrowserSessionLifecycle.invalidateCurrent` 只失效当前浏览器 Session；
- `BrowserSessionPrincipal` 只保存 Account UUID 与 SecurityVersion；
- `BrowserSessionConfiguration` 显式装配 Indexed Redis Repository、SecurityContext Repository、并发 Session Registry 与受控序列化器；
- Bootstrap Basic 使用无状态 SecurityContext 和无状态 RequestCache，不创建浏览器认证 Cookie；
- OIDC 浏览器链缺少 Session Repository 时启动失败关闭，后续本地登录链复用同一 Repository。

## 2. 配置与装配边界

Spring Boot 4 的 Redis Session starter 会按 classpath 无条件尝试装配。CrewScope 排除该自动装配，由以下开关显式启用浏览器 Session：

```text
CREWSCOPE_BROWSER_SESSION_ENABLED=true
CREWSCOPE_REDIS_URL=redis://...
CREWSCOPE_SESSION_NAMESPACE=crewscope:session
CREWSCOPE_SESSION_TTL=12h
CREWSCOPE_MAXIMUM_SESSIONS=5
```

Bootstrap 和 Worker 当前保持 `CREWSCOPE_BROWSER_SESSION_ENABLED=false`。本地账号或 OIDC 浏览器认证必须启用该开关；启用后 Redis 不可用会阻止 Repository 初始化或请求完成，不回退到客户端身份、内存 Session 或 Bootstrap 用户。

配置 Guard 冻结以下契约：

```text
Repository Type  indexed
Save Mode        on-set-attribute
Namespace        crewscope:session
TTL              1 秒至 7 天，默认 12 小时
Session 上限     1 至 20，默认每账号 5 个
淘汰策略         最久未使用 Session
Cookie           CREWSCOPE_SESSION / HttpOnly / Path=/ / SameSite=Lax
```

Namespace 必须为小写、显式隔离且不能使用 `spring:session`。配置只接受 Spring Boot 4 的 `spring.session.data.redis.*`，旧的 `spring.session.redis.namespace` 会导致启动失败。生产 HTTPS 强制 Secure Cookie 由 M7-I08 完成。

## 3. Session 内容与序列化

Redis Session 仅保存：

```text
BrowserSessionPrincipal(Account UUID, SecurityVersion)
固定 ROLE_* Authority
Spring SecurityContext
Spring Session 元数据
```

认证 Token 的 Credentials 固定为 `null`。Jackson 3 多态反序列化只允许 Java 集合/时间、Spring Security 和 CrewScope browser-session 类型及其数组；领域聚合与 `SecurityVersion` 领域对象不能从 Session Payload 反序列化。密码、密码 Hash、LocalCredential、Organization、Team 和完整领域聚合不进入 Session。

## 4. 生命周期与失败语义

建立顺序固定为：

```text
start Session
  -> changeSessionId
  -> 从 Indexed Redis 查询账号现有 Session
  -> 超限时淘汰最久未使用 Session
  -> 注册当前 Session
  -> 保存 credential-free SecurityContext
  -> WebSession Filter 持久化并提交 Cookie
```

Redis 相关的旋转和并发裁决在 SecurityContext 附着前执行。最终 Session 保存失败会使请求失败，不能向浏览器提交可恢复的认证状态。退出删除当前服务端 Session；过期后同一 Cookie 只能得到匿名状态。

## 5. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=BrowserSessionConfigurationM7I02Test,BrowserSessionLifecycleM7I02IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

配置与真实 Redis 共 8 个测试通过：

1. Boot 4 Indexed Namespace、TTL 和边界配置 Guard；
2. SecurityContext 白名单序列化与领域对象拒绝；
3. 匿名 Session 建立、登录旋转、跨请求恢复、续期与退出；
4. Redis Payload 不含密码或密码 Hash；
5. 两个独立 Repository 实例共享 Session；
6. Repository 重建后恢复既有 Session，模拟进程重启；
7. 每账号上限按最久未使用顺序淘汰；
8. Session 过期和 Redis 不可用均失败关闭。

联合回归额外覆盖 `LocalSessionSecurityM7S01IntegrationTest`、`SecurityConfigurationTest` 和 `ActuatorAuthorizationM6I08Test`，证明 JSON 登录 Spike 继续通过、OIDC 装配强制 Session Repository，且 Bootstrap Basic 不返回 Session Cookie。

## 6. 后续边界

- M7-I03 实现 LocalCredentialStore、参数化密码校验与安全 Rehash；
- M7-A01 与 M7-A02 在事务提交和认证成功后复用 `BrowserSessionLifecycle`；
- M7-A03 依据 SecurityVersion 实现全部 Session 撤销；
- M7-I08 为本地/HTTPS 部署启用浏览器 Session，并强制 Secure Cookie 与运维链分离。
