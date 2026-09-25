import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { bootstrapPrincipal, fixtureAuthStore } from '../test/authFixtures'
import { fixtureIds, FixtureScopeGateway } from '../test/scopeFixtures'
import { githubBinding, githubConnection, githubHealth, githubRepository } from '../test/deliveryFixtures'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { HttpDeliveryGateway } from '../domains/delivery/gateway'
import type { GitHubConnection, GitHubRepository, GitHubRepositoryImportJob } from '../domains/delivery/types'
import { useConfirm } from '../composables/useConfirm'
import GitHubSettingsPage from './GitHubSettingsPage.vue'

const a = githubConnection({ id: 'connection-a', externalAccountLogin: 'account-a', teamId: fixtureIds.teamPlatform })
const b = githubConnection({ id: 'connection-b', externalAccountLogin: 'account-b', teamId: fixtureIds.teamPlatform })
const api = HttpDeliveryGateway.prototype
const otherProject = '00000000-0000-0000-0000-000000000403'

describe('GitHub settings request isolation', () => {
  beforeEach(() => {
    vi.spyOn(api, 'listConnections').mockImplementation(async (_scope, owner) => owner === 'USER' ? [] : [a, b])
    vi.spyOn(api, 'listRepositories').mockImplementation(async (_scope, id) => [githubRepository({ fullName: `${id}/repo` })])
    vi.spyOn(api, 'listBindings').mockImplementation(async (_scope, id) => [githubBinding({ connectionId: id, grantId: 'grant', grantVersion: 1 })])
    vi.spyOn(api, 'health').mockResolvedValue(githubHealth())
    vi.spyOn(api, 'createRepositoryImport').mockResolvedValue(job())
    vi.spyOn(api, 'getRepositoryImport').mockResolvedValue(job({ status: 'READY', progressPercent: 100 }))
    vi.spyOn(api, 'cancelRepositoryImport').mockResolvedValue(job({ status: 'CANCELLED' }))
    vi.spyOn(api, 'retryRepositoryImport').mockResolvedValue(job())
    vi.spyOn(api, 'revokeConnection').mockResolvedValue({ commandId: 'c', domainEventId: 'e', committedVersion: 4, correlationId: 'r' })
  })
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); useConfirm().settle(false) })

  it('ignores a previous Team list even if the gateway ignores AbortSignal', async () => {
    const old = deferred<GitHubConnection[]>()
    vi.mocked(api.listConnections).mockImplementation(async (scope, owner) => {
      if (owner === 'USER') return []
      return scope.teamId === fixtureIds.teamPlatform ? old.promise : [{ ...b, externalAccountLogin: 'security-current' }]
    })
    const { wrapper, router } = await mountPage()
    const oldSignal = vi.mocked(api.listConnections).mock.calls[0]![2]
    await router.replace({ query: { team: fixtureIds.teamSecurity } })
    await flushPromises()
    old.resolve([{ ...a, externalAccountLogin: 'stale-platform' }])
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('security-current')
    expect(wrapper.text()).not.toContain('stale-platform')
    wrapper.unmount()
  })

  it('keeps catalog/health/bindings attached to the selected connection on late detail response', async () => {
    const old = deferred<GitHubRepository[]>()
    vi.mocked(api.listRepositories).mockImplementation(async (_scope, id) => id === a.id ? old.promise : [githubRepository({ fullName: 'b/current' })])
    const { wrapper } = await mountPage()
    await wrapper.findAll('.connection-card')[1]!.trigger('click')
    await flushPromises()
    old.resolve([githubRepository({ fullName: 'a/stale' })])
    await flushPromises()
    expect(wrapper.get('.repository-list').text()).toContain('b/current')
    expect(wrapper.text()).not.toContain('a/stale')
    wrapper.unmount()
  })

  it('does not let late verification switch selection back to the original connection', async () => {
    const old = deferred<GitHubConnection>()
    vi.spyOn(api, 'verifyConnection').mockReturnValue(old.promise)
    const { wrapper } = await mountPage()
    await button(wrapper, '验证 Connection').trigger('click')
    await wrapper.findAll('.connection-card')[1]!.trigger('click')
    await flushPromises()
    old.resolve({ ...a, version: 4 })
    await flushPromises()
    expect(wrapper.get('.connection-card.selected').text()).toContain('account-b')
    expect(wrapper.get('.repository-list').text()).toContain('connection-b/repo')
    wrapper.unmount()
  })

  it('does not dispatch a revoke when the target changes while confirmation is open', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '撤销').trigger('click')
    expect(useConfirm().request.value?.description).toContain('account-a')
    await wrapper.findAll('.connection-card')[1]!.trigger('click')
    await flushPromises()
    useConfirm().settle(true)
    await flushPromises()
    expect(api.revokeConnection).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('never follows a late import creation into the newly selected project', async () => {
    const old = deferred<GitHubRepositoryImportJob>()
    vi.mocked(api.createRepositoryImport).mockReturnValue(old.promise)
    const { wrapper, scopeStore, router } = await mountPage()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await button(wrapper, '导入', true).trigger('click')
    await button(wrapper, '开始导入').trigger('click')
    await router.replace({ query: { team: fixtureIds.teamPlatform, project: otherProject } })
    await flushPromises()
    expect(scopeStore.state.selectedProjectId).toBe(otherProject)
    old.resolve(job())
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2500)
    expect(api.createRepositoryImport).toHaveBeenCalledWith(expect.objectContaining({ teamId: fixtureIds.teamPlatform }), fixtureIds.projectCrewScope, expect.anything(), expect.any(String))
    expect(api.getRepositoryImport).not.toHaveBeenCalled()
    expect(wrapper.find('.import-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('drops in-flight poll results after project switch and never schedules a new-scope poll', async () => {
    const old = deferred<GitHubRepositoryImportJob>()
    vi.mocked(api.getRepositoryImport).mockReturnValue(old.promise)
    const { wrapper, scopeStore, router } = await mountPage()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await button(wrapper, '导入', true).trigger('click')
    await button(wrapper, '开始导入').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1200)
    expect(api.getRepositoryImport).toHaveBeenCalledTimes(1)
    const signal = vi.mocked(api.getRepositoryImport).mock.calls[0]![3]
    await router.replace({ query: { team: fixtureIds.teamPlatform, project: otherProject } })
    await flushPromises()
    expect(scopeStore.state.selectedProjectId).toBe(otherProject)
    old.resolve(job())
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2500)
    expect(signal?.aborted).toBe(true)
    expect(api.getRepositoryImport).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.import-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the same job after a failed status read and recovers by reading, not creating', async () => {
    vi.mocked(api.getRepositoryImport).mockRejectedValueOnce(new Error('offline'))
    const { wrapper } = await mountPage()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await button(wrapper, '导入', true).trigger('click')
    await button(wrapper, '开始导入').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1200)
    expect(wrapper.text()).toContain('原任务未被判定失败')
    const keyInput = wrapper.get('.import-panel input')
    expect(keyInput.attributes('disabled')).toBeDefined()
    expect(wrapper.get(`[id="${keyInput.attributes('aria-describedby')}"]`).text()).toContain('仓库代号固定在原任务中')
    expect(wrapper.findAll('button').some(item => item.text().includes('开始导入'))).toBe(false)
    await button(wrapper, '重新读取状态').trigger('click')
    await flushPromises()
    expect(wrapper.get('.import-progress').text()).toContain('导入完成')
    expect(api.createRepositoryImport).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.getRepositoryImport).mock.calls.map(call => call.slice(0, 3)))
      .toEqual(Array(2).fill([{ organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }, fixtureIds.projectCrewScope, 'job-original']))
    wrapper.unmount()
  })

  it('does not reuse a binding scoped to another repository', async () => {
    vi.mocked(api.listBindings).mockResolvedValue([
      githubBinding({ connectionId: a.id, grantId: 'grant', grantVersion: 1,
        repositoryAllowlist: ['github:repository:999'] }),
    ])
    const bind = vi.spyOn(api, 'bindConnection').mockResolvedValue({
      commandId: 'bind-command', domainEventId: 'bind-event', committedVersion: 2, correlationId: 'bind-correlation',
    })
    const { wrapper } = await mountPage()
    await button(wrapper, '导入', true).trigger('click')
    await button(wrapper, '开始导入').trigger('click')
    await flushPromises()
    expect(bind).toHaveBeenCalledWith(
      expect.objectContaining({ teamId: fixtureIds.teamPlatform }), a, ['101'],
    )
    expect(api.createRepositoryImport).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前仓库尚未建立可用绑定')
    wrapper.unmount()
  })

  it('ignores an in-flight poll after unmount without scheduling another read', async () => {
    const old = deferred<GitHubRepositoryImportJob>()
    vi.mocked(api.getRepositoryImport).mockReturnValue(old.promise)
    const { wrapper } = await mountPage()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await button(wrapper, '导入', true).trigger('click')
    await button(wrapper, '开始导入').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1200)
    const signal = vi.mocked(api.getRepositoryImport).mock.calls[0]![3]
    wrapper.unmount()
    old.resolve(job())
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2500)
    expect(signal?.aborted).toBe(true)
    expect(api.getRepositoryImport).toHaveBeenCalledTimes(1)
  })

  it('stops polling on unmount and displays CANCELLED as a terminal state', async () => {
    const { wrapper } = await mountPage()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await button(wrapper, '导入', true).trigger('click')
    await button(wrapper, '开始导入').trigger('click')
    await flushPromises()
    await button(wrapper, '取消导入').trigger('click')
    await flushPromises()
    expect(wrapper.get('.import-progress').text()).toContain('导入已取消')
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(2500)
    expect(api.getRepositoryImport).not.toHaveBeenCalled()
  })

  it('clears credential inputs on identity change even when the Team ID stays the same', async () => {
    const { wrapper, principal } = await mountPage()
    await button(wrapper, '创建 Connection').trigger('click')
    await wrapper.get('input[type="password"]').setValue('test-only-secret')
    principal.id = fixtureIds.secondPrincipal
    await flushPromises()
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    await button(wrapper, '创建 Connection').trigger('click')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('does not close or lock a reopened form when its previous creation completes late', async () => {
    const old = deferred<Awaited<ReturnType<typeof api.createConnection>>>()
    vi.spyOn(api, 'createConnection').mockReturnValue(old.promise)
    const { wrapper } = await mountPage()
    await button(wrapper, '创建 Connection').trigger('click')
    await wrapper.get('.connection-form input[maxlength="100"]').setValue('original-account')
    await wrapper.get('input[type="password"]').setValue('original-test-token')
    await wrapper.get('.connection-form textarea').setValue('owner/repo')
    await wrapper.get('.connection-form').trigger('submit')
    await button(wrapper, '创建 Connection').trigger('click')
    await button(wrapper, '创建 Connection').trigger('click')
    await wrapper.get('input[type="password"]').setValue('new-test-token')
    expect(wrapper.get('.connection-form button[type="submit"]').attributes('disabled')).toBeUndefined()
    old.resolve({ commandId: 'c', domainEventId: 'e', committedVersion: 1, correlationId: 'r' })
    await flushPromises()
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('new-test-token')
    expect(wrapper.text()).not.toContain('GitHub Connection 已创建。')
    wrapper.unmount()
  })
})

