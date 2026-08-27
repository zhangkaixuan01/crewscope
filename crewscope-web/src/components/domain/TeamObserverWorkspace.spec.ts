import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createTeamObserverStore } from '../../domains/teamobserver/store'
import type { TeamObserverGateway } from '../../domains/teamobserver/gateway'
import TeamObserverWorkspace from './TeamObserverWorkspace.vue'

describe('TeamObserverWorkspace', () => {
  it('renders model-controlled content as text and navigates only through re-authorized evidence', async () => {
    const gateway = fixtureGateway()
    const observerStore = createTeamObserverStore(gateway)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/activity', component: { template: '<div />' } }] })
    await router.push('/activity')
    const wrapper = mount(TeamObserverWorkspace, {
      props: { scope: { organizationId: 'org-1', teamId: 'team-1' }, teamName: '平台团队', online: true, variant: 'conversation', observerStore },
      global: { plugins: [router] },
    })

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.findAll('[class="observer-section panel"]')).toHaveLength(5)

    await wrapper.get('button[aria-label^="打开进展证据"]').trigger('click')
    await flushPromises()
    expect(gateway.evidence).toHaveBeenCalledWith(
      expect.anything(), 'session-1', 'invocation-1', 0, expect.any(AbortSignal),
    )
    expect(router.currentRoute.value.fullPath).toBe('/activity?event=event-1')
  })
})

function fixtureGateway(): TeamObserverGateway {
  const summary = {
    observerProfileId: 'team-observer@1', generatedAt: '2026-08-27T08:00:00Z',
    progress: [{ section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: '<img src=x onerror=alert(1)>', evidenceIndex: 0 }],
    blockers: [], reviewBacklog: [], pendingConfirmations: [], anomalies: [],
  }
  return {
    createSession: vi.fn(async () => ({ sessionId: 'session-1', observerProfileId: 'team-observer@1', mode: 'READ_ONLY' as const, createdAt: '2026-08-27T08:00:00Z' })),
    invoke: vi.fn(async () => ({ invocationId: 'invocation-1', resumed: false, events: (async function* () { yield { invocationId: 'invocation-1', sequence: 0, occurredAt: '2026-08-27T08:00:00Z', type: 'STARTED' as const, summary: null, errorCode: null }; yield { invocationId: 'invocation-1', sequence: 1, occurredAt: '2026-08-27T08:00:01Z', type: 'SUMMARY_COMPLETED' as const, summary, errorCode: null } })() })),
    resume: vi.fn(async () => { throw new Error('not used') }),
    cancel: vi.fn(async () => ({ invocationId: 'invocation-1', cancelled: true })),
    summary: vi.fn(async () => summary),
    evidence: vi.fn(async () => ({ evidenceIndex: 0, section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: '<img src=x onerror=alert(1)>', path: '/api/v1/organizations/org-1/teams/team-1/activity/00000000-0000-4000-8000-000000000001', navigationPath: '/activity?event=event-1', authorized: true as const })),
  }
}
