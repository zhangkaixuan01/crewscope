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
  githubBinding: '00000000-0000-1000-8000-000000002202',
  legacyBinding: '00000000-0000-1000-8000-000000002210',
  entryJob: '00000000-0000-1000-8000-000000002401',
  repoJob: '00000000-0000-1000-8000-000000002402',
  failedJob: '00000000-0000-1000-8000-000000002403',
  cancelledJob: '00000000-0000-1000-8000-000000002404',
}

interface JobRecord {
  id: string
  source: 'KNOWLEDGE_ENTRY' | 'REPOSITORY'
  status: 'QUEUED' | 'CHUNKING' | 'EMBEDDING' | 'ACTIVATING' | 'READY' | 'FAILED' | 'CANCELLED'
  entryId: string | null
  projectId: string | null
  indexKey: { bindingId: string, commit: string, chunkPolicyHash: string, modelKey: string, modelRevision: number } | null
  attempt: number
  chunksDone: number
  chunksTotal: number
  failureCode: string | null
  generationBuildSequence: number
  claimedBy: string | null
  leaseExpiresAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

interface CommandRecord {
  method: string
  path: string
  idempotencyKey: string | undefined
  ifMatch: string | undefined
  body: unknown
}

const PAGE_SIZE = 2
let jobs: JobRecord[]
let commands: CommandRecord[]
let listingQueries: Array<URLSearchParams>
// Gate-closed shapes (contract §7): the commands still answer 202 with enqueued: 0 — a skip.
let gateClosed: boolean
// The next cancel POST answers 409 with the live status, mirroring a worker that claimed the job.
let conflictNextCancel: boolean
// Repository-build rejections for the dialog's wording paths: 404 four-coordinate miss, 422 retired.
let rejectNextBuild: 'miss' | 'retired' | null
let revokeManage: boolean
let buildSequence: number

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-08T04:00:00Z'))
  jobs = [
    job(ids.cancelledJob, 'KNOWLEDGE_ENTRY', 'CANCELLED', '2026-08-08T03:00:00Z', { failureCode: 'CANCELLED' }),
    job(ids.failedJob, 'REPOSITORY', 'FAILED', '2026-08-08T03:10:00Z', { failureCode: 'MODEL_DRIFT' }),
    job(ids.repoJob, 'REPOSITORY', 'EMBEDDING', '2026-08-08T03:20:00Z', { claimed: 'knowledge-index-worker-1' }),
    job(ids.entryJob, 'KNOWLEDGE_ENTRY', 'QUEUED', '2026-08-08T03:30:00Z', {}),
  ]
  commands = []
  listingQueries = []
  gateClosed = false
  conflictNextCancel = false
  rejectNextBuild = null
  revokeManage = false
  buildSequence = 0x2500

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
    // RepositoryBuildDialog cascade: the coding domain lists the project's managed bindings.
    if (request.method() === 'GET' && path.endsWith(`/work-projects/${ids.project}/repository-bindings`)) {
      await fulfillJson(route, { items: [repositoryBinding(ids.githubBinding, 'crewscope/backend', 'ACTIVE'), repositoryBinding(ids.legacyBinding, 'crewscope/legacy', 'RETIRED')] })
      return
    }
    // The I01c listing is (createdAt, id) ascending with a jobId keyset cursor (contract §4).
    if (request.method() === 'GET' && path.endsWith('/knowledge/index/jobs')) {
      listingQueries.push(url.searchParams)
      const source = url.searchParams.get('source')
      const status = url.searchParams.get('status')
      const after = url.searchParams.get('after')
      const live = jobs
        .filter(item => !source || item.source === source)
        .filter(item => !status || item.status === status)
        .filter(item => !after || item.createdAt > (jobs.find(candidate => candidate.id === after)?.createdAt ?? ''))
        .sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id))
      const pageItems = live.slice(0, PAGE_SIZE)
      await fulfillJson(route, { items: pageItems, nextAfter: live.length > PAGE_SIZE ? pageItems.at(-1)!.id : null })
      return
    }
    // Contract §2: the three commands are structurally idempotent — no Idempotency-Key, no If-Match.
    if (request.method() === 'POST' && path.endsWith('/knowledge/index/rebuilds')) {
      recordCommand(route, 'POST', path, null)
      if (gateClosed) return fulfillJson(route, { enqueued: 0 }, 202)
      const enqueued = 2
      for (let index = 0; index < enqueued; index += 1) {
        jobs.push(job(nextJobId(), 'KNOWLEDGE_ENTRY', 'QUEUED', '2026-08-08T03:40:00Z', {}))
      }
      return fulfillJson(route, { enqueued }, 202)
    }
    if (request.method() === 'POST' && path.endsWith('/knowledge/index/repository-builds')) {
      recordCommand(route, 'POST', path, request.postDataJSON())
      if (rejectNextBuild === 'miss') {
        rejectNextBuild = null
        return fulfillError(route, 404, 'aggregate_not_found', 'Repository binding not found', {})
      }
      if (rejectNextBuild === 'retired') {
        rejectNextBuild = null
        return fulfillError(route, 422, 'invalid_value', 'Repository binding is not active', { field: 'repositoryIndex.bindingId' })
      }
      if (gateClosed) return fulfillJson(route, { enqueued: 0, job: null }, 202)
      const created = job(nextJobId(), 'REPOSITORY', 'QUEUED', '2026-08-08T03:45:00Z', {})
      jobs.push(created)
      return fulfillJson(route, { enqueued: 1, job: created }, 202)
    }
    const cancelMatch = path.match(/\/knowledge\/index\/jobs\/([^/]+)\/cancel$/)
    if (cancelMatch && request.method() === 'POST') {
      recordCommand(route, 'POST', path, null)
      const current = jobs.find(item => item.id === cancelMatch[1])
      if (!current) return fulfillError(route, 404, 'knowledge_index_job_not_found', 'Knowledge Index Job 不存在', { jobId: cancelMatch[1] ?? '' })
      if (current.status === 'CANCELLED') return fulfillJson(route, current)
      if (current.status !== 'QUEUED' || conflictNextCancel) {
        conflictNextCancel = false
        // The worker claimed the job between the read and the cancel: the row's live state moved.
        const live = current.status === 'QUEUED' ? 'EMBEDDING' : current.status
        return fulfillError(route, 409, 'knowledge_index_job_not_cancellable', 'Only a QUEUED job can be cancelled', { jobId: current.id, status: live })
      }
      current.status = 'CANCELLED'
      current.failureCode = 'CANCELLED'
      current.updatedAt = '2026-08-08T04:00:00Z'
      return fulfillJson(route, current)
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"not_found"}' })
  })
})

