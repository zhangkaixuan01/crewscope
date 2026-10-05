import { expect, test, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'
import { permissions } from '../src/app/auth'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401',
  workspace: '00000000-0000-0000-0000-000000000501',
  member: '00000000-0000-0000-0000-000000000301',
  // Skill ids must satisfy the page's `uuidQuery` guard (version nibble 1-5, variant nibble 8-b).
  publishedSkill: '00000000-0000-1000-8000-000000002501',
  draftSkill: '00000000-0000-1000-8000-000000002502',
  reviewSkill: '00000000-0000-1000-8000-000000002503',
  distillExecution: '00000000-0000-1000-8000-000000007201',
}

interface SkillRecord {
  id: string
  skillKey: string
  status: 'DRAFT' | 'PUBLISHED' | 'DISABLED'
  effectiveRevision: number | null
  latestRevision: number
  draft: { name: string, description: string, content: string } | null
  disableReason: string | null
  version: number
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  origin: { taskExecutionId: string, attempt: number } | null
}

interface VersionRecord {
  skillId: string
  revision: number
  previousRevision: number | null
  content: string
  contentHash: string
  createdAt: string
  createdBy: string
}

interface WriteRecord {
  method: string
  path: string
  ifMatch: string | undefined
  key: string | undefined
  body: unknown
}

const PAGE_SIZE = 2
let skills: SkillRecord[]
let versions: Record<string, VersionRecord[]>
let writes: WriteRecord[]
let listingQueries: Array<URLSearchParams>
let distillCalls: Array<{ key: string, input: { taskExecutionId: string, skillKey: string } }>
let distillSeen: Set<string>
// Injected before a save: the next PATCH answers 409 while the server head moves on, mirroring a
// concurrent editor (contract §7: 409 + currentVersion, the reload never overwrites the form).
let conflictNextDraft: boolean
// The next distillation command commits on the server but its response is lost (503) — the retry
// with the unchanged input reuses the idempotency key and meets §11.1's replay envelope.
let failFirstDistill: boolean
let revokeManage: boolean
// `crewscope.skill.enabled=false` guards every write command with 422 before permissions (contract §9).
let switchDisabled: boolean
// The next publish answers with this envelope instead of committing (422 unchanged / 403 disclosure).
let nextPublishError: { status: number, code: string, message: string } | null
let skillIdSequence: number

