import type { ApiErrorEnvelope, ApiRequestOptions } from './types'

const JSON_CONTENT_TYPE = 'application/json'

export class CrewScopeApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly envelope: ApiErrorEnvelope,
  ) {
    super(envelope.message)
    this.name = 'CrewScopeApiError'
  }
}

export class CrewScopeApiClient {
  private authenticationRequiredHandler: (() => void) | null = null

  constructor(
    private readonly baseUrl = '/api/v1',
    private readonly fetcher: typeof fetch = fetch,
  ) {}

  onAuthenticationRequired(handler: (() => void) | null): void {
    this.authenticationRequiredHandler = handler
  }

  get<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: 'GET' })
  }

  post<T>(path: string, body: unknown, options: ApiRequestOptions = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: 'POST', body })
  }

  async request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const response = await this.open(path, options)
    if (response.status === 204) {
      return undefined as T
    }
    return response.json() as Promise<T>
  }

  /** Opens a validated response for streaming adapters while retaining CrewScope's HTTP boundary. */
  async open(path: string, options: ApiRequestOptions = {}, accept = JSON_CONTENT_TYPE): Promise<Response> {
    // Command metadata belongs to CrewScope's client contract and must not leak into Fetch options.
    const { idempotencyKey, expectedVersion, body, ...requestInit } = options
    const headers = new Headers(requestInit.headers)
    headers.set('Accept', accept)
    if (body !== undefined) {
      headers.set('Content-Type', JSON_CONTENT_TYPE)
    }
    if (idempotencyKey) {
      headers.set('Idempotency-Key', idempotencyKey)
    }
    if (expectedVersion !== undefined) {
      headers.set('If-Match', `"${expectedVersion}"`)
    }
    const csrfToken = csrfTokenFor(requestInit.method)
    if (csrfToken && !headers.has('X-XSRF-TOKEN')) {
      // Spring Security's CookieServerCsrfTokenRepository uses this cookie/header pair in OIDC mode.
      headers.set('X-XSRF-TOKEN', csrfToken)
    }

    let response: Response
    try {
      // Native browser fetch performs a receiver brand check in some engines. Calling the stored
      // function with globalThis avoids an "Illegal invocation" when the client owns the reference.
      response = await this.fetcher.call(globalThis, `${this.baseUrl}${normalizePath(path)}`, {
        ...requestInit,
        // CrewScope is served through one Web origin. Making the browser default explicit keeps
        // Session and CSRF cookies attached to proxied API requests without enabling cross-origin
        // credential forwarding.
        credentials: 'same-origin',
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch (error) {
      // Cancellation belongs to the caller; transport failures enter the stable API error boundary.
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw error
      }
      throw new CrewScopeApiError(0, {
        code: 'network_unavailable',
        message: '网络连接不可用，请检查连接后重试',
        correlationId: 'unavailable',
        retryable: true,
        currentVersion: null,
        details: {},
      })
    }
    if (!response.ok) {
      const error = new CrewScopeApiError(response.status, await readErrorEnvelope(response))
      if (error.status === 401 && error.envelope.code === 'authentication_required') {
        this.authenticationRequiredHandler?.()
      }
      throw error
    }
    return response
  }
}

function csrfTokenFor(method?: string): string | null {
  const normalizedMethod = method?.toUpperCase() ?? 'GET'
  if (['GET', 'HEAD', 'OPTIONS'].includes(normalizedMethod) || typeof document === 'undefined') return null
  const cookie = document.cookie
    .split(';')
    .map(value => value.trim())
    .find(value => value.startsWith('XSRF-TOKEN='))
  if (!cookie) return null
  try {
    return decodeURIComponent(cookie.slice('XSRF-TOKEN='.length))
  } catch {
    return null
  }
}

function normalizePath(path: string): string {
  return path.startsWith('/') ? path : `/${path}`
}

async function readErrorEnvelope(response: Response): Promise<ApiErrorEnvelope> {
  try {
    const envelope = await response.json() as Partial<ApiErrorEnvelope>
    if (envelope.code && envelope.message && envelope.correlationId) {
      return {
        code: envelope.code,
        message: envelope.message,
        correlationId: envelope.correlationId,
        retryable: Boolean(envelope.retryable),
        currentVersion: envelope.currentVersion ?? null,
        details: envelope.details ?? {},
      }
    }
  } catch {
    // Fall through to a stable client-side envelope when the server response is not JSON.
  }
  return {
    code: 'invalid_server_response',
    message: '服务暂时无法完成请求',
    correlationId: response.headers.get('X-Correlation-Id') ?? 'unavailable',
    retryable: response.status >= 500,
    currentVersion: null,
    details: {},
  }
}

export const apiClient = new CrewScopeApiClient()
