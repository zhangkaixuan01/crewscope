# M7-S01 WebFlux Session 与 CSRF 验证记录

> 任务：`M7-S01`<br>
> 日期：2026-08-28<br>
> 结论：通过<br>
> 后续落点：`M7-I02`、`M7-A02`、`M7-F04`

## 1. 验证目标

M7-S01 验证 CrewScope 自有登录所需的最小浏览器认证闭环：

1. WebFlux Spring Security 接受 JSON 登录，不启用 Form Login 或业务入口 HTTP Basic；
2. Spring Session Data Redis 保存 WebSession 和 SecurityContext；
3. 登录成功旋转 Session ID，登录前旧 ID 不能恢复认证；
4. Cookie CSRF Token 通过同源 Vue 请求 Header 回传，缺失和错误 Token 均失败；
5. 两个浏览器上下文拥有独立 Cookie Jar、Session 和认证身份；
6. 退出只失效当前 Session，另一个用户不受影响；
7. Redis Session 使用受控 Namespace 和 TTL；
8. Web 与 API 对浏览器呈现一个 Origin，开发和生产均通过反向代理连接后端。

本 Spike 使用测试内存用户 `alice`、`bob` 代替正式账号数据。`UserAccount`、`LoginIdentity`、V31、密码策略和生产认证 API 不属于本任务，分别由 M7 后续领域、基础设施和 API 任务实现。

## 2. 现有基线与差距

现有 `SecurityConfiguration` 提供两种技术模式：

- `bootstrap` 使用 HTTP Basic、内存 Operator 和禁用 CSRF，适用于当前受控 API 基线；
- `oidc` 使用 OAuth2 Login、浏览器 Session 和 Cookie CSRF，并关闭 HTTP Basic；
- 两种模式都关闭 Spring Form Login；
- 前端 `CrewScopeApiClient` 已读取 `XSRF-TOKEN` Cookie，并在写请求发送 `X-XSRF-TOKEN`；
- Vite 已把 `/api` 和 `/actuator` 代理到 `localhost:8080`。

M7 需要在这些基线上增加 JSON 本地登录、Redis SecurityContext、显式 Session 旋转/退出和正式 AuthStore。Spike 只冻结技术拓扑，不直接替换当前 Bootstrap/OIDC 生产链。

## 3. 冻结拓扑

