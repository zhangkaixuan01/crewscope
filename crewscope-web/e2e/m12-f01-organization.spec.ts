import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Request, type Route } from '@playwright/test'
import { permissions } from '../src/app/auth'
import { ids, mockApi } from './menu-walk'

/**
 * M12-F01 组织设置页的 mock 层 e2e。菜单走查（m9-mobile-reach/m9-dark-theme）已经覆盖
 * 三个 tab 的存在性；这里验的是治理契约在浏览器里的完整生命周期与权限矩阵：
 *
 * 1. **导出工作流** —— 创建（Idempotency-Key 必带）→ 单次 process 收敛为终态（无键）→
 *    对账清单 → 产物下载（文件名 = 作业 ID）。
 * 2. **删除工作流** —— 预检三项为零 → 创建 → 用户名令牌确认（If-Match 引版本、无幂等键、
 *    autocomplete=off、确认后表单卸载）→ 13 步执行 → 备份声明全文；令牌与
 *    confirmationTokenHash 全程不落 DOM 与浏览器存储。
 * 3. **权限矩阵** —— 审计员（治理面 forbidden）、无组织角色成员（只读信息卡）、组织管理员
 *    （有导出面板、删除入口不渲染——不渲染而非渲染后 403，避免无谓的拒绝审计噪音）。
 * 4. **可达性** —— 三个 tab 键盘可达、Axe 零违规、无横向溢出（双 project，390px 档在
 *    narrow-chromium 里真实生效）。
 *
 * 基础 GET（profile/settings/role-grants/teams 等）由 menu-walk 的 mockApi 兜底；本文件的
 * 会话与治理端点路由在其之后注册（Playwright 后注册者优先），未命中走 route.fallback()。
 */

/** The four role tiers of the M12-F01 permission matrix (session-grain, server still enforces). */
type SessionRole = 'ORG_OWNER' | 'ORG_ADMIN' | 'ORG_AUDITOR' | 'MEMBER'

const ORG_PERMISSIONS: Record<SessionRole, string[]> = {
  ORG_OWNER: [permissions.orgAuditRead, permissions.orgGovern, permissions.orgManageSettings],
  ORG_ADMIN: [permissions.orgAuditRead, permissions.orgGovern, permissions.orgManageSettings],
  ORG_AUDITOR: [permissions.orgAuditRead],
  MEMBER: [],
}

const targetAccountId = '00000000-0000-4000-8000-000000000909'
const deletionReason = '离职账号清理：在途任务已收尾、责任已移交，现删除该账号并保留备份对账。'
/** The target account's username — the confirmation token. Distinct from the session username. */
const deletionToken = 'departing-owner'

