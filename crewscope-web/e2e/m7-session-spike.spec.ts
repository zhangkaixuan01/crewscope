import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { createServer, request as nodeRequest, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

type Session = { username: string | null }
type JsonResult = { status: number; body: Record<string, unknown> | null }

const MAX_LOGIN_BODY_BYTES = 8 * 1024
const fixturePasswords = new Map([
  ['alice', 'alice-password'],
  ['bob', 'bob-password'],
])

test('M7-S01 isolates two browser sessions behind one Web/API origin', async ({ browser }) => {
  const sessions = new Map<string, Session>()
  const api = createServer((request, response) => handleApi(request, response, sessions))
  const apiOrigin = await listen(api)
  const web = createServer((request, response) => proxyWebRequest(request, response, apiOrigin))
  const webOrigin = await listen(web)

  const aliceContext = await browser.newContext()
  const bobContext = await browser.newContext()
  try {
    const alice = await aliceContext.newPage()
    const bob = await bobContext.newPage()
    await Promise.all([alice.goto(webOrigin), bob.goto(webOrigin)])

    expect(await browserJson(alice, '/api/v1/auth/session')).toMatchObject({
      status: 200,
      body: { authenticated: false },
    })
    expect(await browserJson(bob, '/api/v1/auth/session')).toMatchObject({
      status: 200,
      body: { authenticated: false },
    })

    const aliceAnonymousSession = await cookieValue(aliceContext, webOrigin, 'SESSION')
    const bobAnonymousSession = await cookieValue(bobContext, webOrigin, 'SESSION')
    expect(aliceAnonymousSession).not.toBe(bobAnonymousSession)

    expect(await browserJson(alice, '/api/v1/auth/login', {
      method: 'POST',
      body: { username: 'alice', password: 'wrong-password' },
      csrf: true,
    })).toMatchObject({ status: 401, body: { code: 'invalid_credentials' } })
    expect(await cookieValue(aliceContext, webOrigin, 'SESSION')).toBe(aliceAnonymousSession)
    expect(await browserJson(alice, '/api/v1/auth/session')).toMatchObject({
      status: 200,
      body: { authenticated: false },
    })

    expect(await browserJson(alice, '/api/v1/auth/login', {
      method: 'POST',
      body: { username: 'alice', password: 'alice-password' },
      csrf: true,
    })).toMatchObject({ status: 200, body: { username: 'alice' } })
    expect(await browserJson(bob, '/api/v1/auth/login', {
      method: 'POST',
      body: { username: 'bob', password: 'bob-password' },
      csrf: true,
    })).toMatchObject({ status: 200, body: { username: 'bob' } })

    const aliceAuthenticatedSession = await cookieValue(aliceContext, webOrigin, 'SESSION')
    const bobAuthenticatedSession = await cookieValue(bobContext, webOrigin, 'SESSION')
    expect(aliceAuthenticatedSession).not.toBe(aliceAnonymousSession)
    expect(bobAuthenticatedSession).not.toBe(bobAnonymousSession)
    expect(aliceAuthenticatedSession).not.toBe(bobAuthenticatedSession)

    expect(await browserJson(alice, '/api/v1/spike/write', {
      method: 'POST',
      body: { value: 'blocked' },
    })).toMatchObject({ status: 403, body: { code: 'csrf_rejected' } })
    expect(await browserJson(alice, '/api/v1/spike/write', {
      method: 'POST',
      body: { value: 'accepted' },
      csrf: true,
    })).toMatchObject({ status: 200, body: { actor: 'alice' } })

    expect(await browserJson(alice, '/api/v1/auth/logout', {
      method: 'POST',
      csrf: true,
    })).toMatchObject({ status: 204, body: null })
    expect(await browserJson(alice, '/api/v1/auth/session')).toMatchObject({
      status: 200,
      body: { authenticated: false },
    })
    expect(await browserJson(bob, '/api/v1/auth/session')).toMatchObject({
      status: 200,
      body: { authenticated: true, username: 'bob' },
    })
  } finally {
    await Promise.all([aliceContext.close(), bobContext.close()])
    await Promise.all([close(web), close(api)])
  }
})

async function browserJson(
  page: import('@playwright/test').Page,
  path: string,
  options: { method?: string; body?: Record<string, unknown>; csrf?: boolean } = {},
): Promise<JsonResult> {
  return page.evaluate(async ({ requestPath, requestOptions }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (requestOptions.body) headers['Content-Type'] = 'application/json'
    if (requestOptions.csrf) {
      const cookie = document.cookie.split(';').map(value => value.trim())
        .find(value => value.startsWith('XSRF-TOKEN='))
      if (cookie) headers['X-XSRF-TOKEN'] = decodeURIComponent(cookie.slice('XSRF-TOKEN='.length))
    }
    const response = await fetch(requestPath, {
      method: requestOptions.method ?? 'GET',
      headers,
      credentials: 'same-origin',
      body: requestOptions.body ? JSON.stringify(requestOptions.body) : undefined,
    })
    return {
      status: response.status,
      body: response.status === 204 ? null : await response.json() as Record<string, unknown>,
    }
  }, { requestPath: path, requestOptions: options })
}

async function cookieValue(
  context: import('@playwright/test').BrowserContext,
  origin: string,
  name: string,
): Promise<string> {
  const cookie = (await context.cookies(origin)).find(value => value.name === name)
  expect(cookie, `cookie ${name}`).toBeDefined()
  return cookie!.value
}

function handleApi(request: IncomingMessage, response: ServerResponse, sessions: Map<string, Session>): void {
  const cookies = parseCookies(request.headers.cookie)
  let sessionId = cookies.SESSION
  let session = sessionId ? sessions.get(sessionId) : undefined
  if (!session) {
    sessionId = randomUUID()
    session = { username: null }
    sessions.set(sessionId, session)
    appendCookie(response, `SESSION=${sessionId}; Path=/; HttpOnly; SameSite=Lax`)
  }
  let csrf = cookies['XSRF-TOKEN']
  if (!csrf) {
    csrf = randomUUID()
    appendCookie(response, `XSRF-TOKEN=${csrf}; Path=/; SameSite=Lax`)
  }

  const path = new URL(request.url ?? '/', 'http://api.local').pathname
  if (request.method === 'GET' && path === '/api/v1/auth/session') {
    sendJson(response, 200, { authenticated: Boolean(session.username), username: session.username })
    return
  }
  if (request.method === 'POST' && request.headers['x-xsrf-token'] !== csrf) {
    sendJson(response, 403, { code: 'csrf_rejected' })
    return
  }
  if (request.method === 'POST' && path === '/api/v1/auth/login') {
    readJson(request).then(credentials => {
      const username = String(credentials.username)
      const password = String(credentials.password)
      if (fixturePasswords.get(username) !== password) {
        sendJson(response, 401, { code: 'invalid_credentials' })
        return
      }
      sessions.delete(sessionId)
      const rotated = randomUUID()
      sessions.set(rotated, { username })
      appendCookie(response, `SESSION=${rotated}; Path=/; HttpOnly; SameSite=Lax`)
      sendJson(response, 200, { authenticated: true, username })
    }).catch(() => sendJson(response, 400, { code: 'invalid_request' }))
    return
  }
  if (request.method === 'POST' && path === '/api/v1/spike/write') {
    if (!session.username) {
      sendJson(response, 401, { code: 'authentication_required' })
      return
    }
    sendJson(response, 200, { accepted: true, actor: session.username })
    return
  }
  if (request.method === 'POST' && path === '/api/v1/auth/logout') {
    sessions.delete(sessionId)
    appendCookie(response, 'SESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0')
    response.writeHead(204)
    response.end()
    return
  }
  sendJson(response, 404, { code: 'not_found' })
}

function proxyWebRequest(request: IncomingMessage, response: ServerResponse, apiOrigin: string): void {
  const path = new URL(request.url ?? '/', 'http://web.local').pathname
  if (!path.startsWith('/api/')) {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><html><body><main>CrewScope M7 Session Spike</main></body></html>')
    return
  }
  const target = new URL(request.url ?? '/', apiOrigin)
  const proxied = nodeRequest(target, {
    method: request.method,
    headers: { ...request.headers, host: target.host },
  }, apiResponse => {
    response.writeHead(apiResponse.statusCode ?? 502, apiResponse.headers)
    apiResponse.pipe(response)
  })
  proxied.on('error', () => sendJson(response, 502, { code: 'proxy_unavailable' }))
  request.pipe(proxied)
}

function parseCookies(header?: string): Record<string, string> {
  return Object.fromEntries((header ?? '').split(';').filter(Boolean).map(entry => {
    const separator = entry.indexOf('=')
    return [entry.slice(0, separator).trim(), entry.slice(separator + 1)]
  }))
}

function appendCookie(response: ServerResponse, cookie: string): void {
  const current = response.getHeader('Set-Cookie')
  response.setHeader('Set-Cookie', [...(Array.isArray(current) ? current : current ? [String(current)] : []), cookie])
}

function sendJson(response: ServerResponse, status: number, body: Record<string, unknown>): void {
  response.writeHead(status, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}

async function readJson(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = []
  let receivedBytes = 0
  for await (const chunk of request) {
    const bytes = Buffer.from(chunk)
    receivedBytes += bytes.length
    if (receivedBytes > MAX_LOGIN_BODY_BYTES) {
      throw new Error('login request exceeds the fixture budget')
    }
    chunks.push(bytes)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string, unknown>
}

async function listen(server: Server): Promise<string> {
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address() as AddressInfo
  return `http://127.0.0.1:${address.port}`
}

async function close(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
}
