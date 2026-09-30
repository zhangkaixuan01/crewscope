import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_REAL_BASE_URL ?? 'http://127.0.0.1:18084'

/**
 * Production-stack browser contract for M9b-Q02; no Vite server or HTTP mocks are installed.
 * Own port (18084) and report so the gate never collides with the M7-Q03 / M9b-Q01 stacks, and
 * the real matrix stays a separately reported tier from the mocked browser matrix.
 *
 * Two projects cover the §9.2 rows: the desktop spine runs everything except the narrow-only
 * mobile spec, and the narrow project runs only that spec (390px main operations). Splitting by
 * project-level testMatch/testIgnore keeps the split declarative — no in-body skips to whitelist.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm9b-q02/**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m9b-q02' }]],
  outputDir: 'test-results-m9b-q02',
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
      name: 'M9b-Q02 Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
      testIgnore: /mobile-main-path/,
    },
    {
      name: 'M9b-Q02 Narrow',
      use: { browserName: 'chromium', viewport: { width: 390, height: 844 } },
      testMatch: /mobile-main-path/,
    },
  ],
})
