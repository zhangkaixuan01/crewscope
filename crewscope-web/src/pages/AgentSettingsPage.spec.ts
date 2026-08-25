import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiClient } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { HttpAgentGateway } from '../domains/agent/gateway'
import { AGENT_STORE, createAgentStore } from '../domains/agent/store'
import type { AgentSummary, CurrentAgentConfiguration } from '../domains/agent/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import AgentSettingsPage from './AgentSettingsPage.vue'

const ids = {
  personal: '00000000-0000-0000-0000-000000005101',
  coding: '00000000-0000-0000-0000-000000005102',
  reviewer: '00000000-0000-0000-0000-000000005103',
  team: '00000000-0000-0000-0000-000000005104',
}

const principal: AuthenticatedPrincipal = {
  id: fixtureIds.principal,
  displayName: '测试成员',
  role: 'Member',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('AgentSettingsPage', () => {
  it('groups Personal, Specialist and Team Agents and renders public configuration facts', async () => {
    const wrapper = await mountPage('ready', ids.coding)

    expect(wrapper.text()).toContain('默认 Personal Agent')
    expect(wrapper.text()).toContain('我的 Specialist')
    expect(wrapper.text()).toContain('团队 Agent')
    expect(wrapper.text()).toContain('deepseek-v4-flash')
    expect(wrapper.text()).toContain('Fallback deepseek-chat')
    expect(wrapper.text()).toContain('coding-specialist@3')
    expect(wrapper.get(`.agent-card[href*="agent=${ids.coding}"]`).attributes('aria-current')).toBe('page')
    expect(wrapper.text()).not.toContain('sk-private')
  })

  it('keeps disabled and archived Agents discoverable with explicit lifecycle states', async () => {
    const wrapper = await mountPage('ready')

    expect(wrapper.get(`.agent-card[href*="agent=${ids.reviewer}"]`).text()).toContain('已禁用')
    expect(wrapper.get(`.agent-card[href*="agent=${ids.team}"]`).text()).toContain('已归档')
    wrapper.unmount()

    const degraded = await mountPage('configuration-error')
    expect(degraded.text()).toContain('配置摘要暂不可用')
    expect(degraded.text()).toContain('成员 Personal Agent')
  })

  it('renders stable empty, forbidden and retryable error states', async () => {
    const loading = await mountPage('loading', undefined, false, false)
    expect(loading.text()).toContain('正在加载 Agent')
    loading.unmount()

    const empty = await mountPage('empty')
    expect(empty.text()).toContain('还没有可访问的 Agent')
    empty.unmount()

    const forbidden = await mountPage('forbidden')
    expect(forbidden.text()).toContain('无权查看 Agent')
    expect(forbidden.text()).not.toContain('server-stack')
    forbidden.unmount()

    const error = await mountPage('error')
    expect(error.text()).toContain('Agent 目录暂时不可用')
    expect(error.findAll('button').some(button => button.text().includes('刷新事实'))).toBe(true)
  })

  it('preserves a safe list when a deep link points outside the visible Team scope', async () => {
    const wrapper = await mountPage('ready', '00000000-0000-0000-0000-000000009999')

    expect(wrapper.text()).toContain('不在当前 Team 可见范围内')
    expect(wrapper.text()).toContain('成员 Personal Agent')
    expect(wrapper.find('.agent-card[aria-current="page"]').exists()).toBe(false)
  })

  it('exposes every Agent as a keyboard-focusable deep link', async () => {
    const wrapper = await mountPage('ready', undefined, true)
    const link = wrapper.get(`.agent-card[href*="agent=${ids.personal}"]`)

    ;(link.element as HTMLElement).focus()
    expect(document.activeElement).toBe(link.element)
    expect(link.element.tagName).toBe('A')
    expect(link.attributes('href')).toContain(`configurationRevision=2`)
    wrapper.unmount()
  })
})

type FixtureMode = 'ready' | 'empty' | 'forbidden' | 'error' | 'loading' | 'configuration-error'

async function mountPage(mode: FixtureMode, selectedAgentId?: string, attachToDocument = false, settle = true) {
  const router = createCrewScopeRouter(createMemoryHistory(), principal)
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const agentStore = createAgentStore(new HttpAgentGateway(new CrewScopeApiClient('/api/v1', agentFetcher(mode))))
  const selected = selectedAgentId ? `&agent=${selectedAgentId}` : ''
  await router.push(`/settings/agents?team=${fixtureIds.teamPlatform}${selected}`)
  await router.isReady()
  const wrapper = mount(AgentSettingsPage, {
    attachTo: attachToDocument ? document.body : undefined,
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [AGENT_STORE as symbol]: agentStore,
      },
    },
  })
  if (settle) await flushPromises()
  else await nextTick()
  return wrapper
}

