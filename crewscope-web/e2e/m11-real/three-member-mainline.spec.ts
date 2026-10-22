import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, command, currentSession, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-Q02 release-gate mainline (M11 plan §6 L130): three isolated test accounts driven by
 * automated browsers — never recruited humans — collaborate in one Team on the four-service
 * stack over multiple work items wired with real dependency relations. The story walks the
 * contract's five verification faces in order: presence/change signals, the graph query,
 * SSE authoritative recovery after a connection loss, one suspend that cuts every channel at
 * once (the Q01 G1 ruling shape: the same revoke severs WS, SSE and the REST plane together),
 * and finally a responsibility handover off the suspended member that leaves no ownerless
 * chain behind. Every assertion observes the deployed product through real UI anchors or the
 * public REST API — no mocks, no real credentials (accounts come from OPEN registration).
 *
 * Order contract: t4 (graph) must run before t5 — t5 mints a fourth work item for the SSE
 * recovery probe and the graph assertions pin the three-node shape. Do not reorder.
 */
test.describe.configure({ mode: 'serial' })

const password = 'Correct-Horse-Battery-Staple-47'

let aliceContext: BrowserContext
let bobContext: BrowserContext
let carolContext: BrowserContext
let alicePage: Page
let bobPage: Page
let carolPage: Page
let bobTab2: Page
let aliceSession: Session
let bobSession: Session
let carolSession: Session
let teamId: string
let conversationId: string
let projectId: string
let suffix: string
let bobMemberId: string
const itemIds: { head: string, middle: string, tail: string } = { head: '', middle: '', tail: '' }

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  suffix = `q02m-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  aliceContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  alicePage = await aliceContext.newPage()
  await register(alicePage, `alice-${suffix}`.slice(0, 48), `alice-${suffix}@example.test`, 'Alice Q02 Owner', password, false)
  await expect(alicePage).toHaveURL(/\/onboarding$/)
  await alicePage.getByRole('textbox', { name: '团队名称' }).fill(`Q02 三人协作 ${suffix}`.slice(0, 96))
  await alicePage.getByRole('button', { name: '创建团队' }).click()
  await expect(alicePage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  aliceSession = await currentSession(alicePage)
  teamId = onlyTeam(aliceSession).teamId

  // Two invitation links, one per joining member — the only way onto a real stack.
  const bobLink = await createInvitation(`bob-${suffix}@example.test`)
  const carolLink = await createInvitation(`carol-${suffix}@example.test`)

  bobContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  bobPage = await bobContext.newPage()
  await joinByInvitation(bobPage, bobLink, `bob-${suffix}`.slice(0, 48), 'Bob Q02')
  bobSession = await currentSession(bobPage)
  expect(onlyTeam(bobSession).teamId).toBe(teamId)

  carolContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  carolPage = await carolContext.newPage()
  await joinByInvitation(carolPage, carolLink, `carol-${suffix}`.slice(0, 48), 'Carol Q02')
  carolSession = await currentSession(carolPage)
  expect(onlyTeam(carolSession).teamId).toBe(teamId)

  // The shared conversation all three browsers sit in, with both members as participants.
  const conversationsPath = teamPath(aliceSession, teamId, 'conversations')
  await command(alicePage, aliceSession, conversationsPath, { title: `Q02 协作 ${suffix}`.slice(0, 96), visibility: 'TEAM' })
  conversationId = await waitForListItem(alicePage, conversationsPath, suffix)
  const conversationPath = `${conversationsPath}/${conversationId}`
  await command(alicePage, aliceSession, `${conversationPath}/participants`, { userPrincipalId: bobSession.principal!.principalId })
  await command(alicePage, aliceSession, `${conversationPath}/participants`, { userPrincipalId: carolSession.principal!.principalId })

  // One project, three chained items, two BLOCKS edges plus one SPLIT_FROM (the F02 recipe).
  const projectsPath = teamPath(aliceSession, teamId, 'work-projects')
  await command(alicePage, aliceSession, projectsPath, { name: `Q02 依赖计划 ${suffix}`.slice(0, 96) })
  projectId = await waitForListItem(alicePage, projectsPath, suffix)
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  for (const [slot, chineseSlot] of [['head', '链头'], ['middle', '链中'], ['tail', '链尾']] as const) {
    await command(alicePage, aliceSession, workItemsPath, {
      type: 'FEATURE',
      title: `Q02 ${chineseSlot} ${suffix}`,
      description: 'M11-Q02 three-member mainline carrier',
      priority: 'MEDIUM',
      labels: ['q02-mainline'],
      dueAt: null,
    })
    itemIds[slot] = await waitForListItem(alicePage, workItemsPath, `${chineseSlot} ${suffix}`)
  }
  const relationsPath = teamPath(aliceSession, teamId, 'work-item-relations')
  await command(alicePage, aliceSession, relationsPath, { type: 'BLOCKS', fromWorkItemId: itemIds.head, toWorkItemId: itemIds.middle })
  await command(alicePage, aliceSession, relationsPath, { type: 'BLOCKS', fromWorkItemId: itemIds.middle, toWorkItemId: itemIds.tail })
  await command(alicePage, aliceSession, relationsPath, { type: 'SPLIT_FROM', fromWorkItemId: itemIds.tail, toWorkItemId: itemIds.middle })

  // The aggregate is the graph's only feed: wait until both blocked rows are real.
  const blockedPath = teamPath(aliceSession, teamId, 'work-graph/blocked-work-items')
  await expect
    .poll(async () => {
      const envelope = await getJson<{ items: Array<{ workItemId: string }> }>(alicePage, `${blockedPath}?limit=100`)
      return envelope.items.map(row => row.workItemId).sort().join(',')
    }, { timeout: 30_000 })
    .toBe([itemIds.middle, itemIds.tail].sort().join(','))

  // Bob executes all three items — the in-flight responsibility the suspend will orphan and
  // the handover will re-home. Carol is deliberately never assigned (handover target must
  // be clean).
  for (const slot of ['head', 'middle', 'tail'] as const) {
    await command(alicePage, aliceSession, `${workItemsPath}/${itemIds[slot]}/responsibilities/executors`, {
      actorPrincipalId: bobSession.principal!.principalId,
    })
  }

  // The member row the revoke story addresses.
  const members = await getJson<Array<{ id: string, userPrincipalId: string }>>(alicePage, teamPath(aliceSession, teamId, 'members'))
  bobMemberId = members.find(row => row.userPrincipalId === bobSession.principal!.principalId)!.id

  async function createInvitation(email: string): Promise<string> {
    await alicePage.goto(`/team/members?team=${teamId}&tab=invitations`)
    await alicePage.getByRole('button', { name: '创建邀请' }).first().click()
    await alicePage.locator('input[name="invitationEmail"]').fill(email)
    await alicePage.locator('select[name="invitationRole"]').selectOption('MEMBER')
    await alicePage.getByRole('button', { name: '创建邀请链接' }).click()
    const link = await alicePage.getByRole('textbox', { name: '一次性邀请链接' }).inputValue()
    expect(link).toMatch(/\/invite#token=[A-Za-z0-9_-]{43}$/)
    return link
  }

  async function joinByInvitation(page: Page, link: string, username: string, displayName: string): Promise<void> {
    await page.goto(link)
    await page.getByRole('button', { name: '创建账号并加入团队' }).click()
    await expect(page).toHaveURL(/\/register$/)
    // The banner means the invite token is parsed — submitting before it risks a team-less
    // registration.
    await expect(page.getByText('已安全载入团队邀请')).toBeVisible()
    await register(page, username, `${username}@example.test`, displayName, password, true)
    await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${teamId}`))
  }
})

