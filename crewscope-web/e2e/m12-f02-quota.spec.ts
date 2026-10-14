import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Request, type Route } from '@playwright/test'
import { permissions } from '../src/app/auth'
import { ids, mockApi } from './menu-walk'

/**
 * M12-F02 配额与用量页的 mock 层 e2e。菜单走查（m9-mobile-reach/m9-dark-theme）已覆盖入口
 * 存在性；这里验的是配额契约在浏览器里的完整生命周期与权限矩阵：
 *
 * 1. **配置生命周期** —— 未配置态 → 保存（Idempotency-Key 必带、CSRF 头、If-Match 引版本、
 *    body 恒两字段）→ 202 后重读 → 清除维度（空输入 = null，绝不是 0）。
 * 2. **409 收敛** —— 乐观锁冲突给中文指引并重读，下一次保存引用持久化版本。
 * 3. **幂等重放** —— Idempotency-Replayed 头把回执标注为重放。
 * 4. **用量窗口** —— 6 个月拼合（锚点无参调用 + 5 个显式月）、sr-only 数据表逐月陈述、
 *    materialized=false 的投影延迟提示。
 * 5. **月份导航** —— URL ?month= 驱动；未来月深链回退锚点且不发出必 400 的请求。
 * 6. **权限矩阵** —— 审计员只读（无编辑入口）、无组织角色成员静态 forbidden（不发请求，
 *    避开全局 403 sink）。
 * 7. **可达性** —— 月份导航键盘可达、Axe 零违规、无横向溢出。
 *
 * 基础 GET 由 menu-walk 的 mockApi 兜底；本文件的路由在其之后注册（后注册者优先）。
 */

type SessionRole = 'ORG_OWNER' | 'ORG_ADMIN' | 'ORG_AUDITOR' | 'MEMBER'

const ORG_PERMISSIONS: Record<SessionRole, string[]> = {
  ORG_OWNER: [permissions.orgAuditRead, permissions.orgGovern, permissions.orgManageSettings],
  ORG_ADMIN: [permissions.orgAuditRead, permissions.orgGovern, permissions.orgManageSettings],
  ORG_AUDITOR: [permissions.orgAuditRead],
  MEMBER: [],
}

const ANCHOR = '2026-10'
/** The six-month window the page assembles client-side (no multi-month endpoint exists). */
const WINDOW = ['2026-05', '2026-06', '2026-07', '2026-08', '2026-09', ANCHOR]

