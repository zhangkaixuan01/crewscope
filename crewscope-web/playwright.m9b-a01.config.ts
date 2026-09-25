import { defineConfig, devices } from '@playwright/test'

// Invoked by A01BrowserIntegrationTest, which supplies a disposable PostgreSQL database.
// No Vite/product/Compose service is started. The test owns short-lived server JVMs.
export default defineConfig({
  testDir: './e2e/m9b-a01', workers: 1, timeout: 150_000,
  forbidOnly: Boolean(process.env.CI), reporter: 'list',
  use: { ...devices['Desktop Chrome'], trace: 'retain-on-failure', serviceWorkers: 'block' },
})