```text
Browser
  |  one Origin; SESSION + XSRF-TOKEN
  v
Web / Reverse Proxy
  |-- static pages and assets
  `-- /api/* --------------------------.
                                         v
                               WebFlux Security Chain
                                 |-- JSON Login Filter
                                 |-- Cookie CSRF
                                 |-- Session SecurityContext Repository
                                 `-- Auth/API Controllers
                                         |
                                         v
                                Spring Session Data Redis
```

浏览器只访问 Web Origin。开发环境由 Vite 代理 `/api`，生产环境由 Nginx/同等入口代理；前端不直接访问另一 Origin 的后端端口，不开启跨源 Credential。

`CrewScopeApiClient` 显式使用：

```text
credentials = same-origin
CSRF cookie = XSRF-TOKEN
CSRF header = X-XSRF-TOKEN
```

Session Cookie 为 HttpOnly；CSRF Cookie 必须允许同源 JavaScript 读取。生产 Session Cookie 继续要求 `Secure`、`SameSite=Lax` 和受控 Path。

## 4. JSON 登录与 Session 固定防护

JSON 登录由精确匹配 `POST /api/v1/auth/login` 的 `AuthenticationWebFilter` 承担：

```text
read JSON credentials within an 8 KiB body budget
  -> ReactiveAuthenticationManager
  -> save SecurityContext to WebSession
  -> WebSession.changeSessionId()
  -> return JSON session summary
```

登录成功处理器在 SecurityContext 保存后调用 `changeSessionId()`。测试先建立匿名 Session，再登录 Alice/Bob，并证明：

- 两个登录前 Session ID 不同；
- 每个用户登录后 ID 都发生变化；
- 两个登录后 ID 仍不同；
- 使用 Alice 的登录前 ID 查询只得到匿名状态；
- 使用当前 ID 的后续独立请求能从 Redis 恢复 Alice/Bob SecurityContext。

登录 JSON Body 在聚合前使用 8 KiB 上限，超限输入不进入认证管理器。错误密码与超限登录体都统一返回 JSON `401 invalid_credentials`，保留原匿名 Session ID，不建立认证会话，也不返回 `WWW-Authenticate: Basic`，因此浏览器不会出现原生 Basic 弹窗。

## 5. CSRF 协议

使用 `CookieServerCsrfTokenRepository.withHttpOnlyFalse()`。Spring Security 7 的 SPA Cookie 回传使用 `ServerCsrfTokenRequestAttributeHandler` 解析原始 Header Token；默认 XOR Handler 面向响应体渲染 Token，不能直接把原始 Cookie 值作为 Header 协议。

登录、受保护写请求和退出均要求 Cookie/Header 匹配。测试证明：

- 缺失 Header 返回 `403 csrf_rejected`；
- 错误 Header 返回 `403 csrf_rejected`；
- 当前 Cookie 值通过 `X-XSRF-TOKEN` 回传后写请求成功；
- CSRF Token 不需要进入 localStorage、sessionStorage、IndexedDB 或前端状态持久化。

## 6. Redis Namespace、TTL 与故障语义

Spring Boot 4.0.6 的 Redis Session 属性前缀为：

```yaml
spring:
  session:
    timeout: 15m
    data:
      redis:
        namespace: crewscope:m7-s01
```

它不同于旧版常见的 `spring.session.redis.namespace`。后续 M7-I02 必须使用 Boot 4 属性 `spring.session.data.redis.namespace`，并由配置测试防止错误属性静默回退到 `spring:session`。

真实 Redis 7.4 Testcontainer 中存在与浏览器当前 Session ID 对应的 `crewscope:m7-s01:sessions:*` Key。主 Session TTL 大于 0，并不超过 15 分钟会话加 5 分钟清理宽限。

Redis 是浏览器认证真相。Redis 不可用时 Session 读取、建立和保存失败关闭，不回退到请求 Header 身份、客户端缓存身份、Bootstrap 超级用户或无状态 Session。进程重启、双实例共享、续期和并发 Session 上限由 M7-I02 继续验证。

## 7. 双浏览器与反向代理验证

Playwright 测试创建两个真实 Chromium `BrowserContext`。测试内 Web Server 与 API Server 使用不同监听端口，Web Server 代理相对 `/api/*` 请求，使浏览器始终只看到一个 Origin。

验证结果：

```text
Alice anonymous SESSION != Bob anonymous SESSION
Alice wrong password = 401 / anonymous SESSION unchanged
Alice login rotates only Alice SESSION
Bob login rotates only Bob SESSION
Alice authenticated SESSION != Bob authenticated SESSION
Alice write without CSRF = 403
Alice write with CSRF = 200 / actor=alice
Alice logout = anonymous
Bob after Alice logout = authenticated / username=bob
```

Node Harness 只验证浏览器 Cookie Jar 和代理语义，不是生产认证实现。Spring/Redis 行为以 Java Testcontainer 集成测试为权威证据。

## 8. 自动化验证

后端测试：

```text
crewscope-server/src/test/java/io/crewscope/server/security/
  LocalSessionSecurityM7S01IntegrationTest.java
```

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=LocalSessionSecurityM7S01IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

浏览器、客户端与构建：

```bash
cd crewscope-web
pnpm test -- src/api/client.spec.ts
pnpm exec playwright test e2e/m7-session-spike.spec.ts --project=desktop-chromium
pnpm build
```

结果：

```text
Vitest: 84 files / 450 tests passed
Playwright: 1 passed
Vue production build: passed
```

## 9. 后续实现边界

- M7-I02 把 Redis Session 依赖移入生产 Scope，冻结 Namespace、TTL、序列化白名单、并发 Session 上限、双实例与故障配置；
- M7-A02 使用正式 Account/Credential/Binding 替换内存用户，实现登录、退出和公开 Session Projection；
- M7-A06 冻结认证路由、401/403、CSRF、Origin、安全响应头和无 Basic Challenge 矩阵；
- M7-F04 建立 AuthStore、启动 Session 恢复、Router Guard、401 恢复和跨标签退出；
- M7-I08 为 HTTPS 入口设置 Secure Cookie，并分离业务 Session 链与精确 Actuator 机器认证链。

测试专用 `spring-boot-starter-session-data-redis` 依赖在 M7-I02 前保持 Test Scope。Spike 不创建生产账号表，也不改变当前部署的认证模式。

## 10. 结论

M7-S01 验证通过。WebFlux JSON 登录、Redis SecurityContext、Session ID 旋转、Cookie CSRF、同源 Vue 请求、双浏览器隔离、退出失效、TTL 和 Web/API 反向代理可以组成 CrewScope 本地用户体系的认证内核。M7-I02、M7-A02 和 M7-F04 可以沿该拓扑实现正式能力。
