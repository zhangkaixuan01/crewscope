import { expect, test, type Browser, type Page } from '@playwright/test'
import { baseURL, command, currentSession, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-F02 real-stack acceptance: the WorkGraph visualization over the deployed four-service
 * stack, fed only by real API facts. The graph view renders the blocking DAG seeded through
 * real commands (head BLOCKS middle BLOCKS tail, plus one SPLIT_FROM), the drawer's relations
 * panel states those facts as sentences, a keyboard-driven add and a two-click remove round-trip
 * through the real 202 command path, the guaranteed cycle rejection surfaces its real 409
 * wording, and the second split source short-circuits in the form before any request.
 */

test.describe.configure({ mode: 'serial' })

const password = 'Correct-Horse-Battery-Staple-47'

let page: Page
let session: Session
let teamId: string
let suffix: string
let projectId: string
const itemIds: { head: string, middle: string, tail: string } = { head: '', middle: '', tail: '' }

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  suffix = `f02g-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `graph-${suffix}`.slice(0, 48), `graph-${suffix}@example.test`, 'F02 Graph', password, false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`F02 依赖 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  // One project, three items, and the relation chain — all through the public command API.
  const projectsPath = teamPath(session, teamId, 'work-projects')
  await command(page, session, projectsPath, { name: `F02 依赖计划 ${suffix}`.slice(0, 96) })
  projectId = await waitForListItem(page, projectsPath, suffix)
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  for (const [slot, chineseSlot] of [['head', '链头'], ['middle', '链中'], ['tail', '链尾']] as const) {
    await command(page, session, workItemsPath, {
      type: 'FEATURE',
      title: `F02 ${chineseSlot} ${suffix}`,
      description: 'M11-F02 workgraph real-stack carrier',
      priority: 'MEDIUM',
      labels: ['f02-graph'],
      dueAt: null,
    })
    itemIds[slot] = await waitForListItem(page, workItemsPath, `${chineseSlot} ${suffix}`)
  }

  const relationsPath = teamPath(session, teamId, 'work-item-relations')
  await command(page, session, relationsPath, { type: 'BLOCKS', fromWorkItemId: itemIds.head, toWorkItemId: itemIds.middle })
  await command(page, session, relationsPath, { type: 'BLOCKS', fromWorkItemId: itemIds.middle, toWorkItemId: itemIds.tail })
  await command(page, session, relationsPath, { type: 'SPLIT_FROM', fromWorkItemId: itemIds.tail, toWorkItemId: itemIds.middle })

  // The aggregate is the graph's only feed: wait until both blocked rows are real.
  const blockedPath = teamPath(session, teamId, 'work-graph/blocked-work-items')
  await expect
    .poll(async () => {
      const envelope = await getJson<{ items: Array<{ workItemId: string }> }>(page, `${blockedPath}?limit=100`)
      return envelope.items.map(row => row.workItemId).sort().join(',')
    }, { timeout: 30_000 })
    .toBe([itemIds.middle, itemIds.tail].sort().join(','))
})

test('the graph view paints the real blocking DAG and its non-graph equivalent', async () => {
  await page.goto(`/work?team=${teamId}&project=${projectId}&view=graph`)

  // Three nodes (two blocked rows plus the pure blocker head) and two BLOCKS edges.
  const canvas = page.getByRole('group', { name: '阻塞依赖图,共 3 个工作项、2 条阻塞关系' })
  await expect(canvas).toBeVisible()
  // The sr-only list says every fact the picture says; the title is the stable part of the line.
  const list = page.getByLabel('依赖图节点清单')
  await expect(list).toContainText(`F02 链头 ${suffix}`)
  await expect(list).toContainText(`F02 链中 ${suffix}`)
  await expect(list).toContainText(`F02 链尾 ${suffix}`)
  await expect(list).toContainText('被 1 项阻塞')
})

