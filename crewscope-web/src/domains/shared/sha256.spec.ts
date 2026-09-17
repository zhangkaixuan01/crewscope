import { webcrypto } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { sha256Digest, sha256Hex } from './sha256'

/*
 * The vectors are published SHA-256 digests (the 56-byte one is the NIST two-block sample) and the
 * UTF-8 ones were cross-checked against the server's `RuntimeContentHash.sha256`, which hashes the
 * same bytes with `MessageDigest`. Byte parity with the server is the whole point of this module:
 * the Diff comment anchor and the Coding evidence descriptors are verified against those digests.
 */
describe('sha256Hex', () => {
  it('matches the published vectors', () => {
    expect(sha256Hex(bytes(''))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256Hex(bytes('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256Hex(bytes('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')))
      .toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1')
  })

  it('hashes UTF-8 bytes the way the server does', () => {
    expect(sha256Hex(bytes('你好，CrewScope'))).toBe('23189c02d91cb6a15b890ad02aeaf2fe8467f7c26c7ca9da8f66740942c88ccc')
    expect(sha256Hex(bytes('评论内容 anchor 1'))).toBe('dda25a07edabd31bc3121fed768ada1714571a41c3f320aaa836412956bde7a0')
  })

  it('covers the padding boundaries of a single block', () => {
    expect(sha256Hex(bytes('a'.repeat(64)))).toBe('ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb')
    expect(sha256Hex(new Uint8Array(1_000_000).fill(0x61)))
      .toBe('cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0')
  })

  it('does not disturb the caller’s bytes', () => {
    const source = bytes('abc')
    sha256Hex(source)
    expect(source).toEqual(bytes('abc'))
  })
})

describe('sha256Digest', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('agrees with the pure implementation when WebCrypto is available', async () => {
    vi.stubGlobal('crypto', { subtle: webcrypto.subtle })
    await expect(sha256Digest(bytes('评论内容 anchor 1'))).resolves.toBe(sha256Hex(bytes('评论内容 anchor 1')))
  })

  it('falls back when the runtime exposes no SubtleCrypto', async () => {
    vi.stubGlobal('crypto', {})
    await expect(sha256Digest(bytes('abc'))).resolves.toBe(sha256Hex(bytes('abc')))
  })

  it('falls back when SubtleCrypto refuses the request', async () => {
    vi.stubGlobal('crypto', { subtle: { digest: () => Promise.reject(new Error('insecure context')) } })
    await expect(sha256Digest(bytes('abc'))).resolves.toBe(sha256Hex(bytes('abc')))
  })
})

function bytes(value: string): Uint8Array {
  return new TextEncoder().encode(value)
}
