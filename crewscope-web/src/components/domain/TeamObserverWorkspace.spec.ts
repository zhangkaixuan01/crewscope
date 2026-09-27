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

  it('offers regeneration and a configuration entry after a first failure without an invocation id', async () => {
    const gateway = fixtureGateway({ invoke: vi.fn(async () => { throw new TypeError('network lost') }) })
    const observerStore = createTeamObserverStore(gateway)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/setup', name: 'setup', component: { template: '<div />' } }] })
    await router.push('/setup')
    const wrapper = mount(TeamObserverWorkspace, {
      props: { scope: { organizationId: 'org-1', teamId: 'team-1' }, teamName: '平台团队', online: true, variant: 'summary', observerStore },
      global: { plugins: [router] },
    })

    await observerStore.invoke('总结')
    await flushPromises()

    // The lost start keeps the honest copy: regenerating is a new invocation, never a retry.
    expect(wrapper.text()).toContain('请勿当作重试')
    expect(wrapper.find('.observer-setup-link').attributes('href')).toBe('/setup?team=team-1')
    const regenerate = wrapper.findAll('button').find(button => button.text().includes('重新生成摘要'))!
    await regenerate.trigger('click')
    await flushPromises()
    expect(gateway.invoke).toHaveBeenCalledTimes(2)
  })

  it('separates rereading the same invocation from regenerating a fresh one', async () => {
    const gateway = fixtureGateway()
    const observerStore = createTeamObserverStore(gateway)
    const wrapper = mount(TeamObserverWorkspace, {
      props: { scope: { organizationId: 'org-1', teamId: 'team-1' }, teamName: '平台团队', online: true, variant: 'summary', observerStore },
    })

    await observerStore.invoke('总结')
    await flushPromises()
    expect(wrapper.text()).toContain('消耗新的模型时间与用量')

    // Rereading re-reads the settled invocation; only regeneration starts a new one.
    await wrapper.findAll('button').find(button => button.text().includes('重读本次结果'))!.trigger('click')
    await flushPromises()
    expect(gateway.summary).toHaveBeenCalledTimes(1)
    expect(gateway.invoke).toHaveBeenCalledTimes(1)

    await wrapper.findAll('button').find(button => button.text().includes('按最新重新生成'))!.trigger('click')
    await flushPromises()
    expect(gateway.invoke).toHaveBeenCalledTimes(2)
  })

  it('keeps one failed evidence row local and retryable without disturbing the summary', async () => {
    let failing = true
    const gateway = fixtureGateway({
      evidence: vi.fn(async () => {
        if (failing) throw new TypeError('evidence route lost')
        // The retry result must match its summary row exactly — the store rejects drifted evidence.
        return { evidenceIndex: 0, section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: '<img src=x onerror=alert(1)>', path: '/api/v1/organizations/org-1/teams/team-1/activity/1', navigationPath: '/activity?event=event-1', authorized: true as const }
      }),
    })
    const observerStore = createTeamObserverStore(gateway)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/activity', component: { template: '<div />' } }] })
    await router.push('/activity')
    const wrapper = mount(TeamObserverWorkspace, {
      props: { scope: { organizationId: 'org-1', teamId: 'team-1' }, teamName: '平台团队', online: true, variant: 'summary', observerStore },
      global: { plugins: [router] },
    })

    await observerStore.invoke('总结')
    await flushPromises()

    await wrapper.get('button[aria-label^="打开进展证据"]').trigger('click')
    await flushPromises()
    // The failure lands beside its row; the shared status and the summary stay completed.
    expect(wrapper.get('.evidence-failure').text()).toBe('暂时无法打开这条证据')
    expect(wrapper.get('button[aria-label^="打开进展证据"]').text()).toContain('重试')
    expect(observerStore.state.phase).toBe('completed')
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')

    failing = false
    await wrapper.get('button[aria-label^="打开进展证据"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.evidence-failure').exists()).toBe(false)
    expect(router.currentRoute.value.fullPath).toBe('/activity?event=event-1')
    expect(gateway.evidence).toHaveBeenCalledTimes(2)
  })
})

function fixtureGateway(overrides: Partial<TeamObserverGateway> = {}): TeamObserverGateway {
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
    ...overrides,
  }
}