test('the relations panel states the seeded edges as real sentences', async () => {
  await page.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.middle}`)
  const drawer = page.getByRole('dialog').filter({ hasText: `F02 链中 ${suffix}` }).first()
  await expect(drawer).toBeVisible()

  // The middle item says both directions of its chain: blocked by head, blocking tail.
  const relations = drawer.locator('.relations-list')
  await expect(relations).toContainText(`「F02 链头 ${suffix}」阻塞本项`)
  await expect(relations).toContainText(`本项阻塞「F02 链尾 ${suffix}」`)
})

test('a real cycle rejection, a legal add that survives reload, and the two-click remove', async () => {
  await page.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.tail}`)
  const drawer = page.getByRole('dialog').filter({ hasText: `F02 链尾 ${suffix}` }).first()
  await expect(drawer).toBeVisible()

  // The tail says both of its real relations: blocked by the middle, split from the middle.
  const relations = drawer.locator('.relations-list')
  await expect(relations).toContainText(`「F02 链中 ${suffix}」阻塞本项`)
  await expect(relations).toContainText(`本项拆分自「F02 链中 ${suffix}」`)

  // tail BLOCKS head closes the loop head -> middle -> tail -> head: the candidate dropdown
  // only offers non-neighbors (head), and the real server's rejection reaches the form.
  await drawer.getByLabel('目标工作项').selectOption(itemIds.head)
  await drawer.getByLabel('关系').selectOption('outgoingBlocker')
  await drawer.getByRole('button', { name: '添加关系' }).click()
  await expect(drawer.locator('.relations-error')).toContainText('这条关系会形成循环阻塞，无法创建。')

  // The same target with the default sentence is a legal edge: head becomes a direct blocker.
  await drawer.getByLabel('关系').selectOption('incomingBlocker')
  await drawer.getByRole('button', { name: '添加关系' }).click()
  await expect(relations).toContainText(`「F02 链头 ${suffix}」阻塞本项`)

  // Reload the whole page: the sentence is a durable server fact, not a local optimistic ghost.
  await page.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.tail}`)
  const reopened = page.getByRole('dialog').filter({ hasText: `F02 链尾 ${suffix}` }).first()
  await expect(reopened.locator('.relations-list')).toContainText(`「F02 链头 ${suffix}」阻塞本项`)

  // Two-click remove: the first arms, the second deletes through the real DELETE path.
  const remove = reopened.locator('.relations-list li')
    .filter({ hasText: `「F02 链头 ${suffix}」阻塞本项` })
    .locator('.relations-remove')
  await remove.click()
  await expect(remove).toHaveText('确认移除')
  await remove.click()
  await expect(reopened.locator('.relations-list')).not.toContainText(`「F02 链头 ${suffix}」阻塞本项`)
})

test('a second split source short-circuits in the form instead of walking into the 409', async () => {
  await page.goto(`/work?team=${teamId}&project=${projectId}&workItem=${itemIds.tail}`)
  const drawer = page.getByRole('dialog').filter({ hasText: `F02 链尾 ${suffix}` }).first()
  await expect(drawer).toBeVisible()

  // tail already splits from middle (seeded): the option is disabled and says why — no request.
  // Native <option> disabled state reads oddly through the accessibility tree on chromium
  // (toBeDisabled reports enabled while the attribute is present), so pin the attribute and
  // the copy — the flipped wording only renders when a source is already taken.
  const splitOption = drawer.getByLabel('关系').locator('option[value="splitFromSource"]')
  await expect(splitOption).toHaveAttribute('disabled')
  await expect(splitOption).toHaveText('本项已有拆分来源')
})

test('the blocked badge rides the real list for every blocked item', async () => {
  await page.goto(`/work?team=${teamId}&project=${projectId}`)
  // middle and tail each carry one active blocker; the badge count is the aggregate's word.
  await expect(page.getByText('被 1 项阻塞')).toHaveCount(2)
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
