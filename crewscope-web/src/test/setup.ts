import { vi } from 'vitest'

Object.defineProperty(window, 'scrollTo', { value: vi.fn(), writable: true })
// jsdom has no layout, so locating a deep-linked row would throw before it could be asserted.
Object.defineProperty(Element.prototype, 'scrollIntoView', { value: vi.fn(), writable: true })
