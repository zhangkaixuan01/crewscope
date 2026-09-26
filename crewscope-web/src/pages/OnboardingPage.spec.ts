import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { permissions } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import type { AgentGateway } from '../domains/agent/gateway'
import { AGENT_STORE, createAgentStore } from '../domains/agent/store'
import type { AgentSummary } from '../domains/agent/types'
import type { IdentityGateway } from '../domains/identity/gateway'
import { AUTH_STORE, createAuthStore } from '../domains/identity/store'
import type { AuthSession } from '../domains/identity/types'
import type { OnboardingGateway } from '../domains/onboarding/gateway'
import { ONBOARDING_STORE, createOnboardingStore } from '../domains/onboarding/store'
import type { OnboardingStatus } from '../domains/onboarding/types'
import { SCOPE_STORE, createScopeStore } from '../domains/scope/store'
import type { SetupGateway } from '../domains/setup/gateway'
import { SETUP_STORE, createSetupStore } from '../domains/setup/store'
import type { SetupReadinessView } from '../domains/setup/types'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import OnboardingPage from './OnboardingPage.vue'

describe('OnboardingPage', () => {
  it('creates the first Team and verifies the default Personal Agent before the workbench', async () => {
    const fixture = await mountPage()
    const input = fixture.wrapper.get<HTMLInputElement>('input[name="teamName"]')
    expect(document.activeElement).toBe(input.element)
    expect(fixture.wrapper.text()).toContain('Owner 责任与权限')

    await input.setValue('Platform Engineering')
    await fixture.wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(fixture.wrapper.text()).toContain('你的工作入口已经就绪'))

    expect(fixture.wrapper.text()).toContain('张凯旋的 Personal Agent')
    expect(fixture.onboarding.createFirstTeam).toHaveBeenCalledOnce()
    expect(fixture.agent.listAgents).toHaveBeenCalledOnce()
    await fixture.wrapper.get('button').trigger('click')
    // Conversation is a lazy route; under the full suite its module may resolve after Vitest's
    // default 1s polling window. Keep the assertion bounded while allowing the real navigation
    // promise to settle, matching the timeout used by the registration flow.
    await vi.waitFor(() => expect(fixture.router.currentRoute.value.name).toBe('conversation'), { timeout: 5_000 })
    expect(fixture.router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
    fixture.wrapper.unmount()
  })

  it('skips onboarding when the current account already has an active Team', async () => {
    const fixture = await mountPage({ initiallyComplete: true })

    await vi.waitFor(() => expect(fixture.router.currentRoute.value.name).toBe('conversation'))
    expect(fixture.router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
    expect(fixture.wrapper.find('form').exists()).toBe(false)
    expect(fixture.onboarding.createFirstTeam).not.toHaveBeenCalled()
    fixture.wrapper.unmount()
  })

  it('focuses a projection interruption and resumes without creating the Team twice', async () => {
    const fixture = await mountPage({ firstAgentReadEmpty: true })
    await fixture.wrapper.get('input[name="teamName"]').setValue('Platform Engineering')
    await fixture.wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(fixture.wrapper.get('[role="alert"]').text()).toContain('工作入口仍在同步'))

    expect(document.activeElement).toBe(fixture.wrapper.get('[role="alert"]').element)
    await fixture.wrapper.get('button').trigger('click')
    await vi.waitFor(() => expect(fixture.wrapper.text()).toContain('张凯旋的 Personal Agent'))
    expect(fixture.onboarding.createFirstTeam).toHaveBeenCalledOnce()
    expect(fixture.agent.listAgents).toHaveBeenCalledTimes(2)
    fixture.wrapper.unmount()
  })

  it('routes the Coding goal from the completion card back into setup as its origin', async () => {
    const fixture = await mountPage()
    await fixture.wrapper.get('input[name="teamName"]').setValue('Platform Engineering')
    await fixture.wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(fixture.wrapper.text()).toContain('你的工作入口已经就绪'))

    await fixture.wrapper.findAll('button').find(button => button.text().includes('先完成 Coding 配置'))!.trigger('click')
    await vi.waitFor(() => expect(fixture.router.currentRoute.value.name).toBe('setup'), { timeout: 5_000 })
    expect(fixture.router.currentRoute.value.query.from).toBe('onboarding')
    expect(fixture.router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
    fixture.wrapper.unmount()
  })

  it('warns about a missing conversation setup without blocking the entry', async () => {
    const fixture = await mountPage({ conversationSetupReady: false })
    await fixture.wrapper.get('input[name="teamName"]').setValue('Platform Engineering')
    await fixture.wrapper.get('form').trigger('submit')

    // 决策 7：readiness 只是提示，完成卡照常出现，进入对话的主动作保持可用。
    await vi.waitFor(() => expect(fixture.wrapper.text()).toContain('你的工作入口已经就绪'))
    expect(fixture.wrapper.text()).toContain('Personal Agent 尚未完成模型配置')
    expect(fixture.wrapper.text()).toContain('进入团队对话')
    expect(fixture.setup.getReadiness).toHaveBeenCalledOnce()
    fixture.wrapper.unmount()
  })
})

