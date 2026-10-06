import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, register, teamPath, type Session } from '../real-backend'

/**
 * M10-Q01 real-stack cross-team isolation probe: one account onboards its team, publishes
 * a knowledge entry and (on a stack with the skill write gate open) a skill, then creates
 * a real neighbour team of the same Organization — the closest possible stranger — which
 * publishes near-identical content under the same entry/skill key, one team-identifying
 * word apart. A member whose only membership is the neighbour team probes the attacking
 * team's real resource ids and meets the shared 404 envelope everywhere (the team-level
 * same-shape duty of S01 §4, here at the HTTP tier with ids that really exist in the
 * store); the neighbour's same-key publication never moves the attacking team's head; and
 * a third account of a different Organization meets the outer 403 policy_denied wall.
 *
 * The vector-SQL and sealed-manifest tier of the same duty lives in the fixed attack sets
 * (CrossTeam*FixedAttackSetM10Q01Test).
 *
 * The skill collision layer adapts to the stack: with the write gate closed the probe
 * meets 422 skill_disabled (itself an all-off contract point) and the layer skips honestly;
 * the m10 release gate also runs this spec on the augmented stack where the layer is live.
 */

type EntryHead = { id: string, entryKey: string, status: string, version: number, effectiveRevision: number | null }
type SkillHead = { id: string, skillKey: string, status: string, version: number, effectiveRevision: number | null }
type TeamRow = { id: string }

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let neighbourContext: BrowserContext
let neighbourPage: Page
let neighbourSession: Session
let teamAlpha: string
let teamNeighbour: string
let entryKey: string
let entryIdAlpha: string
let entryIdNeighbour: string
let profileIdAlpha: string
let skillKey: string
let skillIdAlpha: string | null = null
let skillIdNeighbour: string | null = null
let skillGateOpen = false

const alphaPath = (suffix: string) => teamPath(session, teamAlpha, suffix)
const neighbourPath = (suffix: string) => teamPath(session, teamNeighbour, suffix)

