import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.CREWSCOPE_REAL_BASE_URL ?? 'http://127.0.0.1:18095'

/**
 * Production-stack browser contract for the M12-Q02 release gate: no Vite server and no
 * HTTP mocks — the browser talks to the real four-service Compose stack through the
 * published Nginx entry point, exactly as a deployed browser would. The gate stack runs
 * on the plain quickstart defaults (security.mode=local, no OIDC or quota env keys), so
 * the enterprise read/governance planes are exercised on the §8-2 default install path.
 * Own port (18095) and report keep this tier separate from the M7/M9b/M10/M11 real
 * tiers; `playwright.config.ts` testIgnores this directory so the mock-tier suite never
 * discovers these specs.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'm12-real/**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-m12-real' }]],
  outputDir: 'test-results-m12-real',
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
      name: 'M12 Real Desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
    {
      name: 'M12 Real Narrow',
      use: { browserName: 'chromium', viewport: { width: 390, height: 844 } },
    },
  ],
})
