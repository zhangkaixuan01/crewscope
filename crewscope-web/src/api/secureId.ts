import { CrewScopeApiError } from './client'

/** randomUUID needs a secure context; getRandomValues also works on ordinary HTTP. */
export function secureId(): string {
  try {
    const source = globalThis.crypto
    if (typeof source?.randomUUID === 'function') return source.randomUUID()
    if (typeof source?.getRandomValues === 'function') {
      const bytes = source.getRandomValues(new Uint8Array(16))
      bytes[6] = (bytes[6]! & 0x0f) | 0x40
      bytes[8] = (bytes[8]! & 0x3f) | 0x80
      const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
    }
  } catch {
    // A broken/restricted random source is not permission to fall back to time or Math.random.
  }
  throw new CrewScopeApiError(0, {
    code: 'secure_random_unavailable',
    message: '浏览器无法生成安全操作标识，本次操作尚未发送。请更新浏览器或检查安全设置后再试。',
    correlationId: 'unavailable', retryable: false, currentVersion: null, details: {},
  })
}
