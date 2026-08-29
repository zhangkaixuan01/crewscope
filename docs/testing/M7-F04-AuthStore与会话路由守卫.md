# M7-F04 AuthStore 与会话路由守卫

## 1. 交付结果

M7-F04 已将前端身份从生产固定 Principal 切换为服务端 Session 权威。应用启动、页面刷新和首次受保护路由导航都会等待同一个 Session 恢复请求；身份明确前只渲染 AuthSessionBoundary，不挂载 AppShell 或业务 RouterView。

正式 `/login` 与 `/register` 复用全局 Session 和 CSRF 坐标。登录或注册成功后先通过 AuthStore 重新读取 Account、Principal、Team 和 Permission 的正式投影，再进入安全返回目标、Onboarding 或 Conversation。生产代码不再包含 `bootstrapPrincipal` 或身份占位安装函数，测试身份集中在独立 Fixture。

## 2. AuthStore 合同

AuthStore 是浏览器身份唯一权威，状态固定为：

```text
idle -> restoring -> anonymous | authenticated | error
```

实现规则：

- Session 恢复单飞，默认超时 10 秒；
- AuthStore 原位更新稳定 Principal 对象，使既有领域 Store 不保存第二份账号身份；
- Session DTO 通过闭合 Gateway 白名单重建，认证态必须同时包含 Account 与 Principal；
- `refresh()` 用于登录和注册后的非阻断正式 Session 回读，不切换全屏 Loading；
- Session、Principal、Permission 和 CSRF 坐标不写入 LocalStorage 或 SessionStorage；
- `stop()` 关闭 BroadcastChannel，并使既有恢复请求失效。

Session 恢复失败时只显示稳定的网络、超时或服务不可用状态，不展示内部异常。用户通过“重新检查”显式重试，页面不会自动形成失败重试风暴。

## 3. 路由与 401 恢复

Router Guard 在首次导航前等待 Session 明确：

- `/login` 与 `/register` 是当前公开身份路由；
- 匿名访问业务页返回 `/login`，并保存完整站内 `returnTo`；
- 已认证用户按 Session Permission 进入业务页；
- 权限不足进入 `/access-denied`，保持已认证身份；
- Session 过期、显式退出或跨标签退出时，当前业务页自动返回登录页。

API Client 只有在响应同时满足 `HTTP 401` 与 `code=authentication_required` 时通知 AuthStore。登录失败使用的 `invalid_credentials` 保持表单错误语义，不触发全局退出或跨标签广播。

身份失效后 AuthStore 立即递增 Generation、清空 Principal、广播退出并读取新的匿名 Session/CSRF。退出前发出的 Session 响应即使迟到，也不能重新建立认证态。

## 4. 跨标签与跨账号隔离

跨标签通信固定使用：

```text
Channel: crewscope-auth
Message: { type: "signed-out" }
```

消息不携带 Account、Principal、Organization、Team、Session 或 CSRF 坐标。收到退出消息的标签清理身份，并重新读取自己的匿名 Session。

身份失效会停止 Activity Realtime，并重置 Scope、Conversation、ConversationMessage、ConversationRealtime、TaskIntent、ConversationWorkItemLink、WorkItem、Task、Coding、Model、Agent、Review、Delivery、TeamOps 与 TeamObserver Store。ScopeStore 额外使用 Scope Generation 拒绝旧账号或旧 Team 的迟到响应，避免同一标签重新登录后看到前一账号缓存。

## 5. 首屏与可访问性

`idle / restoring / error` 阶段使用独立 AuthSessionBoundary：

- 首屏恢复不挂载 AppShell；
- Session 失败不闪现业务数据；
- 错误摘要获得焦点并使用稳定公开文案；
- 桌面与 390px 均保持单一 `main`；
- 390px 无横向溢出；
- Reduced Motion 与既有 AuthLayout 规则继续生效。

## 6. 验证证据

验证结果：

- 定向 Vitest：7 个文件、49 个测试全部通过；
- 全量 Vitest：94 个文件、521 个测试全部通过；
- M7 登录、注册与 AuthStore Playwright：Desktop Chromium 与 390px Chromium 共 20/20 通过；
- 全量 Playwright/视觉/Axe：208/208 通过；
- Production Build：`vue-tsc --noEmit` 与 Vite Build 通过；
- Histoire：18 个 Story、141 个 Variant 构建通过；
- Sensitive Field Gate：57 个生产文件、18 个 Story 通过。

真实浏览器额外验证后端不可达时访问 `/conversation` 会安全进入 `/login?returnTo=/conversation`，页面不挂载 AppShell；390px 下保持单一 `main`、零横向溢出并将焦点移入 Session 错误摘要。

## 7. 后续边界

M7-F04 不实现首次 Team 创建页面。下一任务 M7-F05 在当前 AuthStore、Router Guard 和 Session 恢复合同上实现 `/onboarding`，完成第一个 Team、默认 Workspace、Owner 权限与 Personal Agent 初始化体验。
