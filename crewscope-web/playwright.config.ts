import { defineConfig, devices } from '@playwright/test'

const snapshotPlatform = process.platform

export default defineConfig({
  testDir: './e2e',
  // Q03/Q04 own full production stacks and explicit configs, and the m9b-a01 recovery scenario is
  // driven by A01BrowserIntegrationTest through a real test JVM (A01_TEST_* env). The ordinary
  // browser matrix uses the Vite test server and must not discover those specifications.
  testIgnore: ['m7-two-user-real.spec.ts', 'm7-registration-profiles-real.spec.ts', '**/m9b-f01/**', '**/m9b-a01/**', '**/m9b-q01/**', '**/m9b-q02/**', '**/m10-real/**', '**/m11-real/**', '**/m12-real/**', '**/critical-path/**'],
  // Text rasterization differs materially between macOS and the Linux amd64 release host.
  // Keep reviewed baselines per OS instead of weakening the visual regression threshold.
  snapshotPathTemplate: `{testDir}/{testFilePath}-snapshots/{arg}-${snapshotPlatform}{ext}`,
  fullyParallel: true,
  // The mock-tier webServer is a plain Vite dev server. Laptops run four workers fine; the
  // 3-vCPU/7GB macOS CI runner keeps the conservative two. (Note: the split-CI failure burst
  // that once looked like worker starvation — ECONNREFUSED/ERR_FAILED — was actually the e2e
  // job missing `vite build`, see ci.yml; workers 4 vs 2 failed identically at ~290 tests.)
  workers: process.env.CI ? 2 : 4,
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
    // Preview serves the prebuilt dist bundle: every page load costs a static sendfile
    // instead of an on-the-fly module transform, which matters on the 3-vCPU CI runner.
    // Build first (`pnpm build`) — CI does this in its own step, and the m9b fixtures
    // resolve('dist') from the same bundle. Locally an already-running server on the
    // port is reused as before.
    command: 'pnpm preview --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    // Marks the mock-tier server so vite.config.ts serves the inert collaboration
    // handshake channel (M11-F01) on both the dev pipeline and the preview server:
    // page.route cannot mock a WebSocket upgrade, and a failing one would degrade the
    // store mid-screenshot.
    env: { CREWSCOPE_E2E: '1' },
  },
})