test.beforeEach(async ({ page }) => {
  // Keep RelativeTime output deterministic so assertions target stable wording.
  await page.clock.setFixedTime(new Date('2026-10-05T04:00:00Z'))
  skills = [
    skill(ids.draftSkill, 'api-conventions', 'DRAFT', null, 1, {
      name: 'api-conventions', description: '接口约定',
      content: '---\nname: api-conventions\ndescription: 接口约定\n---\n\n所有写命令必须携带 If-Match 与幂等键。',
    }),
    skill(ids.reviewSkill, 'code-review', 'PUBLISHED', 1, 1, null),
    skill(ids.publishedSkill, 'deploy-runbook', 'PUBLISHED', 2, 2, null),
  ]
  versions = {
    [ids.reviewSkill]: [versionRow(ids.reviewSkill, 1, '先写测试再实现。', '3'.repeat(64))],
    // r1 and r2 differ, so the rollback story in S8 can target r1 while r2 stays effective.
    [ids.publishedSkill]: [
      versionRow(ids.publishedSkill, 1, '1. 拉取最新镜像\n2. 观察健康检查。', '1'.repeat(64)),
      versionRow(ids.publishedSkill, 2, '1. 拉取最新镜像\n2. 执行数据库迁移\n3. 观察健康检查。', '2'.repeat(64)),
    ],
  }
  writes = []
  listingQueries = []
  distillCalls = []
  distillSeen = new Set()
  conflictNextDraft = false
  failFirstDistill = false
  revokeManage = false
  switchDisabled = false
  nextPublishError = null
  skillIdSequence = 0x2601

  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') {
      const session = authenticatedSession(ids.organization, ids.principal, ids.team)
      if (revokeManage) {
        // Distillation belongs to the execution creator, not to skill:manage — it stays granted.
        const granted = session.permissions.filter(value => value !== permissions.skillManage)
        session.permissions = granted
        session.teams[0]!.permissions = granted
      }
      await fulfillJson(route, session)
      return
    }
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      await fulfillJson(route, [team()])
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      await fulfillJson(route, { items: [project()], nextCursor: null })
      return
    }
    if (path.endsWith('/skills') && request.method() === 'GET') {
      listingQueries.push(url.searchParams)
      const status = url.searchParams.get('status')
      const after = url.searchParams.get('after')
      const live = skills
        .filter(item => !status || item.status === status)
        .filter(item => !after || item.skillKey > after)
        .sort((left, right) => left.skillKey.localeCompare(right.skillKey))
      const page = live.slice(0, PAGE_SIZE)
      await fulfillJson(route, { items: page, nextAfter: live.length > PAGE_SIZE ? page.at(-1)!.skillKey : null })
      return
    }
    if (path.endsWith('/skills') && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as { skillKey: string, content: string }
      writes.push({ method: 'POST', path, ifMatch: undefined, key, body: input })
      if (switchDisabled) return fulfillError(route, 422, 'skill_disabled', 'Skill 能力未开启', null, { switch: 'crewscope.skill.enabled' })
      skills.push(skill(nextSkillId(), input.skillKey, 'DRAFT', null, 0, { name: input.skillKey, description: '', content: input.content }))
      await fulfillReceipt(route, 0)
      return
    }
    if (request.method() === 'POST' && path.endsWith('/skills/distillations')) {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as { taskExecutionId: string, skillKey: string }
      distillCalls.push({ key, input })
      if (distillSeen.has(key)) {
        // §11.1 replay: the stored envelope has no result body, so skillId/origin/status stay
        // absent and only the echoed skillKey locates the skill.
        await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Idempotency-Replayed': 'true' }, body: JSON.stringify({ ...receipt(), skillKey: input.skillKey }) })
        return
      }
      distillSeen.add(key)
      if (failFirstDistill) {
        failFirstDistill = false
        return fulfillError(route, 503, 'service_unavailable', '服务暂时不可用，请稍后重试')
      }
      const origin = { taskExecutionId: input.taskExecutionId, attempt: 2 }
      const existing = skills.find(item => item.skillKey === input.skillKey)
      const target = existing
        ? { ...existing, origin }
        : skills[skills.push(skill(nextSkillId(), input.skillKey, 'DRAFT', null, 0, {
          name: input.skillKey, description: '蒸馏草稿',
          content: `---\nname: ${input.skillKey}\ndescription: 蒸馏草稿\n---\n\n由任务执行过程蒸馏得出的结论。`,
        })) - 1]!
      target.origin = origin
      await fulfillJson(route, { ...receipt(), skillId: target.id, skillKey: input.skillKey, status: 'DRAFT', origin })
      return
    }
    const skillMatch = path.match(/\/skills\/([^/]+)$/)
    if (skillMatch && request.method() === 'GET') {
      const current = skills.find(item => item.id === skillMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Skill 不存在')
      await fulfillEtag(route, current, `"${current.version}"`)
      return
    }
    if (skillMatch && request.method() === 'PATCH') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const current = skills.find(item => item.id === skillMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Skill 不存在')
      const input = request.postDataJSON() as { content: string }
      writes.push({ method: 'PATCH', path, ifMatch: request.headers()['if-match'], key, body: input })
      if (switchDisabled) return fulfillError(route, 422, 'skill_disabled', 'Skill 能力未开启', null, { switch: 'crewscope.skill.enabled' })
      if (conflictNextDraft) {
        conflictNextDraft = false
        // The concurrent editor committed first: the head moves and the stored draft changes.
        current.version += 1
        current.draft = { name: current.skillKey, description: '服务端并发草稿', content: '---\nname: api-conventions\ndescription: 服务端并发草稿\n---\n\n其他成员保存的内容。' }
        current.updatedBy = ids.member
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此 Skill', current.version)
      }
      if (request.headers()['if-match'] !== `"${current.version}"`) {
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此 Skill', current.version)
      }
      current.draft = { name: current.skillKey, description: '草稿', content: input.content }
      current.version += 1
      current.updatedAt = '2026-10-05T04:00:00Z'
      await fulfillReceipt(route, current.version)
      return
    }
    const versionListMatch = path.match(/\/skills\/([^/]+)\/versions$/)
    if (versionListMatch && request.method() === 'GET') {
      const rows = versions[versionListMatch[1]] ?? []
      await fulfillJson(route, { items: rows, nextAfter: null })
      return
    }
    const versionMatch = path.match(/\/skills\/([^/]+)\/versions\/([^/]+)$/)
    if (versionMatch && request.method() === 'GET') {
      const row = (versions[versionMatch[1]] ?? []).find(item => item.revision === Number(versionMatch[2]))
      if (!row) return fulfillError(route, 404, 'aggregate_not_found', 'Skill Version 不存在')
      await fulfillEtag(route, row, `"${row.contentHash}"`)
      return
    }
    const effectiveMatch = path.match(/\/skills\/([^/]+)\/effective-version$/)
    if (effectiveMatch && request.method() === 'GET') {
      const current = skills.find(item => item.id === effectiveMatch[1])
      const row = current?.effectiveRevision != null
        ? (versions[current.id] ?? []).find(item => item.revision === current.effectiveRevision)
        : undefined
      if (!row) return fulfillError(route, 404, 'aggregate_not_found', '当前没有生效版本')
      await fulfillEtag(route, row, `"${row.contentHash}"`)
      return
    }
    const commandMatch = path.match(/\/skills\/([^/]+)\/(publish|disable|rollback)$/)
    if (commandMatch && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const current = skills.find(item => item.id === commandMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Skill 不存在')
      const input = commandMatch[2] === 'disable'
        ? request.postDataJSON() as { reason?: string } | null
        : commandMatch[2] === 'rollback'
          ? request.postDataJSON() as { toRevision: number }
          : null
      const parsed = (input ?? {}) as { reason?: string, toRevision?: number }
      writes.push({ method: 'POST', path, ifMatch: request.headers()['if-match'], key, body: input })
      if (switchDisabled) return fulfillError(route, 422, 'skill_disabled', 'Skill 能力未开启', null, { switch: 'crewscope.skill.enabled' })
      if (commandMatch[2] === 'publish' && nextPublishError) {
        const envelope = nextPublishError
        nextPublishError = null
        return fulfillError(route, envelope.status, envelope.code, envelope.message, null, envelope.code === 'skill_disclosure_denied' ? { patternFamily: 'SECRETS' } : {})
      }
      if (request.headers()['if-match'] !== `"${current.version}"`) {
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此 Skill', current.version)
      }
      if (commandMatch[2] === 'publish') {
        const revision = current.latestRevision + 1
        versions[current.id] = [
          versionRow(current.id, revision, current.draft?.content ?? '', hashOf(current.draft?.content ?? '')),
          ...(versions[current.id] ?? []),
        ]
        current.status = 'PUBLISHED'
        current.effectiveRevision = revision
        current.latestRevision = revision
        current.draft = null
      } else if (commandMatch[2] === 'disable') {
        current.status = 'DISABLED'
        current.disableReason = parsed.reason ?? null
      } else {
        // Rollback mints a new revision whose content and hash equal the target's — adjacent
        // same-hash rows are the contract's own shape, never an anomaly (contract §3).
        const toRevision = parsed.toRevision ?? 1
        const target = (versions[current.id] ?? []).find(item => item.revision === toRevision)
        if (!target) return fulfillError(route, 404, 'aggregate_not_found', '目标修订不存在')
        const revision = current.latestRevision + 1
        versions[current.id] = [{ ...target, revision, previousRevision: current.latestRevision }, ...(versions[current.id] ?? [])]
        current.effectiveRevision = revision
        current.latestRevision = revision
      }
      current.version += 1
      current.updatedAt = '2026-10-05T04:00:00Z'
      await fulfillReceipt(route, current.version)
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"not_found"}' })
  })
})

