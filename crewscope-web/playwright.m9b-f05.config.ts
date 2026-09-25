import { defineConfig, devices } from '@playwright/test'

// Built assets are served by request interception. No application/Compose service or real API.
export default defineConfig({
  testDir: './e2e/m9b-f05',
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  reporter: 'list',
  use: { ...devices['Desktop Chrome'], trace: 'retain-on-failure', serviceWorkers: 'block' },
})
