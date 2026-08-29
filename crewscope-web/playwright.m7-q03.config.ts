import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_Q03_BASE_URL ?? 'http://127.0.0.1:18080'

/** Production-stack browser contract for M7-Q03; no Vite server or HTTP mocks are installed. */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm7-two-user-real.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m7-q03' }]],
  outputDir: 'test-results-m7-q03',
  timeout: 180_000,
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
      name: 'M7-Q03 Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
    {
      name: 'M7-Q03 Narrow',
      use: {
        ...devices['iPhone 13'],
        browserName: 'chromium',
        viewport: { width: 390, height: 844 },
      },
    },
  ],
})