test('S1 列表筛选、skillKey 游标翻页与详情版本深链', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}`)

  const listing = page.getByRole('region', { name: 'Skill 目录列表' })
  await expect(listing.getByRole('button', { name: /api-conventions/ })).toBeVisible()
  await expect(listing.getByRole('button', { name: /code-review/ })).toBeVisible()
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toHaveCount(0)
  // The provenance line keeps built-in skills out of the fake-data path (acceptance: 来源明确).
  await expect(listing.getByText('内置 Coding Skill（如 java-spring-v1）随模板提供')).toBeVisible()

  await listing.getByRole('button', { name: '读取更多 Skill' }).click()
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toBeVisible()
  expect(listingQueries.at(-1)?.get('after')).toBe('code-review')

  await page.getByLabel('按状态筛选').selectOption('PUBLISHED')
  await expect(listing.getByRole('button', { name: /api-conventions/ })).toHaveCount(0)
  expect(listingQueries.at(-1)?.get('status')).toBe('PUBLISHED')

  // A skillKey deep link pages the unfiltered catalog, then rewrites the stable id into the URL.
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skillKey=deploy-runbook`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await expect(detail).toBeVisible()
  await expect(detail.getByText('deploy-runbook').first()).toBeVisible()
  expect(new URL(page.url()).searchParams.get('skill')).toBe(ids.publishedSkill)
  expect(new URL(page.url()).searchParams.get('skillKey')).toBeNull()

  await detail.getByRole('tab', { name: '版本' }).click()
  await expect(detail.getByRole('button', { name: /^r1/ })).toBeVisible()
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('执行数据库迁移')
  await detail.getByRole('button', { name: /^r1/ }).click()
  await expect(detail.getByRole('region', { name: '版本 r1 内容' })).toContainText('拉取最新镜像')
})

