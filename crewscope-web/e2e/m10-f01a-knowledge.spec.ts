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
  // Entry ids must satisfy the page's `uuidQuery` guard (version nibble 1-5, variant nibble 8-b).
  publishedEntry: '00000000-0000-1000-8000-000000002301',
  draftEntry: '00000000-0000-1000-8000-000000002302',
  retiredEntry: '00000000-0000-1000-8000-000000002303',
  distillExecution: '00000000-0000-1000-8000-000000007201',
}

interface EntryRecord {
  id: string
  entryKey: string
  category: string
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED' | 'DELETED'
  indexStatus: 'PENDING' | 'INDEXED' | 'FAILED'
  effectiveRevision: number | null
  latestRevision: number
  draft: { title: string, content: string } | null
  version: number
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  origin: { taskExecutionId: string, attempt: number } | null
}

interface VersionRecord {
  entryId: string
  revision: number
  previousRevision: number | null
  title: string
  content: string
  contentHash: string
  indexStatus: 'PENDING' | 'INDEXED' | 'FAILED'
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
let entries: EntryRecord[]
let versions: Record<string, VersionRecord[]>
let writes: WriteRecord[]
let listingQueries: Array<URLSearchParams>
let distillCalls: Array<{ key: string, input: { taskExecutionId: string, entryKey: string } }>
let distillSeen: Set<string>
// Injected before a save: the next PATCH answers 409 while the server head moves on, mirroring a
// concurrent editor (contract §7: 409 + currentVersion, the draft reload never overwrites the form).
let conflictNextDraft: boolean
// The next distillation command commits on the server but its response is lost (503) — the retry
// with the unchanged input reuses the idempotency key and meets §11.1's replay envelope.
let failFirstDistill: boolean
let revokeManage: boolean
let entryIdSequence: number

test.beforeEach(async ({ page }) => {
  // Keep RelativeTime output deterministic so assertions target stable wording.
  await page.clock.setFixedTime(new Date('2026-08-08T04:00:00Z'))
  entries = [
    entry(ids.publishedEntry, 'deploy-runbook', 'RUNBOOK', 'PUBLISHED', 1, 3, null, 'INDEXED'),
    entry(ids.draftEntry, 'api-conventions', 'CONVENTION', 'DRAFT', null, 1, { title: '未发布的接口约定草稿', content: '所有写命令必须携带 If-Match 与幂等键。' }, 'INDEXED'),
    entry(ids.retiredEntry, 'old-decision', 'DECISION', 'RETIRED', null, 2, null, 'INDEXED'),
  ]
  versions = {
    [ids.publishedEntry]: [versionRow(ids.publishedEntry, 1, '部署手册', '1. 拉取最新镜像\n2. 执行数据库迁移\n3. 依次重启服务并观察健康检查。')],
    [ids.retiredEntry]: [versionRow(ids.retiredEntry, 1, '旧决策', '曾经生效的缓存策略决策。')],
  }
  writes = []
  listingQueries = []
  distillCalls = []
  distillSeen = new Set()
  conflictNextDraft = false
  failFirstDistill = false
  revokeManage = false
  entryIdSequence = 0x2401

  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') {
      const session = authenticatedSession(ids.organization, ids.principal, ids.team)
      if (revokeManage) {
        const granted = session.permissions.filter(value => value !== permissions.knowledgeManage)
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
    if (path.endsWith('/knowledge/entries') && request.method() === 'GET') {
      listingQueries.push(url.searchParams)
      const status = url.searchParams.get('status')
      const category = url.searchParams.get('category')
      const after = url.searchParams.get('after')
      const live = entries
        .filter(item => !status || item.status === status)
        .filter(item => !category || item.category === category)
        .filter(item => !after || item.entryKey > after)
        .sort((left, right) => left.entryKey.localeCompare(right.entryKey))
      const page = live.slice(0, PAGE_SIZE)
      await fulfillJson(route, { items: page, nextAfter: live.length > PAGE_SIZE ? page.at(-1)!.entryKey : null })
      return
    }
    if (path.endsWith('/knowledge/entries') && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as { entryKey: string, category: string, title: string, content: string }
      writes.push({ method: 'POST', path, ifMatch: undefined, key, body: input })
      entries.push(entry(nextEntryId(), input.entryKey, input.category, 'DRAFT', null, 0, { title: input.title, content: input.content }, 'PENDING'))
      await fulfillReceipt(route, 0)
      return
    }
    if (request.method() === 'POST' && path.endsWith('/knowledge/distillations')) {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as { taskExecutionId: string, entryKey: string, category?: string }
      distillCalls.push({ key, input })
      if (distillSeen.has(key)) {
        // §11.1 replay: the stored envelope has no result body, so entryId/origin/indexStatus
        // stay absent and only the echoed entryKey locates the entry.
        await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Idempotency-Replayed': 'true' }, body: JSON.stringify(receipt()) })
        return
      }
      distillSeen.add(key)
      const origin = { taskExecutionId: input.taskExecutionId, attempt: 1 }
      const existing = entries.find(item => item.entryKey === input.entryKey)
      const target = existing
        ? { ...existing, draft: { title: '蒸馏后的草稿标题', content: '由任务执行过程蒸馏得出的结论。' }, origin }
        : entries[entries.push(entry(nextEntryId(), input.entryKey, input.category ?? 'OTHER', 'DRAFT', null, 0, { title: '蒸馏后的草稿标题', content: '由任务执行过程蒸馏得出的结论。' }, 'PENDING')) - 1]!
      applyOrigin(target, origin)
      if (failFirstDistill) {
        failFirstDistill = false
        return fulfillError(route, 503, 'service_unavailable', '服务暂时不可用，请稍后重试')
      }
      await fulfillJson(route, { ...receipt(), entryId: target.id, entryKey: input.entryKey, origin, indexStatus: 'PENDING' })
      return
    }
    const entryMatch = path.match(/\/knowledge\/entries\/([^/]+)$/)
    if (entryMatch && request.method() === 'GET') {
      const current = entries.find(item => item.id === entryMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Knowledge Entry 不存在')
      await fulfillEtag(route, current, `"${current.version}"`)
      return
    }
    if (entryMatch && request.method() === 'PATCH') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const current = entries.find(item => item.id === entryMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Knowledge Entry 不存在')
      const input = request.postDataJSON() as { title: string, content: string, category?: string }
      writes.push({ method: 'PATCH', path, ifMatch: request.headers()['if-match'], key, body: input })
      if (conflictNextDraft) {
        conflictNextDraft = false
        // The concurrent editor committed first: the head moves and the stored draft changes.
        current.version += 1
        current.draft = { title: '服务端并发草稿', content: '其他成员保存的内容。' }
        current.updatedBy = ids.member
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此条目', current.version)
      }
      if (request.headers()['if-match'] !== `"${current.version}"`) {
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此条目', current.version)
      }
      current.draft = { title: input.title, content: input.content }
      if (input.category) current.category = input.category
      current.version += 1
      current.updatedAt = '2026-08-08T04:00:00Z'
      await fulfillReceipt(route, current.version)
      return
    }
    if (entryMatch && request.method() === 'DELETE') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const current = entries.find(item => item.id === entryMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Knowledge Entry 不存在')
      writes.push({ method: 'DELETE', path, ifMatch: request.headers()['if-match'], key, body: null })
      if (request.headers()['if-match'] !== `"${current.version}"`) {
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此条目', current.version)
      }
      current.status = 'DELETED'
      current.draft = null
      current.indexStatus = 'INDEXED'
      current.version += 1
      await fulfillReceipt(route, current.version)
      return
    }
    const versionListMatch = path.match(/\/knowledge\/entries\/([^/]+)\/versions$/)
    if (versionListMatch && request.method() === 'GET') {
      await fulfillJson(route, { items: versions[versionListMatch[1]] ?? [], nextAfter: null })
      return
    }
    const versionMatch = path.match(/\/knowledge\/entries\/([^/]+)\/versions\/([^/]+)$/)
    if (versionMatch && request.method() === 'GET') {
      const row = (versions[versionMatch[1]] ?? []).find(item => item.revision === Number(versionMatch[2]))
      if (!row) return fulfillError(route, 404, 'aggregate_not_found', 'Knowledge Version 不存在')
      await fulfillEtag(route, row, `"${row.contentHash}"`)
      return
    }
    const effectiveMatch = path.match(/\/knowledge\/entries\/([^/]+)\/effective-version$/)
    if (effectiveMatch && request.method() === 'GET') {
      const current = entries.find(item => item.id === effectiveMatch[1])
      const row = current?.effectiveRevision != null
        ? (versions[current.id] ?? []).find(item => item.revision === current.effectiveRevision)
        : undefined
      if (!row) return fulfillError(route, 404, 'aggregate_not_found', '当前没有生效版本')
      await fulfillEtag(route, row, `"${row.contentHash}"`)
      return
    }
    const commandMatch = path.match(/\/knowledge\/entries\/([^/]+)\/(publish|retire)$/)
    if (commandMatch && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const current = entries.find(item => item.id === commandMatch[1])
      if (!current) return fulfillError(route, 404, 'aggregate_not_found', 'Knowledge Entry 不存在')
      writes.push({ method: 'POST', path, ifMatch: request.headers()['if-match'], key, body: null })
      if (request.headers()['if-match'] !== `"${current.version}"`) {
        return fulfillError(route, 409, 'optimistic_lock_conflict', '其他成员已更新此条目', current.version)
      }
      if (commandMatch[2] === 'publish') {
        const revision = current.latestRevision + 1
        versions[current.id] = [
          versionRow(current.id, revision, current.draft?.title ?? '已发布版本', current.draft?.content ?? ''),
          ...(versions[current.id] ?? []),
        ]
        current.status = 'PUBLISHED'
        current.effectiveRevision = revision
        current.latestRevision = revision
        current.draft = null
        current.indexStatus = 'PENDING'
      } else {
        current.status = 'RETIRED'
        current.effectiveRevision = null
        current.draft = null
      }
      current.version += 1
      current.updatedAt = '2026-08-08T04:00:00Z'
      await fulfillReceipt(route, current.version)
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"not_found"}' })
  })
})

test('知识库列表支持筛选、entryKey 游标翻页与详情版本深链', async ({ page }) => {
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}`)

  const listing = page.getByRole('region', { name: '知识条目列表' })
  await expect(listing.getByRole('button', { name: /api-conventions/ })).toBeVisible()
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toBeVisible()
  await expect(listing.getByRole('button', { name: /old-decision/ })).toHaveCount(0)

  await listing.getByRole('button', { name: '读取更多条目' }).click()
  await expect(listing.getByRole('button', { name: /old-decision/ })).toBeVisible()
  expect(listingQueries.at(-1)?.get('after')).toBe('deploy-runbook')

  await page.getByLabel('按状态筛选').selectOption('PUBLISHED')
  await expect(listing.getByRole('button', { name: /api-conventions/ })).toHaveCount(0)
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toBeVisible()
  expect(listingQueries.at(-1)?.get('status')).toBe('PUBLISHED')

  await page.getByLabel('按分类筛选').selectOption('RUNBOOK')
  await expect(listing.getByRole('button', { name: /deploy-runbook/ })).toBeVisible()
  expect(listingQueries.at(-1)?.get('category')).toBe('RUNBOOK')

  await listing.getByRole('button', { name: /deploy-runbook/ }).click()
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await expect(detail).toBeVisible()
  await expect(detail.getByText('生效修订')).toBeVisible()
  expect(new URL(page.url()).searchParams.get('entry')).toBe(ids.publishedEntry)

  await detail.getByRole('tab', { name: '版本' }).click()
  await expect(detail.getByRole('button', { name: /^r1/ })).toBeVisible()
  await expect(detail.getByText('生效', { exact: true })).toBeVisible()
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('执行数据库迁移')

  await detail.getByRole('button', { name: /^r1/ }).click()
  await expect(detail.getByRole('region', { name: '版本 r1 内容' })).toContainText('部署手册')
})

test('创建对话框提交真实命令并刷新列表', async ({ page }) => {
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '创建条目' }).first().click()

  const dialog = page.getByRole('dialog', { name: '创建知识条目' })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('条目 Key').fill('release-notes')
  await dialog.getByLabel('标题').fill('发布说明')
  await dialog.getByLabel('正文').fill('每两周整理一次变更要点。')
  await dialog.getByRole('button', { name: '创建条目' }).click()

  await expect(dialog).toHaveCount(0)
  const created = writes.find(record => record.method === 'POST' && record.path.endsWith('/knowledge/entries'))
  expect(created?.key).toBeTruthy()
  expect(created?.body).toMatchObject({ entryKey: 'release-notes', category: 'CONVENTION', title: '发布说明', content: '每两周整理一次变更要点。' })
  // The listing orders by entryKey, so the new row lands on the second page past the cursor.
  const listing = page.getByRole('region', { name: '知识条目列表' })
  await listing.getByRole('button', { name: '读取更多条目' }).click()
  await expect(listing.getByRole('button', { name: /release-notes/ })).toBeVisible()
})

test('保存草稿携带 If-Match 与幂等键', async ({ page }) => {
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}&entry=${ids.draftEntry}`)
  const editor = page.getByRole('complementary', { name: '知识条目详情' })
  await expect(editor.getByLabel('标题')).toHaveValue('未发布的接口约定草稿')

