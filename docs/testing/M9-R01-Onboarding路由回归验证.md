# M9-R01 Onboarding 路由回归验证

## 问题

首次创建 Team 并完成 Workspace、Personal Agent 投影确认后，`OnboardingPage` 会调用
`router.replace({ name: 'conversation', query: { team } })`。原测试使用 Vitest 默认 1 秒
`vi.waitFor` 窗口断言路由，Conversation 是懒加载路由；全量测试并发加载模块时，断言可能在
导航完成前超时，表现为仍停留在 `onboarding`。

## 修复

将 Onboarding 路由断言改为有界 5 秒等待，与注册流程的异步路由断言保持一致。运行时导航
实现与路由守卫未改变，仍会携带已创建 Team 的 `team` 查询参数进入 `conversation`。

## 验证结果

在 `crewscope-web` 目录执行：

```bash
pnpm vitest run src/pages/OnboardingPage.spec.ts --reporter=dot
pnpm test -- --run
pnpm check:quality
pnpm build
git diff --check
```

结果：

- Onboarding 专项：3 / 3 通过；
- 前端全量：136 / 136 个测试文件、737 / 737 个测试通过；
- ESLint、Stylelint、Web quality：通过；
- Vite 生产构建：通过；
- Git 空白检查：通过。

本修复只调整测试等待窗口，不改变 Onboarding、Session、Scope 或 Conversation 的业务契约。
