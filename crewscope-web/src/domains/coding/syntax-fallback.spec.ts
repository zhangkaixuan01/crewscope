import { describe, expect, it, vi } from 'vitest'

/**
 * The fallback path, in its own file because it needs a grammar chunk that does not arrive.
 *
 * This is not a hypothetical: a language chunk is a separate network request, and a Diff that fails to
 * render because a syntax chunk was slow or blocked would be strictly worse than a Diff without colour.
 * `loadSyntax` reports the failure and the viewer shows plain text — so an unreachable chunk has to
 * resolve `false`, not reject and not claim success.
 */
vi.mock('prismjs/components/prism-rust', () => {
  throw new Error('syntax chunk unavailable')
})

const { syntaxTokenLines, loadSyntax, hasSyntax } = await import('./syntax')

describe('syntax highlighting when a grammar chunk does not arrive', () => {
  it('reports the failure instead of throwing', async () => {
    await expect(loadSyntax('src/lexer.rs')).resolves.toBe(false)
  })

  it('keeps saying so rather than retrying on every line', async () => {
    // 失败也被记住：否则每渲染一次 Diff 就要再发一次注定失败的请求。
    await expect(loadSyntax('src/lexer.rs')).resolves.toBe(false)
    await expect(loadSyntax('src/lexer.rs')).resolves.toBe(false)
  })

  it('leaves the file type recognised, so the viewer still names it', () => {
    expect(hasSyntax('src/lexer.rs')).toBe(true)
    // 语言是已知的，只是语法没到；调用方据此回落到纯文本，而不是显示「纯文本」这个语言名。
    expect(syntaxTokenLines('fn run() {}', 'src/lexer.rs')).toBeNull()
  })

  it('does not affect a language whose grammar did load', async () => {
    await expect(loadSyntax('src/lexer.ts')).resolves.toBe(true)
    expect(syntaxTokenLines('const a = 1\n', 'src/lexer.ts')).not.toBeNull()
  })
})