test('S2 创建对话框先在客户端拒绝保留名与非法键，再提交真实命令', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '创建 Skill' }).first().click()

  const dialog = page.getByRole('dialog', { name: '创建 Skill' })
  await expect(dialog).toBeVisible()
  // The reserved key never reaches the wire: the submit button itself stays locked.
  // (The key input is addressed by role+css: both fields' accessible names fold in the shared
  // hint text mentioning "skillKey", which makes getByLabel ambiguous here.)
  const keyInput = dialog.getByRole('textbox').first()
  await keyInput.fill('java-spring-v1')
  await expect(dialog.getByRole('button', { name: '创建 Skill', exact: true })).toBeDisabled()
  expect(writes).toHaveLength(0)

  await keyInput.fill('Bad_Key')
  await expect(dialog.getByRole('button', { name: '创建 Skill', exact: true })).toBeDisabled()
  expect(writes).toHaveLength(0)

  const content = '---\nname: release-notes\ndescription: 发布说明\n---\n\n每次发布后回填变更要点。'
  await keyInput.fill('release-notes')
  await dialog.locator('textarea').fill(content)
  await dialog.getByRole('button', { name: '创建 Skill', exact: true }).click()

  await expect(dialog).toHaveCount(0)
  const created = writes.find(record => record.method === 'POST' && record.path.endsWith('/skills'))
  expect(created?.key).toBeTruthy()
  expect(created?.body).toMatchObject({ skillKey: 'release-notes', content })
  const listing = page.getByRole('region', { name: 'Skill 目录列表' })
  await listing.getByRole('button', { name: '读取更多 Skill' }).click()
  await expect(listing.getByRole('button', { name: /release-notes/ })).toBeVisible()
})