test('索引作业列表支持多源多态、筛选、jobId 游标翻页与技术块展开', async ({ page }) => {
  await page.goto(`/knowledge/index?team=${ids.team}&project=${ids.project}`)

  const listing = page.getByRole('region', { name: '索引作业列表' })
  // First page: (createdAt, id) ascending — the cancelled entry job, then the failed repo build.
  // Status wordings are asserted on rows (`li.job-item`) because the status <select> carries
  // the same labels in hidden <option> nodes, which never count as visible.
  await expect(listing.locator('li.job-item', { hasText: '已取消' })).toBeVisible()
  await expect(listing.getByText('嵌入模型与索引键冻结版本不一致')).toBeVisible()

  await listing.getByRole('button', { name: '读取更多作业' }).click()
  await expect(listing.locator('li.job-item', { hasText: '嵌入中' })).toBeVisible()
  // The keyset cursor is the last job id of the previous page (contract §4): the failed job.
  expect(listingQueries.at(-1)?.get('after')).toBe(ids.failedJob)

  await listing.getByRole('button', { name: '读取更多作业' }).click()
  await expect(listing.locator('li.job-item', { hasText: '排队中' })).toBeVisible()
  await expect(listing.getByRole('button', { name: '读取更多作业' })).toHaveCount(0)

  // The manager's technical block carries the lease and the full index key — on the
  // EMBEDDING row, the only one a worker has claimed.
  await listing.locator('li.job-item', { hasText: '嵌入中' }).getByRole('button', { name: '展开详情' }).click()
  await expect(listing.getByText('knowledge-index-worker-1')).toBeVisible()
  await expect(listing.getByText(/text-embedding-v4@3/)).toBeVisible()

  await page.getByLabel('按来源筛选').selectOption('REPOSITORY')
  await expect(listing.getByText('条目', { exact: true })).toHaveCount(0)
  expect(listingQueries.at(-1)?.get('source')).toBe('REPOSITORY')

  await page.getByLabel('按状态筛选').selectOption('QUEUED')
  expect(listingQueries.at(-1)?.get('status')).toBe('QUEUED')
})

