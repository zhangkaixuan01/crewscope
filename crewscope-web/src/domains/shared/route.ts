/**
 * Guards every server-supplied navigation target before it reaches the router.
 *
 * Search results, WorkDesk items and transition remedies all carry a `route` computed by the
 * backend. Treating those as trusted would turn any server-side string into an open redirect, so
 * the rule is the same everywhere: an in-app absolute path, no protocol-relative prefix, no
 * traversal, no whitespace or fragment/escape characters.
 */
export function safeRoute(value: string): boolean {
  return value.startsWith('/')
    && !value.startsWith('//')
    && !value.includes('..')
    && !value.includes('\\')
    && !/[\s#%]/.test(value)
}