test('S3 保存草稿携带 If-Match 与幂等键，frontmatter 错误先挡在本地', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  const editor = detail.getByLabel('草稿全文（frontmatter + 正文）')
  await expect(editor).toHaveValue(/所有写命令必须携带 If-Match 与幂等键。/)

  // A name that diverges from the skillKey is refused before any request leaves the page: the
  // submit restyles into its reason and locks, so there is nothing clickable that could POST.
  await editor.fill('---\nname: other-key\ndescription: 接口约定\n---\n\n正文。')
  await expect(detail.getByText(/name 必须与 skillKey 完全一致/).first()).toBeVisible()
  await expect(detail.getByRole('button', { name: /name 必须与 skillKey 完全一致/ })).toBeDisabled()
  expect(writes).toHaveLength(0)

  await editor.fill('---\nname: api-conventions\ndescription: 接口约定\n---\n\n修订后的接口约定。')
  await detail.getByRole('button', { name: '保存草稿' }).click()

  await expect(detail.getByText(/命令已确认/)).toBeVisible()
  const save = writes.find(record => record.method === 'PATCH')
  expect(save?.ifMatch).toBe('"1"')
  expect(save?.key).toBeTruthy()
  expect(save?.body).toMatchObject({ content: '---\nname: api-conventions\ndescription: 接口约定\n---\n\n修订后的接口约定。' })
})

test('S4 409 冲突保留编辑器并披露服务端版本', async ({ page }) => {
  conflictNextDraft = true
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  const editor = detail.getByLabel('草稿全文（frontmatter + 正文）')
  await editor.fill('---\nname: api-conventions\ndescription: 接口约定\n---\n\n冲突中的本地修订')
  await detail.getByRole('button', { name: '保存草稿' }).click()

  await expect(detail.getByText('Skill 已被其他成员更新')).toBeVisible()
  await expect(detail.getByText(/服务端当前版本 v2/)).toBeVisible()
  await expect(editor).toHaveValue(/冲突中的本地修订/)

  await detail.getByRole('button', { name: '载入服务端内容' }).click()
  const discard = page.getByRole('alertdialog', { name: '放弃未保存的草稿修改？' })
  await expect(discard).toBeVisible()
  await discard.getByRole('button', { name: '放弃修改' }).click()
  await expect(editor).toHaveValue(/其他成员保存的内容。/)
})

test('S5 发布走影响确认，草稿清空并更新生效指针', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })

  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  const confirm = page.getByRole('alertdialog', { name: '发布当前草稿？' })
  await expect(confirm).toContainText('无条件披露扫描')
  await expect(confirm).toContainText('r1')
  await confirm.getByRole('button', { name: '确认发布' }).click()

  const publish = writes.find(record => record.method === 'POST' && record.path.endsWith('/publish'))
  expect(publish?.ifMatch).toBe('"1"')
  expect(publish?.key).toBeTruthy()
  await expect(confirm).toHaveCount(0)
  await expect(detail.getByText('已发布', { exact: true })).toBeVisible()
  await expect(detail.getByText(/命令已确认/)).toBeVisible()

  await detail.getByRole('tab', { name: '版本' }).click()
  await expect(detail.getByRole('button', { name: /^r1/ })).toBeVisible()
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('所有写命令必须携带 If-Match 与幂等键。')
})

test('S6 无变化发布按 422 分流而不是冲突面板', async ({ page }) => {
  nextPublishError = { status: 422, code: 'skill_version_unchanged', message: '草稿内容与生效版本完全相同，没有可发布的新修订' }
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })

  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  // A rejected command keeps the confirmation open and pins the reason inside its own alert.
  const confirm = page.getByRole('alertdialog', { name: '发布当前草稿？' })
  await confirm.getByRole('button', { name: '确认发布' }).click()
  await expect(confirm.getByRole('alert')).toContainText(/没有可发布的新修订/)
  await expect(detail.getByText('Skill 已被其他成员更新')).toHaveCount(0)
})

test('S7 披露扫描拒绝按 403 呈现', async ({ page }) => {
  nextPublishError = { status: 403, code: 'skill_disclosure_denied', message: '内容未通过无条件披露扫描' }
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })

  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  const confirm = page.getByRole('alertdialog', { name: '发布当前草稿？' })
  await confirm.getByRole('button', { name: '确认发布' }).click()
  await expect(confirm.getByRole('alert')).toContainText(/未通过无条件披露扫描/)
})

