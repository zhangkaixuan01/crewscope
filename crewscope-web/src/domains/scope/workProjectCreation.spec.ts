import { createMemoryHistory, createRouter } from 'vue-router'
import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../../test/scopeFixtures'
import { createScopeStore } from './store'
import { createWorkProjectCreationFlow } from './workProjectCreation'

describe('WorkProject creation flow', () => {
  it('selects the created project and removes stale object coordinates from the URL', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/work', name: 'work', component: { template: '<div />' } }],
    })
    await router.push(`/work?team=${fixtureIds.teamPlatform}&workItem=00000000-0000-0000-0000-000000000601&focus=CRW-18&view=board`)
    await router.isReady()
    const gateway = new FixtureScopeGateway()
    gateway.projects[fixtureIds.teamPlatform] = []
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    const flow = createWorkProjectCreationFlow(store, router, router.currentRoute.value)

    flow.show()
    expect(flow.open.value).toBe(true)
    await expect(flow.submit({ key: 'crew', name: 'CrewScope Platform' }, 'project-command-1')).resolves.toBe(true)

    expect(flow.open.value).toBe(false)
    expect(router.currentRoute.value.query).toEqual({
      team: fixtureIds.teamPlatform,
      project: store.state.selectedProjectId,
      view: 'board',
    })
  })

  it('keeps the dialog open and delegates safe retry state to the Scope Store after failure', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/work', name: 'work', component: { template: '<div />' } }],
    })
    await router.push(`/work?team=${fixtureIds.teamPlatform}`)
    await router.isReady()
    const gateway = new FixtureScopeGateway()
    gateway.projects[fixtureIds.teamPlatform] = []
    gateway.createWorkProject = vi.fn(async () => ({
      commandId: crypto.randomUUID(),
      domainEventId: crypto.randomUUID(),
      committedVersion: 0,
      correlationId: crypto.randomUUID(),
    }))
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    const flow = createWorkProjectCreationFlow(store, router, router.currentRoute.value)

    flow.show()
    await expect(flow.submit({ key: 'CREW', name: 'CrewScope Platform' }, 'project-command-retry')).resolves.toBe(false)

    expect(flow.open.value).toBe(true)
    expect(store.state.projectCommandRetryable).toBe(true)
    expect(store.state.projectCommandErrorMessage).toContain('最新事实暂时不可用')
    expect(router.currentRoute.value.query.project).toBeUndefined()
  })
})
