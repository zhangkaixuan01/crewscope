# M7-F05 首次 Team Onboarding

## 1. 交付结果

M7-F05 已实现正式 `/onboarding`。已认证且没有 ACTIVE Team 的用户可以创建第一个 Team；页面明确说明 Team、共享 Workspace、Owner 责任与默认 Personal Agent 的初始化边界。已有 ACTIVE Team 的用户不会重复进入创建流程，服务端状态确认后直接进入 Conversation。

创建成功后页面不使用客户端虚构百分比，而是按服务端事实依次验证正式 Session、新 Team、`initializationStatus=READY` 的 Team 投影，以及当前成员拥有的 `USER + defaultProfile + ACTIVE` Personal Agent。全部事实收敛后才展示 Personal Agent 名称和“进入团队对话”。

## 2. Gateway 与状态机

Onboarding Gateway 固定消费：

```text
GET  /api/v1/onboarding
POST /api/v1/onboarding/team
```

创建请求 Body 只允许 `name`，并从 AuthStore 内存 Session 回传 CSRF Header。`Idempotency-Key` 只保存在 OnboardingStore 的私有内存变量中，不进入响应式状态、URL、LocalStorage、SessionStorage、IndexedDB 或 Telemetry。

状态机固定为：

```text
idle -> loading -> required
required -> submitting -> verifying -> complete
loading | submitting | verifying -> error -> retry
```

网络失败、请求超时、`onboarding_unavailable` 和 `csrf_rejected` 保留同一创建意图与 Idempotency Key；`idempotency_conflict` 清除旧 Key，由下一次提交建立新意图。202 回执后若状态读取中断，重试只恢复 Onboarding 状态，不重复创建 Team。请求使用 Generation 与 AbortController，身份退出、路由离开或新操作会取消旧请求，迟到响应不能回写当前页面。

## 3. 初始化复验

创建命令提交后按以下顺序复验：

1. AuthStore 重新读取正式 Session；
2. Session 中出现当前账号的新 Team Membership；
3. ScopeStore 读取到目标 Team，且 `initializationStatus=READY`；
4. AgentStore 读取到当前 TeamMember 拥有的默认 ACTIVE Personal Agent。

任一步骤未收敛都停留在可恢复状态，不把命令回执当作完整工作入口。重试复用现有服务器事实，不清除已经创建的 Team，也不重新发起首 Team 命令。

## 4. 路由与体验

- `/onboarding` 要求认证，但不要求用户已经拥有 Team 权限；
- 登录安全返回目标允许站内 `/onboarding`，拒绝外部、重复或未注册目标；
- 已有 Team 的用户直接进入 `/conversation?team=<teamId>`；
- 新 Team 完成初始化后由用户显式进入相同 Conversation 入口；
- AuthLayout 保持一个 `main`，桌面显示完整协作说明，390px 使用单列布局；
- 创建态聚焦 Team 名称输入，失败态聚焦 `role=alert`，完成态聚焦完成标题；
- Reduced Motion、键盘顺序、Axe 和零横向溢出沿用 M7 公开身份体验合同。

## 5. 验证证据

验证结果：

- 定向 Vitest：5 个文件、29 个测试全部通过；
- 全量 Vitest：98 个文件、537 个测试全部通过；
- M7-F05 Playwright：Desktop Chromium 与 390px Chromium 共 6/6 通过；
- 全量 Playwright/视觉/Axe：214/214 通过；
- Production Build：`vue-tsc --noEmit` 与 Vite Build 通过；
- Histoire：19 个 Story、145 个 Variant 构建通过；
- Sensitive Field Gate：58 个生产文件、19 个 Story 通过。

真实浏览器额外验证了正式 `/onboarding` 在 Session 服务不可用时保持 AuthSessionBoundary，不挂载业务页面；Onboarding 创建态、投影恢复态和完成态在 390px 下均保持单一 `main`、零横向溢出，焦点分别落在 Team 名称输入、错误摘要和完成标题，完成态显示真实 Personal Agent 名称。

## 6. 后续边界

M7-F05 不实现账号资料、密码、退出或全部设备退出。下一任务 M7-F06 在当前 AuthStore 与 AppShell 身份边界上实现 `/account` 和用户菜单。
