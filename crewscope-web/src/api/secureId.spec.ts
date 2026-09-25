import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { secureId } from './secureId'

describe('secure command IDs', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('uses native randomUUID with the Crypto receiver', () => {
    const source = { randomUUID() { expect(this).toBe(source); return 'native-id' } }
    vi.stubGlobal('crypto', source)
    expect(secureId()).toBe('native-id')
  })

  it('uses only getRandomValues on HTTP and sets UUID version/variant bits', () => {
    const source = { getRandomValues(bytes: Uint8Array) { expect(this).toBe(source); bytes.fill(255); return bytes } }
    vi.stubGlobal('crypto', source)
    const insecureRandom = vi.spyOn(Math, 'random').mockImplementation(() => { throw new Error('forbidden') })
    try {
      expect(secureId()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
    } finally { insecureRandom.mockRestore() }
  })

  it('uses fresh random bytes for every UUID', () => {
    const getRandomValues = globalThis.crypto.getRandomValues.bind(globalThis.crypto)
    vi.stubGlobal('crypto', { getRandomValues })
    const ids = Array.from({ length: 500 }, () => secureId())
    expect(new Set(ids).size).toBe(500)
    expect(ids.every(id => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id))).toBe(true)
  })

  it.each([undefined, {}, { getRandomValues() { throw new Error('denied') } }])('refuses absent or broken secure randomness', source => {
    vi.stubGlobal('crypto', source)
    expect(secureId).toThrow('本次操作尚未发送')
  })

  it('keeps production call sites behind this HTTP-compatible boundary', () => {
    const root = resolve('src')
    const violations = readdirSync(root, { recursive: true, encoding: 'utf8' }).filter(file =>
      /\.(ts|vue)$/.test(file) && !file.includes('.spec.') && !file.includes('.story.') && !file.startsWith('test/'))
      .filter(file => /(?:window\.)?crypto\.randomUUID\s*\(/.test(readFileSync(resolve(root, file), 'utf8')))
    expect(violations).toEqual([])
  })
})
