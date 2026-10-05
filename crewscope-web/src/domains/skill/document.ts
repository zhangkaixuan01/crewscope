import { SKILL_CONTENT_MAX, SKILL_DESCRIPTION_MAX, SKILL_KEY_PATTERN, SKILL_RESERVED_KEYS } from './types'

/**
 * Hand-written frontmatter parser for the skill document contract (§4): a single markdown
 * text opening with a flat `---` block that holds exactly `name` and `description`. No YAML
 * library — nested structures are rejected explicitly, mirroring the server's zero-dependency
 * domain parse.
 */
export interface ParsedSkillDocument {
  name: string | null
  description: string | null
  body: string
}

const FRONTMATTER_DELIMITER = '---'

/**
 * Parses the `---` frontmatter block. Structural failures return null fields only through
 * `validateSkillDocument`; this function stays total so editors can preview partial input.
 */
export function parseSkillDocument(content: string): ParsedSkillDocument {
  const lines = content.split('\n')
  if (lines[0]?.trimEnd() !== FRONTMATTER_DELIMITER) {
    return { name: null, description: null, body: content }
  }
  let name: string | null = null
  let description: string | null = null
  let closeIndex = -1
  for (let index = 1; index < lines.length; index += 1) {
    const line = lines[index]!
    if (line.trimEnd() === FRONTMATTER_DELIMITER) {
      closeIndex = index
      break
    }
    const separator = line.indexOf(':')
    if (separator <= 0) continue
    const key = line.slice(0, separator).trim()
    const value = line.slice(separator + 1).trim()
    if (key === 'name') name = value
    if (key === 'description') description = value
  }
  if (closeIndex === -1) return { name: null, description: null, body: content }
  return { name, description, body: lines.slice(closeIndex + 1).join('\n') }
}

/**
 * Mirrors the server's §4 domain validation and returns the first violation as a
 * user-facing Chinese message, or null when the document is submittable.
 */
export function validateSkillDocument(content: string, skillKey: string): string | null {
  const trimmed = content.trimEnd()
  if (trimmed.length > SKILL_CONTENT_MAX) {
    return `正文超过 ${SKILL_CONTENT_MAX} 字符上限`
  }
  const lines = trimmed.split('\n')
  if (lines[0]?.trimEnd() !== FRONTMATTER_DELIMITER) {
    return '文档必须以 --- 开头的 frontmatter 开始'
  }
  let name: string | null = null
  let description: string | null = null
  let closeIndex = -1
  const seen = new Set<string>()
  for (let index = 1; index < lines.length; index += 1) {
    const line = lines[index]!
    if (line.trimEnd() === FRONTMATTER_DELIMITER) {
      closeIndex = index
      break
    }
    const separator = line.indexOf(':')
    if (separator <= 0 || line.slice(0, separator).trim() === '') {
      return 'frontmatter 只支持平面的「键: 值」字段行，不支持嵌套结构'
    }
    const key = line.slice(0, separator).trim()
    if (key !== 'name' && key !== 'description') {
      return 'frontmatter 恰好只允许 name 与 description 两个字段'
    }
    if (seen.has(key)) {
      return `frontmatter 字段 ${key} 重复出现`
    }
    seen.add(key)
    if (key === 'name') name = line.slice(separator + 1).trim()
    if (key === 'description') description = line.slice(separator + 1).trim()
  }
  if (closeIndex === -1) {
    return 'frontmatter 缺少闭合的 ---'
  }
  if (name === null) return 'frontmatter 缺少 name 字段'
  if (description === null) return 'frontmatter 缺少 description 字段'
  if (name !== skillKey) {
    return 'frontmatter 的 name 必须与 skillKey 完全一致'
  }
  if (description.length > SKILL_DESCRIPTION_MAX) {
    return `description 超过 ${SKILL_DESCRIPTION_MAX} 字符上限`
  }
  if (lines.slice(closeIndex + 1).join('\n').trim().length === 0) {
    return '正文不能为空'
  }
  return null
}

/** Client-side echo of the §4 reserved-key rejection; the server re-checks regardless. */
export function validateSkillKey(skillKey: string): string | null {
  if (SKILL_RESERVED_KEYS.includes(skillKey)) {
    return `skillKey ${skillKey} 是内置 Skill 保留名，请换一个键名`
  }
  if (!SKILL_KEY_PATTERN.test(skillKey)) {
    return 'skillKey 需要 1-63 个字符，以小写字母或数字开头，只能包含小写字母、数字与连字符'
  }
  return null
}
