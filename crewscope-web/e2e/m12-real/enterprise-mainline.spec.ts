import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, command, getJson, login, register, teamPath, type Session } from '../real-backend'

/**
 * M12-Q02 release-gate mainline (M12 plan §6 Q02 row / §8): the enterprise planes walked
 * end to end on one four-service stack brought up through the plain quickstart defaults —
 * no OIDC, quota or realtime env keys (that default path is itself the §8-2 acceptance
 * face). The fresh stack's only ORG_OWNER is the bootstrap operator (ensureInitialOwner at
 * startup); the spec logs in with the stack-local generated bootstrap password (passed by
 * the gate through CREWSCOPE_M12Q02_OPERATOR_PASSWORD, never echoed, destroyed with the
 * stack) and grants ORG_ADMIN / ORG_AUDITOR to two OPEN-registered accounts. Every other
 * account comes from public OPEN registration — no real credentials, no model key.
 *
 * Order contract: the t0 data seeding runs BEFORE any quota configuration exists, which is
 * the structural proof for §8-4 "unconfigured quota changes nothing on the default path";
 * t5 exports the seeded rows (WORK_ITEM_RELATION is a live relation, §8-5); t6 deletes the
 * C account whose context stays open across the whole chain (§8-5/§8-7). Do not reorder.
 */
test.describe.configure({ mode: 'serial' })

const password = 'Correct-Horse-Battery-Staple-47'
const deletionReason
  = 'Q02 发布门禁删除演练靶账号：无在途任务与未转移责任，现按两步确认流程删除并按声明保留备份，用于验证账号级会话撤销。'

/** The session contract as F01 consumes it: the M12 organization projection + org:* grants. */
type OrganizationSession = Session & {
  organization: { organizationId: string, name: string, role: 'ORG_OWNER' | 'ORG_ADMIN' | 'ORG_AUDITOR' } | null
  permissions: string[]
}

type RoleGrant = {
  id: string
  principalId: string
  role: string
  status: string
  version: number
}

type ExportJob = {
  id: string
  status: string
  manifest: {
    families: Array<{ family: string, itemCount: number, pinnedRowCount: number }>
  } | null
  items: Array<{ resourceFamily: string, state: string }>
}

type DeletionJob = {
  id: string
  status: string
  backupRetentionStatement: string
  version: number
  items: Array<{ stepKey: string, state: string }>
}

const operatorPassword = process.env.CREWSCOPE_M12Q02_OPERATOR_PASSWORD

let operatorContext: BrowserContext
let aContext: BrowserContext
let bContext: BrowserContext
let cContext: BrowserContext
let operatorPage: Page
let aPage: Page
let bPage: Page
let cPage: Page
let operatorSession: OrganizationSession
let aSession: OrganizationSession
let bSession: OrganizationSession
let cSession: OrganizationSession
let organizationId: string
let teamId: string
let suffix: string
let aUsername: string
let cUsername: string
const itemIds: { head: string, middle: string } = { head: '', middle: '' }

const orgPath = (leaf: string) => `/api/v1/organizations/${organizationId}/${leaf}`

const orgSession = (page: Page) => getJson<OrganizationSession>(page, '/api/v1/auth/session')

async function putCommand(
  page: Page,
  session: OrganizationSession,
  path: string,
  body: Record<string, unknown>,
  ifMatch: string,
): Promise<void> {
  const response = await page.request.put(path, {
    data: body,
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': ifMatch,
    },
  })
  expect(response.status(), `${path} PUT failed: ${await response.text()}`).toBe(202)
}

async function postAction(
  page: Page,
  session: OrganizationSession,
  path: string,
  body: Record<string, unknown>,
  ifMatch?: string,
): Promise<Record<string, unknown>> {
  const headers: Record<string, string> = { [session.csrf.headerName]: session.csrf.token }
  if (ifMatch !== undefined) headers['If-Match'] = ifMatch
  const response = await page.request.post(path, { data: body, headers })
  expect(response.status(), `${path} action failed: ${await response.text()}`).toBe(200)
  return response.json() as Promise<Record<string, unknown>>
}

