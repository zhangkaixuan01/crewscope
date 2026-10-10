import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_REAL_BASE_URL ?? 'http://127.0.0.1:18087'

/**
 * Production-stack browser contract for M11-I01c collaboration deployment: no Vite server
 * and no HTTP mocks — the browser talks to the real four-service Compose stack through the
 * published Nginx entry point, so the WebSocket upgrade path and the SSE degradation path
 * are exercised exactly as a deployed browser would see them. Own port (18087 for the
 * channel-open stack, 18088 for the degraded one) and report keep this tier separate from
 * the M7-Q03 / M9b / M10 real-stack tiers.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm11-real/**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m11-real' }]],
  outputDir: 'test-results-m11-real',
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
      name: 'M11 Real Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
    {
      name: 'M11 Real Narrow',
      use: { browserName: 'chromium', viewport: { width: 390, height: 844 } },
    },
  ],
})
