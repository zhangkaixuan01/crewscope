import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { SCOPE_STORE, createScopeStore } from '../domains/scope/store'
import { SEARCH_STORE, createSearchStore } from '../domains/search/store'
import type { SearchGateway } from '../domains/search/gateway'
import type { SearchFilter, SearchResultPage } from '../domains/search/types'
import { bootstrapPrincipal } from '../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import SearchPage from './SearchPage.vue'

/** Records every filter the page submits so retry assertions can check the committed conditions. */
class RecordingSearchGateway implements SearchGateway {
  readonly filters: SearchFilter[] = []
  private queue: Array<SearchResultPage | Error> = []
  private pending: Array<(page: SearchResultPage) => void> = []

  async search(_scope: never, filter: SearchFilter): Promise<SearchResultPage> {
    this.filters.push(structuredClone(filter))
    const next = this.queue.shift()
    if (next instanceof Error) throw next
    if (next) return structuredClone(next)
    return new Promise<SearchResultPage>(resolve => { this.pending.push(resolve) })
  }

  enqueue(...outcomes: Array<SearchResultPage | Error>): void {
    this.queue.push(...outcomes)
  }

  resolvePending(page: SearchResultPage): void { this.pending.shift()?.(structuredClone(page)) }
}

interface Harness {
  wrapper: VueWrapper
  router: Router
  gateway: RecordingSearchGateway
}

async function harness(): Promise<Harness> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/search', name: 'search', component: SearchPage },
      { path: '/onboarding', name: 'onboarding', component: { template: '<div />' } },
    ],
  })
  await router.push(`/search?team=${fixtureIds.teamPlatform}&q=发布`)
  await router.isReady()
  const scopeStore = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)
  await scopeStore.synchronize(fixtureIds.teamPlatform)
  const gateway = new RecordingSearchGateway()
  const wrapper = mount(SearchPage, {
    attachTo: document.body,
    global: {
      plugins: [router],
      provide: {
        [SCOPE_STORE as symbol]: scopeStore,
        [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal,
        [SEARCH_STORE as symbol]: createSearchStore(gateway),
      },
      stubs: { AppShell: { template: '<main><slot /></main>' } },
    },
  })
  await flushPromises()
  return { wrapper, router, gateway }
}

function page(itemIds: string[], nextCursor: string | null = null): SearchResultPage {
  return {
    items: itemIds.map(id => ({
      objectType: 'WORK_ITEM' as const, objectId: id, projectId: null, title: `结果 ${id}`,
      subtitle: null, status: 'IN_PROGRESS', updatedAt: '2026-09-26T08:00:00Z',
      route: `/work?workItem=${id}`, snippet: null,
    })),
    nextCursor,
  }
}

describe('SearchPage', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('keeps the last committed result beside the error and retries the committed conditions, not the draft', async () => {
    const { wrapper, router, gateway } = await harness()
    gateway.resolvePending(page(['one']))
    await flushPromises()
    expect(wrapper.text()).toContain('结果 one')

    await wrapper.get('input[aria-label="搜索内容"]').setValue('未提交的草稿')
    gateway.enqueue(new Error('search unavailable'))
    await router.replace({ query: { team: fixtureIds.teamPlatform, q: '第二个' } })
    await flushPromises()
    expect(wrapper.text()).toContain('搜索服务暂时不可用')
    expect(wrapper.text()).toContain('仍显示上次结果')
    expect(wrapper.text()).toContain('结果 one')

    const retry = wrapper.findAll('button').find(button => button.text() === '刷新事实')
    expect(retry).toBeTruthy()
    gateway.enqueue(page(['one', 'two']))
    await retry!.trigger('click')
    await flushPromises()
    // The retry resubmits the query string, never the draft still sitting in the input.
    expect(gateway.filters.at(-1)?.text).toBe('第二个')
    expect(wrapper.text()).toContain('结果 two')
    wrapper.unmount()
  })

  it('marks a refresh in place instead of hiding the previous result', async () => {
    const { wrapper, router, gateway } = await harness()
    gateway.resolvePending(page(['one']))
    await flushPromises()
    expect(wrapper.text()).toContain('结果 one')

    await router.replace({ query: { team: fixtureIds.teamPlatform, q: '刷新中' } })
    await flushPromises()
    expect(wrapper.text()).toContain('正在更新结果')
    expect(wrapper.text()).toContain('仍显示上次结果')
    expect(wrapper.text()).toContain('结果 one')

    gateway.resolvePending(page(['fresh']))
    await flushPromises()
    expect(wrapper.text()).toContain('结果 fresh')
    expect(wrapper.text()).not.toContain('结果 one')
    wrapper.unmount()
  })

  it('treats a failed cursor page as local: rows stay and only that page retries', async () => {
    const { wrapper, gateway } = await harness()
    gateway.resolvePending(page(['one', 'two'], 'cursor-2'))
    await flushPromises()
    expect(wrapper.text()).toContain('找到 2 条结果')

    gateway.enqueue(new Error('page unavailable'))
    await wrapper.get('.load-more button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('重试本页')
    expect(wrapper.text()).toContain('以上结果已保留')
    expect(wrapper.text()).toContain('结果 two')
    // No full-page error panel: the committed result is not presented as stale.
    expect(wrapper.text()).not.toContain('仍显示上次结果')

    gateway.enqueue(page(['three']))
    await wrapper.get('.load-more button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('结果 three')
    expect(wrapper.text()).toContain('找到 3 条结果')
    wrapper.unmount()
  })
})