function sessionFor(role: SessionRole) {
  // mapSession rejects an org:* vocabulary without the organization projection (and vice versa),
  // so the MEMBER tier carries neither — that is exactly the backend's frozen projection rule.
  const teamPermissions = [permissions.scopeRead, permissions.workRead]
  return {
    authenticated: true,
    registrationMode: 'OPEN' as const,
    csrf: { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-org-e2e' },
    account: {
      accountId: '00000000-4000-8000-0000-000000000111', username: 'e2e-member', displayName: '张凯旋',
      platformRole: 'USER' as const, securityVersion: 1, version: 1,
    },
    principal: { principalId: ids.principal, organizationId: ids.organization },
    organization: role === 'MEMBER' ? null : { organizationId: ids.organization, name: 'Test Organization', role },
    teams: [{
      teamId: ids.team, name: 'Platform Engineering',
      memberId: '00000000-4000-8000-0000-000000000301', permissions: teamPermissions,
    }],
    permissions: [...teamPermissions, ...ORG_PERMISSIONS[role]],
  }
}

interface CallFact {
  method: string
  path: string
  body: Record<string, unknown> | null
  idempotencyKey: string | null
  ifMatch: string | null
  csrf: string | null
}

async function installGovernanceApi(page: Page, role: SessionRole = 'ORG_OWNER') {
  const calls: CallFact[] = []
  let exportJob: Record<string, unknown> | null = null
  let deletionJob: Record<string, unknown> | null = null
  // The client lifts the XSRF-TOKEN cookie onto every write (Spring's cookie/header CSRF pair),
  // so the mock tier plants the cookie and the write assertions below verify the pair end to end.
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-org-e2e', url: 'http://127.0.0.1:4173' }])
  await page.route('**/api/v1/auth/session', route => fulfillJson(route, sessionFor(role)))
  await page.route('**/api/v1/organizations/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (method === 'GET' && /\/deletion-precheck$/.test(path)) {
      return fulfillJson(route, {
        accountId: targetAccountId, inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null,
      })
    }
    if (method === 'POST' && /\/data-exports$/.test(path)) {
      record(request)
      exportJob = exportJobBody('PENDING')
      return fulfillJson(route, exportJob, 202)
    }
    if (method === 'POST' && /\/data-exports\/[^/]+\/process$/.test(path)) {
      record(request)
      exportJob = exportJobBody('COMPLETED')
      return fulfillJson(route, exportJob)
    }
    if (method === 'GET' && /\/data-exports\/[^/]+\/artifacts$/.test(path)) {
      return fulfillJson(route, exportArtifactsBody())
    }
    if (method === 'GET' && /\/data-exports\/[^/]+$/.test(path)) {
      return exportJob ? fulfillJson(route, exportJob) : notFoundJson(route)
    }
    if (method === 'POST' && /\/account-deletions$/.test(path)) {
      record(request)
      deletionJob = deletionJobBody('PENDING_CONFIRMATION')
      return fulfillJson(route, deletionJob, 202)
    }
    if (method === 'POST' && /\/account-deletions\/[^/]+\/confirm$/.test(path)) {
      record(request)
      deletionJob = deletionJobBody('CONFIRMED')
      return fulfillJson(route, deletionJob)
    }
    if (method === 'POST' && /\/account-deletions\/[^/]+\/process$/.test(path)) {
      record(request)
      deletionJob = deletionJobBody('COMPLETED')
      return fulfillJson(route, deletionJob)
    }
    return route.fallback()
  })
  return {
    calls,
    get exportJob() { return exportJob },
    get deletionJob() { return deletionJob },
  }

  function record(request: Request): void {
    calls.push({
      method: request.method(),
      path: new URL(request.url()).pathname,
      body: request.postDataJSON() as Record<string, unknown> | null,
      idempotencyKey: request.headers()['idempotency-key'] ?? null,
      ifMatch: request.headers()['if-match'] ?? null,
      csrf: request.headers()['x-xsrf-token'] ?? null,
    })
  }
}

test('导出工作流：创建→单次处理收敛→对账清单→下载产物', async ({ page }) => {
  await mockApi(page)
  const fixture = await installGovernanceApi(page)
  await page.goto('/settings/organization?tab=governance')

  await expect(page.getByRole('heading', { name: '发起数据导出' })).toBeVisible()
  await expect(page.getByText('治理作业没有列表端点')).toBeVisible()
  await page.getByRole('button', { name: '创建导出作业' }).click()

  await expect(page.getByRole('heading', { name: '导出作业' })).toBeVisible()
  const create = fixture.calls.find(call => /\/data-exports$/.test(call.path))!
  expect(create.method).toBe('POST')
  expect(create.body).toEqual({ includeVectors: false })
  expect(create.csrf).toBe('csrf-org-e2e')
  expect(create.idempotencyKey).toBeTruthy()

  const progress = page.getByRole('region', { name: '资源族处理进度' })
  await expect(progress).toContainText('任务')
  await expect(progress).toContainText('凭证密钥（仅引用）')

  await page.getByRole('button', { name: '生成产物' }).click()
  await expect(page.getByRole('heading', { name: '对账清单' })).toBeVisible()
  await expect(page.getByRole('region', { name: '清单族摘要' })).toContainText('VECTOR_STORE_DISABLED')
  // The job machine is the idempotence: process and cancel carry neither key nor version quote.
  const process = fixture.calls.find(call => /\/process$/.test(call.path))!
  expect(process.idempotencyKey).toBeNull()
  expect(process.ifMatch).toBeNull()

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '下载产物' }).click(),
  ])
  expect(download.suggestedFilename()).toBe('export-job-e1.json')
})

