import { flushPromises, mount } from '@vue/test-utils'
import { fixtureResponsibilities, fixtureTimeline, fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { fixtureConversationWorkItemAssociation } from '../../test/conversationWorkItemFixtures'
import WorkItemDetailDrawer from './WorkItemDetailDrawer.vue'
import type { WorkItemAvailableTransition } from '../../domains/workitem/types'

const global = { stubs: { RouterLink: { props: ['to'], template: '<a href="#"><slot /></a>' } } }

describe('WorkItemDetailDrawer', () => {
  it('focuses the close action, exposes valid transitions and closes with Escape', async () => {
    const onTransition = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemDetailDrawer, {
      attachTo: document.body,
      global,
      props: props({ onTransition }),
    })
    await flushPromises()

    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭工作项详情')
    expect(wrapper.get('.transition-control').text()).toContain('提交评审')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeTruthy()
    expect(document.body.style.overflow).toBe('hidden')

    wrapper.unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('submits comments and ResourceLinks through the application callbacks', async () => {
    const onAddComment = vi.fn().mockResolvedValue(undefined)
    const onLinkResource = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemDetailDrawer, {
      global,
      props: props({ onAddComment, onLinkResource }),
    })

    await wrapper.get('#work-item-comment').setValue('  新的协作结论  ')
    await wrapper.get('.comment-form').trigger('submit')
    await flushPromises()
    expect(onAddComment).toHaveBeenCalledWith({ content: '新的协作结论' })

    await wrapper.get('.resource-form select').setValue('EXTERNAL_URL')
    const inputs = wrapper.findAll<HTMLInputElement>('.resource-form input')
    await inputs[0]!.setValue('https://example.com/evidence')
    await inputs[1]!.setValue('验证证据')
    await wrapper.get('.resource-form').trigger('submit')
    await flushPromises()
    expect(onLinkResource).toHaveBeenCalledWith({ resourceType: 'EXTERNAL_URL', resourceReference: 'https://example.com/evidence', label: '验证证据' })
    wrapper.unmount()
  })

  it('separates Personal Agent discussion from the durable Task delegation entry', async () => {
    const wrapper = mount(WorkItemDetailDrawer, { global, props: props() })

    await wrapper.get('.detail-footer button:last-child').trigger('click')
    expect(wrapper.emitted('delegate')).toBeTruthy()
    await wrapper.get('.detail-footer button:first-of-type').trigger('click')
    expect(wrapper.emitted('conversation')).toBeTruthy()
  })

  it('shows visible source Conversations beside responsibility facts', async () => {
    const wrapper = mount(WorkItemDetailDrawer, {
      global,
      props: props({ associationPhase: 'ready', associations: [fixtureConversationWorkItemAssociation] }),
    })

    expect(wrapper.text()).toContain('关联对话')
    expect(wrapper.text()).toContain('团队责任链')
    await wrapper.get('button[aria-label^="返回对话"]').trigger('click')
    expect(wrapper.emitted('openConversation')?.[0]).toEqual([fixtureConversationWorkItemAssociation])
    wrapper.unmount()
  })

  it('uses current member and Agent directory names for authorship facts', () => {
    const details = structuredClone(fixtureWorkItemDetails)
    details.workItem.createdByPrincipalId = '00000000-0000-0000-0000-000000000201'
    details.comments[0]!.authorPrincipalId = '00000000-0000-0000-0000-000000000101'
    const wrapper = mount(WorkItemDetailDrawer, { global, props: props({ details }) })

    expect(wrapper.get('.facts-section').text()).toContain('Coding Agent')
    expect(wrapper.get('.comments-section').text()).toContain('张凯旋')
    wrapper.unmount()
  })

  it('embeds the WorkItem Activity projection after its business timeline', () => {
    const wrapper = mount(WorkItemDetailDrawer, {
      global,
      props: props(),
      slots: {
        activity: '<div data-testid="work-item-activity">WorkItem Activity projection</div>',
      },
    })

    const timeline = wrapper.get('.timeline-section')
    const projection = wrapper.get('.activity-projection-section')
    expect(projection.text()).toContain('WorkItem Activity projection')
    expect(timeline.element.compareDocumentPosition(projection.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    wrapper.unmount()
  })

  it('keeps drafts on command failure and validates empty collaboration forms', async () => {
    const onTransition = vi.fn().mockRejectedValue(new Error('conflict'))
    const onAddComment = vi.fn().mockRejectedValue(new Error('comment failed'))
    const onLinkResource = vi.fn().mockRejectedValue(new Error('resource failed'))
    const wrapper = mount(WorkItemDetailDrawer, { global, props: props({ onTransition, onAddComment, onLinkResource }) })

    await wrapper.get('.transition-control button').trigger('click')
    await wrapper.get('.comment-form').trigger('submit')
    await wrapper.get('.resource-form').trigger('submit')
    await flushPromises()
    expect(onTransition).toHaveBeenCalledWith('IN_REVIEW')
    expect(onAddComment).not.toHaveBeenCalled()
    expect(onLinkResource).not.toHaveBeenCalled()

    await wrapper.get('#work-item-comment').setValue('保留评论草稿')
    await wrapper.get('.comment-form').trigger('submit')
    await wrapper.findAll<HTMLInputElement>('.resource-form input')[0]!.setValue('https://example.com/draft')
    await wrapper.get('.resource-form').trigger('submit')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('#work-item-comment').element.value).toBe('保留评论草稿')
    expect(wrapper.findAll<HTMLInputElement>('.resource-form input')[0]!.element.value).toBe('https://example.com/draft')
    wrapper.unmount()
  })

  it('renders loading, error, conflict, terminal and external-source states', async () => {
    const loading = mount(WorkItemDetailDrawer, { global, props: props({ phase: 'loading', details: null }) })
    expect(loading.text()).toContain('正在加载')
    loading.unmount()

    const onRetry = vi.fn()
    const failed = mount(WorkItemDetailDrawer, { global, props: props({ phase: 'error', details: null, errorMessage: '读取失败', onRetry }) })
    expect(failed.text()).toContain('读取失败')
    await failed.get('button:not([aria-label="关闭工作项详情"])').trigger('click')
    expect(onRetry).toHaveBeenCalled()
    failed.unmount()

    const externalDetails = structuredClone(fixtureWorkItemDetails)
    externalDetails.workItem.source = 'JIRA'
    externalDetails.workItem.status = 'BLOCKED'
    externalDetails.resourceLinks.push({ id: 'safe', workItemId: externalDetails.workItem.id, resourceType: 'EXTERNAL_URL', resourceReference: 'https://example.com/path', label: null, createdAt: '2026-08-08T04:00:00Z', createdByPrincipalId: null })
    externalDetails.resourceLinks.push({ id: 'unsafe', workItemId: externalDetails.workItem.id, resourceType: 'EXTERNAL_URL', resourceReference: 'javascript:alert(1)', label: '危险引用', createdAt: '2026-08-08T04:00:00Z', createdByPrincipalId: null })
    const external = mount(WorkItemDetailDrawer, { global, props: props({
      details: externalDetails,
      availableTransitions: [transition({
        actionId: 'resume-work',
        targetStatus: 'IN_PROGRESS',
        label: '继续执行',
        enabled: false,
        reason: 'EXTERNAL_PROVIDER_MANAGED',
        reasonMessage: '此工作项由外部 Provider 管理',
        remedyLabel: '查看集成设置',
        remedyRoute: '/settings/integrations',
      })],
      versionConflict: { attemptedVersion: 1, currentVersion: null },
      commandErrorMessage: '发生冲突',
    }) })
    expect(external.text()).toContain('此工作项由外部 Provider 管理')
    expect(external.get('.transition-reason a').text()).toBe('查看集成设置')
    expect(external.get('.transition-action button').attributes('disabled')).toBeDefined()
    expect(external.text()).toContain('当前版本为 未知')
    expect(external.findAll('a[href="https://example.com/path"]').length).toBe(1)
    expect(external.find('a[href^="javascript:"]').exists()).toBe(false)
    external.unmount()

    const archivedDetails = structuredClone(fixtureWorkItemDetails)
    archivedDetails.workItem.status = 'ARCHIVED'
    archivedDetails.comments = []
    archivedDetails.resourceLinks = []
    const archived = mount(WorkItemDetailDrawer, { global, props: props({ details: archivedDetails, availableTransitions: [] }) })
    expect(archived.text()).toContain('当前状态没有后续动作')
    expect(archived.find('.comment-form').exists()).toBe(false)
    archived.unmount()
  })
  it('never widens the action list beyond the server verdict and never submits a blocked action', async () => {
    const onTransition = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemDetailDrawer, { global, props: props({
      onTransition,
      availableTransitions: [transition({
        actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', reversible: true,
      }), transition({
        actionId: 'complete', targetStatus: 'DONE', label: '标记完成', enabled: false,
        reason: 'REVIEWER_REQUIRED', reasonMessage: '需要先指派 Reviewer',
        remedyLabel: '指派 Reviewer', remedyRoute: '/work?panel=responsibility',
      })],
    }) })

    // The generated state machine also allows BLOCKED and CANCELLED from IN_PROGRESS; neither is
    // offered here, because the server did not return them.
    const actions = wrapper.findAll('.transition-action')
    expect(actions.length).toBe(2)
    expect(wrapper.text()).not.toContain('标记阻塞')

    await actions[1]!.get('button').trigger('click')
    await flushPromises()
    expect(onTransition).not.toHaveBeenCalled()
    expect(wrapper.get('.transition-reason').text()).toContain('需要先指派 Reviewer')

    // Reversible actions commit on the first click, because an undo window follows.
    await actions[0]!.get('button').trigger('click')
    await flushPromises()
    expect(onTransition).toHaveBeenCalledWith('IN_REVIEW')
    wrapper.unmount()
  })

  it('asks twice before an irreversible action, which has no reverse edge to undo', async () => {
    const onTransition = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemDetailDrawer, { global, props: props({
      onTransition,
      availableTransitions: [transition({
        actionId: 'cancel', targetStatus: 'CANCELLED', label: '取消工作项', strength: 'DANGER',
      })],
    }) })

    await wrapper.get('.transition-action button').trigger('click')
    await flushPromises()
    expect(onTransition).not.toHaveBeenCalled()
    expect(wrapper.get('.transition-action button').text()).toContain('再次点击确认取消工作项')

    await wrapper.get('.transition-action button').trigger('click')
    await flushPromises()
    expect(onTransition).toHaveBeenCalledWith('CANCELLED')
    wrapper.unmount()
  })

  it('offers a retry instead of a guessed action list when availability fails', async () => {
    const onRetryAvailability = vi.fn()
    const wrapper = mount(WorkItemDetailDrawer, { global, props: props({
      availabilityPhase: 'error',
      availabilityErrorMessage: '暂时无法加载可执行动作',
      availableTransitions: [],
      onRetryAvailability,
    }) })

    expect(wrapper.find('.transition-control').exists()).toBe(false)
    await wrapper.get('.transition-section button').trigger('click')
    expect(onRetryAvailability).toHaveBeenCalled()
    wrapper.unmount()
  })
})