function agentFetcher(mode: FixtureMode): typeof fetch {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://crewscope.test')
    if (url.pathname.endsWith('/agent-profiles')) {
      if (mode === 'loading') return new Promise<Response>(() => {})
      if (mode === 'empty') return json({ items: [] })
      if (mode === 'forbidden') return apiError(403, 'policy_denied', 'Agent directory forbidden')
      if (mode === 'error') return apiError(503, 'agent_directory_unavailable', 'Agent 目录暂时不可用')
      return json({ items: agents() })
    }
    const configurationMatch = url.pathname.match(/\/agent-profiles\/([^/]+)\/configurations\/current$/)
    if (configurationMatch) {
      if (mode === 'configuration-error' && configurationMatch[1] === ids.coding) {
        return apiError(503, 'agent_configuration_unavailable', 'Agent Configuration unavailable')
      }
      return json(configuration(configurationMatch[1]!), 200, { ETag: '"2"' })
    }
    return apiError(404, 'fixture_not_found', 'Fixture route not found')
  }) as unknown as typeof fetch
}

function agents(): AgentSummary[] {
  return [
    agent(ids.personal, '成员 Personal Agent', 'USER', 'PERSONAL', 'personal-assistant', true, 'ACTIVE'),
    agent(ids.coding, '代码实现 Agent', 'USER', 'CODING', 'coding-specialist', false, 'ACTIVE', 3),
    agent(ids.reviewer, '质量审查 Agent', 'USER', 'REVIEWER', 'reviewer-specialist', false, 'DISABLED'),
    agent(ids.team, '团队交付 Agent', 'TEAM', 'ORCHESTRATOR', 'team-orchestrator', false, 'ARCHIVED'),
  ]
}

function agent(
  id: string,
  displayName: string,
  ownershipType: AgentSummary['ownershipType'],
  runtimeRole: string,
  templateKey: string,
  defaultProfile: boolean,
  status: string,
  templateVersion = 1,
): AgentSummary {
  return {
    id, principalId: id.replace('51', '61'), displayName, principalStatus: status,
    organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform,
    workspaceId: fixtureIds.workspacePlatform, ownershipType,
    ownerMemberId: ownershipType === 'USER' ? fixtureIds.memberOwner : null,
    runtimeRole, templateKey, templateVersion, defaultProfile, status,
    currentConfigurationRevision: 2, currentConfigurationHash: 'a'.repeat(64),
    createdAt: '2026-08-25T01:00:00Z', updatedAt: '2026-08-25T02:00:00Z', version: 4,
  }
}

function configuration(profileId: string): CurrentAgentConfiguration & { apiKey?: string } {
  const teamOwned = profileId === ids.team
  return {
    revision: 2, previousRevision: 1, templateKey: 'fixture-template', templateVersion: 1,
    templateContentHash: 'b'.repeat(64),
    personalBinding: teamOwned ? null : binding('PERSONAL'),
    teamBinding: profileId === ids.personal ? null : binding('TEAM'),
    supplementalInstructions: null, approvedSkillKeys: [], memoryPolicy: null, budgetPolicy: null,
    generateOptions: {
      temperature: null, topP: null, maximumOutputTokens: 120000, reasoningMode: 'AUTO',
      cacheEnabled: true, parallelToolCalls: true, seed: null, maximumAttempts: 2,
    },
    policyPackId: 'default', policyPackVersion: 1, configurationHash: 'c'.repeat(64),
    createdAt: '2026-08-25T02:00:00Z',
    apiKey: 'sk-private',
  }
}

function binding(executionScope: 'PERSONAL' | 'TEAM') {
  return {
    executionScope, kind: 'EXPLICIT',
    primary: {
      connectionId: '00000000-0000-0000-0000-000000005201', providerKey: 'deepseek',
      catalogEntryId: '00000000-0000-0000-0000-000000005301', modelId: 'deepseek-v4-flash', catalogRevision: 4,
    },
    fallback: {
      connectionId: '00000000-0000-0000-0000-000000005202', providerKey: 'deepseek',
      catalogEntryId: '00000000-0000-0000-0000-000000005302', modelId: 'deepseek-chat', catalogRevision: 3,
    },
  }
}

function apiError(status: number, code: string, message: string): Response {
  return json({ code, message, correlationId: 'safe', retryable: status >= 500, currentVersion: null, details: { internal: 'server-stack' } }, status)
}

function json(body: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}