test.afterAll(async () => {
  await aliceContext.close()
  await bobContext.close()
  await carolContext.close()
})

test('three accounts share one screen: presence, pills, and the multitab dedupe', async () => {
  const conversationUrl = `/conversation?team=${teamId}&conversation=${conversationId}`
  await alicePage.goto(conversationUrl)
  await bobPage.goto(conversationUrl)
  await carolPage.goto(conversationUrl)
  for (const memberPage of [alicePage, bobPage, carolPage]) {
    await expect(memberPage.getByLabel('发送消息')).toBeVisible()
    await expect(memberPage.locator('.collab-pill')).toContainText('实时协作在线')
  }

  // Each side's roster names both peers (the snapshot carries the subscriber too, so the
  // assertion matches on the peers, not the full label).
  await expect(alicePage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Bob Q02/)
  await expect(alicePage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Carol Q02/)
  await expect(bobPage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Alice Q02 Owner/)
  await expect(bobPage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Carol Q02/)
  await expect(carolPage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Bob Q02/)

  // S01 multitab handover (F01 left it to Q02): a second tab on Bob's cookie is a second
  // connection of the same principal — presence dedupes by principal and badges the tabs.
  bobTab2 = await bobContext.newPage()
  await bobTab2.goto(conversationUrl)
  await expect(bobTab2.getByLabel('发送消息')).toBeVisible()
  await expect(alicePage.locator('.presence-group [title="Bob Q02（2 个标签页）"]')).toBeVisible({ timeout: 20_000 })
  const label = await alicePage.locator('.collaboration-strip .presence-group').getAttribute('aria-label')
  expect((label?.match(/Bob Q02/g) ?? []).length, `presence must dedupe by principal, label was: ${label}`).toBe(1)
})

