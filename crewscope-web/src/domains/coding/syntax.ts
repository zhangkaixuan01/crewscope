import Prism from 'prismjs'

/**
 * Syntax highlighting for the Diff viewer, on Prism.
 *
 * Two decisions shape this file, and both are about what a Diff actually is: somebody else's code,
 * rendered inside our page.
 *
 * **Prism tokenizes to a tree, not to markup.** `highlight()` returns an HTML string, which this
 * viewer would have to trust and inject — and DOMPurify would then have to be re-audited for it. The
 * Token stream needs neither: the caller renders the tokens as elements, so no string from a patch
 * ever becomes markup. That is why the public entry point returns token arrays and the HTML path is
 * never called.
 *
 * **Grammars load per file type, on demand.** Only the language of the file being read is fetched,
 * and a grammar that fails to load is not an error the reader has to handle: `loadSyntax` reports
 * false and the viewer falls back to plain text, which is exactly what it showed before this file
 * existed. A Diff that will not render because a syntax chunk was slow is a worse trade than a Diff
 * without colour.
 */

/** The emphasis classes the viewer can render. Anything unclassified stays plain. */
export type SyntaxTokenKind = 'plain' | 'keyword' | 'string' | 'comment' | 'function' | 'number' | 'type'

export interface SyntaxToken {
  readonly text: string
  readonly kind: SyntaxTokenKind
}

interface SyntaxLanguage {
  /** What the viewer prints for this file type. */
  readonly label: string
  /** The Prism grammar to tokenize with. */
  readonly grammar: string
  /**
   * The grammars to load before it, in order.
   *
   * Order is not decoration. Each grammar file evaluates `Prism.languages.extend('<parent>')` at
   * module scope, and extending a grammar that is not there yet throws — `Cannot set properties of
   * undefined` — rather than degrading, so `tsx` above all must come after both `jsx` and
   * `typescript`. Prism's core bundle happens to ship markup, css, clike and javascript already, but
   * each entry below lists everything it needs anyway: a table whose correctness depends on what the
   * core bundle includes this release is a table that breaks on a dependency bump.
   *
   * Every list is read off its component's own `extend`/`insertBefore` call, and a test tokenizes a
   * sample of every supported language, so a list that is wrong shows up as a failure rather than as
   * a file type that quietly renders plain.
   */
  readonly grammars: readonly string[]
}

const plainText = '纯文本'

const languages: Record<string, SyntaxLanguage> = {
  ts: { label: 'TypeScript', grammar: 'typescript', grammars: ['clike', 'javascript', 'typescript'] },
  tsx: { label: 'TSX', grammar: 'tsx', grammars: ['markup', 'clike', 'javascript', 'jsx', 'typescript', 'tsx'] },
  js: { label: 'JavaScript', grammar: 'javascript', grammars: ['clike', 'javascript'] },
  jsx: { label: 'JSX', grammar: 'jsx', grammars: ['markup', 'clike', 'javascript', 'jsx'] },
  java: { label: 'Java', grammar: 'java', grammars: ['clike', 'java'] },
  kt: { label: 'Kotlin', grammar: 'kotlin', grammars: ['clike', 'kotlin'] },
  py: { label: 'Python', grammar: 'python', grammars: ['python'] },
  go: { label: 'Go', grammar: 'go', grammars: ['clike', 'go'] },
  rs: { label: 'Rust', grammar: 'rust', grammars: ['rust'] },
  md: { label: 'Markdown', grammar: 'markdown', grammars: ['markup', 'markdown'] },
  css: { label: 'CSS', grammar: 'css', grammars: ['css'] },
  json: { label: 'JSON', grammar: 'json', grammars: ['json'] },
  yml: { label: 'YAML', grammar: 'yaml', grammars: ['yaml'] },
  yaml: { label: 'YAML', grammar: 'yaml', grammars: ['yaml'] },
}

/**
 * The Prism token types each rendering class absorbs.
 *
 * Seven classes rather than Prism's fifty-odd types, because the design system has seven code colours
 * and a slot per token type would produce a rainbow nobody can read. Everything not listed renders as
 * plain body text: punctuation and operators are not emphasis, they are the code itself, and colouring
 * them is what makes a Diff look like a toy.
 *
 * The `type` class is the one that needed a rule rather than a list of guesses: it takes every token
 * that *names* something — a class, a built-in type, an HTML tag, a CSS selector or property, a JSON
 * key, a Markdown heading — because those are the same kind of thing to a reader, and a name coloured
 * differently depending on which grammar found it is a false distinction.
 */
const tokenKinds: Record<string, SyntaxTokenKind> = {
  comment: 'comment', prolog: 'comment', doctype: 'comment', cdata: 'comment', blockquote: 'comment',
  string: 'string', char: 'string', 'attr-value': 'string', regex: 'string', url: 'string', 'template-string': 'string', 'code-snippet': 'string',
  keyword: 'keyword', boolean: 'keyword', important: 'keyword', atrule: 'keyword', rule: 'keyword',
  function: 'function', method: 'function', 'function-variable': 'function',
  number: 'number', constant: 'number', symbol: 'number',
  'class-name': 'type', builtin: 'type', tag: 'type', selector: 'type', namespace: 'type', annotation: 'type',
  title: 'type', key: 'type', property: 'type',
}