async function waitForListItem(page: Page, listPath: string, marker: string): Promise<string> {
  const deadline = Date.now() + 30_000
  for (;;) {
    const list = await getJson<{ items: Array<{ id: string, name?: string, title?: string }> }>(page, `${listPath}?limit=50`)
    const found = (list.items ?? []).find(item => (item.name ?? item.title ?? '').includes(marker))?.id
    if (found) return found
    if (Date.now() > deadline) throw new Error(`item with marker ${marker} never appeared`)
    await page.waitForTimeout(1000)
  }
}

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  if (!operatorPassword) {
    throw new Error('CREWSCOPE_M12Q02_OPERATOR_PASSWORD is required — run this spec through scripts/m12-q02-release-gate.sh')
  }
  suffix = `q02e-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  aUsername = `q02a-${suffix}`.slice(0, 48)
  cUsername = `q02c-${suffix}`.slice(0, 48)
  const newContext = () => browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })

  // The bootstrap operator is the fresh stack's only ORG_OWNER (§8-3 trusted binding).
  operatorContext = await newContext()
  operatorPage = await operatorContext.newPage()
  // login() expects the page to already sit on the login form (the m11 spec never logs in
  // through it from a blank page), so navigate there first.
  await operatorPage.goto('/login')
  await login(operatorPage, 'crewscope-monitor', operatorPassword)
  operatorSession = await orgSession(operatorPage)
  organizationId = operatorSession.principal!.organizationId
  expect(operatorSession.organization?.role).toBe('ORG_OWNER')

  // A (admin candidate) goes through UI registration and team onboarding.
  aContext = await newContext()
  aPage = await aContext.newPage()
  await register(aPage, aUsername, `${aUsername}@example.test`, 'Q02 Admin Candidate', password, false)
  await expect(aPage).toHaveURL(/\/onboarding$/)
  await aPage.getByRole('textbox', { name: '团队名称' }).fill(`Q02 企业主线 ${suffix}`.slice(0, 96))
  await aPage.getByRole('button', { name: '创建团队' }).click()
  await expect(aPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  aSession = await orgSession(aPage)
  expect(aSession.teams).toHaveLength(1)
  teamId = aSession.teams[0]!.teamId

  // B (auditor candidate) registers and founds its own team: the organization-scoped
  // principal row only exists once an account carries a team membership (or an org role),
  // and t1 must grant ORG_AUDITOR to a real principal of this organization.
  bContext = await newContext()
  bPage = await bContext.newPage()
  const bUsername = `q02b-${suffix}`.slice(0, 48)
  await register(bPage, bUsername, `${bUsername}@example.test`, 'Q02 Auditor Candidate', password, false)
  await expect(bPage).toHaveURL(/\/onboarding$/)
  await bPage.getByRole('textbox', { name: '团队名称' }).fill(`Q02 审计候选 ${suffix}`.slice(0, 96))
  await bPage.getByRole('button', { name: '创建团队' }).click()
  await expect(bPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  bSession = await orgSession(bPage)
  expect(bSession.organization).toBeNull()
  expect(bSession.principal?.principalId).toBeTruthy()

  // C (deletion target) also founds a team: an org principal must exist for the session to
  // project at all — resolveSession returns empty without one, so a team-less C would sit on
  // an ANONYMOUS session and t6's post-deletion revocation assertion would pass vacuously.
  // The founded team also feeds real TEAM_MEMBER rows into t6's DELETE_DERIVED_ROWS steps.
  cContext = await newContext()
  cPage = await cContext.newPage()
  await register(cPage, cUsername, `${cUsername}@example.test`, 'Q02 Deletion Target', password, false)
  await expect(cPage).toHaveURL(/\/onboarding$/)
  await cPage.getByRole('textbox', { name: '团队名称' }).fill(`Q02 删除靶 ${suffix}`.slice(0, 96))
  await cPage.getByRole('button', { name: '创建团队' }).click()
  await expect(cPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  cSession = await orgSession(cPage)
  expect(cSession.authenticated).toBe(true)
  expect(cSession.account?.accountId).toBeTruthy()
  expect(cSession.principal?.principalId).toBeTruthy()
  expect(cSession.organization).toBeNull()

  // Governance carriers, created while NO quota is configured (§8-4 structural evidence):
  // one project, two work items, one live BLOCKS edge the export must pin (§8-5).
  const projectsPath = teamPath(aSession, teamId, 'work-projects')
  await command(aPage, aSession, projectsPath, { name: `Q02 治理计划 ${suffix}`.slice(0, 96) })
  const projectId = await waitForListItem(aPage, projectsPath, suffix)
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  for (const [slot, chineseSlot] of [['head', '链头'], ['middle', '链中']] as const) {
    await command(aPage, aSession, workItemsPath, {
      type: 'FEATURE',
      title: `Q02 ${chineseSlot} ${suffix}`,
      description: 'M12-Q02 enterprise mainline governance carrier',
      priority: 'MEDIUM',
      labels: ['q02-mainline'],
      dueAt: null,
    })
    itemIds[slot] = await waitForListItem(aPage, workItemsPath, `${chineseSlot} ${suffix}`)
  }
  const relationsPath = teamPath(aSession, teamId, 'work-item-relations')
  await command(aPage, aSession, relationsPath, { type: 'BLOCKS', fromWorkItemId: itemIds.head, toWorkItemId: itemIds.middle })
  // The blocked aggregate is the relation's only feed: wait until the edge is really there.
  const blockedPath = teamPath(aSession, teamId, 'work-graph/blocked-work-items')
  await expect
    .poll(async () => {
      const envelope = await getJson<{ items: Array<{ workItemId: string }> }>(aPage, `${blockedPath}?limit=100`)
      return envelope.items.map(row => row.workItemId).join(',')
    }, { timeout: 30_000 })
    .toBe(itemIds.middle)
})

test('t1: bootstrap owner grants converge onto the live session projection', async () => {
  const grants = await getJson<RoleGrant[]>(operatorPage, orgPath('role-grants'))
  expect(grants.some(g => g.role === 'ORG_OWNER' && g.status === 'ACTIVE' && g.principalId === operatorSession.principal!.principalId)).toBe(true)

  await command(operatorPage, operatorSession, orgPath('role-grants'), {
    principalId: aSession.principal!.principalId,
    role: 'ORG_ADMIN',
    reason: 'M12-Q02 release gate: ORG_ADMIN for the settings/quota faces',
  })
  await command(operatorPage, operatorSession, orgPath('role-grants'), {
    principalId: bSession.principal!.principalId,
    role: 'ORG_AUDITOR',
    reason: 'M12-Q02 release gate: ORG_AUDITOR for the SLI/audit faces',
  })

  // The session projection resolves the role per request, so the open sessions see the
  // grants immediately. Orthogonality (§8-7): the org:* vocabulary joins the top-level
  // permissions while the team grant set keeps living untouched in teams[].permissions.
  const aRefreshed = await orgSession(aPage)
  expect(aRefreshed.organization?.role).toBe('ORG_ADMIN')
  expect(aRefreshed.permissions).toContain('org:manage-settings')
  expect(aRefreshed.teams[0]!.permissions.length).toBeGreaterThan(0)
  expect(aRefreshed.teams[0]!.permissions.every(p => !p.startsWith('org:'))).toBe(true)

  const bRefreshed = await orgSession(bPage)
  expect(bRefreshed.organization?.role).toBe('ORG_AUDITOR')
  expect(bRefreshed.permissions).toContain('org:audit-read')
  expect(bRefreshed.permissions).not.toContain('org:manage-settings')

  // The auditor cannot mint grants (ROLE_ASSIGN is admin/owner-only) — target A, a real
  // principal of this organization, so the 403 is purely the capability denial.
  const denied = await bPage.request.post(orgPath('role-grants'), {
    data: { principalId: aSession.principal!.principalId, role: 'ORG_ADMIN', reason: 'must be rejected' },
    headers: {
      [bRefreshed.csrf.headerName]: bRefreshed.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(denied.status()).toBe(403)
})

test('t2: organization settings lifecycle and the F01 page anchor', async () => {
  const fresh = await getJson<{ auditEventRetentionDays: number | null, version: number }>(operatorPage, orgPath('settings'))
  expect(fresh.auditEventRetentionDays).toBeNull()
  expect(fresh.version).toBe(0)

  // First write materializes the row with version staying 0 (the V70 quota-row semantics);
  // the second write is the first versioned change and lifts it to 1.
  await putCommand(operatorPage, operatorSession, orgPath('settings'), { auditEventRetentionDays: 90 }, '"0"')
  const afterOperator = await getJson<{ auditEventRetentionDays: number, version: number }>(operatorPage, orgPath('settings'))
  expect(afterOperator).toMatchObject({ auditEventRetentionDays: 90, version: 0 })

  const aRefreshed = await orgSession(aPage)
  await putCommand(aPage, aRefreshed, orgPath('settings'), { auditEventRetentionDays: 120 }, '"0"')
  const afterAdmin = await getJson<{ auditEventRetentionDays: number, version: number }>(operatorPage, orgPath('settings'))
  expect(afterAdmin).toMatchObject({ auditEventRetentionDays: 120, version: 1 })

  // The auditor holds AUDIT_READ_ORG alone: settings stay admin+ on BOTH the read and the
  // write side (the Q01 family-matrix ruling; the A01 contract table's "AUDITOR+" tag on
  // this row is a mislabel — the vocabulary set never carried SETTINGS_READ to auditors).
  const bRefreshed = await orgSession(bPage)
  expect((await bPage.request.get(orgPath('settings'))).status()).toBe(403)
  const bPut = await bPage.request.put(orgPath('settings'), {
    data: { auditEventRetentionDays: 30 },
    headers: {
      [bRefreshed.csrf.headerName]: bRefreshed.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': '"1"',
    },
  })
  expect(bPut.status()).toBe(403)

  await aPage.goto('/settings/organization')
  await expect(aPage.getByRole('heading', { name: '审计保留策略' })).toBeVisible()
  await expect(aPage.getByText('当前策略：保留 120 天')).toBeVisible()
})

test('t3: quota configuration, usage projection and the auditor boundary', async () => {
  const aRefreshed = await orgSession(aPage)
  const unconfigured = await getJson<{ monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null, version: number }>(aPage, orgPath('quota'))
  expect(unconfigured).toMatchObject({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })
  const emptyUsage = await getJson<{ materialized: boolean }>(aPage, `${orgPath('quota')}/usage`)
  expect(emptyUsage.materialized).toBe(false)

  // The quota row follows the same first-write semantics: the materializing write keeps
  // version 0 and only the next change lifts it.
  await putCommand(aPage, aRefreshed, orgPath('quota'), { monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 2000000 }, '"0"')
  const configured = await getJson<{ monthlyTaskIntakeLimit: number, monthlyTokenBudget: number, version: number }>(aPage, orgPath('quota'))
  expect(configured).toMatchObject({ monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 2000000, version: 0 })

  const usage = await getJson<{ materialized: boolean, monthlyTaskIntake: { limit: number, count: number } }>(aPage, `${orgPath('quota')}/usage`)
  expect(usage.materialized).toBe(true)
  expect(usage.monthlyTaskIntake).toMatchObject({ limit: 5, count: 0 })

  // The auditor never touches the configuration plane (SETTINGS_READ is admin+).
  const bRefreshed = await orgSession(bPage)
  expect((await bPage.request.get(orgPath('quota'))).status()).toBe(403)

  await aPage.goto('/settings/quota')
  await expect(aPage.getByRole('heading', { name: '配额配置', exact: true })).toBeVisible()
  await expect(aPage.getByText('每月任务受理上限 5；每月 Token 预算 2,000,000')).toBeVisible()
})

test('t4: the SLI read plane serves all six indicators on the default four services', async () => {
  const bRefreshed = await orgSession(bPage)
  const sli = await getJson<{
    reportingZone: string,
    metrics: Array<{ id: string, windows: unknown[] }>,
  }>(bPage, orgPath('sli'))
  expect(sli.reportingZone).toBe('Asia/Shanghai')
  expect(sli.metrics.map(metric => metric.id)).toEqual([
    'api.latency.p95',
    'agent.first.token',
    'queueing.latency',
    'execution.success.rate',
    'notification.delivery.rate',
    'knowledge.retrieval.latency.p95',
  ])
  for (const metric of sli.metrics) {
    expect(metric.windows.length).toBeGreaterThan(0)
  }

  // The owner reads the same plane (OWNER ⊃ AUDITOR) — no external monitoring stack took part.
  expect((await operatorPage.request.get(orgPath('sli'))).status()).toBe(200)
})

test('t5: the governance export pins the mainline rows including the live WorkGraph edge', async () => {
  const key = crypto.randomUUID()
  const createHeaders = {
    [operatorSession.csrf.headerName]: operatorSession.csrf.token,
    'Idempotency-Key': key,
  }
  const first = await operatorPage.request.post(orgPath('data-exports'), { data: { includeVectors: false }, headers: createHeaders })
  expect(first.status(), await first.text()).toBe(202)
  const replay = await operatorPage.request.post(orgPath('data-exports'), { data: { includeVectors: false }, headers: createHeaders })
  expect(replay.status()).toBe(202)
  expect(replay.headers()['idempotency-replayed']).toBe('true')

  const job = await first.json() as ExportJob
  expect(job.status).toBe('PENDING')
  expect(job.items).toHaveLength(22)

  await postAction(operatorPage, operatorSession, `${orgPath('data-exports')}/${job.id}/process`, {})
  await expect
    .poll(async () => {
      const polled = await getJson<ExportJob>(operatorPage, `${orgPath('data-exports')}/${job.id}`)
      return polled.status
    }, { timeout: 60_000 })
    .toBe('COMPLETED')

  // pinnedRowCount is the row evidence (itemCount counts batches, not rows — two seeded
  // work items land in one batch).
  const finished = await getJson<ExportJob>(operatorPage, `${orgPath('data-exports')}/${job.id}`)
  const families = new Map(finished.manifest!.families.map(family => [family.family, family.pinnedRowCount]))
  expect(families.get('WORK_ITEM')!).toBeGreaterThanOrEqual(2)
  expect(families.get('WORK_ITEM_RELATION')!).toBeGreaterThanOrEqual(1)
  expect(families.get('TEAM')!).toBeGreaterThanOrEqual(1)
  expect(families.get('USER_ACCOUNT')!).toBeGreaterThanOrEqual(4)

  const artifacts = await operatorPage.request.get(`${orgPath('data-exports')}/${job.id}/artifacts`)
  expect(artifacts.status()).toBe(200)
  expect(artifacts.headers()['content-disposition']).toContain(`export-${job.id}.json`)
})

test('t6: the two-step account deletion chain revokes the open session via the version bump', async () => {
  const cAccountId = cSession.account!.accountId
  const precheck = await getJson<{ inFlightTaskExecutions: number, activeResponsibilities: number }>(
    operatorPage,
    `${orgPath('deletion-precheck')}?accountId=${cAccountId}`,
  )
  expect(precheck.inFlightTaskExecutions).toBe(0)
  expect(precheck.activeResponsibilities).toBe(0)

  const create = await operatorPage.request.post(orgPath('account-deletions'), {
    data: { accountId: cAccountId, reason: deletionReason },
    headers: {
      [operatorSession.csrf.headerName]: operatorSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(create.status(), await create.text()).toBe(202)
  const job = await create.json() as DeletionJob
  expect(job.status).toBe('PENDING_CONFIRMATION')
  expect(job.items).toHaveLength(13)

  // The unconfirmed process is rejected by the state machine.
  const premature = await operatorPage.request.post(`${orgPath('account-deletions')}/${job.id}/process`, {
    data: {},
    headers: { [operatorSession.csrf.headerName]: operatorSession.csrf.token },
  })
  expect(premature.status()).toBe(422)

  await postAction(
    operatorPage,
    operatorSession,
    `${orgPath('account-deletions')}/${job.id}/confirm`,
    { confirmationToken: cUsername },
    `"${job.version}"`,
  )
  const processed = await postAction(operatorPage, operatorSession, `${orgPath('account-deletions')}/${job.id}/process`, {}) as unknown as DeletionJob
  expect(processed.status).toBe('COMPLETED')
  expect(processed.items.every(step => step.state === 'DONE')).toBe(true)
  expect(processed.backupRetentionStatement).toContain('retain-backups.sh keeps daily snapshots for 7 days')

  // The deletion disabled the identity side and bumped the security version: C's still-open
  // browser session is anonymous on its next request (ADR-038 §3.1 chain, §8-7).
  const openResponse = await cPage.request.get('/api/v1/auth/session')
  const openBody = await openResponse.json() as OrganizationSession
  expect(openBody.authenticated).toBe(false)

  // Re-login falls into the constant public failure contract (401).
  const retry = await cPage.request.post('/api/v1/auth/login', {
    data: { identifier: cUsername, password },
    headers: { [openBody.csrf.headerName]: openBody.csrf.token },
  })
  expect(retry.status()).toBe(401)
})

test('t7: the session list and single-session revocation (F03 anchor)', async ({ browser }: { browser: Browser }) => {
  const aContext2 = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const aPage2 = await aContext2.newPage()
  await aPage2.goto('/login')
  await login(aPage2, aUsername, password)

  const aRefreshed = await orgSession(aPage)
  const listing = await getJson<{ sessions: Array<{ sessionId: string, lastAccessedAt: string, current: boolean }> }>(aPage, '/api/v1/auth/sessions')
  expect(listing.sessions).toHaveLength(2)
  expect(listing.sessions.filter(session => session.current)).toHaveLength(1)
  const stamps = listing.sessions.map(session => session.lastAccessedAt)
  expect([...stamps].sort().reverse()).toEqual(stamps)

  const other = listing.sessions.find(session => !session.current)!
  const revoke = await aPage.request.delete(`/api/v1/auth/sessions/${other.sessionId}`, {
    headers: { [aRefreshed.csrf.headerName]: aRefreshed.csrf.token },
  })
  expect(revoke.status()).toBe(204)
  const revokedSide = await (await aPage2.request.get('/api/v1/auth/session')).json() as OrganizationSession
  expect(revokedSide.authenticated).toBe(false)
  const survivingSide = await (await aPage.request.get('/api/v1/auth/session')).json() as OrganizationSession
  expect(survivingSide.authenticated).toBe(true)

  // A repeated delete answers 404 session_not_found — existence is never confirmed.
  const repeat = await aPage.request.delete(`/api/v1/auth/sessions/${other.sessionId}`, {
    headers: { [aRefreshed.csrf.headerName]: aRefreshed.csrf.token },
  })
  expect(repeat.status()).toBe(404)
  expect(((await repeat.json()) as { code: string }).code).toBe('session_not_found')
  await aContext2.close()
})