test('typing shows both one-person and two-person shapes, then self-clears', async () => {
  const indicator = alicePage.locator('.typing-indicator')
  await expect(bobPage.getByLabel('消息内容')).toBeEnabled()
  await bobPage.getByLabel('消息内容').fill('主线输入信号的第一条')
  await expect(indicator).toContainText('Bob Q02 正在输入…')

  // Carol types within Bob's anchor window: the label switches to the two-person shape.
  await carolPage.getByLabel('消息内容').fill('主线输入信号的第二条')
  await expect(indicator).toContainText('Bob Q02 和 Carol Q02 正在输入…')

  // typingWindowSeconds is 5s on this stack and the client owns the self-clear — no more
  // keystrokes and the row falls silent on its own. The ≥3 shape needs a fourth principal
  // and is out of the three-account contract shape (written ruling in the gate document).
  await expect(indicator).toHaveCount(0, { timeout: 15_000 })
})

test('a remote change banners the dirty drawer and never overwrites it', async () => {
  await alicePage.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.middle}`)
  const drawer = alicePage.getByRole('dialog').filter({ hasText: `Q02 链中 ${suffix}` }).first()
  await expect(drawer).toBeVisible()
  await expect(alicePage.locator('.collab-pill')).toContainText('实时协作在线')

  await drawer.getByRole('button', { name: '编辑内容' }).click()
  const title = drawer.getByLabel('标题', { exact: true })
  const localTitle = `本地未保存标题 ${suffix}`
  await title.fill(localTitle)

  // Carol rewrites the description through the REST plane; the signal channel carries the
  // version bump to Alice's open, dirty drawer (ADR-032's interruption red line).
  const workItemsPath = `${teamPath(aliceSession, teamId, 'work-projects')}/${projectId}/work-items`
  const before = await getJson<{ workItem: { version: number } }>(carolPage, `${workItemsPath}/${itemIds.middle}`)
  const response = await carolPage.request.patch(`${workItemsPath}/${itemIds.middle}`, {
    data: { description: `远端修改 ${suffix}` },
    headers: {
      [carolSession.csrf.headerName]: carolSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${before.workItem.version}"`,
    },
  })
  expect(response.status()).toBe(202)

  await expect(drawer.locator('.change-banner')).toContainText('该工作项已被更新')
  await expect(title).toHaveValue(localTitle)

  await drawer.locator('.change-banner').getByRole('button', { name: '点击刷新' }).click()
  await expect(drawer.locator('.change-banner')).toHaveCount(0)
  await expect(drawer.locator('.detail-hero__status .mono')).toHaveText(`v${before.workItem.version + 1}`)
})