function sessionFor(role: SessionRole) {
  const teamPermissions = [permissions.scopeRead, permissions.workRead]
  return {
    authenticated: true,
    registrationMode: 'OPEN' as const,
    csrf: { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-quota-e2e' },
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

async function installQuotaApi(page: Page, role: SessionRole = 'ORG_ADMIN') {
  const calls: CallFact[] = []
  const usageMonths: string[] = []
  let configuration = { monthlyTaskIntakeLimit: null as number | null, monthlyTokenBudget: null as number | null, version: 0 }
  /** What the next PUT answers: the default success, a one-shot 409, or a replayed receipt. */
  let putBehavior: 'ok' | 'conflict' | 'replay' = 'ok'
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-quota-e2e', url: 'http://127.0.0.1:4173' }])
  await page.route('**/api/v1/auth/session', route => fulfillJson(route, sessionFor(role)))
  await page.route('**/api/v1/organizations/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && /\/quota\/usage$/.test(path)) {
      const month = new URL(request.url()).searchParams.get('month') ?? ANCHOR
      usageMonths.push(month)
      return fulfillJson(route, usageBody(month, configuration))
    }
    if (request.method() === 'GET' && /\/quota$/.test(path)) {
      return fulfillJson(route, configuration)
    }
    if (request.method() === 'PUT' && /\/quota$/.test(path)) {
      record(request)
      if (putBehavior === 'conflict') {
        putBehavior = 'ok'
        // Someone else's write is what a 409 means: the re-read must expose their version.
        configuration = { ...configuration, monthlyTaskIntakeLimit: 6, version: 3 }
        return fulfillJson(route, {
          code: 'optimistic_lock_conflict', message: '配额已被其他管理员更新', correlationId: 'corr-quota-e2e',
          retryable: false, currentVersion: 3, details: {},
        }, 409)
      }
      const replayed = putBehavior === 'replay'
      const body = request.postDataJSON() as { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null }
      configuration = {
        monthlyTaskIntakeLimit: body.monthlyTaskIntakeLimit,
        monthlyTokenBudget: body.monthlyTokenBudget,
        version: configuration.version + 1,
      }
      return fulfillJson(route, receiptBody(), 202, replayed ? { 'Idempotency-Replayed': 'true' } : {})
    }
    return route.fallback()
  })
  return {
    calls,
    usageMonths,
    get configuration() { return configuration },
    conflictNext(): void { putBehavior = 'conflict' },
    replayNext(): void { putBehavior = 'replay' },
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

test('配置生命周期：未配置态→保存两维度→重读→清除维度', async ({ page }) => {
  await mockApi(page)
  const fixture = await installQuotaApi(page)
  await page.goto('/settings/quota')

  await expect(page.getByText('每月任务受理上限 未配置')).toBeVisible()
  await expect(page.getByText('每月 Token 预算 未配置')).toBeVisible()

  await page.getByRole('button', { name: '调整配额' }).click()
  await page.getByLabel('每月任务受理上限').fill('5')
  await page.getByLabel('每月 Token 预算').fill('2000000')
  await page.getByRole('button', { name: '保存配额' }).click()
  await expect(page.getByText('配额已保存')).toBeVisible()

  const save = fixture.calls[0]!
  expect(save.method).toBe('PUT')
  expect(save.body).toEqual({ monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 2_000_000 })
  expect(save.idempotencyKey).toBeTruthy()
  expect(save.ifMatch).toBe('"0"')
  expect(save.csrf).toBe('csrf-quota-e2e')
  await expect(page.getByText('每月任务受理上限 5')).toBeVisible()
  await expect(page.getByText('每月 Token 预算 2,000,000')).toBeVisible()

  // An emptied field clears the dimension (null on the wire) — never zero.
  await page.getByRole('button', { name: '调整配额' }).click()
  await page.getByLabel('每月任务受理上限').fill('')
  await page.getByRole('button', { name: '保存配额' }).click()
  await expect(page.getByText('配额已保存')).toBeVisible()
  expect(fixture.calls[1]!.body).toEqual({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: 2_000_000 })
  expect(fixture.calls[1]!.ifMatch).toBe('"1"')
})

test('409 乐观锁冲突给出中文指引并收敛到持久化版本', async ({ page }) => {
  await mockApi(page)
  const fixture = await installQuotaApi(page)
  await page.goto('/settings/quota')

  fixture.conflictNext()
  await page.getByRole('button', { name: '调整配额' }).click()
  await page.getByLabel('每月任务受理上限').fill('5')
  await page.getByRole('button', { name: '保存配额' }).click()
  await expect(page.getByText(/内容已变化/)).toBeVisible()
  // The conflict keeps the editor open so the admin can re-decide against the re-read state.

  await page.getByLabel('每月任务受理上限').fill('7')
  await page.getByRole('button', { name: '保存配额' }).click()
  await expect(page.getByText('配额已保存')).toBeVisible()
  expect(fixture.calls[1]!.ifMatch).toBe('"3"')
})

test('幂等重放回执被标注，重放保存不重复计数', async ({ page }) => {
  await mockApi(page)
  const fixture = await installQuotaApi(page)
  await page.goto('/settings/quota')

  fixture.replayNext()
  await page.getByRole('button', { name: '调整配额' }).click()
  await page.getByLabel('每月任务受理上限').fill('5')
  await page.getByRole('button', { name: '保存配额' }).click()
  await expect(page.getByText('配额已保存（该命令此前已受理，幂等重放）')).toBeVisible()
})

test('6 个月用量窗口：指标、构成表与 sr-only 数据表逐月陈述', async ({ page }) => {
  await mockApi(page)
  const fixture = await installQuotaApi(page)
  await page.goto('/settings/quota')
  await expect(page.getByText('任务受理（2026-10）')).toBeVisible()

  // One parameterless anchor call plus five explicit months — no multi-month endpoint exists.
  expect(fixture.usageMonths).toContain(ANCHOR)
  for (const month of WINDOW.slice(0, 5)) expect(fixture.usageMonths).toContain(month)

  // The anchor month carries the projection-lag note; past months are materialized.
  await expect(page.getByText('实际用量统计存在投影延迟')).toBeVisible()
  await expect(page.getByText('任务受理（2026-10）')).toBeVisible()
  await expect(page.getByText('6 / 未配置')).toBeVisible()

  const intakeTable = page.locator('table[aria-label="每月任务受理数趋势（数据表）"]')
  for (const month of WINDOW) await expect(intakeTable).toContainText(month)
  await expect(page.locator('table[aria-label="每月 Token 趋势（数据表）"]')).toContainText('2026-05')
  await expect(page.locator('table[aria-label="2026-10 实际 Token 构成（数据表）"]')).toContainText('缓存输入')

  await expect(page.getByText('近 6 个月趋势')).toBeVisible()
  expect(await page.locator('svg[role="group"]').count()).toBe(3)
})

test('月份导航 URL 驱动，未来月深链回退锚点不发请求', async ({ page }) => {
  await mockApi(page)
  const fixture = await installQuotaApi(page)
  await page.goto('/settings/quota')

  await page.getByRole('button', { name: '2026-07', exact: true }).click()
  await expect(page).toHaveURL(/month=2026-07/)
  await expect(page.getByText('任务受理（2026-07）')).toBeVisible()
  // 2026-07 is materialized: the projection note is anchored to the reporting month only.
  await expect(page.getByText('实际用量统计存在投影延迟')).toHaveCount(0)

  const monthsBeforeDeepLink = fixture.usageMonths.length
  await page.goto('/settings/quota?month=2026-12')
  await expect(page.getByText('任务受理（2026-10）')).toBeVisible()
  expect(fixture.usageMonths.slice(monthsBeforeDeepLink)).not.toContain('2026-12')
})

test('权限矩阵：审计员只读，无组织角色的成员静态 forbidden', async ({ page }) => {
  await mockApi(page)
  await installQuotaApi(page, 'ORG_AUDITOR')
  await page.goto('/settings/quota')
  await expect(page.getByText('每月任务受理上限 未配置')).toBeVisible()
  await expect(page.getByRole('button', { name: '调整配额' })).toHaveCount(0)

  await mockApi(page)
  const memberFixture = await installQuotaApi(page, 'MEMBER')
  await page.goto('/settings/quota')
  await expect(page.getByText('需要组织审计角色')).toBeVisible()
  // The member tier never dispatches: a 403 here would trip the global revocation sink.
  expect(memberFixture.calls).toHaveLength(0)
  expect(memberFixture.usageMonths).toHaveLength(0)
})

test('月份导航键盘可达、Axe 零违规、无横向溢出', async ({ page }) => {
  await mockApi(page)
  await installQuotaApi(page)
  await page.goto('/settings/quota')

  const september = page.getByRole('button', { name: '2026-09', exact: true })
  await september.focus()
  await expect(september).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: ANCHOR, exact: true })).toBeFocused()

  await expect(page.getByText('近 6 个月趋势')).toBeVisible()
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

// ---------------------------------------------------------------------------
// Server-shaped bodies (the raw wire contract).
// ---------------------------------------------------------------------------

/** Deterministic per-month counters: the k-th window month reports k tasks and k*… tokens. */
function windowIndex(month: string): number {
  return WINDOW.indexOf(month) + 1
}

function usageBody(
  month: string,
  configuration: { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null },
) {
  const k = Math.max(windowIndex(month), 1)
  const input = k * 20_000
  const output = k * 10_000
  const cachedInput = k * 5_000
  return {
    month,
    monthlyTaskIntake: { limit: configuration.monthlyTaskIntakeLimit, count: k },
    monthlyTokenBudget: {
      limit: configuration.monthlyTokenBudget,
      reservedTokens: k * 100_000,
      actualTokens: { input, output, cachedInput, total: input + output + cachedInput },
    },
    // Only the reporting month lags; past months are settled.
    materialized: month !== ANCHOR,
  }
}

function receiptBody() {
  return {
    commandId: 'command-q1',
    domainEventId: 'event-q1',
    committedVersion: 1,
    correlationId: 'corr-quota-e2e',
    replayed: false,
  }
}

function fulfillJson(route: Route, value: unknown, status = 200, headers: Record<string, string> = {}) {
  return route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(value) })
}
