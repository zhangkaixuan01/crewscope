import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import type { ProxyOptions } from 'vite'

// The API enforces same-origin requests. During local development Vite proxies browser calls
// to localhost:8080, so rewrite Origin to the proxy target instead of making the API relax its
// production boundary.
const localApiProxy: ProxyOptions = {
  target: 'http://localhost:8080',
  changeOrigin: true,
  configure(proxy) {
    proxy.on('proxyReq', request => request.setHeader('Origin', 'http://localhost:8080'))
  },
}

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
      // Keep every production TS/Vue module in the denominator. Tests and Histoire/spike fixtures
      // are development assets and must not make an otherwise untested business path look covered.
      include: ['src/**/*.{ts,vue}'],
      exclude: [
        'src/**/*.spec.ts',
        'src/**/*.test.ts',
        'src/test/**',
        'src/**/*.story.vue',
        'src/spikes/**',
      ],
      thresholds: {
        // Q01 baseline: all production modules are measured. Raise these ratchets
        // as page-level characterization tests are added; never lower them silently.
        statements: 65,
        branches: 60,
        functions: 65,
        lines: 70,
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': localApiProxy,
      '/actuator': localApiProxy,
    },
  },
})