test('删除工作流：预检→创建→用户名令牌确认→13 步执行→备份声明', async ({ page }) => {
  await mockApi(page)
  const fixture = await installGovernanceApi(page)
  await page.goto('/settings/organization?tab=governance')

  await page.getByRole('textbox', { name: '目标账号 ID（UUID）' }).fill(targetAccountId)
  await page.getByRole('button', { name: '运行预检' }).click()
  await expect(page.getByText('三项先决均为零，可以发起删除作业。')).toBeVisible()

  await page.getByRole('textbox', { name: '删除原因（20 至 500 字）' }).fill(deletionReason)
  await page.getByRole('button', { name: '创建删除作业' }).click()
  await expect(page.getByRole('heading', { name: '删除作业', exact: true })).toBeVisible()

  const create = fixture.calls.find(call => /\/account-deletions$/.test(call.path))!
  expect(create.body).toEqual({ accountId: targetAccountId, reason: deletionReason })
  expect(create.idempotencyKey).toBeTruthy()

  const token = page.getByRole('textbox', { name: '确认令牌（目标账号的用户名）' })
  await expect(token).toHaveAttribute('autocomplete', 'off')
  // The DTO allowlist must have stripped the hash before it could ever reach the DOM.
  await expect(page.locator('body')).not.toContainText('e2e-hash-must-never-render')

  await token.fill(deletionToken)
  await page.getByRole('button', { name: '确认删除作业' }).click()

  await expect(page.getByRole('button', { name: '开始执行' })).toBeVisible()
  const confirm = fixture.calls.find(call => /\/confirm$/.test(call.path))!
  expect(confirm.body).toEqual({ confirmationToken: deletionToken })
  expect(confirm.ifMatch).toBe('"1"')
  expect(confirm.idempotencyKey).toBeNull()
  expect(confirm.csrf).toBe('csrf-org-e2e')
  // The confirmed echo is one-shot: the field unmounts with the state change.
  await expect(token).toHaveCount(0)

  await page.getByRole('button', { name: '开始执行' }).click()
  await expect(page.getByRole('heading', { name: '备份保留声明' })).toBeVisible()
  await expect(page.getByText('每日快照保留 7 天')).toBeVisible()
  await expect(page.getByRole('region', { name: '删除协调步骤' }).locator('tbody tr')).toHaveCount(13)

  // The token is a one-shot echo: neither browser storage nor the rendered panel keeps it.
  const persisted = await page.evaluate(() =>
    JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
  expect(persisted).not.toContain(deletionToken)
  await expect(page.locator('body')).not.toContainText(deletionToken)
})

test('权限矩阵：审计员可读策略与角色但治理面被拒', async ({ page }) => {
  await mockApi(page)
  await installGovernanceApi(page, 'ORG_AUDITOR')
  await page.goto('/settings/organization?tab=governance')
  await expect(page.getByText('需要治理角色')).toBeVisible()

  await page.getByRole('link', { name: '组织角色' }).click()
  await expect(page.getByRole('heading', { name: '组织角色', exact: true })).toBeVisible()

  await page.getByRole('link', { name: '常规', exact: true }).click()
  await expect(page.getByText('当前策略：永久保留')).toBeVisible()
  await expect(page.getByRole('button', { name: '调整策略' })).toHaveCount(0)
})

test('权限矩阵：无组织角色的成员只见信息卡', async ({ page }) => {
  await mockApi(page)
  await installGovernanceApi(page, 'MEMBER')
  await page.goto('/settings/organization')
  await expect(page.getByRole('heading', { name: '组织信息' })).toBeVisible()
  await expect(page.getByText('审计保留策略由组织管理员配置，当前角色无权查看。')).toBeVisible()

  await page.goto('/settings/organization?tab=roles')
  await expect(page.getByText('需要组织审计角色')).toBeVisible()
  await page.goto('/settings/organization?tab=governance')
  await expect(page.getByText('需要治理角色')).toBeVisible()
})

test('权限矩阵：组织管理员见导出面板但删除入口不渲染', async ({ page }) => {
  await mockApi(page)
  await installGovernanceApi(page, 'ORG_ADMIN')
  await page.goto('/settings/organization?tab=governance')

  await expect(page.getByRole('heading', { name: '发起数据导出' })).toBeVisible()
  await expect(page.getByText('账号删除仅限组织 Owner 执行')).toBeVisible()
  await expect(page.getByRole('heading', { name: '第一步：删除预检' })).toHaveCount(0)
})

test('三个 tab 键盘可达、Axe 零违规、无横向溢出', async ({ page }) => {
  await mockApi(page)
  await installGovernanceApi(page)
  await page.goto('/settings/organization')
  await expect(page.getByRole('heading', { name: '组织信息' })).toBeVisible()

  const general = page.getByRole('link', { name: '常规', exact: true })
  await general.focus()
  await expect(general).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '组织角色' })).toBeFocused()

  const tabs = [
    ['general', page.getByRole('heading', { name: '组织信息' })],
    ['roles', page.getByRole('heading', { name: '组织角色', exact: true })],
    ['governance', page.getByRole('heading', { name: '发起数据导出' })],
  ] as const
  for (const [tab, ready] of tabs) {
    await page.goto(`/settings/organization?tab=${tab}`)
    await expect(ready).toBeVisible()
    expect(await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  }
})

// ---------------------------------------------------------------------------
// Server-shaped job bodies (the raw wire contract, hash and all).
// ---------------------------------------------------------------------------

