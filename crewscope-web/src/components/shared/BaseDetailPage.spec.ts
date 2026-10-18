import { mount } from '@vue/test-utils'
import BaseDetailPage from './BaseDetailPage.vue'
import type { DetailCommandState } from './BaseDetailPage.vue'
import type { EntryLike, Etagged, Resource } from './types'

/**
 * Shell contract tests: the domain twins keep their own specs for badges, actions and
 * wiring. Here we pin the shared frame — state gates, the conflict strip's reload handoff,
 * the audit fold's permission gate and the tombstone branch.
 */
describe('BaseDetailPage', () => {
  it('gates the first load and keeps the reload compact once the entry is mounted', async () => {
    const loading = mounted({ entryResource: res('loading', null) })
    expect(loading.text()).toContain('正在读取条目详情')

    const reload = mounted({ entryResource: res('loading', head()), slots: { identity: '<span>key-1</span>' } })
    expect(reload.text()).toContain('正在回读服务端版本')
    expect(reload.text()).toContain('key-1')
  })

  it('hands the conflict strip reload to the consumer and echoes the server version', async () => {
    const wrapper = mounted({ command: { phase: 'conflict', operation: 'save-draft', receipt: null, errorMessage: '已被他人修改', currentVersion: 7 } })
    expect(wrapper.text()).toContain('条目已被其他成员更新')
    expect(wrapper.text()).toContain('服务端当前版本 v7')

    await wrapper.findAll('button').filter(b => b.text() === '载入服务端内容')[0]!.trigger('click')
    expect(wrapper.emitted('loadServerDraft')).toHaveLength(1)
  })

  it('shows the command receipt strip on success', () => {
    const wrapper = mounted({ command: { phase: 'success', operation: 'publish', receipt: { commandId: 'abcd1234ef', committedVersion: 6 }, errorMessage: null, currentVersion: null } })
    const strip = wrapper.get('.detail-panel__receipt[role="status"]')
    expect(strip.text()).toContain('abcd1234')
    expect(strip.text()).toContain('v6')
  })

  it('folds the audit block behind canManage with the domain id label', () => {
    const manager = mounted({ canManage: true })
    expect(manager.get('.detail-panel__audit').text()).toContain('条目 ID')
    expect(manager.get('.detail-panel__audit').text()).toContain('执行 exec-9 · 第 2 次尝试')

    const member = mounted()
    expect(member.find('.detail-panel__audit').exists()).toBe(false)
  })

  it('swaps actions and tabs for the tombstone frame', () => {
    const wrapper = mounted({
      tombstone: true,
      slots: {
        identity: '<span>key-1</span>',
        tombstone: '<p data-tombstone>已删除</p>',
        versions: '<div data-versions>版本面板</div>',
      },
    })
    expect(wrapper.find('[data-tombstone]').exists()).toBe(true)
    expect(wrapper.find('[data-versions]').exists()).toBe(true)
    expect(wrapper.find('[role="tablist"]').exists()).toBe(false)
    expect(wrapper.find('[role="tabpanel"]').exists()).toBe(false)
  })

  it('wires the draft/versions tabpanels and forwards tab changes', async () => {
    const wrapper = mounted({
      slots: {
        identity: '<span>key-1</span>',
        draft: '<p data-draft>草稿面板</p>',
        versions: '<p data-versions>版本面板</p>',
      },
    })
    const draftPanel = wrapper.get('#probe-tabs-panel-draft')
    expect(draftPanel.attributes('role')).toBe('tabpanel')
    expect(draftPanel.attributes('aria-labelledby')).toBe('probe-tabs-tab-draft')
    expect(wrapper.get('[data-draft]').element.parentElement).toBe(draftPanel.element)

    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    expect(wrapper.emitted('changeTab')?.[0]).toEqual(['versions'])
  })

  it('exposes the offline action reason to the actions slot', () => {
    const wrapper = mounted({
      online: false,
      slots: {
        identity: '<span>key-1</span>',
        actions: '<p data-reason>{{ disabledReason }}</p>',
      },
    })
    expect(wrapper.get('[data-reason]').text()).toBe('离线期间管理命令保持关闭')
  })
})

const baseCommand: DetailCommandState = { phase: 'idle', operation: null, receipt: null, errorMessage: null, currentVersion: null }

function mounted(overrides: Record<string, unknown> = {}) {
  const { slots, ...props } = overrides
  return mount(BaseDetailPage, {
    props: {
      entryResource: res('ready', head()),
      command: baseCommand,
      tab: 'draft',
      online: true,
      eyebrow: 'Knowledge entry',
      title: '条目详情',
      detailLabel: '知识条目详情',
      closeLabel: '关闭条目详情',
      loadingTitle: '正在读取条目详情',
      conflictTitle: '条目已被其他成员更新',
      commandFailedTitle: '知识库命令失败',
      auditIdLabel: '条目 ID',
      tabsId: 'probe-tabs',
      ...props,
    },
    slots: slots as Record<string, string> | undefined,
  })
}

function res(phase: Resource<Etagged<EntryLike>>['phase'], value: Etagged<EntryLike> | null): Resource<Etagged<EntryLike>> {
  return { phase, value, errorMessage: phase === 'error' ? '读取失败' : null, errorStatus: null }
}

function head(): Etagged<EntryLike> {
  return {
    value: {
      id: 'id-1', latestRevision: 3, effectiveRevision: 2,
      updatedAt: '2026-10-02T01:00:00Z', createdBy: 'p-1', updatedBy: 'p-2', createdAt: '2026-10-01T00:00:00Z',
      origin: { taskExecutionId: 'exec-9', attempt: 2 },
    },
    etag: '"3"',
  }
}
