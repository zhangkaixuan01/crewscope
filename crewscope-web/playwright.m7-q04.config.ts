import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_Q04_BASE_URL ?? 'http://127.0.0.1:18081'

/** Real production-stack registration-profile and Bootstrap replacement contract for M7-Q04. */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm7-registration-profiles-real.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m7-q04' }]],
  outputDir: 'test-results-m7-q04',
  timeout: 240_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    contextOptions: { reducedMotion: 'reduce' },
  },
})
