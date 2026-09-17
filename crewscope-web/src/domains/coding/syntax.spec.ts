import { beforeAll, describe, expect, it } from 'vitest'
import { hasSyntax, loadSyntax, plainTokenLine, syntaxLanguageLabel, syntaxTokenLines, type SyntaxToken } from './syntax'

/**
 * The highlighter is only as good as the grammars it actually managed to load, and a grammar that
 * quietly failed to arrive looks exactly like a file type we never supported: plain text. So the
 * central case here tokenizes a real sample of **every** supported language and asserts that colour
 * came out — that is the evidence the dependency chains in `loadSyntax` are right, and it fails loudly
 * if Prism renames a grammar or a component stops extending the one before it.
 */

const samples: Record<string, { sample: string, path: string }> = {
  TypeScript: { path: 'src/app/session.ts', sample: 'export function total(count: number): number {\n  return count + 1\n}\n' },
  TSX: { path: 'src/components/Card.tsx', sample: 'const card = <Panel title="面板" count={3} />\n' },
  JavaScript: { path: 'scripts/build.js', sample: 'function greet(name) {\n  return "hi"\n}\n' },
  JSX: { path: 'src/components/Panel.jsx', sample: 'const panel = <Panel title="面板" />\n' },
  Java: { path: 'src/main/java/Runner.java', sample: 'public class Runner {\n  private int count = 3;\n}\n' },
  Kotlin: { path: 'src/main/kotlin/Main.kt', sample: 'fun main() {\n  val n: Int = 1\n}\n' },
  Python: { path: 'tools/report.py', sample: 'def run(count):\n    return count + 1\n' },
  Go: { path: 'cmd/server/main.go', sample: 'package main\n\nfunc run() int {\n  return 1\n}\n' },
  Rust: { path: 'src/lexer.rs', sample: 'fn run() -> i32 {\n    let n: i32 = 1;\n    n\n}\n' },
  Markdown: { path: 'docs/README.md', sample: '# 标题\n\n正文\n' },
  CSS: { path: 'src/design/tokens.css', sample: '.card { color: red; }\n' },
  JSON: { path: 'package.json', sample: '{"name": "面板", "count": 3}\n' },
  YAML: { path: '.github/workflows/ci.yml', sample: 'name: 面板\ncount: 3\n' },
}

/** Flattens the token rows back into the text they were made from. */
function reconstruct(lines: SyntaxToken[][]): string {
  return lines.map(line => line.map(token => token.text).join('')).join('\n')
}

function kindsOf(lines: SyntaxToken[][]): Set<string> {
  return new Set(lines.flat().map(token => token.kind))
}

describe('syntax highlighting', () => {
  beforeAll(async () => {
    for (const { path } of Object.values(samples)) await loadSyntax(path)
  })

  it.each(Object.entries(samples))('highlights %s, so its grammar and its dependencies loaded', (_label, { path, sample }) => {
    const lines = syntaxTokenLines(sample, path)

    expect(lines).not.toBeNull()
    // 不是「有一个 token」而是「有强调色」：整段回落纯文本时这里才是空的，也正是本用例要拦的情形。
    expect([...kindsOf(lines!)]).not.toEqual(['plain'])
  })

  it.each(Object.entries(samples))('rebuilds %s exactly, character for character', (_label, { path, sample }) => {
    const lines = syntaxTokenLines(sample, path)!

    // 逐字符复原是同一条不变量，同时钉住两件事：没有字符被丢掉，也没有被复制。
    expect(reconstruct(lines)).toBe(sample)
    expect(lines).toHaveLength(sample.split('\n').length)
  })

  it('classifies TypeScript across all six emphasis classes', () => {
    const lines = syntaxTokenLines(
      '/** 累计 */\nexport function total(count: number): number {\n  const label: string = "总计" // 再算一次\n  return count + 1\n}\n',
      'src/app/total.ts',
    )
    const kinds = kindsOf(lines!)

    for (const kind of ['comment', 'keyword', 'function', 'number', 'type', 'string']) {
      expect(kinds, `TypeScript 应当产出 ${kind}`).toContain(kind)
    }
    // 标点与运算符不是强调，它们就是代码本身。
    const punctuation = lines!.flat().filter(token => token.text === '(' || token.text === '{')
    expect(punctuation.every(token => token.kind === 'plain')).toBe(true)
  })

  it('keeps a line count that a multi-line construct cannot shift', () => {
    // 块注释、模板字符串与 Markdown 围栏都跨行，逐行切分会把第一行之后的每一行都染错。
    const source = [
      'const note = `第一行',
      '第二行`;',
      '/* 一段',
      '   跨行的注释 */',
      'const done = 1;',
      '',
    ].join('\n')
    const lines = syntaxTokenLines(source, 'src/app/note.ts')!

    expect(lines).toHaveLength(6)
    expect(reconstruct(lines)).toBe(source)
    // 注释的第二行仍然被认成注释：这正是逐行切分做不到的地方。
    expect(lines[3]!.every(token => token.kind === 'comment')).toBe(true)
    // 空行留成空行，不会被并进上一行。
    expect(lines[5]).toEqual([])
  })

  it('answers for a file type it has no grammar for', () => {
    expect(hasSyntax('design/logo.svg')).toBe(false)
    expect(syntaxLanguageLabel('design/logo.svg')).toBe('纯文本')
    expect(syntaxLanguageLabel('DESIGN/logo.SVG')).toBe('纯文本')
    expect(syntaxTokenLines('<svg/>', 'design/logo.svg')).toBeNull()
    // 大小写与无扩展名都不该误判成某种语言。
    expect(hasSyntax('Makefile')).toBe(false)
    expect(syntaxLanguageLabel('src/app/session.TS')).toBe('TypeScript')
  })

  it('offers a single plain token for a line it cannot highlight', () => {
    expect(plainTokenLine('  return 1')).toEqual([{ text: '  return 1', kind: 'plain' }])
  })
})