test('S8 回滚铸造同哈希新修订且不做异常标记', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.publishedSkill}&tab=versions`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await expect(detail.getByRole('button', { name: /^r1/ })).toBeVisible()

  await detail.getByRole('button', { name: '回滚到此版本' }).first().click()
  const confirm = page.getByRole('alertdialog', { name: '回滚到 r1？' })
  await expect(confirm).toContainText('内容与哈希与 r1 完全相同')
  await confirm.getByRole('button', { name: '确认回滚' }).click()

  const rollback = writes.find(record => record.method === 'POST' && record.path.endsWith('/rollback'))
  expect(rollback?.ifMatch).toBe('"2"')
  expect(rollback?.body).toMatchObject({ toRevision: 1 })
  await expect(detail.getByRole('button', { name: /^r3/ })).toBeVisible()
  // The fresh r3 row shows the r1 hash beside it — same-hash neighbours are the contract's shape.
  await expect(detail.getByRole('button', { name: /^r3/ })).toContainText('11111111')
  await expect(detail.getByText(/异常/)).toHaveCount(0)
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('拉取最新镜像')
})

test('S9 禁用携带原因，头部呈现并保留最后生效修订', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.publishedSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })

  await detail.getByRole('button', { name: '禁用 Skill' }).click()
  const confirm = page.getByRole('alertdialog', { name: '禁用这个 Skill？' })
  await expect(confirm).toContainText('没有删除操作')
  await confirm.getByLabel(/禁用原因/).fill('已被新的部署流程取代。')
  await confirm.getByRole('button', { name: '确认禁用' }).click()

  const disable = writes.find(record => record.method === 'POST' && record.path.endsWith('/disable'))
  expect(disable?.ifMatch).toBe('"2"')
  expect(disable?.body).toMatchObject({ reason: '已被新的部署流程取代。' })

  await expect(detail.getByText('此 Skill 已禁用：已被新的部署流程取代。')).toBeVisible()
  // Disable keeps the evidence: the last effective revision stays readable on the head.
  await expect(detail.getByText(/最后生效修订为 r2/)).toBeVisible()
})

test('S10 蒸馏回执区分首次确认与幂等重放', async ({ page }) => {
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '从执行蒸馏' }).first().click()

  const dialog = page.getByRole('dialog', { name: '从执行蒸馏 Skill' })
  await expect(dialog.getByText('仅任务执行创建者本人可提炼该执行')).toBeVisible()
  await dialog.getByLabel('任务执行 ID').fill(ids.distillExecution)
  await dialog.getByLabel('目标 skillKey').fill('postmortem-cache')
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()

  const receipt = dialog.getByRole('region', { name: '蒸馏回执' })
  await expect(receipt.getByText('postmortem-cache')).toBeVisible()
  await expect(receipt.getByText(/第 2 次尝试/)).toBeVisible()
  await expect(receipt.getByRole('button', { name: '打开 Skill' })).toBeVisible()
  expect(distillCalls).toHaveLength(1)
  const firstKey = distillCalls[0]!.key

  await receipt.getByRole('button', { name: '打开 Skill' }).click()
  await expect(dialog).toHaveCount(0)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await expect(detail.getByText('postmortem-cache').first()).toBeVisible()

  // Retry shape: the server committed the command but the response was lost (503). The form
  // keeps its inputs, so an unchanged resubmit reuses the idempotency key — §11.1's replay
  // envelope comes back and the receipt switches to the replay wording.
  failFirstDistill = true
  await detail.getByRole('button', { name: '关闭 Skill 详情' }).click()
  await page.getByRole('button', { name: '从执行蒸馏' }).first().click()
  await dialog.getByLabel('任务执行 ID').fill(ids.distillExecution)
  await dialog.getByLabel('目标 skillKey').fill('retry-cache')
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()
  await expect(dialog.getByRole('alert')).toContainText('提交结果尚未确认。请保留当前内容，重试会沿用原操作标识')
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()

  await expect(receipt.getByText('此蒸馏命令此前已确认过，本次为幂等重放，没有重复创建内容。')).toBeVisible()
  await expect(receipt.getByText('retry-cache')).toBeVisible()
  await expect(receipt.getByRole('button', { name: '打开 Skill' })).toHaveCount(0)
  expect(distillCalls).toHaveLength(3)
  expect(distillCalls[2]!.key).toBe(distillCalls[1]!.key)
  expect(distillCalls[1]!.key).not.toBe(firstKey)
})

test('S11 无 skill:manage 时读面完整、管理入口隐藏，蒸馏入口保留', async ({ page }) => {
  revokeManage = true
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)

  const listing = page.getByRole('region', { name: 'Skill 目录列表' })
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await expect(listing.getByRole('button', { name: /api-conventions/ })).toBeVisible()
  await expect(detail.getByText('api-conventions').first()).toBeVisible()
  await expect(detail.getByRole('tab', { name: '版本' })).toBeVisible()

  await expect(page.getByRole('button', { name: '创建 Skill' })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '发布当前草稿' })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '禁用 Skill' })).toHaveCount(0)
  await expect(detail.getByText('审计信息')).toHaveCount(0)
  await expect(detail.getByLabel('草稿全文（frontmatter + 正文）')).toBeDisabled()
  await expect(detail.getByText('需要 Skill 管理权限').first()).toBeVisible()
  // The distillation right belongs to the execution creator (§8), so the entrance survives.
  await expect(page.getByRole('button', { name: '从执行蒸馏' }).first()).toBeVisible()
})

test('S12 开关关闭时读面完整、写命令统一 422', async ({ page }) => {
  switchDisabled = true
  await page.goto(`/skills?team=${ids.team}&project=${ids.project}`)

  const listing = page.getByRole('region', { name: 'Skill 目录列表' })
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toHaveCount(0)
  await listing.getByRole('button', { name: '读取更多 Skill' }).click()
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toBeVisible()

  await page.goto(`/skills?team=${ids.team}&project=${ids.project}&skill=${ids.draftSkill}`)
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  const confirm = page.getByRole('alertdialog', { name: '发布当前草稿？' })
  await confirm.getByRole('button', { name: '确认发布' }).click()
  await expect(confirm.getByRole('alert')).toContainText(/Skill 能力未开启/)
})

function team() {
  return {
    id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
    initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: ids.workspace, version: 1,
  }
}

function project() {
  return {
    id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
    createdAt: '2026-10-05T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-10-05T01:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function nextSkillId(): string {
  const suffix = skillIdSequence.toString(16).padStart(4, '0')
  skillIdSequence += 1
  return `00000000-0000-1000-8000-00000000${suffix}`
}

function skill(
  id: string,
  skillKey: string,
  status: SkillRecord['status'],
  effectiveRevision: number | null,
  version: number,
  draft: { name: string, description: string, content: string } | null,
): SkillRecord {
  return {
    id, skillKey, status, effectiveRevision, latestRevision: effectiveRevision ?? 0,
    draft, disableReason: null, version,
    createdAt: '2026-10-05T01:00:00Z', updatedAt: '2026-10-05T03:00:00Z',
    createdBy: ids.principal, updatedBy: ids.principal, origin: null,
  }
}

function versionRow(skillId: string, revision: number, content: string, contentHash: string): VersionRecord {
  return {
    skillId, revision, previousRevision: revision > 1 ? revision - 1 : null, content, contentHash,
    createdAt: '2026-10-05T02:00:00Z', createdBy: ids.principal,
  }
}

/** The mock's stand-in for the server's content hash: same content in, same hash out. */
function hashOf(content: string): string {
  return String((content.length % 9) + 1).repeat(64)
}

function receipt() {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 0, correlationId: crypto.randomUUID() }
}

function fulfillJson(route: Route, value: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function fulfillEtag(route: Route, value: unknown, etag: string): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: etag, 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function fulfillReceipt(route: Route, committedVersion: number): Promise<void> {
  return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }) })
}

function fulfillError(route: Route, status: number, code: string, message: string, currentVersion: number | null = null, details: Record<string, unknown> = {}): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, correlationId: crypto.randomUUID(), retryable: status >= 500 || status === 409, currentVersion, details }) })
}
