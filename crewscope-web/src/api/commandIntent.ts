import { CrewScopeApiError } from './client'
import { secureId } from './secureId'

export type CommandFailure = 'unknown' | 'rejected' | 'conflict'

/** A retryable transport error is not proof that a write was rejected. */
export function commandFailure(error: unknown): CommandFailure {
  if (!(error instanceof CrewScopeApiError)) return 'unknown'
  if (error.envelope.code === 'secure_random_unavailable') return 'rejected'
  if (error.status === 409 || error.status === 412) return 'conflict'
  if (error.status === 0 || error.status === 408 || error.status >= 500) return 'unknown'
  return error.status >= 400 && error.status < 500 ? 'rejected' : 'unknown'
}

export function commandFailureMessage(error: unknown, fallback: string): string {
  if (error instanceof CrewScopeApiError && ['creation_result_pending', 'creation_projection_pending'].includes(error.envelope.code))
    return error.envelope.message
  return commandFailure(error) === 'unknown'
    ? '提交结果尚未确认。请保留当前内容，再次提交将使用原操作标识，不会自动新建另一条命令。'
    : error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

/** Canonical JSON for equality of in-memory request snapshots, not a persisted hash or secret. */
export function canonicalCommandInput(value: unknown): string {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value)
  if (typeof value === 'number' && Number.isFinite(value)) return JSON.stringify(value)
  if (Array.isArray(value)) return `[${Array.from(value, canonicalCommandInput).join(',')}]`
  if (typeof value === 'object' && value !== null) {
    const prototype = Object.getPrototypeOf(value)
    if (prototype !== Object.prototype && prototype !== null) throw new TypeError('Expected a JSON command input')
    const record = value as Record<string, unknown>
    return `{${Object.keys(record).filter(key => record[key] !== undefined).sort()
      .map(key => `${JSON.stringify(key)}:${canonicalCommandInput(record[key])}`).join(',')}}`
  }
  throw new TypeError('Expected a JSON command input')
}

interface Intent<I, R> {
  key: string
  input: I
  pending: Promise<R> | null
  uncertain: boolean
}

/**
 * Opt-in ONLY for endpoints with a server idempotency contract. No automatic retry or persistence.
 * Owners clear this session-local registry on identity reset. It never evicts unresolved intents
 * to make room: that would silently give an uncertain command a new key.
 */
export function createCommandIntents<I, R>(limit = 100) {
  const intents = new Map<string, Intent<I, R>>()

  function execute(coordinates: Record<string, unknown>, input: I, send: (input: I, key: string) => Promise<R>): Promise<R> {
    const canonicalInput = canonicalCommandInput(input)
    const fingerprint = canonicalCommandInput([coordinates, JSON.parse(canonicalInput)])
    let intent = intents.get(fingerprint)
    if (intent?.pending) return intent.pending
    if (!intent) {
      if (intents.size >= limit) {
        throw new CrewScopeApiError(429, {
          code: 'command_recovery_capacity', message: '待确认的操作过多，请先核实已有操作，不要重复创建。',
          correlationId: 'unavailable', retryable: false, currentVersion: null, details: {},
        })
      }
      intent = { key: secureId(), input: JSON.parse(canonicalInput) as I, pending: null, uncertain: false }
      intents.set(fingerprint, intent)
    }
    const current = intent
    // Defer dispatch so duplicate synchronous callers see pending before any adapter is invoked.
    const pending = Promise.resolve().then(() => {
      if (intents.get(fingerprint) !== current) throw new Error('Command context was cleared before dispatch')
      return send(JSON.parse(canonicalCommandInput(current.input)) as I, current.key)
    })
      .then(result => {
        if (intents.get(fingerprint) === current) intents.delete(fingerprint)
        return result
      }, error => {
        if (commandFailure(error) === 'unknown') current.uncertain = true
        // A later permission/conflict response cannot prove that an earlier lost response did not commit.
        if (!current.uncertain && intents.get(fingerprint) === current) intents.delete(fingerprint)
        throw error
      }).finally(() => { current.pending = null })
    current.pending = pending
    return pending
  }

  return { execute, clear: () => intents.clear() }
}
