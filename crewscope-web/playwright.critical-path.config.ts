import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_REAL_BASE_URL ?? 'http://127.0.0.1:18090'

/**
 * 首跑命脉冒烟（M13h）：一条真实 Compose 栈上的全新账号全 UI 链路。与 m10-real 同一套
 * quickstart 栈约定（CREWSCOPE_REAL_BASE_URL 指向 web 端口），但目的不同——m10-real 冻结
 * 知识/技能域契约，这一层只守「一个真人从零开始能否用起来」的命脉：注册 → 建团 → 进对话
 * → 发第一条消息。判例：m13 期间两次发布级断砖（注册 503 registration_unavailable、建团
 * 422 modelProvider.contentHash）发生时 mock e2e 全绿，只有真实栈能拦住。
 *
 * 单桌面项目：这是产品路径冒烟，不是响应式契约（窄屏布局由 m13f/m13g 的 mock 层守）。
 * 栈是无凭据形态（quickstart 生成的 env 不含任何模型 API key），Agent 回复因此断言设计
 * 边界：失败必须可见、可恢复、不吞用户输入，而不是断言 LLM 输出。
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'critical-path/**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-critical-path' }]],
  outputDir: 'test-results-critical-path',
  timeout: 300_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL,
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    contextOptions: { reducedMotion: 'reduce' },
  },
  projects: [
    {
      name: 'Critical Path Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
  ],
})
