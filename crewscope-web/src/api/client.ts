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
  constructor(
    private readonly baseUrl = '/api/v1',
    private readonly fetcher: typeof fetch = fetch,
  ) {}

  get<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: 'GET' })
  }

  post<T>(path: string, body: unknown, options: ApiRequestOptions = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: 'POST', body })
  }

  async request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    // Command metadata belongs to CrewScope's client contract and must not leak into Fetch options.
    const { idempotencyKey, expectedVersion, body, ...requestInit } = options
    const headers = new Headers(requestInit.headers)
    headers.set('Accept', JSON_CONTENT_TYPE)
    if (body !== undefined) {
      headers.set('Content-Type', JSON_CONTENT_TYPE)
    }
    if (idempotencyKey) {
      headers.set('Idempotency-Key', idempotencyKey)
    }
    if (expectedVersion !== undefined) {
      headers.set('If-Match', `"${expectedVersion}"`)
    }

    let response: Response
    try {
      response = await this.fetcher(`${this.baseUrl}${normalizePath(path)}`, {
        ...requestInit,
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
      throw new CrewScopeApiError(response.status, await readErrorEnvelope(response))
    }
    if (response.status === 204) {
      return undefined as T
    }
    return response.json() as Promise<T>
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
