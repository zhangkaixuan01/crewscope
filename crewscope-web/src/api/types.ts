export interface ApiErrorEnvelope {
  code: string
  message: string
  correlationId: string
  retryable: boolean
  currentVersion: number | null
  details: Record<string, string>
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body' | 'credentials'> {
  body?: unknown
  idempotencyKey?: string
  expectedVersion?: number
}
