#!/usr/bin/env node

/** M13b-F11（C2）ratchet for StatePanel error surfaces.
 *
 * An error panel that offers neither a recovery action nor an explicit "there is none" stance
 * leaves the member stranded, so the contract is enforced mechanically: every `<StatePanel>` that
 * can render `state="error"` (statically or through a `:state` expression that mentions 'error')
 * must wire `@retry` or explicitly declare `:retryable="false"` — the latter renders the panel's
 * built-in "no automatic recovery" note. `offline` is deliberately out of scope: its recovery
 * happens at the network layer and every offline panel already says so in its description.
 *
 * Story fixtures are excluded (States.story.vue is the catalog itself). The scan is
 * quote-aware because `@retry` handlers may contain `>` inside arrow functions.
 */
import { readFile, readdir } from 'node:fs/promises'
import { relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')

async function collect(dir) {
  const files = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = resolve(dir, entry.name)
    if (entry.isDirectory()) files.push(...await collect(path))
    else if (entry.name.endsWith('.vue') && !entry.name.endsWith('.story.vue')) files.push(path)
  }
  return files
}

/** Advances past one tag's closing `>` without being fooled by `>` inside quoted attribute values. */
function tagEnd(source, from) {
  let quote = ''
  for (let i = from; i < source.length; i += 1) {
    const char = source[i]
    if (quote) {
      if (char === quote) quote = ''
    } else if (char === '"' || char === "'" || char === '`') quote = char
    else if (char === '>') return i
  }
  return -1
}

const violations = []
for (const file of await collect(sourceRoot)) {
  const source = await readFile(file, 'utf8')
  for (const match of source.matchAll(/<StatePanel\b/g)) {
    const end = tagEnd(source, match.index)
    if (end === -1) continue
    const tag = source.slice(match.index, end)
    const staticState = /\sstate\s*=\s*"([^"]*)"/.exec(tag)
    const dynamicState = /:state\s*=\s*"([^"]*)"/.exec(tag)
    const mayRenderError = staticState?.[1] === 'error' || (dynamicState !== null && dynamicState[1].includes("'error'"))
    if (!mayRenderError) continue
    const hasRetry = /@retry/.test(tag)
    const declaresNoRetry = /:retryable\s*=\s*"false"/.test(tag)
    if (!hasRetry && !declaresNoRetry) {
      const line = source.slice(0, match.index).split('\n').length
      violations.push(`${relative(root, file)}:${line}`)
    }
  }
}

if (violations.length > 0) {
  console.error(`StatePanel error 面板缺少恢复动作（C2）：${violations.length} 处`)
  for (const violation of violations) console.error(`  ${violation}`)
  console.error('error 态必须接 @retry，或显式声明 :retryable="false"（渲染「无自动恢复」说明）。')
  process.exit(1)
}
console.log('check-state-panel-recovery: ok')
