import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_REAL_BASE_URL ?? 'http://127.0.0.1:18085'

/**
 * Production-stack browser contract for M10-F01a knowledge management: no Vite server and no
 * HTTP mocks — every request hits the real A02 knowledge API on a Compose stack. Own port
 * (18085) and report keep this tier separate from the M7-Q03 / M9b-Q01 / M9b-Q02 stacks.
 *
 * The desktop project runs the whole lifecycle spine; the narrow project reruns it at 390px
 * because the master-detail page degrades to a single column there.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm10-real/**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m10-real' }]],
  outputDir: 'test-results-m10-real',
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
      name: 'M10 Real Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
    {
      name: 'M10 Real Narrow',
      use: { browserName: 'chromium', viewport: { width: 390, height: 844 } },
    },
  ],
})
