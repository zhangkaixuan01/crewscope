import { canonicalCommandInput, commandFailure } from './commandIntent'
import { CrewScopeApiError } from './client'
import { secureId } from './secureId'

/** Explicit opt-in for non-secret, idempotent Gateway methods; the number is the key argument. */
export function createCommandGateway<T extends object>(source: T, methods: Partial<Record<keyof T, number>>, limit = 100) {
  const intents = new Map<string, { key: string, uncertain: boolean, pending: Promise<unknown> | null }>()
  const keyOwners = new Map<string, string>()
  let epoch = 0
  const gateway = new Proxy(source, {
    get(target, property, receiver) {
      const method = Reflect.get(target, property, receiver)
      const keyIndex = methods[property as keyof T]
      if (typeof method !== 'function' || keyIndex === undefined) return typeof method === 'function' ? method.bind(target) : method
      return (...args: unknown[]) => {
        // No mutable Vue objects, caller keys, AbortSignals, or credentials in the fingerprint.
        const input = args.slice(0, keyIndex).map(value => value === undefined ? undefined : JSON.parse(canonicalCommandInput(value)))
        const fingerprint = canonicalCommandInput([String(property), input.map(value => value ?? null)])
        let intent = intents.get(fingerprint)
        if (intent?.pending) return intent.pending
        if (!intent) {
          if (intents.size >= limit) throw new CrewScopeApiError(429, {
            code: 'command_recovery_capacity', message: '待确认操作过多，请先核实原操作。',
            correlationId: 'unavailable', retryable: false, currentVersion: null, details: {},
          })
          const proposed = typeof args[keyIndex] === 'string' ? args[keyIndex] as string : secureId()
          const key = keyOwners.has(proposed) && keyOwners.get(proposed) !== fingerprint ? secureId() : proposed
          intent = { key, uncertain: false, pending: null }
          intents.set(fingerprint, intent)
          keyOwners.set(key, fingerprint)
        }
        const current = intent
        const started = epoch
        // Install pending before dispatch, including gateways that throw synchronously.
        let resolve!: (value: unknown) => void
        let reject!: (error: unknown) => void
        const pending = new Promise<unknown>((yes, no) => { resolve = yes; reject = no })
        current.pending = pending
        const success = (value: unknown) => {
          if (started !== epoch) { reject(new DOMException('Command context changed', 'AbortError')); return }
          intents.delete(fingerprint)
          keyOwners.delete(current.key)
          current.pending = null
          resolve(value)
        }
        const failure = (error: unknown) => {
          if (commandFailure(error) === 'unknown') current.uncertain = true
          if (!current.uncertain && intents.get(fingerprint) === current) {
            intents.delete(fingerprint)
            keyOwners.delete(current.key)
          }
          current.pending = null
          const uncertain = commandFailure(error) === 'unknown'
          const presented = uncertain ? new CrewScopeApiError(error instanceof CrewScopeApiError ? error.status : 0, {
            code: error instanceof CrewScopeApiError ? error.envelope.code : 'command_result_unknown',
            message: '提交结果尚未确认。请保留当前内容，重试会沿用原操作标识；请勿重复新建。',
            correlationId: error instanceof CrewScopeApiError ? error.envelope.correlationId : 'unavailable',
            retryable: true, currentVersion: null, details: {},
          }) : error
          reject(started === epoch ? presented : new DOMException('Command context changed', 'AbortError'))
        }
        try { Promise.resolve(method.apply(target, [...input, current.key, ...args.slice(keyIndex + 1)])).then(success, failure) }
        catch (error) { failure(error) }
        return pending
      }
    },
  })
  return { gateway, clear: () => { epoch += 1; intents.clear(); keyOwners.clear() } }
}
