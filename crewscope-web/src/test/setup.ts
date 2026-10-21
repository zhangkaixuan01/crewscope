import { vi } from 'vitest'

// jsdom-only patches: node-environment specs (logic without a DOM) run this file too,
// where the globals do not exist — skip instead of throwing at collection time.
if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'scrollTo', { value: vi.fn(), writable: true })
  // jsdom has no layout, so locating a deep-linked row would throw before it could be asserted.
  Object.defineProperty(Element.prototype, 'scrollIntoView', { value: vi.fn(), writable: true })
}
