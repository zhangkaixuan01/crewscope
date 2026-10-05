import { expect, test, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

// M10-F01c I02a: the member's own agent memory view under /settings/agents — the three policy
// states, the confirmation-gated clear with both structural-idempotency receipts, and the
// revoked-member forbidden panel. The I02c injection-evidence cases live in app-shell.spec.ts,
// where the deep task-drawer cascade fixtures already exist.

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401',
  workspace: '00000000-0000-0000-0000-000000000501',
  member: '00000000-0000-0000-0000-000000000301',
  agent: '00000000-0000-1000-8000-000000006101',
}

const POLICY_ID = '7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20'

// The GET answers the three wire shapes of contract §2; the DELETE is structurally idempotent —
// the second call clears zero entries and still advances the generation.
let memoryShape: 'healthy' | 'unconfigured' | 'degraded'
let memoryDenied: boolean
let memoryEntries: Array<Record<string, unknown>>
let generation: number
let deleteCommands: Array<{ idempotencyKey: string | undefined, ifMatch: string | undefined }>

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-08T04:00:00Z'))
  memoryShape = 'healthy'
  memoryDenied = false
  memoryEntries = [memoryEntry()]
  generation = 3
  deleteCommands = []

  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') {
      return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    }
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      return fulfillJson(route, [team()])
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      return fulfillJson(route, { items: [project()], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/agent-templates')) {
      return fulfillJson(route, { items: [agentTemplate('USER', 'coding-specialist')] })
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/agent-profiles`)) {
      return fulfillJson(route, { items: [agentProfile()] })
    }
    const agentDetailMatch = path.match(/\/agent-profiles\/([^/]+)$/)
    if (request.method() === 'GET' && agentDetailMatch) {
      return route.fulfill({
        status: 200, contentType: 'application/json', headers: { ETag: '"2"' },
        body: JSON.stringify(agentProfile()),
      })
    }
    if (request.method() === 'GET' && path.match(/\/agent-profiles\/[^/]+\/configurations\/current$/)) {
      return route.fulfill({
        status: 200, contentType: 'application/json', headers: { ETag: '"2"', 'Cache-Control': 'no-store' },
        body: JSON.stringify(agentConfiguration()),
      })
    }
    if (request.method() === 'GET' && path.match(/\/agent-profiles\/[^/]+\/configurations$/)) {
      return fulfillJson(route, { items: [] })
    }
    if (request.method() === 'GET' && path.match(/\/agent-profiles\/[^/]+\/model-catalog$/)) {
      return fulfillJson(route, { items: selectableModels() })
    }
    if (request.method() === 'GET' && path.match(/\/agent-profiles\/[^/]+\/memory$/)) {
      if (memoryDenied) return fulfillError(route, 403, 'policy_denied', '当前身份不是该 Team 的活动成员')
      return fulfillJson(route, memoryView())
    }
    // Contract §4: the clear carries no Idempotency-Key and no If-Match — replay safety is
    // structural (already-cleared entries answer zero), not header-negotiated.
    if (request.method() === 'DELETE' && path.match(/\/agent-profiles\/[^/]+\/memory$/)) {
      deleteCommands.push({ idempotencyKey: request.headers()['idempotency-key'], ifMatch: request.headers()['if-match'] })
      const clearedCount = memoryEntries.length
      memoryEntries = []
      generation += 1
      return fulfillJson(route, { clearedCount, clearanceGeneration: generation })
    }
    return route.fallback()
  })
})

test('M10-F01c agent memory renders the three policy states without blurring', async ({ page }) => {
  await page.goto(`/settings/agents?team=${ids.team}&agent=${ids.agent}`)
  const region = page.getByRole('region', { name: '辅助记忆' })

  // Healthy: policy digest, live entry and the gated clear entry.
  await expect(region.getByText('90 天')).toBeVisible()
  await expect(region.getByText('100 条')).toBeVisible()
  await expect(region.getByText('reply-language')).toBeVisible()
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeVisible()

  // Unconfigured: the policy switch lives in the form above, so no clearing entry here.
  memoryShape = 'unconfigured'
  await page.reload()
  await expect(region.getByText('未启用辅助记忆')).toBeVisible()
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeHidden()

  // Degraded: the dangling reference is stated as unavailable — never worded as an empty list.
  memoryShape = 'degraded'
  await page.reload()
  await expect(region.getByText('引用的记忆策略暂不可用')).toBeVisible()
  await expect(region.getByText('清除仍然可用')).toBeVisible()
  await expect(region.getByText('还没有记忆条目')).toBeHidden()
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeVisible()
})

test('M10-F01c clearing my memory is confirmation-gated and receipts both generations', async ({ page }) => {
  await page.goto(`/settings/agents?team=${ids.team}&agent=${ids.agent}`)
  const region = page.getByRole('region', { name: '辅助记忆' })
  const clear = region.getByRole('button', { name: '清除我的辅助记忆' })
  const confirm = region.locator('input[type="checkbox"]')

  // The gate closes the command until the confirmation is checked, with its reason stated.
  await expect(clear).toBeDisabled()
  await expect(region.getByText('需要先勾选确认。')).toBeVisible()

  await confirm.check()
  await expect(clear).toBeEnabled()
  await clear.click()
  await expect(region.locator('.memory-banner')).toHaveAttribute('role', 'status')
  await expect(region.locator('.memory-banner')).toHaveText('已清除 1 条记忆 · 清空代际 4')
  await expect(region.getByText('还没有记忆条目')).toBeVisible()
  await expect(confirm).not.toBeChecked()

  // The structural replay: zero entries cleared, generation still advances.
  await confirm.check()
  await clear.click()
  await expect(region.locator('.memory-banner')).toHaveText('已清除 0 条记忆 · 清空代际 5')

  expect(deleteCommands).toHaveLength(2)
  for (const command of deleteCommands) {
    expect(command.idempotencyKey).toBeUndefined()
    expect(command.ifMatch).toBeUndefined()
  }
})

test('M10-F01c a revoked member sees the forbidden panel instead of memory contents', async ({ page }) => {
  memoryDenied = true
  await page.goto(`/settings/agents?team=${ids.team}&agent=${ids.agent}`)
  const region = page.getByRole('region', { name: '辅助记忆' })

  await expect(region.getByText('无权读取辅助记忆')).toBeVisible()
  await expect(region.getByText('reply-language')).toBeHidden()
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeHidden()
  const explain = region.getByRole('link', { name: '查看权限说明' })
  await expect(explain).toHaveAttribute('href', /requiredPermission=scope(%3A|:)read/)
})

function memoryView() {
  if (memoryShape === 'unconfigured') {
    return { policyReference: null, policy: null, degraded: null, clearanceGeneration: generation, entries: [], entryCount: 0 }
  }
  if (memoryShape === 'degraded') {
    return {
      policyReference: { policyId: POLICY_ID, version: 99 }, policy: null, degraded: 'POLICY_UNAVAILABLE',
      clearanceGeneration: generation, entries: [], entryCount: 0,
    }
  }
  return {
    policyReference: { policyId: POLICY_ID, version: 1 },
    policy: { policyId: POLICY_ID, version: 1, ttlDays: 90, maxEntriesPerOwner: 100, valueMaxBytes: 1024 },
    degraded: null, clearanceGeneration: generation,
    entries: memoryEntries, entryCount: memoryEntries.length,
  }
}

function memoryEntry() {
  return {
    memoryKey: 'reply-language', value: '简体中文', version: 2,
    expiresAt: '2026-11-08T04:00:00Z', createdAt: '2026-08-08T03:00:00Z',
    updatedAt: '2026-09-08T03:00:00Z', createdBy: ids.principal, updatedBy: ids.principal,
  }
}

function team() {
  return { id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: ids.workspace, version: 1 }
}

function project() {
  return { id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 0, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal }
}

function agentTemplate(ownershipType: 'USER' | 'TEAM', key: string) {
  return {
    publisherType: 'ORGANIZATION', publisherId: ids.organization, key, version: 1, runtimeRole: 'CODING',
    allowedOwnershipTypes: [ownershipType], allowedExecutionScopes: ['PERSONAL'],
    declaredCapabilities: ['coding', 'repository'], requiredModelCapabilities: ['TOOLS'], approvedSkillKeys: [],
    memberConfigurableSlots: ['MODEL_BINDING', 'SUPPLEMENTAL_INSTRUCTIONS', 'APPROVED_SKILLS', 'OUTPUT_PREFERENCE'],
    administratorConfigurableSlots: ['BUDGET'], creatable: true, platformManaged: false,
    contentHash: 'd'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  }
}

function agentProfile() {
  return {
    id: ids.agent, principalId: '00000000-0000-0000-0000-000000000601', displayName: 'CrewScope Coding Agent',
    principalStatus: 'ACTIVE', organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    ownershipType: 'USER', ownerMemberId: ids.member, runtimeRole: 'CODING',
    templateKey: 'coding-specialist', templateVersion: 1, defaultProfile: false, status: 'ACTIVE',
    currentConfigurationRevision: 2, currentConfigurationHash: 'a'.repeat(64),
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T04:00:00Z', version: 2,
  }
}

function agentConfiguration() {
  return {
    revision: 2, previousRevision: 1, templateKey: 'coding-specialist', templateVersion: 1,
    templateContentHash: 'b'.repeat(64),
    personalBinding: {
      executionScope: 'PERSONAL', kind: 'EXPLICIT',
      primary: { connectionId: '00000000-0000-0000-0000-000000002001', providerKey: 'deepseek', catalogEntryId: '00000000-0000-0000-0000-000000002101', modelId: 'deepseek-v4-flash', catalogRevision: 4 },
      fallback: null,
    },
    teamBinding: null, supplementalInstructions: null, approvedSkillKeys: [], memoryPolicy: null, budgetPolicy: null,
    generateOptions: { temperature: null, topP: null, maximumOutputTokens: 120000, reasoningMode: 'DEFAULT', cacheEnabled: true, parallelToolCalls: true, seed: null, maximumAttempts: 2 },
    policyPackId: 'default', policyPackVersion: 1, configurationHash: 'c'.repeat(64), createdAt: '2026-08-08T04:00:00Z',
  }
}

function selectableModels() {
  return [{
    connectionId: '00000000-0000-0000-0000-000000002001', connectionOwnerType: 'USER', connectionOwnerId: ids.principal,
    providerKey: 'deepseek', providerDisplayName: 'DeepSeek', catalogEntryId: '00000000-0000-0000-0000-000000002101',
    modelId: 'deepseek-v4-flash', catalogRevision: 4, modelDisplayName: 'deepseek-v4-flash', region: 'cn',
    contextWindowTokens: 128000, maximumOutputTokens: 120000, capabilities: ['TOOLS'],
    price: { inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'USD' },
  }]
}

function fulfillJson(route: Route, value: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function fulfillError(route: Route, status: number, code: string, message: string): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, correlationId: crypto.randomUUID(), retryable: false, currentVersion: null, details: {} }) })
}
