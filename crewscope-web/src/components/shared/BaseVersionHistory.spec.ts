import { mount } from '@vue/test-utils'
import BaseVersionHistory from './BaseVersionHistory.vue'
import type { Etagged, Resource, VersionLike, VersionPageResource } from './types'

/**
 * Shell contract tests (the domain twins keep their own specs for vocabulary and wiring):
 * rollback stays dormant until the domain opts in, slots carry the status vocabulary, and
 * the title/revision fallback follows the version shape.
 */
describe('BaseVersionHistory', () => {
  it('keeps the rollback affordance dormant without canRollback', () => {
    const wrapper = mounted({ history: page([version(1), version(2)], null), effectiveRevision: 2, canManage: true })
    expect(wrapper.text()).not.toContain('回滚到此版本')
  })

  it('emits rollback for non-effective revisions once the domain opts in', async () => {
    const wrapper = mounted({
      history: page([version(1), version(2)], null),
      effectiveRevision: 2,
      canManage: true,
      canRollback: true,
    })
    const buttons = wrapper.findAll('button').filter(button => button.text() === '回滚到此版本')
    expect(buttons).toHaveLength(1)
    await buttons[0]!.trigger('click')
    expect(wrapper.emitted('rollback')?.[0]).toEqual([1])
  })

  it('renders the published title on titled versions and falls back to 修订 rN', () => {
    const withTitle = mounted({
      history: page([version(1, { title: '部署手册' })], null),
      effectiveRevision: 1,
      selectedRevision: 1,
      versionResource: ready(version(1, { title: '部署手册' })),
    })
    expect(withTitle.findAll('.version-history__row-main')[0]!.text()).toContain('部署手册')
    expect(withTitle.get('section[aria-label="版本 r1 内容"]').text()).toContain('部署手册')

    const untitled = mounted({
      history: page([version(1, { title: undefined })], null),
      effectiveRevision: 1,
      selectedRevision: 1,
      versionResource: ready(version(1, { title: undefined })),
    })
    expect(untitled.get('section[aria-label="版本 r1 内容"] h4').text()).toBe('修订 r1')
  })

  it('carries the subject noun into the loading copy', () => {
    const wrapper = mount(BaseVersionHistory, {
      props: {
        history: { ...page([], null), phase: 'loading' },
        versionResource: undefined,
        effective: undefined,
        effectiveRevision: null,
        selectedRevision: null,
        online: true,
        subject: 'Skill',
        noEffectiveNote: '注释',
      },
    })
    expect(wrapper.text()).toContain('正在读取这个Skill的已发布版本。')
  })

  it('injects domain status vocabulary through the badge and hint slots', () => {
    const wrapper = mount(BaseVersionHistory, {
      props: {
        history: page([version(1)], null),
        effective: undefined,
        effectiveRevision: 1,
        selectedRevision: 1,
        versionResource: ready(version(1)),
        online: true,
        subject: '条目',
        noEffectiveNote: '注释',
      },
      slots: {
        'version-badge': '<span class="probe-badge">徽章</span>',
        'version-hint': '<span class="probe-hint">提示</span>',
      },
    })
    const head = wrapper.get('.version-history__content-head')
    expect(head.find('.probe-badge').exists()).toBe(true)
    expect(wrapper.find('.version-history__hint .probe-hint').exists()).toBe(true)
  })
})

interface TestVersion extends VersionLike {
  title?: string
}

function mounted(overrides: Record<string, unknown>) {
  return mount(BaseVersionHistory, {
    props: {
      history: page([version(1)], null),
      versionResource: undefined,
      effective: undefined,
      effectiveRevision: null,
      selectedRevision: null,
      online: true,
      subject: '条目',
      noEffectiveNote: '注释',
      ...overrides,
    },
  })
}

function page(items: TestVersion[], nextAfter: number | null): VersionPageResource<TestVersion> {
  return { phase: 'ready', value: items, errorMessage: null, errorStatus: null, nextAfter, loadingMore: false }
}

function ready(value: TestVersion): Resource<Etagged<TestVersion>> {
  return { phase: 'ready', value: { value, etag: `"${value.contentHash}"` }, errorMessage: null, errorStatus: null }
}

function version(revision: number, overrides: Partial<TestVersion> = {}): TestVersion {
  return { revision, title: `r${revision} 标题`, content: `内容 r${revision}`, contentHash: 'a'.repeat(64), createdAt: '2026-10-02T01:00:00Z', ...overrides }
}
