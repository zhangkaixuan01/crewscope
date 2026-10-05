import { expect, test, type APIResponse, type BrowserContext, type Page } from '@playwright/test'
import {
  baseURL,
  currentSession,
  login,
  onlyTeam,
  register,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M10-F02 real-stack contract: the skill catalog page drives the real A03 API end to end — no
 * Vite server, no HTTP mocks, no fixtures. This is the tier the delivery contract insists on,
 * covering what the mocked matrix structurally cannot prove: the real 202 command receipts, the
 * real ETag/version discipline on the head (numeric) and versions (64-hex content hashes), the
 * 409 currentVersion envelope from a genuine concurrent writer, the rollback's same-hash new
 * revision, and the permission wall between skill:manage holders and plain members.
 *
 * The stack must come up with the write gate open (deploy/team-beta: CREWSCOPE_SKILL_ENABLED=true);
 * with the gate closed every write below answers 422 skill_disabled and the suite fails loudly —
 * which is exactly the signal wanted, never a silent pass.
 *
 * The lifecycle is serial: each test hands the skill head to the next one.
 */

type SkillHead = {
  id: string
  skillKey: string
  status: string
  effectiveRevision: number | null
  latestRevision: number
  version: number
  draft: { name: string, description: string, content: string } | null
  disableReason: string | null
}

type VersionRow = {
  revision: number
  previousRevision: number | null
  content: string
  contentHash: string
}

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string
let skillId: string
let skillKey: string

const skillRoot = () => teamPath(session, teamId, 'skills')

async function head(): Promise<SkillHead> {
  const response = await page.request.get(`${skillRoot()}/${skillId}`)
  expect(response.ok()).toBe(true)
  return response.json() as Promise<SkillHead>
}

async function versions(): Promise<VersionRow[]> {
  const response = await page.request.get(`${skillRoot()}/${skillId}/versions`)
  expect(response.ok()).toBe(true)
  const body = await response.json() as { items: VersionRow[] }
  return body.items
}

/** A member-side write outside the page under test: moves the real head so the page's next
 *  command carries a stale If-Match — the concurrent-editor shape of contract §7. */
async function concurrentPatch(content: string, ifMatch: string): Promise<APIResponse> {
  return page.request.patch(`${skillRoot()}/${skillId}`, {
    data: { content },
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': ifMatch,
    },
  })
}

const draftContent = (description: string, body: string) =>
  `---\nname: ${skillKey}\ndescription: ${description}\n---\n\n${body}`

