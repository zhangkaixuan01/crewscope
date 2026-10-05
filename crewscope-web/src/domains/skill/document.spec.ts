import { parseSkillDocument, validateSkillDocument, validateSkillKey } from './document'

const CONTRACT_SAMPLE = '---\nname: deploy-runbook-v2\ndescription: Marker d1 of the drill.\n---\n\nBody of the document.'

describe('parseSkillDocument', () => {
  it('parses the contract §4 sample into its flat fields and body', () => {
    const parsed = parseSkillDocument(CONTRACT_SAMPLE)

    expect(parsed.name).toBe('deploy-runbook-v2')
    expect(parsed.description).toBe('Marker d1 of the drill.')
    expect(parsed.body).toBe('\nBody of the document.')
  })

  it('stays total on structural failures — fields fall back to null for the editor preview', () => {
    expect(parseSkillDocument('no frontmatter here')).toEqual({ name: null, description: null, body: 'no frontmatter here' })
    expect(parseSkillDocument('---\nname: x\nunclosed').name).toBeNull()
  })
})

describe('validateSkillDocument', () => {
  it('accepts the contract sample, including trailing whitespace that the server strips', () => {
    expect(validateSkillDocument(`${CONTRACT_SAMPLE}\n\n  `, 'deploy-runbook-v2')).toBeNull()
  })

  it('rejects a document without the opening delimiter', () => {
    expect(validateSkillDocument('name: deploy-runbook-v2\n---\nbody', 'deploy-runbook-v2'))
      .toBe('文档必须以 --- 开头的 frontmatter 开始')
  })

  it('rejects a frontmatter without the closing delimiter', () => {
    expect(validateSkillDocument('---\nname: deploy-runbook-v2\ndescription: d', 'deploy-runbook-v2'))
      .toBe('frontmatter 缺少闭合的 ---')
  })

  it('rejects nested or non key-value lines — the domain parses flat YAML only', () => {
    expect(validateSkillDocument('---\n  - list item\nname: k\ndescription: d\n---\nbody', 'k'))
      .toBe('frontmatter 只支持平面的「键: 值」字段行，不支持嵌套结构')
  })

  it('rejects keys outside the closed name/description vocabulary', () => {
    expect(validateSkillDocument('---\nname: k\ndescription: d\nversion: 2\n---\nbody', 'k'))
      .toBe('frontmatter 恰好只允许 name 与 description 两个字段')
  })

  it('rejects duplicate fields', () => {
    expect(validateSkillDocument('---\nname: k\nname: k\ndescription: d\n---\nbody', 'k'))
      .toBe('frontmatter 字段 name 重复出现')
  })

  it('rejects a missing name or description field', () => {
    expect(validateSkillDocument('---\ndescription: d\n---\nbody', 'k')).toBe('frontmatter 缺少 name 字段')
    expect(validateSkillDocument('---\nname: k\n---\nbody', 'k')).toBe('frontmatter 缺少 description 字段')
  })

  it('rejects a name that drifted from the skillKey', () => {
    expect(validateSkillDocument(CONTRACT_SAMPLE, 'other-key')).toBe('frontmatter 的 name 必须与 skillKey 完全一致')
  })

  it('rejects an over-limit description and an over-limit document', () => {
    const longDescription = `---\nname: k\ndescription: ${'长'.repeat(201)}\n---\nbody`
    expect(validateSkillDocument(longDescription, 'k')).toBe('description 超过 200 字符上限')

    const huge = `---\nname: k\ndescription: d\n---\n${'x'.repeat(65537)}`
    expect(validateSkillDocument(huge, 'k')).toBe('正文超过 65536 字符上限')
  })

  it('rejects an empty body — the document is the runtime skill source', () => {
    expect(validateSkillDocument('---\nname: k\ndescription: d\n---\n\n  \n', 'k')).toBe('正文不能为空')
  })
})

describe('validateSkillKey', () => {
  it('accepts single-character and hyphen-tail keys — the skill pattern is looser than entry keys', () => {
    expect(validateSkillKey('a')).toBeNull()
    expect(validateSkillKey('deploy-runbook-v2-')).toBeNull()
    expect(validateSkillKey('a'.repeat(63))).toBeNull()
  })

  it('rejects the reserved built-in key', () => {
    expect(validateSkillKey('java-spring-v1')).toBe('skillKey java-spring-v1 是内置 Skill 保留名，请换一个键名')
  })

  it('rejects upper case, underscores, a bad first character and over-length keys', () => {
    expect(validateSkillKey('Deploy-Runbook')).toContain('skillKey')
    expect(validateSkillKey('deploy_runbook')).toContain('skillKey')
    expect(validateSkillKey('-deploy')).toContain('skillKey')
    expect(validateSkillKey('a'.repeat(64))).toContain('skillKey')
  })
})
