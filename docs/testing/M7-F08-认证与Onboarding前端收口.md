# M7-F08 认证与 Onboarding 前端收口

## 1. 交付结果

M7-F08 完成开放用户体系前端实现波次的统一收口。正式 `/login`、`/register`、`/onboarding`、`/account`、`/invite` 与 Team 邀请管理入口继续使用同一套 Session、路由、安全返回目标、焦点、响应式和隐私边界；生产 Web 不依赖固定 Principal，不展示或引导业务 HTTP Basic 登录。

本任务不新增第二套认证页面，也不改变 M7-A01 至 M7-A07 的服务端协议。收口重点是把此前分任务验证的状态纳入统一 Coverage、Histoire、双视口 Playwright/Axe、生产构建、公开字段扫描和 Demo 文档入口。

## 2. 真实 Coverage 分母

原 Coverage 只统计 `api`、`app` 和组件目录，没有统计 M7 的 Identity、Account、Onboarding、Invitation 领域模块及五个正式页面。M7-F08 将以下代码永久加入 Vitest Coverage：

- `src/domains/identity/**`；
- `src/domains/account/**`；
- `src/domains/onboarding/**`；
- `src/domains/invitation/**`；
- Login、Register、Onboarding、Account、Invite 五个正式页面。

新增四组 Presentation 状态矩阵，覆盖登录、注册、Session、账号、首次 Team 和邀请的超时、离线、冲突、限流、CSRF、无效请求、服务不可用与未知错误。测试只断言稳定公开 Code 和用户文案，并证明服务端异常正文、Correlation 等私有详情不会进入界面。AccountPage 同时补充进入加载与离开清理测试。

扩展分母后阈值保持不降低：Statements 80%、Branches 70%、Functions 75%、Lines 80%。

## 3. 全状态、响应式与可访问性

M7 的认证与协作入口由生产组件 Story 和正式页面 E2E 共同覆盖：

- Histoire 保留 Loading、Session Error、Invalid Credential、Locked、OPEN、INVITE_ONLY、DISABLED、首次 Team、邀请可用/过期/不可用、账号资料与安全操作等状态；
- Playwright 的每个场景同时运行 Desktop Chromium 与 390px Chromium；
- 正式登录、注册、Onboarding、账号和邀请路径验证单一 `main`、零横向溢出、确定初始焦点、错误摘要聚焦、键盘操作和 Axe 零违规；
- M7 身份体验视觉基线继续按 macOS/Linux 分平台冻结，避免用放宽截图阈值掩盖字体或布局漂移；
- Reduced Motion、Fragment 清理、密码与一次性证明零持久化继续进入自动门禁。

## 4. 公开字段与身份入口

敏感字段脚本新增 Onboarding 领域和正式页面，将扫描范围扩展到 78 个生产文件和 21 个 Story。门禁继续禁止身份数据进入 LocalStorage、SessionStorage、IndexedDB 或 Console，并新增以下回归约束：

- 生产 Web 源码不得重新引用测试 `bootstrapPrincipal`；
- 生产 Web 不得出现业务 Basic Auth 或 `WWW-Authenticate: Basic` 文案；
- README 不得发布固定的占位业务登录凭证；
- Token、密码、CSRF、Session 与 Provider Secret 继续遵循既有单向输入和公开投影白名单。

提交前审查进一步让登录页和账号安全工作区在组件卸载时显式清空全部密码与 Step-up 证明并中止在途请求，使“离开页面即清理”的实现合同不依赖垃圾回收时机。

README 和 `deploy/team-beta/demo.sh` 已明确 Demo 的正式 `/register`、`/login` 入口、Operator 密码文件和 Prometheus 机器账号隔离。普通用户通过 OPEN 注册进入 Onboarding；Operator 使用同一登录页；Prometheus 凭证不能作为 Web 登录。提交前审查同步升级 Team Beta 部署合同门禁，要求 Demo 脚本持续输出这组正式入口和凭证边界，防止后续回退到 Bootstrap 业务登录文案。

## 5. 验证证据

执行：

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm check:sensitive
pnpm exec playwright test

cd ..
node scripts/check-doc-links.mjs
node scripts/check-team-beta-deployment.mjs
node scripts/check-team-beta-recovery.mjs
git diff --check
sh -n deploy/team-beta/demo.sh
```

结果：

- Vitest：111 个文件、625 个测试全部通过；
- Coverage：Statements 80.18%、Branches 73.91%、Functions 82.72%、Lines 83.90%；
- Production Build：Vue TypeScript 检查与 Vite Build 通过；
- Histoire：21 个 Story、153 个 Variant 通过；
- Playwright/Visual/Axe：Desktop 与 390px 共 240/240 通过；
- Sensitive Field Gate：78 个生产文件、21 个 Story 通过；
- Team Beta 部署与恢复合同检查通过；
- 文档链接、差异格式与 Demo Shell 语法检查通过。

## 6. 后续

M7 前端 F01 至 F08 已全部完成。下一任务 M7-Q01 建立本地认证固定攻击集，验证密码、账号枚举、暴力尝试、Session 固定/劫持、CSRF、Cookie、Origin、开放重定向以及日志和响应泄漏边界。
