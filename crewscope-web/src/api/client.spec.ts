import { CrewScopeApiClient, CrewScopeApiError } from './client'

describe('CrewScopeApiClient', () => {
  it('sends command protocol headers and JSON body', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    const client = new CrewScopeApiClient('/api/v1', fetcher)

    await client.post('/work-items', { title: 'Demo' }, {
      idempotencyKey: 'cmd-001',
      expectedVersion: 7,
    })

    expect(fetcher).toHaveBeenCalledOnce()
    const [url, request] = fetcher.mock.calls[0]!
    const headers = new Headers(request?.headers)
    expect(url).toBe('/api/v1/work-items')
    expect(headers.get('Accept')).toBe('application/json')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Idempotency-Key')).toBe('cmd-001')
    expect(headers.get('If-Match')).toBe('"7"')
    expect(request?.credentials).toBe('same-origin')
    expect(request?.body).toBe(JSON.stringify({ title: 'Demo' }))
    expect(request).not.toHaveProperty('idempotencyKey')
    expect(request).not.toHaveProperty('expectedVersion')
  })

  it('invokes a stored fetch function with the browser global receiver', async () => {
    const receivers: unknown[] = []
    const fetcher = vi.fn(function (this: unknown) {
      receivers.push(this)
      return Promise.resolve(new Response(JSON.stringify({ ok: true }), { status: 200 }))
    }) as unknown as typeof fetch
    const client = new CrewScopeApiClient('/api/v1', fetcher)

    await client.get('/teams')

    expect(receivers).toEqual([globalThis])
  })

  it('forwards Spring Security Cookie CSRF tokens on state-changing OIDC requests', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-token%3D42; path=/'
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }))
    const client = new CrewScopeApiClient('/api/v1', fetcher)

    await client.post('/teams/team-1/members', { userPrincipalId: 'principal-1' })

    const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-token=42')
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  it('preserves a valid service error envelope', async () => {
    const envelope = {
      code: 'version_conflict',
      message: '事实已更新',
      correlationId: 'corr-101',
      retryable: false,
      currentVersion: 8,
      details: { field: 'status' },
    }
    const client = new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify(envelope), { status: 409, headers: { 'Content-Type': 'application/json' } }),
    ))

    const error = await client.get('/work-items/CRW-18').catch(value => value) as CrewScopeApiError

    expect(error).toBeInstanceOf(CrewScopeApiError)
    expect(error.status).toBe(409)
    expect(error.envelope).toEqual(envelope)
  })

  it('normalizes a non-JSON server failure without leaking its body', async () => {
    const client = new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>().mockResolvedValue(
      new Response('<h1>proxy error</h1>', { status: 502, headers: { 'X-Correlation-Id': 'corr-proxy' } }),
    ))

    const error = await client.get('/health').catch(value => value) as CrewScopeApiError

    expect(error.envelope).toEqual(expect.objectContaining({
      code: 'invalid_server_response',
      correlationId: 'corr-proxy',
      retryable: true,
    }))
    expect(error.message).not.toContain('proxy error')
  })

  it('normalizes transport failures into a retryable API error', async () => {
    const client = new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('socket detail')))

    const error = await client.get('/health').catch(value => value) as CrewScopeApiError

    expect(error.status).toBe(0)
    expect(error.envelope.code).toBe('network_unavailable')
    expect(error.envelope.retryable).toBe(true)
    expect(error.message).not.toContain('socket detail')
  })

  it('notifies the identity boundary only for authentication-required 401 responses', async () => {
    const handler = vi.fn()
    const fetcher = vi.fn(async () => new Response(JSON.stringify({
      code: 'authentication_required', message: 'Session expired', correlationId: 'corr-401',
      retryable: false, currentVersion: null, details: {},
    }), { status: 401, headers: { 'Content-Type': 'application/json' } }))
    const client = new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch)
    client.onAuthenticationRequired(handler)

    await expect(client.get('/teams')).rejects.toMatchObject({ status: 401 })

    expect(handler).toHaveBeenCalledOnce()
  })
})
