import { expect, test, type Page, type Route } from '@playwright/test'
import { ids, mockA05App, workUrl } from '../m9b-a05/fixtures'

/**
 * R21 选人器：20+ 混合候选目录下，类型过滤先于分页（请求带 types=AGENT，USER 永不漏进
 * 候选）、续页可达全量、重名靠副行区分、已选以 chip 回显且可「更换」不必先清除。
 * 世界复用 A05 的 work 抽屉；目录端点在本夹具后注册，优先于 A05 的全量拦截。
 */

interface DirectoryRequest { types: string | null, q: string | null, after: string | null }

interface PrincipalRow { principalId: string, kind: string, displayName: string, status: string, roles: string[] }

/** 22 个 AGENT（含一对重名与一个已暂停）+ 2 个 USER：混合目录是过滤器在服务端的证物。 */
function directory(): PrincipalRow[] {
  const rows: PrincipalRow[] = [
    { principalId: '00000000-0000-4000-8000-000000002101', kind: 'AGENT', displayName: '发布哨兵', status: 'ACTIVE', roles: ['RELEASE_MANAGE'] },
    { principalId: '00000000-0000-4000-8000-000000002102', kind: 'AGENT', displayName: '发布哨兵', status: 'ACTIVE', roles: [] },
    { principalId: '00000000-0000-4000-8000-000000002103', kind: 'AGENT', displayName: '夜间值班 Agent', status: 'SUSPENDED', roles: [] },
    ...Array.from({ length: 19 }, (_, index) => ({
      principalId: `00000000-0000-4000-8000-0000000021${String(index + 10).padStart(2, '0')}`,
      kind: 'AGENT', displayName: `专项 Agent ${String(index + 1).padStart(2, '0')}`, status: 'ACTIVE', roles: [],
    })),
    { principalId: '00000000-0000-4000-8000-000000002201', kind: 'USER', displayName: '张凯旋', status: 'ACTIVE', roles: ['OWNER'] },
    { principalId: '00000000-0000-4000-8000-000000002202', kind: 'USER', displayName: '林晨', status: 'ACTIVE', roles: [] },
  ]
  return rows
}

async function mockPrincipalDirectory(page: Page): Promise<{ requests: DirectoryRequest[] }> {
  const requests: DirectoryRequest[] = []
  await page.route(/\/principals\?/, async (route: Route) => {
    const url = new URL(route.request().url())
    const types = url.searchParams.get('types')
    const after = url.searchParams.get('after')
    requests.push({ types, q: url.searchParams.get('q'), after })
    const matching = directory().filter(row => !types || types.split(',').includes(row.kind))
    const start = after ? 20 : 0
    const page_items = matching.slice(start, start + 20)
    return route.fulfill({
      status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify({ items: page_items, nextCursor: start + 20 < matching.length ? 'directory-page-2' : null }),
    })
  })
  return { requests }
}

async function openExecutorPicker(page: Page) {
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  await expect(drawer).toBeVisible()
  await drawer.getByText('搜索目录页之外的 Agent').click()
  // The Agent 目录 region above ships a select with the same accessible name; the picker is the
  // combobox inside the by-name search form.
  const input = drawer.locator('.agent-assignment form').first().getByRole('combobox')
  await input.click()
  const listbox = drawer.getByRole('listbox', { name: 'Agent Executor' })
  await expect(listbox).toBeVisible()
  return { drawer, input, listbox }
}

test('walks the whole filtered directory through the continuation without ever leaking users', async ({ page }) => {
  await mockA05App(page)
  const { requests } = await mockPrincipalDirectory(page)
  const { drawer, listbox } = await openExecutorPicker(page)

  // The kind filter rides the request (R21); pagination walks the filtered set, not a client slice.
  expect(requests[0]).toMatchObject({ types: 'AGENT', q: null, after: null })
  await expect(listbox.getByRole('option')).toHaveCount(20)
  // A mixed catalog never surfaces its USER half, even before the continuation.
  await expect(listbox).not.toContainText('林晨')

  await drawer.getByRole('button', { name: '加载更多候选' }).click()
  await expect(listbox.getByRole('option')).toHaveCount(22)
  await expect(drawer.getByRole('button', { name: '加载更多候选' })).toHaveCount(0)
  expect(requests[1]).toMatchObject({ types: 'AGENT', after: 'directory-page-2' })
  await expect(listbox).not.toContainText('张凯旋')

  // The namesake pair is told apart by the subline, not by an identifier.
  const namesakes = listbox.getByRole('option', { name: /发布哨兵/ })
  await expect(namesakes).toHaveCount(2)
  await expect(namesakes.first()).toContainText('Agent · RELEASE_MANAGE')
  await expect(namesakes.nth(1)).toContainText('Agent')
})

test('echoes the chosen subject as a chip and replaces it without clearing first', async ({ page }) => {
  await mockA05App(page)
  await mockPrincipalDirectory(page)
  const { drawer, listbox } = await openExecutorPicker(page)

  // A suspended subject stays selectable but is labelled, so the outcome is predictable.
  const suspended = listbox.getByRole('option', { name: /夜间值班 Agent/ })
  await expect(suspended).toContainText('已暂停')
  await suspended.click()
  const chip = drawer.locator('.principal-chip').first()
  await expect(chip).toContainText('夜间值班 Agent')
  await expect(chip).toContainText('已暂停')
  // The resolved identifier is emitted to the form, never surfaced to the member.
  await expect(chip).not.toContainText('00000000-')
  await expect(drawer.getByRole('button', { name: '添加', exact: true }).first()).toBeEnabled()

  // 更换 re-opens the search box without dropping the current value first (R21).
  await drawer.getByRole('button', { name: '更换Agent Executor' }).click()
  const reopened = drawer.locator('.agent-assignment form').first().getByRole('combobox')
  await expect(reopened).toBeVisible()
  await expect(drawer.locator('.principal-chip')).toHaveCount(0)
  await reopened.press('Escape')
  await expect(chip).toContainText('夜间值班 Agent')
})