async function mountPage() {
  const principal = reactive({ ...bootstrapPrincipal, permissions: new Set(bootstrapPrincipal.permissions) })
  const scopes = new FixtureScopeGateway()
  scopes.projects[fixtureIds.teamPlatform]!.push({ ...scopes.projects[fixtureIds.teamPlatform]![0]!, id: otherProject, key: 'NEXT', name: 'Next project' })
  const scopeStore = createScopeStore(scopes, principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  await router.push(`/settings/integrations/github?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
  await router.isReady()
  const wrapper = mount(GitHubSettingsPage, { global: { plugins: [router], provide: {
    [AUTH_PRINCIPAL as symbol]: principal, [SCOPE_STORE as symbol]: scopeStore,
  } } })
  await flushPromises()
  return { wrapper, scopeStore, principal, router }
}
function button(wrapper: ReturnType<typeof mount>, label: string, exact = false) {
  return wrapper.findAll('button').find(item => exact ? item.text().trim() === label : item.text().includes(label))!
}
function job(overrides: Partial<GitHubRepositoryImportJob> = {}): GitHubRepositoryImportJob {
  return { id: 'job-original', organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform,
    projectId: fixtureIds.projectCrewScope, connectionId: a.id, connectionVersion: 3,
    externalRepositoryId: '101', repositoryFullName: 'connection-a/repo', repositoryKey: 'repo', defaultBranch: 'main',
    status: 'REQUESTED', progressPercent: 0, attempt: 1, failureCode: null, bindingId: null,
    createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z', ...overrides }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