function exportJobBody(state: 'PENDING' | 'COMPLETED') {
  const done = state === 'COMPLETED'
  return {
    id: 'job-e1',
    organizationId: ids.organization,
    requestedByPrincipalId: ids.principal,
    status: state,
    includeVectors: false,
    manifest: done ? {
      generatedAt: '2026-10-13T10:00:00Z',
      families: [
        { family: 'TASK', itemCount: 1, pinnedRowCount: 12, checksum: 'a1'.repeat(32), skipped: [] },
        { family: 'CREDENTIAL_SECRET', itemCount: 1, pinnedRowCount: 3, checksum: 'b2'.repeat(32), skipped: ['VECTOR_STORE_DISABLED'] },
      ],
      vectors: { encoding: 'float32' },
    } : null,
    version: done ? 2 : 1,
    createdAt: '2026-10-13T08:00:00Z',
    updatedAt: done ? '2026-10-13T10:00:00Z' : '2026-10-13T08:00:00Z',
    items: [
      exportItem('i-1', 'TASK', done, 12),
      exportItem('i-2', 'CREDENTIAL_SECRET', done, 3),
    ],
  }
}

function exportItem(id: string, family: string, done: boolean, rowCount: number) {
  return {
    id, resourceFamily: family, batchSequence: 0, rowCount,
    state: done ? 'DONE' : 'PENDING',
    checksum: done ? 'c3'.repeat(32) : null,
    errorCode: null,
    processedAt: done ? '2026-10-13T09:00:00Z' : null,
    version: 2,
  }
}

function exportArtifactsBody() {
  return {
    manifest: exportJobBody('COMPLETED').manifest,
    artifacts: [{
      id: 'artifact-1', itemId: 'i-1', artifactSequence: 0, byteSize: 2048,
      checksum: 'd4'.repeat(32), rows: [{ id: 1 }, { id: 2 }],
    }],
  }
}

/** DELETE_DERIVED_ROWS pins one item per derived table — 3 + 8 + 2 = 13 coordination rows. */
const DERIVED_ROW_TARGETS = [
  'TEAM_MEMBER_ROLE', 'NOTIFICATION_PREFERENCE', 'INBOX_DISPOSITION', 'CONVERSATION_PARTICIPANT',
  'COMMAND_RESULT', 'TEAM_INVITATION', 'ORGANIZATION_ROLE_GRANT', 'TEAM_MEMBER',
]

function deletionJobBody(status: 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'COMPLETED') {
  const done = status === 'COMPLETED'
  const step = (id: string, stepKey: string, targetRef: string, expectedCount: number) => ({
    id, stepKey, targetRef, expectedCount,
    state: done ? 'DONE' : 'PENDING',
    resultCount: done ? expectedCount : null,
    errorCode: null,
    processedAt: done ? '2026-10-13T09:00:00Z' : null,
    version: 2,
  })
  return {
    id: 'job-d1',
    organizationId: ids.organization,
    accountId: targetAccountId,
    targetPrincipalId: '00000000-4000-8000-0000-000000000910',
    reason: deletionReason,
    confirmationTokenHash: 'sha256:e2e-hash-must-never-render',
    status,
    reservationCheck: '{"inFlightTaskExecutions":0,"activeResponsibilities":0,"openReservations":null,"checkedAt":"2026-10-13T08:00:00Z"}',
    backupRetentionStatement: '备份保留声明：每日快照保留 7 天、每周快照保留 4 周；恢复后请重跑删除对账。',
    version: status === 'PENDING_CONFIRMATION' ? 1 : 2,
    createdAt: '2026-10-13T08:00:00Z',
    updatedAt: '2026-10-13T08:00:00Z',
    items: [
      step('s-1', 'REVOKE_TASK_CREDENTIAL_GRANTS', 'TASK_CREDENTIAL_GRANT', 2),
      step('s-2', 'REVOKE_SUBJECT_CREDENTIALS', 'CREDENTIAL_SECRET', 1),
      step('s-3', 'TOMBSTONE_ACCOUNT_ARTIFACTS', 'ARTIFACT_REFERENCE', 5),
      ...DERIVED_ROW_TARGETS.map((target, index) => step(`s-4-${index}`, 'DELETE_DERIVED_ROWS', target, 3)),
      step('s-5', 'TOMBSTONE_IDENTITY', 'ACCOUNT', 1),
      step('s-6', 'RECORD_BACKUP_RETENTION', 'BACKUP', 1),
    ],
  }
}

function fulfillJson(route: Route, value: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
}

function notFoundJson(route: Route) {
  return route.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-org-e2e', retryable: false, currentVersion: null, details: {} }),
  })
}
