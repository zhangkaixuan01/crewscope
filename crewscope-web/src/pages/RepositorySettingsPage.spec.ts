import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiClient } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import { CODING_STORE, createCodingStore } from '../domains/coding/store'
import { HttpCodingGateway } from '../domains/coding/gateway'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import RepositorySettingsPage from './RepositorySettingsPage.vue'

const principal: AuthenticatedPrincipal = {
  id: fixtureIds.principal,
  displayName: '测试管理员',
  role: 'Team Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('RepositorySettingsPage', () => {
  afterEach(() => vi.useRealTimers())

  it('renders Catalog and Binding facts through the path-free browser boundary', async () => {
    const fetcher = repositoryFetcher()
    const wrapper = await mountPage(fetcher)
    const text = wrapper.text()

    expect(text).toContain('crewscope-java')
    expect(text).toContain('1 个 RepositoryBinding')
    expect(text).toContain('Canonical Path')
    expect(text).not.toContain('/private/managed')
  })

  it('offers only available repositories that are not already bound to the WorkProject', async () => {
    const wrapper = await mountPage(repositoryFetcher(), true)
    const opener = wrapper.findAll('button').find(button => button.text().trim() === '绑定仓库')!

    await opener.trigger('click')
    await nextTick()

    const options = wrapper.findAll('option').map(option => option.text())
    expect(options).toContain('agentscope-java')
    expect(options).not.toContain('crewscope-java')
    wrapper.unmount()
  })

  it('shows a stable forbidden state when server authorization rejects Catalog access', async () => {
    const fetcher = repositoryFetcher(true)
    const wrapper = await mountPage(fetcher)

    expect(wrapper.text()).toContain('需要 Team 管理员权限')
    expect(wrapper.text()).not.toContain('server-stack')
  })

  it('moves focus into the create panel and restores the exact opener after Escape', async () => {
    const wrapper = await mountPage(repositoryFetcher(), true)
    const opener = wrapper.findAll('button').find(button => button.text().trim() === '绑定仓库')!

    await opener.trigger('click')
    await nextTick()
    expect(wrapper.get('select').element).toBe(document.activeElement)

    await wrapper.get('.create-binding').trigger('keydown', { key: 'Escape' })
    await nextTick()
    expect(wrapper.find('.create-binding').exists()).toBe(false)
    expect(document.activeElement?.getAttribute('data-repository-create-trigger')).toBe('header')
    wrapper.unmount()
  })

  it('keeps loaded facts readable and disables repository writes while offline', async () => {
    const online = vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false)
    window.dispatchEvent(new Event('offline'))
    const wrapper = await mountPage(repositoryFetcher())

    expect(wrapper.text()).toContain('仓库写操作已暂停')
    expect(wrapper.text()).toContain('crewscope-java')
    const writeButtons = wrapper.findAll('button').filter(button => ['绑定仓库', 'Preflight', '停用'].includes(button.text().trim()))
    expect(writeButtons.length).toBeGreaterThan(0)
    expect(writeButtons.every(button => button.attributes('disabled') !== undefined)).toBe(true)

    wrapper.unmount()
    online.mockRestore()
    window.dispatchEvent(new Event('online'))
  })

  it('fails closed for new bindings when a Catalog refresh loses authority', async () => {
    const wrapper = await mountPage(repositoryFetcher(false, true), true)
    const refresh = wrapper.findAll('button').find(button => button.text().trim() === '刷新')!

    await refresh.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Repository Catalog 暂时不可用')
    const createButtons = wrapper.findAll('button').filter(button => button.text().trim() === '绑定仓库')
    expect(createButtons.length).toBeGreaterThan(0)
    expect(createButtons.every(button => button.attributes('disabled') !== undefined)).toBe(true)
    wrapper.unmount()
  })

  it('creates the first WorkProject in place before loading repository settings', async () => {
    vi.useFakeTimers()
    const wrapper = await mountPage(repositoryFetcher(), true, true)

    expect(wrapper.text()).toContain('这个 Team 还没有 WorkProject')
    await wrapper.findAll('button').find(button => button.text().trim() === '创建 WorkProject')!.trigger('click')
    const inputs = document.body.querySelectorAll<HTMLInputElement>('.project-create-dialog input')
    inputs[0]!.value = 'crew'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1]!.value = 'CrewScope Platform'
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    document.body.querySelector<HTMLFormElement>('.project-create-dialog')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(wrapper.text()).toContain('1 个 RepositoryBinding')
    expect(wrapper.text()).toContain('crewscope-java')
    expect(document.body.querySelector('.project-create-dialog')).toBeNull()
    wrapper.unmount()
  })
})

async function mountPage(fetcher: typeof fetch, attachToDocument = false, withoutProject = false) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const scopeGateway = new FixtureScopeGateway()
  if (withoutProject) scopeGateway.projects[fixtureIds.teamPlatform] = []
  const scopeStore = createScopeStore(scopeGateway, principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, withoutProject ? null : fixtureIds.projectCrewScope)
  const codingStore = createCodingStore(new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher)))
  await router.push(withoutProject
    ? `/settings/repositories?team=${fixtureIds.teamPlatform}`
    : `/settings/repositories?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
  await router.isReady()
  const wrapper = mount(RepositorySettingsPage, {
    attachTo: attachToDocument ? document.body : undefined,
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [CODING_STORE as symbol]: codingStore,
      },
    },
  })
  await flushPromises()
  return wrapper
}

function repositoryFetcher(forbidden = false, failCatalogAfterFirst = false): typeof fetch {
  let catalogRequests = 0
  return vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input), 'http://crewscope.test').pathname
    if (path.endsWith('/repository-catalog')) {
      catalogRequests += 1
      if (forbidden || (failCatalogAfterFirst && catalogRequests > 1)) return json({
        code: forbidden ? 'policy_denied' : 'repository_catalog_unavailable',
        message: forbidden ? 'Repository Catalog forbidden' : 'Repository Catalog unavailable', correlationId: 'safe',
        retryable: !forbidden, currentVersion: null, details: { internal: 'server-stack' },
      }, forbidden ? 403 : 503)
      return json({ items: [
        {
          repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main',
          canonicalPath: '/private/managed/crewscope-java.git',
        },
        { repositoryKey: 'agentscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' },
      ] })
    }
    if (path.endsWith('/repository-bindings')) return json({ items: [binding()] })
    return json(binding())
  }) as unknown as typeof fetch
}

function binding() {
  return {
    id: '00000000-0000-0000-0000-000000004101', organizationId: fixtureIds.organization,
    teamId: fixtureIds.teamPlatform, workspaceId: fixtureIds.workspacePlatform,
    projectId: fixtureIds.projectCrewScope, kind: 'LOCAL_MANAGED', repositoryKey: 'crewscope-java',
    defaultBranch: 'main', status: 'ACTIVE', version: 2,
    createdAt: '2026-08-20T01:00:00Z', createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-08-20T02:00:00Z', updatedByPrincipalId: fixtureIds.principal,
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