test('the graph view renders the real blocking DAG with its non-graph equivalent', async () => {
  // Runs before the SSE-recovery test on purpose: that test mints a fourth work item and
  // these assertions pin the three-node, two-edge shape.
  await alicePage.goto(`/work?team=${teamId}&project=${projectId}&view=graph`)
  await expect(alicePage.getByRole('group', { name: '阻塞依赖图,共 3 个工作项、2 条阻塞关系' })).toBeVisible()

  const list = alicePage.getByLabel('依赖图节点清单')
  await expect(list).toContainText(`Q02 链头 ${suffix}`)
  await expect(list).toContainText(`Q02 链中 ${suffix}`)
  await expect(list).toContainText(`Q02 链尾 ${suffix}`)
  await expect(list).toContainText('被 1 项阻塞')

  // The tail's relations panel states both of its real edges as sentences.
  await alicePage.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.tail}`)
  const drawer = alicePage.getByRole('dialog').filter({ hasText: `Q02 链尾 ${suffix}` }).first()
  await expect(drawer).toBeVisible()
  const relations = drawer.locator('.relations-list')
  await expect(relations).toContainText(`「Q02 链中 ${suffix}」阻塞本项`)
  await expect(relations).toContainText(`本项拆分自「Q02 链中 ${suffix}」`)
})

test('a lost connection recovers the authoritative state through SSE, not the socket', async () => {
  await alicePage.goto(`/activity?team=${teamId}`)
  const badge = alicePage.locator('.summary-strip .status-badge')
  const factsHeading = alicePage.locator('.summary-strip h2')
  await expect(badge).toContainText('实时')
  const factsBefore = await factsCount()

  // Only Alice's browser goes offline — the stack and both peers stay untouched, which is
  // what makes the recovery anchor (snapshot + durable cursor) provably WS-independent.
  await aliceContext.setOffline(true)
  await expect(badge).toContainText('离线')

  // While Alice is cut off, Carol commits a fact through the REST plane.
  const workItemsPath = `${teamPath(aliceSession, teamId, 'work-projects')}/${projectId}/work-items`
  await command(carolPage, carolSession, workItemsPath, {
    type: 'FEATURE',
    title: `Q02 恢复探针 ${suffix}`.slice(0, 96),
    description: 'M11-Q02 SSE recovery probe',
    priority: 'MEDIUM',
    labels: ['q02-mainline'],
    dueAt: null,
  })

  await aliceContext.setOffline(false)
  await expect(badge).toContainText('实时', { timeout: 30_000 })

  // The reconnected stream replays from the durable cursor: the missed fact lands, and the
  // authoritative REST read agrees — recovery came from the snapshot + cursor, never WS.
  await expect
    .poll(async () => await factsCount(), { timeout: 30_000 })
    .toBeGreaterThan(factsBefore)
  const items = await getJson<{ items: Array<{ id: string, title?: string }> }>(alicePage, `${workItemsPath}?limit=50`)
  expect(items.items.some(item => (item.title ?? '').includes(`恢复探针 ${suffix}`))).toBe(true)

  // The untouched peer rode through the same window still live.
  await expect(carolPage.locator('.collab-pill')).toContainText('实时协作在线')

  async function factsCount(): Promise<number> {
    return Number.parseInt((await factsHeading.textContent()) ?? '0', 10)
  }
})

test('one suspend cuts every channel at once — and no member can fire it but the owner', async () => {
  const conversationUrl = `/conversation?team=${teamId}&conversation=${conversationId}`
  await alicePage.goto(conversationUrl)
  await bobPage.goto(conversationUrl)
  await carolPage.goto(conversationUrl)
  await expect(bobPage.locator('.collab-pill')).toContainText('实时协作在线')
  await expect(alicePage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Bob Q02/)

  // Bob's second tab now watches the Team Activity stream instead — his SSE channel for
  // this drill. Each tab holds its own transport, and each is revalidated independently.
  await bobTab2.goto(`/activity?team=${teamId}`)
  const bobBadge = bobTab2.locator('.summary-strip .status-badge')
  await expect(bobBadge).toContainText('实时')

  // Negative face first: a plain MEMBER cannot suspend anyone (policy wall, §8.11's
  // "no privilege escalation" clause — the owner is the last-owner protection's peer).
  const membersPath = teamPath(aliceSession, teamId, 'members')
  const members = await getJson<Array<{ id: string, userPrincipalId: string, status: string, version: number }>>(alicePage, membersPath)
  const bobRow = members.find(row => row.userPrincipalId === bobSession.principal!.principalId)
  expect(bobRow!.status).toBe('ACTIVE')
  const suspendPath = `/api/v1/organizations/${aliceSession.principal!.organizationId}/teams/${teamId}/members/${bobRow!.id}/suspend`
  const forbidden = await carolPage.request.post(suspendPath, {
    headers: {
      [carolSession.csrf.headerName]: carolSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${bobRow!.version}"`,
    },
  })
  expect(forbidden.status()).toBe(403)
  expect(await forbidden.text()).toContain('policy_denied')
  const afterAttempt = await getJson<Array<{ id: string, status: string }>>(alicePage, membersPath)
  expect(afterAttempt.find(row => row.id === bobRow!.id)!.status).toBe('ACTIVE')

  // The real command from the owner. One suspend — every channel answers.
  const suspend = await alicePage.request.post(suspendPath, {
    headers: {
      [aliceSession.csrf.headerName]: aliceSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${bobRow!.version}"`,
    },
  })
  expect(suspend.status()).toBe(202)

  // Channel REST: the authoritative row flips.
  await expect
    .poll(
      async () => (await getJson<Array<{ id: string, status: string }>>(alicePage, membersPath))
        .find(row => row.id === bobRow!.id)?.status,
      { timeout: 20_000 },
    )
    .toBe('SUSPENDED')

  // Channel WS: the swept socket drops Bob's live state on his main tab.
  await expect(bobPage.locator('.collab-pill')).not.toContainText('实时协作在线', { timeout: 20_000 })

  // Channel presence: both survivors' rosters let Bob go.
  await expect(alicePage.locator('.collaboration-strip .presence-group'))
    .not.toHaveAttribute('aria-label', /Bob Q02/, { timeout: 20_000 })
  await expect(carolPage.locator('.collaboration-strip .presence-group'))
    .not.toHaveAttribute('aria-label', /Bob Q02/, { timeout: 20_000 })

  // Channel SSE (the Q02-only browser-side evidence; the server contract lives in
  // TeamActivityStreamRevocationTest): the idle-probe recheck closes the stream, the
  // client's backoff reconnect hits the door's 403, and the store settles on forbidden —
  // and stays there instead of flapping back to live.
  await expect(bobBadge).toContainText('无权限', { timeout: 45_000 })
  await bobTab2.waitForTimeout(10_000)
  await expect(bobBadge).toContainText('无权限')

  // Residue: the cut member cannot re-enter on a fresh socket with the same session.
  const outcome = await bobPage.evaluate(
    ({ organizationId: organization, team }: { organizationId: string, team: string }) => {
      const url = `${location.origin.replace(/^http/, 'ws')}/api/v1/collaboration/ws`
      return new Promise<{ denied: boolean, closeCode: number | null }>(resolve => {
        const socket = new WebSocket(url)
        const timeout = window.setTimeout(() => {
          try { socket.close() } catch { /* never opened */ }
          resolve({ denied: false, closeCode: null })
        }, 15_000)
        socket.onmessage = message => {
          const frame = JSON.parse(String(message.data)) as Record<string, unknown>
          if (frame.type === 'error' && frame.code === 'forbidden_scope') {
            window.clearTimeout(timeout)
            resolve({ denied: true, closeCode: null })
            socket.close(1000, 'spec complete')
          }
        }
        socket.onclose = event => {
          window.clearTimeout(timeout)
          resolve({ denied: false, closeCode: event.code })
        }
        socket.onopen = () => {
          socket.send(JSON.stringify({ type: 'subscribe', scope: { organization, team } }))
        }
        socket.onerror = () => { /* onclose carries the verdict */ }
      })
    },
    { organizationId: bobSession.principal!.organizationId, team: teamId },
  )
  expect(outcome.denied).toBe(true)

  // The REST plane denies the suspended member on this Team while the account itself stays
  // signed in — a Team revoke is not an account logout (§8.11's account-level distinction).
  const teamRead = await bobPage.request.get(membersPath)
  expect(teamRead.status()).toBe(403)
  const session = await getJson<{ authenticated: boolean }>(bobPage, '/api/v1/auth/session')
  expect(session.authenticated).toBe(true)
})