  await editor.getByLabel('标题').fill('修订后的接口约定')
  await editor.getByRole('button', { name: '保存草稿' }).click()

  await expect(editor.getByText(/命令已确认/)).toBeVisible()
  const save = writes.find(record => record.method === 'PATCH')
  expect(save?.ifMatch).toBe('"1"')
  expect(save?.key).toBeTruthy()
  expect(save?.body).toMatchObject({ title: '修订后的接口约定' })
  await expect(editor.getByLabel('标题')).toHaveValue('修订后的接口约定')
})

test('发布把草稿提升为生效版本', async ({ page }) => {
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}&entry=${ids.draftEntry}`)
  const detail = page.getByRole('complementary', { name: '知识条目详情' })

  await detail.getByRole('button', { name: '发布当前草稿' }).click()

  const publish = writes.find(record => record.method === 'POST' && record.path.endsWith('/publish'))
  expect(publish?.ifMatch).toBe('"1"')
  expect(publish?.key).toBeTruthy()
  await expect(detail.getByText('已发布', { exact: true })).toBeVisible()
  await expect(detail.getByText('生效修订').locator('..')).toContainText('r1')
  await expect(detail.getByText('当前没有未保存的草稿')).toBeVisible()

  await detail.getByRole('tab', { name: '版本' }).click()
  await expect(detail.getByRole('button', { name: /^r1/ })).toBeVisible()
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('所有写命令必须携带 If-Match 与幂等键。')
})

test('409 冲突保留编辑器并披露服务端版本', async ({ page }) => {
  conflictNextDraft = true
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}&entry=${ids.draftEntry}`)
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await detail.getByLabel('标题').fill('冲突中的本地修订')
  await detail.getByRole('button', { name: '保存草稿' }).click()

  await expect(detail.getByText('条目已被其他成员更新')).toBeVisible()
  await expect(detail.getByText(/服务端当前版本 v2/)).toBeVisible()
  // D4 layer three: the reload refreshed the head facts without overwriting the local draft.
  await expect(detail.getByLabel('标题')).toHaveValue('冲突中的本地修订')

  await detail.getByRole('button', { name: '载入服务端内容' }).click()
  const discard = page.getByRole('alertdialog', { name: '放弃未保存的草稿修改？' })
  await expect(discard).toBeVisible()
  await discard.getByRole('button', { name: '放弃修改' }).click()
  await expect(detail.getByLabel('标题')).toHaveValue('服务端并发草稿')
})

