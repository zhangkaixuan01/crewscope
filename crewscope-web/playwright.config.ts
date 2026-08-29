import { defineConfig, devices } from '@playwright/test'

const snapshotPlatform = process.platform

export default defineConfig({
  testDir: './e2e',
  // Q03/Q04 own full production stacks and explicit configs. The ordinary browser matrix uses the
  // Vite test server and must not discover those release-only specifications automatically.
  testIgnore: ['m7-two-user-real.spec.ts', 'm7-registration-profiles-real.spec.ts'],
  // Text rasterization differs materially between macOS and the Linux amd64 release host.
  // Keep reviewed baselines per OS instead of weakening the visual regression threshold.
  snapshotPathTemplate: `{testDir}/{testFilePath}-snapshots/{arg}-${snapshotPlatform}{ext}`,
  fullyParallel: true,
  // The release gate runs after Docker-heavy integration tests; two browser workers avoid launch starvation.
  workers: 2,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    // Keep datetime-local inputs and localized audit timestamps deterministic across developer and CI hosts.
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    contextOptions: { reducedMotion: 'reduce' },
  },
  expect: {
    toHaveScreenshot: {
      // Browser engines render text slightly differently across macOS development and Linux CI.
      maxDiffPixelRatio: 0.02,
    },
  },
  projects: [
    {
      name: 'desktop-chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } },
    },
    {
      name: 'narrow-chromium',
      use: { ...devices['iPhone 13'], browserName: 'chromium', viewport: { width: 390, height: 844 } },
    },
  ],
  webServer: {
    command: 'pnpm dev --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
  },
})
