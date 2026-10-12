import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, command, currentSession, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-Q01 real-stack attack face ① (cross-Team subscription) + ⑤ (relation reads): an
 * attacker with perfectly legitimate credentials — a real browser session of a fully
 * onboarded account in another Team of the same deployment — aims both channels at the
 * victim Team's coordinates. The WebSocket scope wall must answer forbidden_scope with
 * nothing but welcome + error frames (no presence, no names, no titles), and every
 * WorkGraph read face must answer 403 policy_denied. This is the deployment-level proof
 * the fixture-level tests cannot give: real session cookies, real CSRF posture, the
 * Nginx upgrade hop, and a target who is actually present on the scope.
 */
test.describe.configure({ mode: 'serial' })

let victimContext: BrowserContext
let attackerContext: BrowserContext
let victimPage: Page
let attackerPage: Page
let victimSession: Session
let attackerSession: Session
let teamId: string
let projectId: string
let workItemId: string
let suffix: string

const password = 'Correct-Horse-Battery-Staple-47'

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  suffix = `q01a-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')

  // The victim: an onboarded owner whose Team actually holds a work item and whose page
  // is genuinely present on the team scope — so a broken wall would leak a real roster.
  victimContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  victimPage = await victimContext.newPage()
  await register(victimPage, `victim-${suffix}`.slice(0, 48), `victim-${suffix}@example.test`, 'Alice Q01 Victim', password, false)
  await expect(victimPage).toHaveURL(/\/onboarding$/)
  await victimPage.getByRole('textbox', { name: '团队名称' }).fill(`Q01 猎物 ${suffix}`.slice(0, 96))
  await victimPage.getByRole('button', { name: '创建团队' }).click()
  await expect(victimPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  victimSession = await currentSession(victimPage)
  teamId = onlyTeam(victimSession).teamId

  const projectsPath = teamPath(victimSession, teamId, 'work-projects')
  await command(victimPage, victimSession, projectsPath, { name: `Q01 越权计划 ${suffix}`.slice(0, 96) })
  const projects = await getJson<{ items: Array<{ id: string, name: string }> }>(victimPage, `${projectsPath}?limit=50`)
  projectId = projects.items.find(project => project.name.includes(suffix))!.id
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  await command(victimPage, victimSession, workItemsPath, {
    type: 'FEATURE',
    title: `Q01 越权载体 ${suffix}`.slice(0, 96),
    description: 'M11-Q01 cross-team attack carrier',
    priority: 'MEDIUM',
    labels: ['q01-attack'],
    dueAt: null,
  })
  const items = await getJson<{ items: Array<{ id: string, title: string }> }>(victimPage, `${workItemsPath}?limit=50`)
  workItemId = items.items.find(item => item.title.includes(suffix))!.id

  // The attacker: an independent, fully legitimate account of this deployment — its own
  // Team, its own session, no stolen cookies. The strongest outsider shape there is.
  attackerContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  attackerPage = await attackerContext.newPage()
  await register(attackerPage, `attacker-${suffix}`.slice(0, 48), `attacker-${suffix}@example.test`, 'Mallory Q01', password, false)
  await expect(attackerPage).toHaveURL(/\/onboarding$/)
  await attackerPage.getByRole('textbox', { name: '团队名称' }).fill(`Q01 攻击者 ${suffix}`.slice(0, 96))
  await attackerPage.getByRole('button', { name: '创建团队' }).click()
  await expect(attackerPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  attackerSession = await currentSession(attackerPage)

  // The victim parks on their work page, present on the team scope through the product.
  await victimPage.goto(`/work?team=${teamId}&project=${projectId}&workItem=${workItemId}`)
  await expect(victimPage.locator('.collab-pill')).toContainText('实时协作在线')
})

test.afterAll(async () => {
  await victimContext.close()
  await attackerContext.close()
})

test('the attacker subscribing the victim Team scope gets forbidden_scope and no leak', async () => {
  const organizationId = victimSession.principal?.organizationId
  expect(organizationId).toBeTruthy()

  const outcome = await attackerPage.evaluate(
    ({ organizationId: organization, team }: { organizationId: string, team: string }) => {
      const url = `${location.origin.replace(/^http/, 'ws')}/api/v1/collaboration/ws`
      return new Promise<{
        frames: Array<Record<string, unknown>>
        closeCode: number | null
      }>(resolve => {
        const frames: Array<Record<string, unknown>> = []
        let denied = false
        const socket = new WebSocket(url)
        const timeout = window.setTimeout(() => {
          try { socket.close() } catch { /* never opened */ }
          resolve({ frames, closeCode: null })
        }, 30_000)
        socket.onmessage = message => {
          const frame = JSON.parse(String(message.data)) as Record<string, unknown>
          frames.push(frame)
          if (frame.type === 'error' && frame.code === 'forbidden_scope') {
            denied = true
            window.clearTimeout(timeout)
            socket.close(1000, 'spec complete')
          }
        }
        socket.onclose = event => {
          window.clearTimeout(timeout)
          if (denied) resolve({ frames, closeCode: event.code })
        }
        socket.onopen = () => {
          socket.send(JSON.stringify({ type: 'subscribe', scope: { organization, team } }))
        }
        socket.onerror = () => { /* onclose carries the verdict */ }
      })
    },
    { organizationId: organizationId!, team: teamId },
  )

  // The wall: forbidden_scope answered, on an endpoint that still works for its own
  // members (the connection itself opened and closed by the spec, not by the server).
  expect(outcome.closeCode).toBe(1000)
  const types = outcome.frames.map(frame => String(frame.type))
  expect(types).toContain('welcome')
  const errorFrame = outcome.frames.find(frame => frame.type === 'error')
  expect(errorFrame?.code).toBe('forbidden_scope')

  // Least disclosure: apart from welcome/ping, nothing else may arrive — no snapshot,
  // no delta, no typing; and no frame may carry the victim's identity or coordinates.
  for (const type of types) {
    expect(['welcome', 'ping', 'error', 'pong']).toContain(type)
  }
  expect(outcome.frames.some(frame => frame.type === 'presence_snapshot')).toBe(false)
  expect(outcome.frames.some(frame => frame.type === 'presence_delta')).toBe(false)
  expect(outcome.frames.some(frame => frame.type === 'typing')).toBe(false)
  expect(JSON.stringify(outcome.frames)).not.toContain('Alice Q01 Victim')
  expect(JSON.stringify(outcome.frames)).not.toContain(workItemId)
})

test('the attacker reading the victim Team graph faces gets 403 policy_denied on every face', async () => {
  const faces = [
    `/api/v1/organizations/${victimSession.principal!.organizationId}/teams/${teamId}/work-projects/${projectId}/work-items/${workItemId}/relations`,
    `/api/v1/organizations/${victimSession.principal!.organizationId}/teams/${teamId}/work-projects/${projectId}/work-items/${workItemId}/blocking-chain`,
    `/api/v1/organizations/${victimSession.principal!.organizationId}/teams/${teamId}/work-graph/blocked-work-items`,
  ]
  for (const face of faces) {
    const response = await attackerPage.request.get(face)
    expect(response.status(), `${face} must hit the team wall`).toBe(403)
    const body = (await response.json()) as { code?: string, edges?: unknown, items?: unknown }
    expect(body.code).toBe('policy_denied')
    expect(body.edges).toBeUndefined()
    expect(body.items).toBeUndefined()
  }
})
