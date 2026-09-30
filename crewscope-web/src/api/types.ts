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
  /**
   * Marks a preview question (a preflight) rather than an acting request. A preview denial —
   * 403 POLICY_DENIED answering a hypothetical the form asked on purpose — is not a membership
   * revocation, so it must not fire the F05 forbidden sink and wipe the team's local drafts
   * (M9b-Q02: a slow preflight 403 landed mid-typing and ate the configure-return draft).
   */
  preview?: boolean
}
