import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_REAL_BASE_URL ?? 'http://127.0.0.1:18082'

/**
 * Production-stack browser contract for M9b-Q01; no Vite server or HTTP mocks are installed.
 * Own port (18082) and report so the gate never collides with the M7-Q03 stack, and the real
 * matrix stays a separately reported tier from the mocked browser matrix.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm9b-q01/**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m9b-q01' }]],
  outputDir: 'test-results-m9b-q01',
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
      name: 'M9b-Q01 Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
  ],
})
