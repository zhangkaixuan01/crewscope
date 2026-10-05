import { mount } from '@vue/test-utils'
import KnowledgeEntryDetail from './KnowledgeEntryDetail.vue'
import type { Etagged } from '../../domains/settings/types'
import type { KnowledgeCommandState, KnowledgeResource, KnowledgeVersionPageResource } from '../../domains/knowledge/store'
import type { KnowledgeEntrySummary, KnowledgeVersion } from '../../domains/knowledge/types'

describe('KnowledgeEntryDetail', () => {
  it('renders head facts and the audit block for managers', () => {
    const wrapper = mounted()

    expect(wrapper.get('aside[aria-label="知识条目详情"]')).toBeTruthy()
    expect(wrapper.text()).toContain('deploy-runbook')
    expect(wrapper.text()).toContain('操作手册')
    expect(wrapper.text()).toContain('已发布')
    expect(wrapper.text()).toContain('生效修订')
    expect(wrapper.text()).toContain('r2')
    expect(wrapper.text()).toContain('审计信息')
    expect(wrapper.text()).toContain('principal-creator')
  })

  it('hides the audit block from readers without knowledge:manage', () => {
    const wrapper = mounted({ canManage: false })

    expect(wrapper.text()).not.toContain('审计信息')
    expect(wrapper.text()).not.toContain('principal-creator')
    expect(wrapper.text()).toContain('deploy-runbook')
  })

  it('renders the loading and error states with retry', () => {
    const loading = mounted({ entryResource: resource<Etagged<KnowledgeEntrySummary>>({ phase: 'loading' }) })
    expect(loading.text()).toContain('正在读取条目详情')

    const failed = mounted({ entryResource: resource<Etagged<KnowledgeEntrySummary>>({ phase: 'error', errorMessage: '服务暂时无法完成请求' }) })
    expect(failed.text()).toContain('服务暂时无法完成请求')
  })

  it('shows the tombstone rest state without any command affordances', () => {
    const wrapper = mounted({ entryResource: detail(entry({ status: 'DELETED' })) })

    expect(wrapper.text()).toContain('此条目已删除')
    expect(wrapper.text()).toContain('删除墓碑')
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.find('[role="tablist"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('r1')
  })

  it('surfaces the conflict panel with the server head version and the reload action', async () => {
    const wrapper = mounted({
      command: command({ phase: 'conflict', operation: 'save-draft', errorMessage: '其他成员已更新此条目', currentVersion: 7 }),
    })

    expect(wrapper.text()).toContain('条目已被其他成员更新')
    expect(wrapper.text()).toContain('其他成员已更新此条目')
    expect(wrapper.text()).toContain('服务端当前版本 v7')

    // The form is clean, so the discard guard passes without a dialog and the server draft lands.
    await wrapper.findAll('button').find(button => button.text() === '载入服务端内容')!.trigger('click')
    await Promise.resolve()
    await wrapper.vm.$nextTick()

    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('服务端草稿标题')
  })

  it('keeps the editor untouched when the discard guard is declined', async () => {
    const wrapper = mounted({
      command: command({ phase: 'conflict', operation: 'save-draft', errorMessage: '其他成员已更新此条目', currentVersion: 7 }),
    })
    await wrapper.get('input[type="text"]').setValue('成员正在编辑的标题')

    // confirmDiscard is still pending on the shared confirm surface — the editor must not change yet.
    wrapper.findAll('button').find(button => button.text() === '载入服务端内容')!.trigger('click')
    await wrapper.vm.$nextTick()

    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('成员正在编辑的标题')
  })

  it('forwards save, publish, retire and delete through the toolbar and editor', async () => {
    const wrapper = mounted()

    await wrapper.get('input[type="text"]').setValue('修订标题')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ title: '修订标题' })

    const actions = wrapper.get('nav[aria-label^="条目 deploy-runbook"]')
    await actions.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('publish')).toHaveLength(1)
    expect(wrapper.text()).toContain('废弃条目')
  })

  it('hides the toolbar from readers and blocks offline members with a spoken reason', () => {
    const reader = mounted({ canManage: false })
    expect(reader.find('nav').exists()).toBe(false)

    const offline = mounted({ online: false })
    expect(offline.get('nav button[disabled]').attributes('title')).toBe('离线期间管理命令保持关闭')
  })

  it('shows the command receipt banner after a confirmed command', () => {
    const wrapper = mounted({
      command: command({
        phase: 'success',
        operation: 'publish',
        receipt: { commandId: '00000000-0000-0000-0000-00000000abcd', domainEventId: 'e', committedVersion: 6, correlationId: 'c' },
      }),
    })

    expect(wrapper.get('.knowledge-detail__receipt[role="status"]').text()).toContain('00000000')
    expect(wrapper.get('.knowledge-detail__receipt[role="status"]').text()).toContain('v6')
  })

  it('switches tabs and forwards revision selection to the page', async () => {
    const wrapper = mounted({ tab: 'versions' })

    await wrapper.findAll('[role="tab"]')[0]!.trigger('click')
    expect(wrapper.emitted('changeTab')?.[0]).toEqual(['draft'])

    await wrapper.findAll('button.version-history__row')[0]!.trigger('click')
    expect(wrapper.emitted('selectRevision')?.[0]).toEqual([1])
  })

  it('closes through the aside header action', async () => {
    const wrapper = mounted()
    await wrapper.get('button[aria-label="关闭条目详情"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})

function mounted(overrides: Record<string, unknown> = {}) {
  return mount(KnowledgeEntryDetail, {
    props: {
      entryResource: detail(),
      command: null,
      history: page(),
      versionResource: undefined,
      effective: undefined,
      effectiveRevision: 2,
      selectedRevision: null,
      tab: 'draft',
      scope: { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' },
      canManage: true,
      online: true,
      ...overrides,
    },
  })
}

function detail(override: KnowledgeEntrySummary = entry()): KnowledgeResource<Etagged<KnowledgeEntrySummary>> {
  return { phase: 'ready', value: { value: override, etag: `"${override.version}"` }, errorMessage: null, errorStatus: null }
}

function resource<T>(overrides: Partial<KnowledgeResource<T>>): KnowledgeResource<T> {
  return { phase: 'ready', value: null, errorMessage: null, errorStatus: null, ...overrides }
}

function page(): KnowledgeVersionPageResource {
  return { phase: 'ready', value: [version(1)], errorMessage: null, errorStatus: null, nextAfter: null, loadingMore: false }
}

function command(overrides: Partial<KnowledgeCommandState>): KnowledgeCommandState {
  return {
    phase: 'idle', operation: null, resourceId: 'entry-1', receipt: null,
    errorMessage: null, errorStatus: null, currentVersion: null, retryable: false, ...overrides,
  }
}

function entry(overrides: Partial<KnowledgeEntrySummary> = {}): KnowledgeEntrySummary {
  return {
    id: 'entry-1', entryKey: 'deploy-runbook', category: 'RUNBOOK', status: 'PUBLISHED', indexStatus: 'INDEXED',
    effectiveRevision: 2, latestRevision: 2, draft: { title: '服务端草稿标题', content: '服务端草稿内容' }, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: 'principal-creator', updatedBy: 'principal-editor', origin: null, ...overrides,
  }
}

function version(revision: number): KnowledgeVersion {
  return {
    entryId: 'entry-1', revision, previousRevision: null, title: '部署手册', content: '先排干连接池。',
    contentHash: 'a'.repeat(64), indexStatus: 'INDEXED', createdAt: '2026-10-02T01:00:00Z', createdBy: 'principal-1',
  }
}