test('删除走确认对话框落到墓碑', async ({ page }) => {
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}&entry=${ids.draftEntry}`)
  const detail = page.getByRole('complementary', { name: '知识条目详情' })

  await detail.getByRole('button', { name: '删除条目' }).click()
  const confirm = page.getByRole('alertdialog', { name: '删除知识条目' })
  await expect(confirm).toContainText('删除墓碑')
  await expect(confirm.getByRole('button', { name: '确认删除' })).toBeDisabled()

  await confirm.getByRole('checkbox').check()
  await confirm.getByRole('button', { name: '确认删除' }).click()

  const remove = writes.find(record => record.method === 'DELETE')
  expect(remove?.ifMatch).toBe('"1"')
  expect(remove?.key).toBeTruthy()
  await expect(confirm).toHaveCount(0)
  await expect(detail.getByText('此条目已删除')).toBeVisible()
  await expect(page.getByRole('region', { name: '知识条目列表' }).getByRole('button', { name: /已删除/ })).toBeVisible()
  await expect(detail.getByRole('button', { name: '发布当前草稿' })).toHaveCount(0)
})

test('无 knowledge:manage 时读面完整且管理入口隐藏', async ({ page }) => {
  revokeManage = true
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}&entry=${ids.draftEntry}`)

  const listing = page.getByRole('region', { name: '知识条目列表' })
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await expect(listing.getByRole('button', { name: /api-conventions/ })).toBeVisible()
  await expect(detail.getByText('api-conventions')).toBeVisible()
  await expect(detail.getByRole('tab', { name: '版本' })).toBeVisible()

  await expect(page.getByRole('button', { name: '创建条目' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '从执行蒸馏' })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '发布当前草稿' })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '删除条目' })).toHaveCount(0)
  await expect(detail.getByText('审计信息')).toHaveCount(0)
  await expect(detail.getByText('需要知识库管理权限').first()).toBeVisible()
  await expect(detail.getByLabel('标题')).toBeDisabled()
})

