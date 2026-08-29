import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    exclude: ['e2e/**', 'node_modules/**', 'dist/**', '.histoire/**'],
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: [
        'src/api/**/*.ts',
        'src/app/**/*.ts',
        'src/components/**/*.vue',
        'src/domains/{identity,account,onboarding,invitation}/**/*.ts',
        'src/pages/{Login,Register,Onboarding,Account,Invite}Page.vue',
      ],
      exclude: ['src/**/*.story.vue'],
      thresholds: {
        statements: 80,
        branches: 70,
        functions: 75,
        lines: 80,
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
