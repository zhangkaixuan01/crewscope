import { mount } from '@vue/test-utils'
import KnowledgeVersionHistory from './KnowledgeVersionHistory.vue'
import type { Etagged } from '../../domains/settings/types'
import type { KnowledgeResource, KnowledgeVersionPageResource } from '../../domains/knowledge/store'
import type { KnowledgeVersion } from '../../domains/knowledge/types'

describe('KnowledgeVersionHistory', () => {
  it('lists revisions with short hashes and marks the effective one', async () => {
    const wrapper = mounted({
      history: page([version(1), version(2)], null),
      effectiveRevision: 2,
    })

    const rows = wrapper.findAll('button.version-history__row')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('r1')
    expect(rows[0]!.text()).toContain('部署手册')
    expect(rows[0]!.text()).toContain('aaaaaaaa')
    expect(rows[1]!.text()).toContain('生效')

    await rows[0]!.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([1])
  })

  it('renders the empty history explanation before the first publish', () => {
    const wrapper = mounted({ history: page([], null) })

    expect(wrapper.text()).toContain('尚未发布生效版本')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it.each([
    ['loading', { phase: 'loading' }, '正在读取版本历史'],
    ['error', { phase: 'error', errorMessage: '服务暂时无法完成请求' }, '服务暂时无法完成请求'],
  ] as const)('renders the %s state', (_name, overrides, expected) => {
    const wrapper = mounted({ history: { ...page([], null), ...overrides } as KnowledgeVersionPageResource })
    expect(wrapper.text()).toContain(expected)
  })

  it('shows the selected version content with the authoritative index hint', () => {
    const wrapper = mounted({
      history: page([version(1, { indexStatus: 'PENDING' }), version(2, { contentHash: 'b'.repeat(64) })], 2),
      effectiveRevision: 2,
      selectedRevision: 1,
      versionResource: ready(version(1, { indexStatus: 'PENDING' })),
    })

    const content = wrapper.get('section[aria-label="版本 r1 内容"]')
    expect(content.text()).toContain('先排干连接池。')
    expect(content.text()).toContain('待索引')
    // The hint is the contract's authoritative PENDING wording — it must never read as unsaved.
    expect(content.text()).toContain('索引状态与保存状态无关：内容已保存，等待后台建立索引')
    expect(content.text()).not.toContain('未保存')
  })

  it('keeps the load-more footer until the revision cursor is exhausted', async () => {
    const paging = mounted({ history: page([version(1)], 1), effectiveRevision: 1, selectedRevision: 1, versionResource: ready(version(1)) })
    await paging.get('.version-history__more button').trigger('click')
    expect(paging.emitted('loadMore')).toHaveLength(1)

    const done = mounted({ history: page([version(1)], null), effectiveRevision: 1, selectedRevision: 1, versionResource: ready(version(1)) })
    expect(done.find('.version-history__more').exists()).toBe(false)
  })

  it('renders the effective card, or the no-effective note for retired entries', () => {
    const effective = mounted({
      history: page([version(1), version(2)], null),
      effectiveRevision: 2,
      effective: ready(version(2, { content: '生效内容' })),
    })
    const card = effective.get('section[aria-label="生效版本"]')
    expect(card.text()).toContain('r2')
    expect(card.text()).toContain('生效内容')

    const retired = mounted({
      history: page([version(1), version(2)], null),
      effectiveRevision: null,
      effective: { phase: 'empty', value: null, errorMessage: null, errorStatus: null },
    })
    expect(retired.text()).toContain('当前没有生效版本')
  })

  it('surfaces version detail loading and error states without clobbering the list', () => {
    const pending = mounted({
      history: page([version(1)], null), effectiveRevision: 1, selectedRevision: 1,
    })
    expect(pending.text()).toContain('正在读取版本内容')
    expect(pending.text()).toContain('r1')

    const failed = mounted({
      history: page([version(1)], null), effectiveRevision: 1, selectedRevision: 1,
      versionResource: { phase: 'error', value: null, errorMessage: '版本内容暂时无法读取', errorStatus: 503 },
    })
    expect(failed.text()).toContain('版本内容暂时无法读取')
  })
})

function mounted(overrides: Record<string, unknown>) {
  return mount(KnowledgeVersionHistory, {
    props: {
      history: page([version(1)], null),
      versionResource: undefined,
      effective: undefined,
      effectiveRevision: null,
      selectedRevision: null,
      online: true,
      ...overrides,
    },
  })
}

function page(items: KnowledgeVersion[], nextAfter: number | null, overrides: Partial<KnowledgeVersionPageResource> = {}): KnowledgeVersionPageResource {
  return { phase: 'ready', value: items, errorMessage: null, errorStatus: null, nextAfter, loadingMore: false, ...overrides }
}

function ready(value: KnowledgeVersion): KnowledgeResource<Etagged<KnowledgeVersion>> {
  return { phase: 'ready', value: { value, etag: `"${value.contentHash}"` }, errorMessage: null, errorStatus: null }
}

function version(revision: number, overrides: Partial<KnowledgeVersion> = {}): KnowledgeVersion {
  return {
    entryId: 'entry-1', revision, previousRevision: revision > 1 ? revision - 1 : null,
    title: '部署手册', content: '先排干连接池。', contentHash: 'a'.repeat(64), indexStatus: 'INDEXED',
    createdAt: '2026-10-02T01:00:00Z', createdBy: 'principal-1', ...overrides,
  }
}