test('the handover off the suspended member leaves no ownerless responsibility chain', async () => {
  // Preview first: the suspended member's three EXECUTOR assignments are all in flight.
  const previewPath = teamPath(aliceSession, teamId, `members/${bobMemberId}/responsibilities?role=EXECUTOR`)
  const preview = await getJson<Array<{ assignmentId: string, workItemId: string }>>(alicePage, previewPath)
  expect(preview.map(row => row.workItemId).sort()).toEqual([itemIds.head, itemIds.middle, itemIds.tail].sort())

  // Create + process the handover onto Carol (the source stays suspended the whole time —
  // a suspended member's responsibilities can still be re-homed).
  const handoversPath = teamPath(aliceSession, teamId, 'responsibility-handovers')
  const create = await alicePage.request.post(handoversPath, {
    data: {
      sourceMemberId: bobMemberId,
      targetPrincipalId: carolSession.principal!.principalId,
      role: 'EXECUTOR',
    },
    headers: {
      [aliceSession.csrf.headerName]: aliceSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  // Read the body once (a response body cannot be consumed twice) before asserting the code.
  const created = await create.json() as { job: { jobId: string } }
  expect(create.status()).toBe(202)
  const jobPath = `${handoversPath}/${created.job.jobId}`

  // Process is a synchronous command over the durable job (controller okView — only the
  // create acknowledgement is 202), so the contract answer here is 200.
  const process = await alicePage.request.post(`${jobPath}/process`, {
    headers: { [aliceSession.csrf.headerName]: aliceSession.csrf.token },
  })
  expect(process.status()).toBe(200)

  await expect
    .poll(async () => {
      const job = await getJson<{ status: string, items: Array<{ state: string }> }>(alicePage, jobPath)
      return job.status === 'COMPLETED' && job.items.length === 3 && job.items.every(item => item.state === 'DONE')
        ? 'done'
        : `${job.status}/${job.items.map(item => item.state).join(',')}`
    }, { timeout: 30_000 })
    .toBe('done')

  // The no-ownerless-chain invariant, item by item: exactly one ACTIVE EXECUTOR remains and
  // it is Carol's principal (release + assignExecutor replay semantics).
  const workItemsPath = `${teamPath(aliceSession, teamId, 'work-projects')}/${projectId}/work-items`
  for (const slot of ['head', 'middle', 'tail'] as const) {
    const responsibilities = await getJson<Array<{ role: string, actorPrincipalId: string }>>(
      alicePage,
      `${workItemsPath}/${itemIds[slot]}/responsibilities`,
    )
    const executors = responsibilities.filter(row => row.role === 'EXECUTOR')
    expect(executors, `${slot} must keep exactly one active executor`).toHaveLength(1)
    expect(executors[0]!.actorPrincipalId).toBe(carolSession.principal!.principalId)
  }

  // The product says so too: the middle item's responsibility panel names Carol, not Bob.
  await alicePage.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.middle}`)
  const drawer = alicePage.getByRole('dialog').filter({ hasText: `Q02 链中 ${suffix}` }).first()
  await expect(drawer).toBeVisible()
  await expect(drawer).toContainText('Carol Q02')
  await expect(drawer).not.toContainText('Bob Q02')
})

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