function transition(overrides: Partial<WorkItemAvailableTransition>): WorkItemAvailableTransition {
  return {
    actionId: 'request-review',
    targetStatus: 'IN_REVIEW',
    label: '提交评审',
    strength: 'PRIMARY',
    reversible: false,
    enabled: true,
    reason: null,
    reasonMessage: null,
    remedyLabel: null,
    remedyRoute: null,
    ...overrides,
  }
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    scope: { organizationId: '00000000-0000-0000-0000-000000000001', teamId: '00000000-0000-0000-0000-000000000201' },
    phase: 'ready' as const,
    details: structuredClone(fixtureWorkItemDetails),
    errorMessage: null,
    commandPending: null,
    commandErrorMessage: null,
    versionConflict: null,
    availabilityPhase: 'ready' as const,
    availableTransitions: [
      transition({ actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', reversible: true }),
      transition({ actionId: 'block', targetStatus: 'BLOCKED', label: '标记阻塞', strength: 'SECONDARY', reversible: true }),
      transition({ actionId: 'cancel', targetStatus: 'CANCELLED', label: '取消工作项', strength: 'DANGER' }),
    ],
    availabilityErrorMessage: null,
    canParticipate: true,
    canDelegate: true,
    canManageResponsibility: true,
    responsibilityPhase: 'ready' as const,
    responsibilities: structuredClone(fixtureResponsibilities),
    responsibilityCandidates: [
      { principalId: '00000000-0000-0000-0000-000000000101', displayName: '张凯旋' },
      { principalId: '00000000-0000-0000-0000-000000000102', displayName: '林晨' },
    ],
    responsibilityAgentCandidates: [{
      principalId: '00000000-0000-0000-0000-000000000201', displayName: 'Coding Agent',
      ownershipType: 'USER' as const, runtimeRole: 'SPECIALIST',
    }],
    responsibilityAgentPhase: 'ready' as const,
    responsibilityAgentErrorMessage: null,
    responsibilityAgentLoadingMore: false,
    responsibilityAgentHasMore: false,
    responsibilityErrorMessage: null,
    responsibilityCommandPending: null,
    responsibilityCommandErrorMessage: null,
    timelinePhase: 'ready' as const,
    timeline: structuredClone(fixtureTimeline),
    timelineNextCursor: null,
    timelineLoadingMore: false,
    timelineErrorMessage: null,
    associationPhase: 'empty' as const,
    associations: [],
    associationErrorMessage: null,
    onRetry: vi.fn(),
    onRetryAvailability: vi.fn(),
    onTransition: vi.fn().mockResolvedValue(undefined),
    onAddComment: vi.fn().mockResolvedValue(undefined),
    onLinkResource: vi.fn().mockResolvedValue(undefined),
    onReplaceOwner: vi.fn().mockResolvedValue(undefined),
    onAssignExecutor: vi.fn().mockResolvedValue(undefined),
    onAssignGateReviewer: vi.fn().mockResolvedValue(undefined),
    onAssignAdvisoryReviewer: vi.fn().mockResolvedValue(undefined),
    onReleaseResponsibility: vi.fn().mockResolvedValue(undefined),
    onRetryResponsibilityAgents: vi.fn(),
    onLoadMoreResponsibilityAgents: vi.fn(),
    onLoadTimelineMore: vi.fn().mockResolvedValue(undefined),
    onRetryAssociations: vi.fn(),
    ...overrides,
  }
}
