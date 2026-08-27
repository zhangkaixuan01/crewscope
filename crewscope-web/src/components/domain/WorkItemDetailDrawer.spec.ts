import { flushPromises, mount } from '@vue/test-utils'
import { fixtureResponsibilities, fixtureTimeline, fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { fixtureConversationWorkItemAssociation } from '../../test/conversationWorkItemFixtures'
import WorkItemDetailDrawer from './WorkItemDetailDrawer.vue'

describe('WorkItemDetailDrawer', () => {
  it('focuses the close action, exposes valid transitions and closes with Escape', async () => {
    const onTransition = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemDetailDrawer, {
      attachTo: document.body,
      props: props({ onTransition }),
    })
    await flushPromises()

    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭工作项详情')
    expect(wrapper.get<HTMLSelectElement>('select[aria-label="目标状态"]').element.value).toBe('IN_REVIEW')
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
    const wrapper = mount(WorkItemDetailDrawer, { props: props() })

    await wrapper.get('.detail-footer button:last-child').trigger('click')
    expect(wrapper.emitted('delegate')).toBeTruthy()
    await wrapper.get('.detail-footer button:first-of-type').trigger('click')
    expect(wrapper.emitted('conversation')).toBeTruthy()
  })

  it('shows visible source Conversations beside responsibility facts', async () => {
    const wrapper = mount(WorkItemDetailDrawer, {
      props: props({ associationPhase: 'ready', associations: [fixtureConversationWorkItemAssociation] }),
    })

    expect(wrapper.text()).toContain('关联对话')
    expect(wrapper.text()).toContain('团队责任链')
    await wrapper.get('button[aria-label^="返回对话"]').trigger('click')
    expect(wrapper.emitted('openConversation')?.[0]).toEqual([fixtureConversationWorkItemAssociation])
    wrapper.unmount()
  })

  it('embeds the WorkItem Activity projection after its business timeline', () => {
    const wrapper = mount(WorkItemDetailDrawer, {
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
    const wrapper = mount(WorkItemDetailDrawer, { props: props({ onTransition, onAddComment, onLinkResource }) })

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
    const loading = mount(WorkItemDetailDrawer, { props: props({ phase: 'loading', details: null }) })
    expect(loading.text()).toContain('正在加载')
    loading.unmount()

    const onRetry = vi.fn()
    const failed = mount(WorkItemDetailDrawer, { props: props({ phase: 'error', details: null, errorMessage: '读取失败', onRetry }) })
    expect(failed.text()).toContain('读取失败')
    await failed.get('button:not([aria-label="关闭工作项详情"])').trigger('click')
    expect(onRetry).toHaveBeenCalled()
    failed.unmount()

    const externalDetails = structuredClone(fixtureWorkItemDetails)
    externalDetails.workItem.source = 'JIRA'
    externalDetails.workItem.status = 'BLOCKED'
    externalDetails.resourceLinks.push({ id: 'safe', workItemId: externalDetails.workItem.id, resourceType: 'EXTERNAL_URL', resourceReference: 'https://example.com/path', label: null, createdAt: '2026-08-08T04:00:00Z', createdByPrincipalId: null })
    externalDetails.resourceLinks.push({ id: 'unsafe', workItemId: externalDetails.workItem.id, resourceType: 'EXTERNAL_URL', resourceReference: 'javascript:alert(1)', label: '危险引用', createdAt: '2026-08-08T04:00:00Z', createdByPrincipalId: null })
    const external = mount(WorkItemDetailDrawer, { props: props({ details: externalDetails, versionConflict: { attemptedVersion: 1, currentVersion: null }, commandErrorMessage: '发生冲突' }) })
    expect(external.text()).toContain('外部 Provider 工作项由来源系统管理状态')
    expect(external.text()).toContain('当前版本为 未知')
    expect(external.findAll('a[href="https://example.com/path"]').length).toBe(1)
    expect(external.find('a[href^="javascript:"]').exists()).toBe(false)
    external.unmount()

    const archivedDetails = structuredClone(fixtureWorkItemDetails)
    archivedDetails.workItem.status = 'ARCHIVED'
    archivedDetails.comments = []
    archivedDetails.resourceLinks = []
    const archived = mount(WorkItemDetailDrawer, { props: props({ details: archivedDetails }) })
    expect(archived.text()).toContain('已归档工作项没有后续状态')
    expect(archived.find('.comment-form').exists()).toBe(false)
    archived.unmount()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'ready' as const,
    details: structuredClone(fixtureWorkItemDetails),
    errorMessage: null,
    commandPending: null,
    commandErrorMessage: null,
    versionConflict: null,
    canParticipate: true,
    canDelegate: true,
    canManageResponsibility: true,
    responsibilityPhase: 'ready' as const,
    responsibilities: structuredClone(fixtureResponsibilities),
    responsibilityCandidates: [
      { principalId: '00000000-0000-0000-0000-000000000101', displayName: '张凯旋' },
      { principalId: '00000000-0000-0000-0000-000000000102', displayName: '林晨' },
    ],
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
    onTransition: vi.fn().mockResolvedValue(undefined),
    onAddComment: vi.fn().mockResolvedValue(undefined),
    onLinkResource: vi.fn().mockResolvedValue(undefined),
    onReplaceOwner: vi.fn().mockResolvedValue(undefined),
    onAssignExecutor: vi.fn().mockResolvedValue(undefined),
    onAssignGateReviewer: vi.fn().mockResolvedValue(undefined),
    onAssignAdvisoryReviewer: vi.fn().mockResolvedValue(undefined),
    onReleaseResponsibility: vi.fn().mockResolvedValue(undefined),
    onLoadTimelineMore: vi.fn().mockResolvedValue(undefined),
    onRetryAssociations: vi.fn(),
    ...overrides,
  }
}