test('取消作业就地替换行，409 披露当前状态且行不动', async ({ page }) => {
  await page.goto(`/knowledge/index?team=${ids.team}&project=${ids.project}`)

  const listing = page.getByRole('region', { name: '索引作业列表' })
  await listing.getByRole('button', { name: '读取更多作业' }).click()
  await listing.getByRole('button', { name: '读取更多作业' }).click()
  const queuedRow = listing.locator('li.job-item', { hasText: '排队中' })
  await expect(queuedRow).toBeVisible()

  // A worker claims the job between read and cancel: the POST answers 409 with the live status.
  conflictNextCancel = true
  await queuedRow.getByRole('button', { name: '取消作业' }).click()
  await expect(page.getByRole('alert')).toContainText('Only a QUEUED job can be cancelled（当前状态：嵌入中）')
  await expect(queuedRow.getByText('排队中')).toBeVisible()
  const conflicted = commands.find(record => record.path.endsWith('/cancel'))
  expect(conflicted?.idempotencyKey).toBeUndefined()
  expect(conflicted?.ifMatch).toBeUndefined()

  // The retry meets the idempotent snapshot swap: the row becomes cancelled in place.
  await queuedRow.getByRole('button', { name: '取消作业' }).click()
  await expect(page.getByRole('status')).toContainText('已取消排队中的作业')
  // The swap is in place: the page keeps all four rows, no row stays QUEUED, and the
  // cancelled count grows to 2 (the fixture row plus the one just cancelled).
  await expect(listing.locator('li.job-item', { hasText: '排队中' })).toHaveCount(0)
  await expect(listing.locator('li.job-item', { hasText: '已取消' })).toHaveCount(2)
  await expect(listing.getByRole('button', { name: '取消作业' })).toHaveCount(0)
})

test('重建回执区分入队与闸关跳过两种形态', async ({ page }) => {
  await page.goto(`/knowledge/index?team=${ids.team}&project=${ids.project}`)

  await page.getByRole('button', { name: '重建知识索引' }).click()
  // The banner is scoped by class: the listing's StatePanel also answers role=status
  // while the forced re-read holds it in the loading state.
  await expect(page.locator('p.index-banner')).toContainText('已入队 2 个知识重建作业')
  const rebuild = commands.find(record => record.path.endsWith('/knowledge/index/rebuilds'))
  expect(rebuild?.idempotencyKey).toBeUndefined()
  expect(rebuild?.ifMatch).toBeUndefined()

  // The store invalidates the listing, so the fresh queue re-reads immediately.
  const listing = page.getByRole('region', { name: '索引作业列表' })
  await listing.getByRole('button', { name: '读取更多作业' }).click()

  gateClosed = true
  await page.getByRole('button', { name: '关闭提示' }).click()
  await page.getByRole('button', { name: '重建知识索引' }).click()
  // Gate closed is a skip, never an error (contract §7).
  await expect(page.locator('p.index-banner')).toContainText('未新建作业：索引开关未开启，或没有可重建的生效版本')
})