async function mountPage(options: {
  initiallyComplete?: boolean
  firstAgentReadEmpty?: boolean
  conversationSetupReady?: boolean
} = {}) {
  let teamCreated = Boolean(options.initiallyComplete)
  let agentReads = 0
  const identity: IdentityGateway = {
    session: vi.fn(async () => session(teamCreated)),
    login: vi.fn(),
    logout: vi.fn(),
    register: vi.fn(),
  }
  const onboarding: OnboardingGateway = {
    status: vi.fn(async (): Promise<OnboardingStatus> => teamCreated
      ? { state: 'COMPLETE', onboardingRequired: false, activeTeamCount: 1 }
      : { state: 'TEAM_REQUIRED', onboardingRequired: true, activeTeamCount: 0 }),
    createFirstTeam: vi.fn(async () => {
      teamCreated = true
      return {
        commandId: 'command-1', domainEventId: 'event-1', committedVersion: 0,
        correlationId: 'correlation-1', replayed: false,
      }
    }),
  }
  const agent: AgentGateway = {
    listAgents: vi.fn(async () => {
      agentReads += 1
      const items = options.firstAgentReadEmpty && agentReads === 1 ? [] : [personalAgent()]
      return { items, nextOffset: null }
    }),
  } as unknown as AgentGateway
  const setup: SetupGateway = {
    getReadiness: vi.fn(async (scope: { organizationId: string, teamId: string }) => readiness(scope, options.conversationSetupReady === false)),
    getConfigurationHealth: vi.fn(),
  }
  const authStore = createAuthStore(identity, { channelFactory: () => null })
  const onboardingStore = createOnboardingStore(onboarding)
  const scopeStore = createScopeStore(new FixtureScopeGateway(), authStore.principal)
  const agentStore = createAgentStore(agent)
  const setupStore = createSetupStore(setup)
  const router = createCrewScopeRouter(createMemoryHistory(), authStore)
  await router.push('/onboarding')
  await router.isReady()
  const wrapper = mount(OnboardingPage, {
    attachTo: document.body,
    global: {
      plugins: [router],
      provide: {
        [AUTH_STORE as symbol]: authStore,
        [ONBOARDING_STORE as symbol]: onboardingStore,
        [SCOPE_STORE as symbol]: scopeStore,
        [AGENT_STORE as symbol]: agentStore,
        [SETUP_STORE as symbol]: setupStore,
      },
    },
  })
  await flushPromises()
  return { wrapper, router, onboarding, agent, setup }
}

function session(hasTeam: boolean): AuthSession {
  const granted = Object.values(permissions)
  return {
    authenticated: true,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-onboarding' },
    account: {
      accountId: 'account-1', username: 'alice', displayName: 'Alice',
      platformRole: 'USER', securityVersion: 1, version: 1,
    },
    principal: { principalId: fixtureIds.principal, organizationId: fixtureIds.organization },
    teams: hasTeam ? [{
      teamId: fixtureIds.teamPlatform, name: 'Platform Engineering', memberId: fixtureIds.memberOwner,
      permissions: granted,
    }] : [],
    permissions: granted,
  }
}

function readiness(scope: { organizationId: string, teamId: string }, conversationActionRequired: boolean): SetupReadinessView {
  return {
    scope, snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', requiredReady: !conversationActionRequired,
    capabilities: [
      {
        capability: 'PERSONAL_CONVERSATION', required: true,
        status: conversationActionRequired ? 'ACTION_REQUIRED' : 'READY',
        reasonCode: conversationActionRequired ? 'PERSONAL_AGENT_CONFIGURATION_REQUIRED' : 'READY',
        canConfigure: conversationActionRequired, responsibleParty: '当前成员', actionKey: conversationActionRequired ? 'OPEN_AGENT_SETTINGS' : null,
      },
      ...(['TEAM_TASK', 'CODING_REVIEW', 'GITHUB_DRAFT_PR', 'LARK_NOTIFICATIONS', 'TEAM_OBSERVER'] as const).map(capability => ({
        capability, required: false, status: 'READY' as const, reasonCode: 'READY',
        canConfigure: false, responsibleParty: 'Team 管理员', actionKey: null,
      })),
    ],
  }
}

function personalAgent(): AgentSummary {
  return {
    id: 'agent-1', principalId: 'agent-principal-1', displayName: '张凯旋的 Personal Agent',
    principalStatus: 'ACTIVE', organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform,
    workspaceId: fixtureIds.workspacePlatform, ownershipType: 'USER', ownerMemberId: fixtureIds.memberOwner,
    runtimeRole: 'PERSONAL', templateKey: 'personal-agent', templateVersion: 1,
    defaultProfile: true, status: 'ACTIVE', currentConfigurationRevision: 1,
    currentConfigurationHash: 'a'.repeat(64), createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T01:00:00Z', version: 0,
  }
}