test('R1 the onboarding owner reaches the skill catalog and reads the real empty listing', async ({ browser }, testInfo) => {
  const suffix = `f02-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const narrow = testInfo.project.name.includes('Narrow')
  context = await browser.newContext({
    baseURL,
    viewport: narrow ? { width: 390, height: 844 } : { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()

  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'F02 Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`F02 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  await page.goto(`/skills?team=${teamId}`)
  // exact keeps the loading panel's "正在读取 Skill 目录" h3 out of the match while it mounts.
  await expect(page.getByRole('heading', { name: 'Skill 目录', exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Skill 目录列表' }).getByText('暂无 Skill')).toBeVisible()
})

test('R2 the create dialog lands a real DRAFT head whose frontmatter name equals the key', async () => {
  skillKey = `f02-${Date.now()}`.replace(/[^a-z0-9-]/g, '')
  await page.getByRole('button', { name: '创建 Skill' }).first().click()
  const dialog = page.getByRole('dialog', { name: '创建 Skill' })
  await dialog.getByRole('textbox').first().fill(skillKey)
  await dialog.locator('textarea').fill(draftContent('发布流程约定', '发布前必须跑全量回归并观察健康检查。'))
  await dialog.getByRole('button', { name: '创建 Skill', exact: true }).click()
  await expect(dialog).toHaveCount(0)

  const listing = page.getByRole('region', { name: 'Skill 目录列表' })
  await expect(listing.getByRole('button', { name: new RegExp(skillKey) })).toBeVisible()

  const stored = await page.request.get(`${skillRoot()}?limit=50`)
  const created = ((await stored.json()) as { items: SkillHead[] }).items.find(item => item.skillKey === skillKey)
  expect(created?.status).toBe('DRAFT')
  // The server-side document validator is the authority: name must equal the key it was filed under.
  expect(created?.draft).toMatchObject({ name: skillKey, description: '发布流程约定' })
  skillId = created!.id

  await listing.getByRole('button', { name: new RegExp(skillKey) }).click()
  await expect(page.getByRole('complementary', { name: 'Skill 详情' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('skill')).toBe(skillId)
})

test('R3 saving the draft through the page advances the real head version', async () => {
  const before = await head()
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  const editor = detail.getByLabel('草稿全文（frontmatter + 正文）')
  await editor.fill(draftContent('发布流程约定（修订）', '发布前必须跑全量回归、数据库迁移演练并观察健康检查。'))
  await detail.getByRole('button', { name: '保存草稿' }).click()
  await expect(detail.getByText(/命令已确认/)).toBeVisible()

  const after = await head()
  expect(after.version).toBe(before.version + 1)
  expect(after.draft?.content).toBe(draftContent('发布流程约定（修订）', '发布前必须跑全量回归、数据库迁移演练并观察健康检查。'))
})

test('R10 an unchanged replay of the same Idempotency-Key echoes the original command receipt', async () => {
  // Same key, same body, same If-Match: the idempotency layer answers the stored receipt
  // instead of committing a second time — the header is the only visible difference.
  const key = crypto.randomUUID()
  const body = { content: draftContent('幂等重放', '同键重放必须返回首次命令的回执。') }
  const headers = {
    [session.csrf.headerName]: session.csrf.token,
    'Idempotency-Key': key,
    'If-Match': `"${(await head()).version}"`,
  }
  const first = await page.request.patch(`${skillRoot()}/${skillId}`, { data: body, headers })
  expect(first.status()).toBe(202)
  expect(first.headers()['idempotency-replayed']).toBeUndefined()

  const replay = await page.request.patch(`${skillRoot()}/${skillId}`, { data: body, headers })
  expect(replay.status()).toBe(202)
  expect(replay.headers()['idempotency-replayed']).toBe('true')
  expect(((await replay.json()) as { commandId: string }).commandId)
    .toBe(((await first.json()) as { commandId: string }).commandId)
})

test('R4 a stale If-Match save meets the real 409 and the editor keeps the local draft', async () => {
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })

  const moved = await head()
  const outside = await concurrentPatch(
    draftContent('页外并发草稿', '另一名成员在页外保存的内容。'),
    `"${moved.version}"`,
  )
  expect(outside.status()).toBe(202)
  const serverHead = await head()
  expect(serverHead.version).toBe(moved.version + 1)

  const editor = detail.getByLabel('草稿全文（frontmatter + 正文）')
  await editor.fill(draftContent('冲突期间保留的本地修订', '页面编辑器在冲突期间不被覆写。'))
  await detail.getByRole('button', { name: '保存草稿' }).click()

  await expect(detail.getByText('Skill 已被其他成员更新')).toBeVisible()
  await expect(detail.getByText(new RegExp(`服务端当前版本 v${serverHead.version}`))).toBeVisible()
  await expect(editor).toHaveValue(/冲突期间保留的本地修订/)

  await detail.getByRole('button', { name: '载入服务端内容' }).click()
  const discard = page.getByRole('alertdialog', { name: '放弃未保存的草稿修改？' })
  await discard.getByRole('button', { name: '放弃修改' }).click()
  await expect(editor).toHaveValue(/另一名成员在页外保存的内容。/)
})

test('R5 publishing mints effective revision 1 with a content-hash ETag', async () => {
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  const confirm = page.getByRole('alertdialog', { name: '发布当前草稿？' })
  await confirm.getByRole('button', { name: '确认发布' }).click()
  await expect(detail.getByText('已发布', { exact: true })).toBeVisible()

  const published = await head()
  expect(published.status).toBe('PUBLISHED')
  expect(published.effectiveRevision).toBe(1)
  expect(published.draft).toBeNull()

  expect(await versions()).toHaveLength(1)
  const effective = await page.request.get(`${skillRoot()}/${skillId}/effective-version`)
  expect(effective.ok()).toBe(true)
  expect(new Headers(effective.headers()).get('ETag')).toMatch(/^"[0-9a-f]{64}"$/)

  await detail.getByRole('tab', { name: '版本' }).click()
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('另一名成员在页外保存的内容。')
})

test('R6 republishing unchanged content meets the real 422 skill_version_unchanged', async () => {
  const current = await head()
  const same = await concurrentPatch((await versions())[0]!.content, `"${current.version}"`)
  expect(same.status()).toBe(202)

  const publish = await page.request.post(`${skillRoot()}/${skillId}/publish`, {
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${(await head()).version}"`,
    },
  })
  expect(publish.status()).toBe(422)
  expect(((await publish.json()) as { code: string }).code).toBe('skill_version_unchanged')
})

test('R7 a new draft republishes as revision 2', async () => {
  // R6's out-of-band patch moved the real head again; a user re-enters the page, so the
  // editor reloads the server draft instead of saving blind into a guaranteed 409.
  await page.reload()
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await expect(detail).toBeVisible()
  await detail.getByRole('tab', { name: '草稿' }).click()
  // R6's out-of-band patch left the draft equal to r1's content, so the tab opens straight
  // into the editor — no empty state to pass through before the revision-2 edit.
  await detail.getByLabel('草稿全文（frontmatter + 正文）')
    .fill(draftContent('发布流程约定（第 2 版）', '新增：发布后 30 分钟内保持灰度观察。'))
  await detail.getByRole('button', { name: '保存草稿' }).click()
  await expect(detail.getByText(/命令已确认/)).toBeVisible()

  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  await page.getByRole('alertdialog', { name: '发布当前草稿？' }).getByRole('button', { name: '确认发布' }).click()
  await expect(detail.getByText(/命令已确认/)).toBeVisible()

  expect((await head()).effectiveRevision).toBe(2)
  expect(await versions()).toHaveLength(2)
})

test('R8 rolling back to r1 mints r3 carrying the identical content hash', async () => {
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  // R7 finished on the draft tab; the rollback entry lives in the version history.
  await detail.getByRole('tab', { name: '版本' }).click()
  const rows = await versions()
  const r1 = rows.find(row => row.revision === 1)!

  await detail.getByRole('button', { name: '回滚到此版本' }).first().click()
  const confirm = page.getByRole('alertdialog', { name: '回滚到 r1？' })
  await confirm.getByRole('button', { name: '确认回滚' }).click()

  await expect(detail.getByRole('button', { name: /^r3/ })).toBeVisible()
  const after = await versions()
  expect((await head()).effectiveRevision).toBe(3)
  // Same-hash neighbours are the contract's own shape for a rollback (contract §3) — the real
  // store is the only tier that can prove the server truly preserves both rows verbatim.
  expect(after.find(row => row.revision === 3)!.contentHash).toBe(r1.contentHash)
  expect(after.find(row => row.revision === 3)!.content).toBe(r1.content)
})

test('R9 disabling with a reason keeps the evidence and the 404 effective shape', async () => {
  const detail = page.getByRole('complementary', { name: 'Skill 详情' })
  await detail.getByRole('button', { name: '禁用 Skill' }).click()
  const confirm = page.getByRole('alertdialog', { name: '禁用这个 Skill？' })
  await confirm.getByLabel(/禁用原因/).fill('被新的发布流程取代。')
  await confirm.getByRole('button', { name: '确认禁用' }).click()

  await expect(detail.getByText(`此 Skill 已禁用：被新的发布流程取代。`)).toBeVisible()
  await expect(detail.getByText(/最后生效修订为 r3/)).toBeVisible()

  const disabled = await head()
  expect(disabled.status).toBe('DISABLED')
  expect(disabled.disableReason).toBe('被新的发布流程取代。')
  // With the skill off the line, effective-version answers the shared 404 envelope — the
  // page treats null as an answer, never as an error.
  const effective = await page.request.get(`${skillRoot()}/${skillId}/effective-version`)
  expect(effective.status()).toBe(404)
  expect(((await effective.json()) as { code: string }).code).toBe('aggregate_not_found')

  // The unfiltered deep link still resolves the DISABLED key (risk 3): status filters would
  // hide it, the catalog's key search never does.
  await page.goto(`/skills?team=${teamId}&skillKey=${skillKey}`)
  await expect(page.getByRole('complementary', { name: 'Skill 详情' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('skill')).toBe(skillId)
})

test('R11 a plain member reads the catalog but meets 403 policy_denied on every write', async ({ browser }) => {
  // Owner invites a plain MEMBER through the real invitation flow (m7 two-user precedent).
  const suffix = `f02m-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  await page.goto(`/team/members?team=${teamId}&tab=invitations`)
  await page.getByRole('button', { name: '创建邀请' }).first().click()
  await page.locator('input[name="invitationEmail"]').fill(`member-${suffix}@example.test`)
  await page.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await page.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await page
    .getByRole('textbox', { name: '一次性邀请链接' })
    .inputValue()

  const memberContext = await browser.newContext({ baseURL, timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce' })
  const memberPage = await memberContext.newPage()
  await memberPage.goto(invitationLink)
  // The landing route only carries the button; the safe-landing note appears on /register (m7 precedent).
  await memberPage.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(memberPage).toHaveURL(/\/register$/)
  await expect(memberPage.getByText('已安全载入团队邀请')).toBeVisible()
  await register(memberPage, `member-${suffix}`.slice(0, 48), `member-${suffix}@example.test`, 'F02 Member', 'Correct-Horse-Battery-Staple-47', true)
  await expect(memberPage).not.toHaveURL(/\/register$/)

  const memberSession = await currentSession(memberPage)
  const memberRoot = teamPath(memberSession, teamId, 'skills')

  // Read side stays open to every active member (contract §8): the head answers 200.
  const read = await memberPage.request.get(`${memberRoot}/${skillId}`)
  expect(read.ok()).toBe(true)

  // The page hides the manage entrances but keeps the distillation entry (the creator's own
  // right, never granted by skill:manage).
  await memberPage.goto(`/skills?team=${teamId}&skill=${skillId}`)
  const memberDetail = memberPage.getByRole('complementary', { name: 'Skill 详情' })
  await expect(memberDetail).toBeVisible()
  await expect(memberPage.getByRole('button', { name: '创建 Skill', exact: true })).toHaveCount(0)
  await expect(memberDetail.getByRole('button', { name: '发布当前草稿' })).toHaveCount(0)
  await expect(memberPage.getByRole('button', { name: '从执行蒸馏' }).first()).toBeVisible()

  // The API wall itself: a member-side publish meets 403 policy_denied, not a silent pass.
  const publish = await memberPage.request.post(`${memberRoot}/${skillId}/publish`, {
    headers: {
      [memberSession.csrf.headerName]: memberSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${(await head()).version}"`,
    },
  })
  expect(publish.status()).toBe(403)
  expect(((await publish.json()) as { code: string }).code).toBe('policy_denied')

  await memberContext.close()
})

test('R12 unknown ids answer the shared 404 envelope across the whole read family', async () => {
  const unknown = '00000000-0000-1000-8000-000000009901'
  for (const suffix of ['', '/versions', '/versions/1', '/effective-version']) {
    const response = await page.request.get(`${skillRoot()}/${unknown}${suffix}`)
    expect(response.status(), suffix).toBe(404)
    expect(((await response.json()) as { code: string }).code).toBe('aggregate_not_found')
  }
})

test('R13 the real domain validator rejects the reserved key and a diverging frontmatter name', async () => {
  const reserved = await page.request.post(skillRoot(), {
    data: { skillKey: 'java-spring-v1', content: '---\nname: java-spring-v1\ndescription: 保留名\n---\n\n正文。' },
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(reserved.status()).toBe(422)
  expect(((await reserved.json()) as { code: string }).code).toBe('invalid_value')

  const diverging = await concurrentPatch(
    '---\nname: other-key\ndescription: 名不一致\n---\n\n正文。',
    `"${(await head()).version}"`,
  )
  expect(diverging.status()).toBe(422)
  expect(((await diverging.json()) as { code: string }).code).toBe('invalid_value')
})

// Real distillation needs a completed Task execution owned by the caller plus a configured LLM
// provider; the gate exports the execution id only when both exist. The two receipt shapes stay
// covered by the mocked matrix (S10), the real LLM chain belongs to Q02 — the skip is honest.
test('R14 distillation through the page creates a real DRAFT skill', async () => {
  const execution = process.env.CREWSCOPE_M10_SKILL_DISTILL_EXECUTION
  test.skip(!execution, 'no completed own task execution + LLM provider on this stack')

  await page.goto(`/skills?team=${teamId}`)
  await page.getByRole('button', { name: '从执行蒸馏' }).first().click()
  const dialog = page.getByRole('dialog', { name: '从执行蒸馏 Skill' })
  await dialog.getByLabel('任务执行 ID').fill(execution!)
  await dialog.getByLabel('目标 skillKey').fill(`f02-distill-${Date.now()}`)
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()

  const receipt = dialog.getByRole('region', { name: '蒸馏回执' })
  await expect(receipt.getByRole('button', { name: '打开 Skill' })).toBeVisible()
})