test('仓库索引构建级联选择、校验 commit 并呈现两路错误', async ({ page }) => {
  await page.goto(`/knowledge/index?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '构建仓库索引' }).click()

  const dialog = page.getByRole('dialog', { name: '构建仓库索引' })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('项目').selectOption(ids.project)
  await expect(dialog.getByLabel('仓库绑定')).toBeEnabled()
  // Only ACTIVE bindings are options; the retired one never appears.
  const bindingText = await dialog.getByLabel('仓库绑定').textContent()
  expect(bindingText).toContain('crewscope/backend · main')
  expect(bindingText).not.toContain('crewscope/legacy')
  await dialog.getByLabel('仓库绑定').selectOption(ids.githubBinding)

  // An invalid commit keeps the submit button disabled, so no command can leave the page.
  await dialog.getByLabel('提交（Commit）').fill('abc123')
  await expect(dialog.getByRole('button', { name: '入队构建' })).toBeDisabled()
  expect(commands.filter(record => record.path.endsWith('/repository-builds'))).toHaveLength(0)

  const commit = '2'.repeat(40)
  rejectNextBuild = 'miss'
  await dialog.getByLabel('提交（Commit）').fill(commit)
  await dialog.getByRole('button', { name: '入队构建' }).click()
  await expect(dialog.getByRole('alert')).toContainText('仓库绑定不存在或不属于该项目。')

  rejectNextBuild = 'retired'
  await dialog.getByRole('button', { name: '入队构建' }).click()
  await expect(dialog.getByRole('alert')).toContainText('所选仓库绑定已停用。')

  await dialog.getByRole('button', { name: '入队构建' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('status')).toContainText('已入队仓库索引作业（00000000，状态：排队中）')
  const build = commands.find(record => record.path.endsWith('/knowledge/index/repository-builds'))
  expect(build?.body).toEqual({ projectId: ids.project, bindingId: ids.githubBinding, commit })
  expect(build?.idempotencyKey).toBeUndefined()
  expect(build?.ifMatch).toBeUndefined()

  const listing = page.getByRole('region', { name: '索引作业列表' })
  await listing.getByRole('button', { name: '读取更多作业' }).click()
  await expect(listing.locator('li.job-item', { hasText: '排队中' })).toBeVisible()
})

test('无 knowledge:manage 时读面完整且命令入口与技术块隐藏', async ({ page }) => {
  revokeManage = true
  await page.goto(`/knowledge/index?team=${ids.team}&project=${ids.project}`)

  const listing = page.getByRole('region', { name: '索引作业列表' })
  await expect(listing.locator('li.job-item', { hasText: '已取消' })).toBeVisible()
  await expect(listing.getByText('嵌入模型与索引键冻结版本不一致')).toBeVisible()

  await expect(page.getByRole('button', { name: '重建知识索引' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '构建仓库索引' })).toHaveCount(0)
  await expect(listing.getByRole('button', { name: '取消作业' })).toHaveCount(0)

  // The row expands for readers too, but into the restricted notice — never the lease facts.
  await listing.getByRole('button', { name: '展开详情' }).first().click()
  await expect(listing.getByText('技术详情仅对具有知识管理权限的成员展示。')).toBeVisible()
  await expect(listing.getByText('持有租约')).toHaveCount(0)
  await expect(listing.getByText('创建者')).toHaveCount(0)
})

test('15 秒自动刷新可开关', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-08-08T04:00:00Z') })
  await page.goto(`/knowledge/index?team=${ids.team}&project=${ids.project}`)
  const listing = page.getByRole('region', { name: '索引作业列表' })
  await expect(listing.locator('li.job-item', { hasText: '已取消' })).toBeVisible()
  const initialReads = listingQueries.length

  await page.clock.fastForward(15_000)
  await expect.poll(() => listingQueries.length).toBe(initialReads + 1)

  await page.getByLabel('15 秒自动刷新').uncheck()
  await page.clock.fastForward(45_000)
  expect(listingQueries.length).toBe(initialReads + 1)
})

function recordCommand(route: Route, method: string, path: string, body: unknown): void {
  commands.push({ method, path, idempotencyKey: route.request().headers()['idempotency-key'], ifMatch: route.request().headers()['if-match'], body })
}

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
    createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function repositoryBinding(id: string, repositoryKey: string, status: string) {
  return {
    id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project,
    kind: 'GITHUB', repositoryKey, defaultBranch: 'main', status, version: 1,
    createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T01:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function job(
  id: string,
  source: JobRecord['source'],
  status: JobRecord['status'],
  createdAt: string,
  extra: { failureCode?: string, claimed?: string },
): JobRecord {
  return {
    id, source, status,
    entryId: source === 'KNOWLEDGE_ENTRY' ? '00000000-0000-1000-8000-000000002301' : null,
    projectId: source === 'REPOSITORY' ? ids.project : null,
    indexKey: source === 'REPOSITORY'
      ? { bindingId: ids.githubBinding, commit: '2'.repeat(40), chunkPolicyHash: '3'.repeat(64), modelKey: 'text-embedding-v4', modelRevision: 3 }
      : null,
    attempt: status === 'QUEUED' ? 0 : 1,
    chunksDone: status === 'EMBEDDING' ? 128 : 0,
    chunksTotal: status === 'EMBEDDING' ? 512 : 0,
    failureCode: extra.failureCode ?? null,
    generationBuildSequence: 1,
    claimedBy: extra.claimed ?? null,
    leaseExpiresAt: extra.claimed ? '2026-08-08T04:02:00Z' : null,
    createdBy: ids.principal,
    createdAt,
    updatedAt: createdAt,
  }
}

function nextJobId(): string {
  buildSequence += 1
  return `00000000-0000-1000-8000-00000000${buildSequence.toString(16).padStart(4, '0')}`
}

function fulfillJson(route: Route, value: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function fulfillError(route: Route, status: number, code: string, message: string, details: Record<string, string>): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, correlationId: crypto.randomUUID(), retryable: false, currentVersion: null, details }) })
}