function extension(path: string): string {
  return path.split('.').at(-1)?.toLowerCase() ?? ''
}

function languageFor(path: string): SyntaxLanguage | null {
  return languages[extension(path)] ?? null
}

/** What the viewer prints for a path's file type, including the plain-text fallback. */
export function syntaxLanguageLabel(path: string): string {
  return languageFor(path)?.label ?? plainText
}

/** Whether any grammar is offered for this path at all. */
export function hasSyntax(path: string): boolean {
  return languageFor(path) !== null
}

/**
 * One loader per grammar, written out rather than built from a template string.
 *
 * A computed import specifier is not statically analyzable, so the bundler cannot split the grammars
 * and would either inline all of them or fail. This table is what makes 「按文件类型动态 import」
 * true of the built output and not just of the source.
 */
const grammarLoaders: Record<string, () => Promise<unknown>> = {
  markup: () => import('prismjs/components/prism-markup'),
  clike: () => import('prismjs/components/prism-clike'),
  javascript: () => import('prismjs/components/prism-javascript'),
  typescript: () => import('prismjs/components/prism-typescript'),
  jsx: () => import('prismjs/components/prism-jsx'),
  tsx: () => import('prismjs/components/prism-tsx'),
  java: () => import('prismjs/components/prism-java'),
  kotlin: () => import('prismjs/components/prism-kotlin'),
  python: () => import('prismjs/components/prism-python'),
  go: () => import('prismjs/components/prism-go'),
  rust: () => import('prismjs/components/prism-rust'),
  markdown: () => import('prismjs/components/prism-markdown'),
  css: () => import('prismjs/components/prism-css'),
  json: () => import('prismjs/components/prism-json'),
  yaml: () => import('prismjs/components/prism-yaml'),
}

/** One promise per grammar, so a second file of the same type is free and never double-fetches. */
const loadedGrammars = new Map<string, Promise<boolean>>()

function loadGrammar(name: string): Promise<boolean> {
  const existing = loadedGrammars.get(name)
  if (existing) return existing
  const loader = grammarLoaders[name]
  // An unknown name is a programming error in the table above, and it must not read as "loaded".
  const pending = loader ? loader().then(() => true, () => false) : Promise.resolve(false)
  loadedGrammars.set(name, pending)
  return pending
}

/**
 * Fetches the grammars one path needs, in dependency order. Resolves false when highlighting is not
 * available — an unknown file type, or a chunk that did not arrive — and the caller shows plain text.
 */
export async function loadSyntax(path: string): Promise<boolean> {
  const language = languageFor(path)
  if (!language) return false
  for (const name of language.grammars) {
    // Sequential, because the next grammar extends this one at module scope.
    if (!await loadGrammar(name)) return false
  }
  return true
}

/**
 * Tokenizes a whole document and returns one token list per line.
 *
 * Whole-document rather than line-by-line, because the constructs worth highlighting are the ones
 * that cross lines: a block comment, a template literal, a Markdown fence. Splitting first would
 * colour every line after the first one wrong, which looks like a bug in the reader's source.
 */
export function syntaxTokenLines(text: string, path: string): SyntaxToken[][] | null {
  const language = languageFor(path)
  const grammar = language ? Prism.languages[language.grammar] : null
  if (!grammar) return null
  const lines: SyntaxToken[][] = [[]]
  for (const node of Prism.tokenize(text, grammar)) walk(node, 'plain', lines)
  return lines
}

/** The single plain token a line renders as when highlighting is unavailable. */
export function plainTokenLine(text: string): SyntaxToken[] {
  return [{ text, kind: 'plain' }]
}

/**
 * One node of a Prism token stream. Grammars nest, and several return bare arrays inside the stream,
 * so the walker accepts arrays at any depth rather than assuming the two-level shape most grammars
 * happen to produce.
 */
type PrismNode = string | Prism.Token | Array<string | Prism.Token>

function walk(node: PrismNode, kind: SyntaxTokenKind, lines: SyntaxToken[][]): void {
  if (typeof node === 'string') {
    push(node, kind, lines)
    return
  }
  if (Array.isArray(node)) {
    for (const child of node) walk(child, kind, lines)
    return
  }
  const next = kindOf(node)
  walk(node.content, next, lines)
}

function kindOf(token: Prism.Token): SyntaxTokenKind {
  const aliases = Array.isArray(token.alias) ? token.alias : token.alias ? [token.alias] : []
  for (const type of [token.type, ...aliases]) {
    const kind = tokenKinds[type]
    if (kind) return kind
  }
  return 'plain'
}

/**
 * Appends one token's text, starting a new line wherever it contains a newline.
 *
 * This is the whole reason the view is line-oriented: a token that spans lines becomes one token per
 * line here, so the renderer never has to reason about a token that crosses a row boundary.
 */
function push(text: string, kind: SyntaxTokenKind, lines: SyntaxToken[][]): void {
  const parts = text.split('\n')
  for (const [index, part] of parts.entries()) {
    if (index > 0) lines.push([])
    if (part) lines[lines.length - 1]!.push({ text: part, kind })
  }
}
