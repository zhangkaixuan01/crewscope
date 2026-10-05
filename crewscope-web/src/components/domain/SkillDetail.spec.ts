import { mount } from '@vue/test-utils'
import SkillDetail from './SkillDetail.vue'
import type { SkillDraftScope } from './SkillDraftEditor.vue'
import type { SkillCommandState, SkillResource, SkillVersionPageResource } from '../../domains/skill/store'
import type { Etagged } from '../../domains/settings/types'
import type { SkillSummary, SkillVersion } from '../../domains/skill/types'

const scope: SkillDraftScope = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' }

describe('SkillDetail', () => {
  it('renders the head facts with revision pointers', () => {
    const wrapper = mounted({})

    expect(wrapper.text()).toContain('deploy-runbook-v2')
    expect(wrapper.text()).toContain('已发布')
    expect(wrapper.text()).toContain('r1')
    expect(wrapper.text()).toContain('生效修订')
  })

  it('keeps the disable reason and the last-effective fact visible for every member', () => {
    const wrapper = mounted({
      skillResource: ready(skill({ status: 'DISABLED', disableReason: '弃用，改用 v3', effectiveRevision: 2, draft: null })),
      effectiveRevision: null,
      canManage: false,
    })

    const note = wrapper.get('.skill-detail__disabled')
    expect(note.text()).toContain('弃用，改用 v3')
    expect(note.text()).toContain('历史版本全部保留')
    expect(note.text()).toContain('最后生效修订为 r2')
    // Read side stays open: the versions tab and audit stay reachable without skill:manage.
    expect(wrapper.text()).not.toContain('禁用 Skill')
  })

  it('gates publish on a live draft and disable on the PUBLISHED status', () => {
    const draftless = mounted({ skillResource: ready(skill({ draft: null })) })
    expect(draftless.get('.skill-detail__actions button').attributes('disabled')).toBeDefined()

    const draftHead = mounted({})
    expect(draftHead.get('.skill-detail__actions button').attributes('disabled')).toBeUndefined()
    expect(draftHead.text()).toContain('禁用 Skill')

    const draftOnly = mounted({ skillResource: ready(skill({ status: 'DRAFT' })) })
    expect(draftOnly.text()).not.toContain('禁用 Skill')
  })

  it('emits the management intents from the action nav and version rows', async () => {
    const wrapper = mounted({ canManage: true })

    await wrapper.get('.skill-detail__actions button').trigger('click')
    expect(wrapper.emitted('publish')).toHaveLength(1)

    await wrapper.findAll('button').find(button => button.text() === '禁用 Skill')!.trigger('click')
    expect(wrapper.emitted('disable')).toHaveLength(1)

    const rollback = wrapper.findAll('button').find(button => button.text() === '回滚到此版本')
    expect(rollback).toBeUndefined()
  })

  it('surfaces the conflict panel with the server head version and the reload escape', () => {
    const wrapper = mounted({
      command: { ...command(), phase: 'conflict', errorMessage: '其他成员已更新此 Skill', currentVersion: 7 },
    })

    expect(wrapper.text()).toContain('其他成员已更新此 Skill')
    expect(wrapper.text()).toContain('v7')
    expect(wrapper.text()).toContain('载入服务端内容')
  })

  it('shows the audit block only to managers, including distillation origin', () => {
    const manager = mounted({ canManage: true, skillResource: ready(skill({ origin: { taskExecutionId: 'exec-1', attempt: 2 } })) })
    expect(manager.get('.skill-detail__audit').text()).toContain('执行 exec-1')
    expect(manager.get('.skill-detail__audit').text()).toContain('第 2 次尝试')

    const member = mounted({ canManage: false })
    expect(member.find('.skill-detail__audit').exists()).toBe(false)
  })

  it('renders the loading and error detail states', () => {
    const loading = mount(SkillDetail, { props: { ...baseProps(), skillResource: { phase: 'loading', value: null, errorMessage: null, errorStatus: null } } })
    expect(loading.text()).toContain('正在读取 Skill 详情')

    const failed = mount(SkillDetail, { props: { ...baseProps(), skillResource: { phase: 'error', value: null, errorMessage: '详情暂时无法读取', errorStatus: 503 } } })
    expect(failed.text()).toContain('详情暂时无法读取')
  })
})

function baseProps() {
  return {
    skillResource: ready(skill({})),
    command: null,
    history: page([version(1)], null),
    versionResource: undefined,
    effective: undefined,
    effectiveRevision: 1,
    selectedRevision: null,
    tab: 'draft' as const,
    scope,
    canManage: true,
    online: true,
    baselineContent: '---\nname: deploy-runbook-v2\n---\nbaseline',
  }
}

function mounted(overrides: Record<string, unknown>) {
  return mount(SkillDetail, { props: { ...baseProps(), ...overrides } })
}

function command(): SkillCommandState {
  return {
    phase: 'idle', operation: null, resourceId: null, receipt: null,
    errorMessage: null, errorStatus: null, currentVersion: null, retryable: false,
  }
}

function ready(value: SkillSummary): SkillResource<Etagged<SkillSummary>> {
  return { phase: 'ready', value: { value, etag: `"${value.version}"` }, errorMessage: null, errorStatus: null }
}

function page(items: SkillVersion[], nextAfter: number | null): SkillVersionPageResource {
  return { phase: 'ready', value: items, errorMessage: null, errorStatus: null, nextAfter, loadingMore: false }
}

function skill(overrides: Partial<SkillSummary>): SkillSummary {
  return {
    id: 'skill-1', skillKey: 'deploy-runbook-v2', status: 'PUBLISHED',
    effectiveRevision: 1, latestRevision: 1,
    draft: { name: 'deploy-runbook-v2', description: 'd', content: '---\nname: deploy-runbook-v2\ndescription: d\n---\n\nbody' },
    disableReason: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: 'principal-1', updatedBy: 'principal-1', origin: null,
    ...overrides,
  }
}

function version(revision: number): SkillVersion {
  return {
    skillId: 'skill-1', revision, previousRevision: revision > 1 ? revision - 1 : null,
    content: '---\nname: deploy-runbook-v2\n---\nbody', contentHash: 'a'.repeat(64),
    createdAt: '2026-10-02T01:00:00Z', createdBy: 'principal-1',
  }
}