async function publishKnowledge(
  root: string,
  key: string,
  title: string,
  content: string,
): Promise<EntryHead> {
  const created = await page.request.post(root, {
    data: { entryKey: key, category: 'RUNBOOK', title, content },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(created.status(), await created.text()).toBe(202)
  const stored = (await page.request.get(`${root}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const draft = (await stored).items.find(item => item.entryKey === key)
  expect(draft?.status).toBe('DRAFT')
  const published = await page.request.post(`${root}/${draft!.id}/publish`, {
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${draft!.version}"`,
    },
  })
  expect(published.status()).toBe(202)
  const head = (await page.request.get(`${root}/${draft!.id}`)).json() as Promise<EntryHead>
  expect((await head).status).toBe('PUBLISHED')
  return await head
}

test('the attacking team onboards, publishes its resources and creates the same-Organization neighbour', async ({ browser }, testInfo) => {
  const suffix = `q01a-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: testInfo.project.name.includes('Narrow') ? { width: 390, height: 844 } : { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'Q01 Alpha', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`Q01 Alpha ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  expect(session.teams).toHaveLength(1)
  teamAlpha = session.teams[0]!.teamId

  entryKey = `q01-runbook-${Date.now()}`
  const entry = await publishKnowledge(
    alphaPath('knowledge/entries'), entryKey, 'Alpha 部署手册',
    '部署前 Alpha 队必须跑全量回归并观察健康检查十分钟。')
  entryIdAlpha = entry.id

  const profiles = (await page.request.get(alphaPath('agent-profiles'))).json() as Promise<{ items: Array<{ id: string, ownershipType: string }> }>
  profileIdAlpha = (await profiles).items.find(item => item.ownershipType === 'USER')!.id

  // The skill write gate decides whether the collision layer runs on this stack.
  skillKey = `q01-review-${Date.now()}`
  const probe = await page.request.post(alphaPath('skills'), {
    data: { skillKey, content: `---\nname: ${skillKey}\ndescription: Alpha 逐行审查\n---\n\nAlpha 队逐行审查。` },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  if (probe.status() === 202) {
    skillGateOpen = true
    const stored = (await page.request.get(`${alphaPath('skills')}?limit=50`)).json() as Promise<{ items: SkillHead[] }>
    const draft = (await stored).items.find(item => item.skillKey === skillKey)!
    const published = await page.request.post(`${alphaPath('skills')}/${draft.id}/publish`, {
      headers: {
        [session.csrf.headerName]: session.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${draft.version}"`,
      },
    })
    expect(published.status()).toBe(202)
    const after = (await page.request.get(`${alphaPath('skills')}/${draft.id}`)).json() as Promise<SkillHead>
    expect((await after).status).toBe('PUBLISHED')
    skillIdAlpha = (await after).id
  } else {
    // The all-off stack's own contract: writes stop at the gate before anything else.
    expect(probe.status()).toBe(422)
    expect(((await probe.json()) as { code: string }).code).toBe('skill_disabled')
  }

  // The closest possible stranger: a second real team of the same Organization.
  const created = await page.request.post(
    `/api/v1/organizations/${session.principal!.organizationId}/teams`,
    {
      data: { name: `Q01 Neighbour ${suffix}`.slice(0, 96) },
      headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    })
  expect(created.status(), await created.text()).toBe(202)
  const teams = (await page.request.get(`/api/v1/organizations/${session.principal!.organizationId}/teams`)).json() as Promise<TeamRow[]>
  const known = new Set(session.teams.map(item => item.teamId))
  const fresh = (await teams).filter(item => !known.has(item.id))
  expect(fresh).toHaveLength(1)
  teamNeighbour = fresh[0]!.id
})

test('the neighbour team publishes near-identical content under the same keys', async () => {
  // One team-identifying word apart, under the same entry key — the neighbour shape the
  // fixed attack sets freeze at the SQL tier.
  const entry = await publishKnowledge(
    neighbourPath('knowledge/entries'), entryKey, '邻队部署手册',
    '部署前邻队必须跑全量回归并观察健康检查十分钟。')
  entryIdNeighbour = entry.id

  if (skillGateOpen) {
    const root = neighbourPath('skills')
    const created = await page.request.post(root, {
      data: { skillKey, content: `---\nname: ${skillKey}\ndescription: 邻队按节奏审查\n---\n\n邻队按自己的节奏审查。` },
      headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    })
    expect(created.status()).toBe(202)
    const stored = (await page.request.get(`${root}?limit=50`)).json() as Promise<{ items: SkillHead[] }>
    const draft = (await stored).items.find(item => item.skillKey === skillKey)!
    const published = await page.request.post(`${root}/${draft.id}/publish`, {
      headers: {
        [session.csrf.headerName]: session.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${draft.version}"`,
      },
    })
    expect(published.status()).toBe(202)
    const after = (await page.request.get(`${root}/${draft.id}`)).json() as Promise<SkillHead>
    expect((await after).status).toBe('PUBLISHED')
    skillIdNeighbour = (await after).id
  }
})

test('the neighbour\'s real ids never surface through the attacking team\'s endpoints — the shared 404 envelope', async () => {
  // Tier one — the team predicate itself: a legitimate member of the attacking team asks
  // its own endpoints for resource ids that really exist in the store but hang on the
  // neighbour. The 200-level right to the endpoint makes the 404 purely the predicate's
  // (the exact shape the fixed attack sets prove at the SQL tier).
  const profiles = (await page.request.get(neighbourPath('agent-profiles'))).json() as Promise<{ items: Array<{ id: string, ownershipType: string }> }>
  const profileIdNeighbour = (await profiles).items.find(item => item.ownershipType === 'USER')!.id

  const readPaths = [
    `knowledge/entries/${entryIdNeighbour}`,
    `knowledge/entries/${entryIdNeighbour}/versions`,
    `knowledge/entries/${entryIdNeighbour}/effective-version`,
    `agent-profiles/${profileIdNeighbour}/memory`,
  ]
  if (skillGateOpen) {
    readPaths.push(`skills/${skillIdNeighbour}`)
  }
  for (const pathSuffix of readPaths) {
    const response = await page.request.get(alphaPath(pathSuffix))
    expect(response.status(), pathSuffix).toBe(404)
    expect(((await response.json()) as { code: string }).code, pathSuffix).toBe('aggregate_not_found')
  }

  const clear = await page.request.delete(alphaPath(`agent-profiles/${profileIdNeighbour}/memory`), {
    headers: { [session.csrf.headerName]: session.csrf.token },
  })
  expect(clear.status()).toBe(404)
  expect(((await clear.json()) as { code: string }).code).toBe('aggregate_not_found')
})

test('a neighbour member probes the attacking team\'s endpoints and meets the member wall', async ({ browser }) => {
  // Tier two — the member guard: a principal of the same Organization whose only
  // membership is the neighbour team is refused on the attacking team's endpoints with
  // 403 policy_denied. The owner invites one through the real invitation flow (the m7
  // two-user precedent), because the owner itself legitimately holds both teams.
  const suffix = `q01n-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  await page.goto(`/team/members?team=${teamNeighbour}&tab=invitations`)
  await page.getByRole('button', { name: '创建邀请' }).first().click()
  await page.locator('input[name="invitationEmail"]').fill(`member-${suffix}@example.test`)
  await page.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await page.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await page.getByRole('textbox', { name: '一次性邀请链接' }).inputValue()

  neighbourContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  neighbourPage = await neighbourContext.newPage()
  await neighbourPage.goto(invitationLink)
  await neighbourPage.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(neighbourPage).toHaveURL(/\/register$/)
  await register(neighbourPage, `member-${suffix}`.slice(0, 48), `member-${suffix}@example.test`, 'Q01 Neighbour', 'Correct-Horse-Battery-Staple-47', true)
  await expect(neighbourPage).not.toHaveURL(/\/register$/)
  neighbourSession = await currentSession(neighbourPage)
  expect(neighbourSession.teams.map(item => item.teamId)).toContain(teamNeighbour)

  const target = (pathSuffix: string) => teamPath(neighbourSession, teamAlpha, pathSuffix)
  for (const pathSuffix of [
    `knowledge/entries/${entryIdAlpha}`,
    'knowledge/index/jobs?limit=10',
    'skills?limit=50',
    'observability/cost/months',
  ]) {
    const response = await neighbourPage.request.get(target(pathSuffix))
    expect(response.status(), pathSuffix).toBe(403)
    expect(((await response.json()) as { code: string }).code, pathSuffix).toBe('policy_denied')
  }

  const rebuild = await neighbourPage.request.post(target('knowledge/index/rebuilds'), {
    headers: {
      [neighbourSession.csrf.headerName]: neighbourSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(rebuild.status()).toBe(403)
  expect(((await rebuild.json()) as { code: string }).code).toBe('policy_denied')
})

test('the neighbour\'s same-key publications never move the attacking team\'s heads', async () => {
  // The same entry key exists in both teams' stores now; the attacking team's listing must
  // still resolve it to exactly its own row.
  const listing = (await page.request.get(`${alphaPath('knowledge/entries')}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const sameKey = (await listing).items.filter(item => item.entryKey === entryKey)
  expect(sameKey).toHaveLength(1)
  expect(sameKey[0]!.id).toBe(entryIdAlpha)
  expect(sameKey[0]!.status).toBe('PUBLISHED')

  if (!skillGateOpen) {
    await context.close()
    test.skip(true, 'the skill write gate is closed on this stack — the collision layer runs on the augmented stack')
  }

  const skillListing = (await page.request.get(`${alphaPath('skills')}?limit=50`)).json() as Promise<{ items: SkillHead[] }>
  const sameSkillKey = (await skillListing).items.filter(item => item.skillKey === skillKey)
  expect(sameSkillKey).toHaveLength(1)
  expect(sameSkillKey[0]!.id).toBe(skillIdAlpha)
  expect(sameSkillKey[0]!.status).toBe('PUBLISHED')
  expect(sameSkillKey[0]!.effectiveRevision).toBe(1)
})

test('an account of a different Organization meets the outer 403 wall', async ({ browser }) => {
  // The Organization boundary sits outside the team boundary: a foreign account is
  // refused with 403 policy_denied before any resource is considered — blocked, never
  // leaked; the team-level same-shape duty above is the inner, finer-grained one.
  const suffix = `q01b-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const foreignContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const foreignPage = await foreignContext.newPage()
  await register(foreignPage, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'Q01 Foreign', 'Correct-Horse-Battery-Staple-47', false)
  await expect(foreignPage).toHaveURL(/\/onboarding$/)
  await foreignPage.getByRole('textbox', { name: '团队名称' }).fill(`Q01 Foreign ${suffix}`.slice(0, 96))
  await foreignPage.getByRole('button', { name: '创建团队' }).click()
  await expect(foreignPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  for (const suffixPath of [`knowledge/entries/${entryIdAlpha}`, 'observability/cost/months']) {
    const response = await foreignPage.request.get(alphaPath(suffixPath))
    expect(response.status(), suffixPath).toBe(403)
    expect(((await response.json()) as { code: string }).code, suffixPath).toBe('policy_denied')
  }

  await foreignContext.close()
  await neighbourContext.close()
  await context.close()
})
