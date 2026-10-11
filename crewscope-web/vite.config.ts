import { createHash } from 'node:crypto'
import type { Duplex } from 'node:stream'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import type { Plugin, ProxyOptions } from 'vite'
import type { IncomingMessage } from 'node:http'

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

// The collaboration signal channel upgrades to WebSocket — http-proxy needs its own
// entry (ws target) listed before /api, or the longer /api prefix wins the match.
const localCollaborationWsProxy: ProxyOptions = {
  target: 'ws://localhost:8080',
  ws: true,
  changeOrigin: true,
  configure(proxy) {
    proxy.on('proxyReq', request => request.setHeader('Origin', 'http://localhost:8080'))
  },
}

// The default Playwright tier runs `pnpm dev` with page-route mocks and no backend. A
// WebSocket handshake cannot be mocked by page.route, and the failed upgrade would push
// the collaboration store to `degraded` on the third retry (~3.5s in) — flipping the
// status pill and the degraded placeholders into screenshots mid-test. The e2e dev
// server (CREWSCOPE_E2E_DEV=1, set by playwright.config.ts) instead completes the
// upgrade and stays silent: with no welcome frame the store never leaves `connecting`,
// so every collaboration affordance stays hidden and the visual baselines keep their
// pre-F01 shape. Local dev keeps the real proxy and the honest degraded stance.
const e2eDevServer = process.env.CREWSCOPE_E2E_DEV === '1'

function inertCollaborationChannelPlugin(): Plugin {
  const WEBSOCKET_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
  return {
    name: 'crewscope-e2e-inert-collaboration-channel',
    configureServer(server) {
      server.httpServer?.on('upgrade', (request: IncomingMessage, socket: Duplex) => {
        if (!request.url?.startsWith('/api/v1/collaboration/ws')) return
        const key = request.headers['sec-websocket-key']
        if (typeof key !== 'string') {
          socket.destroy()
          return
        }
        const accept = createHash('sha1').update(`${key}${WEBSOCKET_GUID}`).digest('base64')
        socket.write(
          'HTTP/1.1 101 Switching Protocols\r\n'
          + 'Upgrade: websocket\r\n'
          + 'Connection: Upgrade\r\n'
          + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
        )
        socket.on('error', () => socket.destroy())
      })
    },
  }
}

export default defineConfig({
  plugins: e2eDevServer ? [vue(), inertCollaborationChannelPlugin()] : [vue()],
  build: {
    rollupOptions: {
      output: {
        // The framework runtime (vue + vue-router) is a stable, always-loaded slice of
        // the initial payload. Splitting it into its own chunk keeps the app chunk
        // under the M9-Q01 single-chunk budget without changing what the browser
        // downloads up front — the same bytes ship as two parallel requests.
        manualChunks: { vendor: ['vue', 'vue-router'] },
      },
    },
  },
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
        // M10-Q01 close-out ratchet: measured 72.75/66.41/75.34/77.23 on 2026-10-06.
        statements: 72,
        branches: 66,
        functions: 75,
        lines: 77,
      },
    },
  },
  server: {
    port: 5173,
    proxy: e2eDevServer
      ? {
        // The inert plugin owns the collaboration upgrade in the e2e tier; a ws:true
        // entry here would race it with a proxy attempt against the absent backend.
        '/api': localApiProxy,
        '/actuator': localApiProxy,
      }
      : {
        '/api/v1/collaboration/ws': localCollaborationWsProxy,
        '/api': localApiProxy,
        '/actuator': localApiProxy,
      },
  },
})