test('蒸馏回执区分首次确认与幂等重放', async ({ page }) => {
  await page.goto(`/knowledge?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '从执行蒸馏' }).first().click()

  // First confirmation: entry coordinates plus the authoritative PENDING wording (never "未保存").
  const dialog = page.getByRole('dialog', { name: '从执行蒸馏知识' })
  await dialog.getByLabel('任务执行 ID').fill(ids.distillExecution)
  await dialog.getByLabel('目标条目 Key').fill('postmortem-cache')
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()

  const receipt = dialog.getByRole('region', { name: '蒸馏回执' })
  await expect(receipt.getByText('postmortem-cache')).toBeVisible()
  await expect(receipt.getByText(/第 1 次尝试/)).toBeVisible()
  await expect(receipt.getByText('待索引', { exact: true })).toBeVisible()
  await expect(receipt.getByText('内容已保存，等待后台建立索引')).toBeVisible()
  await expect(receipt.getByRole('button', { name: '打开条目' })).toBeVisible()
  expect(distillCalls).toHaveLength(1)
  const firstKey = distillCalls[0]!.key

  // The distilled entry lands on the listing (second page past the entryKey cursor); its row
  // opens the detail deep link.
  await receipt.getByRole('button', { name: '打开条目' }).click()
  await expect(dialog).toHaveCount(0)
  const listing = page.getByRole('region', { name: '知识条目列表' })
  await listing.getByRole('button', { name: '读取更多条目' }).click()
  await listing.getByRole('button', { name: /postmortem-cache/ }).click()
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await expect(detail.getByText('postmortem-cache')).toBeVisible()

  // Retry shape: the server committed the command but the response was lost (503). The form
  // keeps its inputs, so an unchanged resubmit reuses the idempotency key — §11.1's replay
  // envelope comes back and the receipt switches to the replay wording.
  failFirstDistill = true
  await detail.getByRole('button', { name: '关闭条目详情' }).click()
  await page.getByRole('button', { name: '从执行蒸馏' }).first().click()
  await dialog.getByLabel('任务执行 ID').fill(ids.distillExecution)
  await dialog.getByLabel('目标条目 Key').fill('retry-cache')
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()
  await expect(dialog.getByRole('alert')).toContainText('提交结果尚未确认。请保留当前内容，重试会沿用原操作标识')
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()

  await expect(receipt.getByText('此蒸馏命令此前已确认过，本次为幂等重放，没有重复创建内容。')).toBeVisible()
  await expect(receipt.getByRole('button', { name: '打开条目' })).toHaveCount(0)
  expect(distillCalls).toHaveLength(3)
  expect(distillCalls[2]!.key).toBe(distillCalls[1]!.key)
  expect(distillCalls[1]!.key).not.toBe(firstKey)
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
    createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T01:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function nextEntryId(): string {
  const suffix = entryIdSequence.toString(16).padStart(4, '0')
  entryIdSequence += 1
  return `00000000-0000-1000-8000-00000000${suffix}`
}

function entry(
  id: string,
  entryKey: string,
  category: string,
  status: EntryRecord['status'],
  effectiveRevision: number | null,
  version: number,
  draft: { title: string, content: string } | null,
  indexStatus: EntryRecord['indexStatus'],
): EntryRecord {
  return {
    id, entryKey, category, status, indexStatus, effectiveRevision,
    latestRevision: effectiveRevision ?? 0, draft, version,
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z',
    createdBy: ids.principal, updatedBy: ids.principal, origin: null,
  }
}

function applyOrigin(target: EntryRecord, origin: { taskExecutionId: string, attempt: number }): void {
  target.origin = origin
  target.indexStatus = 'PENDING'
}

function versionRow(entryId: string, revision: number, title: string, content: string): VersionRecord {
  return {
    entryId, revision, previousRevision: revision > 1 ? revision - 1 : null, title, content,
    contentHash: String(revision).repeat(64), indexStatus: 'INDEXED',
    createdAt: '2026-08-08T02:00:00Z', createdBy: ids.principal,
  }
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

function fulfillError(route: Route, status: number, code: string, message: string, currentVersion: number | null = null): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, correlationId: crypto.randomUUID(), retryable: status >= 500 || status === 409, currentVersion, details: {} }) })
}
