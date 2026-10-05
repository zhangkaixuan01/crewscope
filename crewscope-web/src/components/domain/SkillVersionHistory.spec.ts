import { mount } from '@vue/test-utils'
import SkillVersionHistory from './SkillVersionHistory.vue'
import type { Etagged } from '../../domains/settings/types'
import type { SkillResource, SkillVersionPageResource } from '../../domains/skill/store'
import type { SkillVersion } from '../../domains/skill/types'

describe('SkillVersionHistory', () => {
  it('lists revisions with short hashes and marks the effective one', async () => {
    const wrapper = mounted({
      history: page([version(1), version(2)], null),
      effectiveRevision: 2,
    })

    const rows = wrapper.findAll('.version-history__row-main')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('r1')
    expect(rows[0]!.text()).toContain('aaaaaaaa')
    expect(rows[1]!.text()).toContain('生效')

    await rows[0]!.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([1])
  })

  it('offers inline rollback for managers on non-effective revisions only', async () => {
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

  it('hides rollback without permission, offline, or without a live effective pointer', () => {
    const base = {
      history: page([version(1), version(2)], null),
      effectiveRevision: 2,
      selectedRevision: null,
    }
    const noPermission = mounted({ ...base, canManage: false, canRollback: true })
    const noPointer = mounted({ ...base, canManage: true, canRollback: false, effectiveRevision: null })
    const offline = mounted({ ...base, canManage: true, canRollback: true, online: false })

    for (const view of [noPermission, noPointer, offline]) {
      expect(view.text()).not.toContain('回滚到此版本')
    }
  })

  it('renders adjacent rows with identical hashes as an unremarkable legal shape', () => {
    // Rollback appends a new revision whose contentHash equals the target's (contract §3) —
    // the history must not flag, group or hide either row.
    const wrapper = mounted({
      history: page([version(1), version(2), version(3, { previousRevision: 2, contentHash: 'a'.repeat(64) })], null),
      effectiveRevision: 3,
    })

    const rows = wrapper.findAll('.version-history__row-main')
    expect(rows).toHaveLength(3)
    expect(wrapper.text()).not.toContain('异常')
    expect(wrapper.text()).not.toContain('重复')
  })

  it('renders the empty history explanation before the first publish', () => {
    const wrapper = mounted({ history: page([], null) })

    expect(wrapper.text()).toContain('尚未发布生效版本')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('shows the selected version content and the effective card, or the no-effective note', () => {
    const selected = mounted({
      history: page([version(1), version(2)], null),
      effectiveRevision: 2,
      selectedRevision: 1,
      versionResource: ready(version(1)),
      effective: ready(version(2, { content: '生效内容' })),
    })

    expect(selected.get('section[aria-label="版本 r1 内容"]').text()).toContain(document(1))
    expect(selected.get('section[aria-label="生效版本"]').text()).toContain('生效内容')

    const disabled = mounted({
      history: page([version(1)], null),
      effectiveRevision: null,
      effective: { phase: 'empty', value: null, errorMessage: null, errorStatus: null },
    })
    expect(disabled.text()).toContain('当前没有生效版本')
  })

  it('keeps the load-more footer until the revision cursor is exhausted', async () => {
    const paging = mounted({ history: page([version(1)], 1), effectiveRevision: 1, selectedRevision: 1, versionResource: ready(version(1)) })
    await paging.get('.version-history__more button').trigger('click')
    expect(paging.emitted('loadMore')).toHaveLength(1)

    const done = mounted({ history: page([version(1)], null), effectiveRevision: 1, selectedRevision: 1, versionResource: ready(version(1)) })
    expect(done.find('.version-history__more').exists()).toBe(false)
  })
})

function mounted(overrides: Record<string, unknown>) {
  return mount(SkillVersionHistory, {
    props: {
      history: page([version(1)], null),
      versionResource: undefined,
      effective: undefined,
      effectiveRevision: null,
      selectedRevision: null,
      online: true,
      canManage: false,
      canRollback: false,
      rollingBack: false,
      ...overrides,
    },
  })
}

function page(items: SkillVersion[], nextAfter: number | null, overrides: Partial<SkillVersionPageResource> = {}): SkillVersionPageResource {
  return { phase: 'ready', value: items, errorMessage: null, errorStatus: null, nextAfter, loadingMore: false, ...overrides }
}

function ready(value: SkillVersion): SkillResource<Etagged<SkillVersion>> {
  return { phase: 'ready', value: { value, etag: `"${value.contentHash}"` }, errorMessage: null, errorStatus: null }
}

function version(revision: number, overrides: Partial<SkillVersion> = {}): SkillVersion {
  return {
    skillId: '00000000-0000-0000-0000-000000008101', revision,
    previousRevision: revision > 1 ? revision - 1 : null,
    content: document(revision), contentHash: revision === 2 ? 'b'.repeat(64) : 'a'.repeat(64),
    createdAt: '2026-10-02T01:00:00Z', createdBy: 'principal-1', ...overrides,
  }
}

function document(revision: number) {
  return `---\nname: deploy-runbook-v2\ndescription: Marker r${revision}.\n---\n\nBody of the document.`
}
