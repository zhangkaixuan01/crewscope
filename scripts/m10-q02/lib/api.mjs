/**
 * Session-cookie API client for the M10-Q02 comparison driver (real stack, no browser).
 *
 * Mirrors the browser plumbing of crewscope-web/e2e/real-backend.ts in plain Node:
 * double-submit CSRF (XSRF-TOKEN cookie must travel with CREWSCOPE_SESSION or writes are
 * rejected before authentication), Idempotency-Key on every command, If-Match strong ETags,
 * and a 401-triggered re-login. The client only ever handles throwaway gate accounts from
 * the coordinates file — never provider credentials.
 */
import { randomUUID } from 'node:crypto'

export class ApiClient {
  /**
   * @param {string} baseURL e.g. http://127.0.0.1:18090
   * @param {{ identifier: string, password: string }} credentials throwaway gate account
   */
  constructor(baseURL, credentials) {
    this.baseURL = baseURL.replace(/\/$/, '')
    this.credentials = credentials
    this.cookies = new Map()
    this.csrf = { headerName: 'X-XSRF-TOKEN', token: '' }
    this.authenticated = false
  }

  absorbCookies(response) {
    const setCookies = response.headers.getSetCookie?.() ?? []
    for (const line of setCookies) {
      const [pair] = line.split(';')
      const equals = pair.indexOf('=')
      if (equals > 0) this.cookies.set(pair.slice(0, equals).trim(), pair.slice(equals + 1).trim())
    }
  }

  cookieHeader() {
    return [...this.cookies.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
  }

  async request(path, { method = 'GET', body, ifMatch, idempotency = false } = {}) {
    const headers = { Cookie: this.cookieHeader() }
    if (this.csrf.token) headers[this.csrf.headerName] = this.csrf.token
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`
    if (idempotency) headers['Idempotency-Key'] = randomUUID()
    // Reviewer execute and settle polls are single long HTTP calls (the reviewer model runs
    // inside the request); undici's 5-minute default would cut the slow tail short.
    const response = await fetch(`${this.baseURL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(600_000),
    })
    this.absorbCookies(response)
    return response
  }

  /** Establishes (or re-establishes) a logged-in session and a fresh CSRF token. */
  async ensureSession() {
    const preflight = await this.request('/api/v1/auth/session')
    const preflightBody = await preflight.json()
    if (preflightBody?.authenticated) {
      this.csrf = preflightBody.csrf
      this.authenticated = true
      return this
    }
    // Login needs the pre-login CSRF cookie + header pair; the session endpoint already set it.
    this.csrf = preflightBody?.csrf ?? this.csrf
    const login = await this.request('/api/v1/auth/login', {
      method: 'POST',
      body: { identifier: this.credentials.identifier, password: this.credentials.password },
      idempotency: true,
    })
    if (!login.ok) throw new Error(`login failed: ${login.status} ${(await login.text()).slice(0, 200)}`)
    const session = await this.request('/api/v1/auth/session')
    const sessionBody = await session.json()
    if (!sessionBody?.authenticated) throw new Error('login did not authenticate the session')
    this.csrf = sessionBody.csrf
    this.authenticated = true
    return this
  }

  /** Authenticated GET returning parsed JSON; 401 re-logins once. */
  async getJson(path) {
    let response = await this.request(path)
    if (response.status === 401) {
      await this.ensureSession()
      response = await this.request(path)
    }
    if (!response.ok) {
      const error = new Error(`GET ${path} -> ${response.status}: ${(await response.text()).slice(0, 300)}`)
      error.status = response.status
      throw error
    }
    return response.json()
  }

  /**
   * Authenticated command POST expecting 202. `ifMatch` is the raw version (quoted here).
   * 401 re-logins once; the error text is returned verbatim for caller-side handling.
   * @returns {Promise<{ status: number, body: unknown }>}
   */
  async command(path, body, { ifMatch, expected = 202 } = {}) {
    const send = () => this.request(path, {
      method: 'POST',
      // A bodyless command (resume, review execute) must stay bodyless: the server
      // rejects request bodies on those endpoints with 400 invalid_request.
      body,
      ifMatch,
      idempotency: true,
    })
    let response = await send()
    if (response.status === 401) {
      await this.ensureSession()
      response = await send()
    }
    const text = await response.text()
    let parsed = null
    try { parsed = text ? JSON.parse(text) : null } catch { parsed = { raw: text.slice(0, 300) } }
    if (response.status !== expected && ![200, 201, 202].includes(response.status)) {
      const error = new Error(`POST ${path} -> ${response.status}: ${JSON.stringify(parsed).slice(0, 400)}`)
      error.status = response.status
      error.body = parsed
      throw error
    }
    return { status: response.status, body: parsed }
  }

  /** Raw authenticated GET returning text (for patch artifacts). */
  async getText(path) {
    const response = await this.request(path)
    if (response.status === 401) {
      await this.ensureSession()
      return this.request(path).then(inner => inner.text())
    }
    return response.text()
  }
}

/**
 * Settle loop transplanted from the m9b-q02 real coding precedent (github-coding-pr.spec.ts):
 * poll the attempt list, resume once at the CONFIRMATION interrupt, recheck once after a
 * FAILED/CANCELLED/RECOVERING sighting (transient recovery), and stop at any terminal state.
 *
 * @returns {Promise<{ status: string, taskId: string, executionId: string, resumedConfirmation: boolean }>}
 */
export async function awaitTerminal(client, { taskId, executionId, tasksPath, deadlineMs = 2_700_000 }) {
  let resumedConfirmation = false
  let status = 'MISSING'
  while (Date.now() < deadlineMs) {
    const attempts = await client.getJson(`${tasksPath}/${taskId}/attempts`)
    const current = attempts.find(item => item.id === executionId)
    status = current?.status ?? 'MISSING'
    if (status === 'COMPLETED') break
    if (status === 'FAILED' || status === 'CANCELLED' || status === 'RECOVERING') {
      await sleep(30_000)
      const recheck = await client.getJson(`${tasksPath}/${taskId}/attempts`)
      status = recheck.find(item => item.id === executionId)?.status ?? status
      if (status !== 'RUNNING' && status !== 'WAITING') break
    }
    if (status === 'WAITING' && current?.waiting?.reason === 'CONFIRMATION' && !resumedConfirmation) {
      resumedConfirmation = true
      await client.command(`${tasksPath}/${taskId}/attempts/${executionId}/resume`, undefined, {
        ifMatch: current.version,
        expected: 200,
      })
    }
    await sleep(15_000)
  }
  return { status, taskId, executionId, resumedConfirmation }
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
