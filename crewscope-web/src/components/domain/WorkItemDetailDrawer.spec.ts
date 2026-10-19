import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { activateF05Identity, clearF05UserData } from '../../app/f05Storage'
import { readWorkItemCommentDraft } from '../../domains/workitem/commentDraft'
import { fixtureResponsibilities, fixtureTimeline, fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { fixtureConversationWorkItemAssociation } from '../../test/conversationWorkItemFixtures'
import { COLLABORATION_STORE } from '../../domains/collaboration/store'
import { createScriptedCollaborationStore } from '../../test/collaborationFixtures'
import { WORK_ITEM_STORE, type WorkItemStore } from '../../domains/workitem/store'
import { AGENT_STORE, type AgentStore } from '../../domains/agent/store'
import { CONVERSATION_WORK_ITEM_LINK_STORE, type ConversationWorkItemLinkStore } from '../../domains/conversation/workItemLinkStore'
import WorkItemDetailDrawer from './WorkItemDetailDrawer.vue'
import type { WorkItemAvailableTransition } from '../../domains/workitem/types'
import type { TaskStatus } from '../../domains/task/types'

const global = { stubs: { RouterLink: { props: ['to'], template: '<a href="#"><slot /></a>' }, teleport: true } }

/** The drawer subscribes to the work-item/agent/link stores itself (M12b-F05). */
function mountDrawer(
  fixture: ReturnType<typeof props>,
  options: { attachTo?: boolean, provide?: Record<string, unknown>, slots?: Record<string, string> } = {},
) {
  return mount(WorkItemDetailDrawer, {
    props: fixture.drawerProps,
    attachTo: options.attachTo === true ? document.body : undefined,
    slots: options.slots,
    global: {
      provide: {
        [WORK_ITEM_STORE as symbol]: fixture.workItemStore,
        [AGENT_STORE as symbol]: fixture.agentStore,
        [CONVERSATION_WORK_ITEM_LINK_STORE as symbol]: fixture.linkStore,
        ...options.provide,
      },
      stubs: global.stubs,
    },
  })
}

describe('WorkItemDetailDrawer', () => {
  it('focuses the close action, exposes valid transitions and closes with Escape', async () => {
    const onTransition = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountDrawer(props({ onTransition }), { attachTo: true })
    await flushPromises()

    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭工作项详情')
    expect(wrapper.get('.transition-control').text()).toContain('提交评审')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeTruthy()
    // 滚动锁由 useFocusTrap 的 cs-modal-open 引用计数承担（F02 下沉），不再是 body 内联样式。
    expect(document.body.classList.contains('cs-modal-open')).toBe(true)

    wrapper.unmount()
    expect(document.body.classList.contains('cs-modal-open')).toBe(false)
  })

  it('submits comments and ResourceLinks through the application callbacks', async () => {
    const onAddComment = vi.fn().mockResolvedValue(undefined)
    const onLinkResource = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountDrawer(props({ onAddComment, onLinkResource }))

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
    const wrapper = mountDrawer(props())

    await wrapper.get('.detail-footer__actions button:last-child').trigger('click')
    expect(wrapper.emitted('delegate')).toEqual([['']])
    await wrapper.get('.detail-footer__actions button:first-of-type').trigger('click')
    expect(wrapper.emitted('conversation')).toBeTruthy()
  })

  it('renders stored comments through the safe Markdown pipeline with table semantics intact', () => {
    const details = structuredClone(fixtureWorkItemDetails)
    details.comments[0]!.content = '结论 | 数量 |\n| --- | --- |\n| 接口已冻结 | 2 |'
    const wrapper = mountDrawer(props({ details }))

    // Semantic structure, not flattened text: th/td survive because the same SafeMarkdown
    // whitelist backs chat, comments and line-review contexts (R40).
    expect(wrapper.get('.comment-body table thead th').text()).toBe('结论')
    expect(wrapper.findAll('.comment-body tbody td').map(cell => cell.text())).toEqual(['接口已冻结', '2'])
    wrapper.unmount()
  })

  it('keeps “发布评论” and “让 Agent 处理” as two separate intents from the same draft', async () => {
    const onAddComment = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountDrawer(props({ onAddComment }))

    await wrapper.get('#work-item-comment').setValue('按新接口契约补齐回调')
    await wrapper.get('.comment-actions button[title^="以这条评论为执行目标"]').trigger('click')
    // R42: the delegate intent carries the draft for the dialog to pre-fill; it publishes nothing.
    expect(wrapper.emitted('delegate')).toEqual([['按新接口契约补齐回调']])
    expect(onAddComment).not.toHaveBeenCalled()
    expect(wrapper.get<HTMLTextAreaElement>('#work-item-comment').element.value).toBe('按新接口契约补齐回调')

    await wrapper.get('.comment-form').trigger('submit')
    await flushPromises()
    expect(onAddComment).toHaveBeenCalledWith({ content: '按新接口契约补齐回调' })
    wrapper.unmount()
  })

  it('previews the comment draft through SafeMarkdown and returns to editing without losing text', async () => {
    const wrapper = mountDrawer(props())

    await wrapper.get('#work-item-comment').setValue('**结论**先行')
    const toggle = wrapper.get('.comment-preview-toggle')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    await toggle.trigger('click')

    expect(wrapper.get('.comment-preview strong').text()).toBe('结论')
    expect(wrapper.find('#work-item-comment').exists()).toBe(false)

    await toggle.trigger('click')
    expect((wrapper.get<HTMLTextAreaElement>('#work-item-comment').element).value).toBe('**结论**先行')
    wrapper.unmount()
  })

  it('shows visible source Conversations beside responsibility facts', async () => {
    const wrapper = mountDrawer(props({ associationPhase: 'ready', associations: [fixtureConversationWorkItemAssociation] }))

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
    const wrapper = mountDrawer(props({ details }))

    expect(wrapper.get('.facts-section').text()).toContain('Coding Agent')
    expect(wrapper.get('.comments-section').text()).toContain('张凯旋')
    wrapper.unmount()
  })

  it('embeds the WorkItem Activity projection after its business timeline', () => {
    const wrapper = mountDrawer(props(), {
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

  it('offers the real parallel Task list when several Tasks run and none is chosen', async () => {
    const onOpenTask = vi.fn()
    const wrapper = mountDrawer(props({
      taskSelectionRequired: true,
      parallelTasks: [
        { id: 'task-a', objective: '搭建登录页', status: 'ACTIVE' },
        { id: 'task-b', objective: '编写集成测试', status: 'WAITING' },
      ],
      onOpenTask,
    }))

    expect(wrapper.get('.parallel-tasks').attributes('role')).toBe('status')
    expect(wrapper.get('.parallel-tasks').text()).toContain('2 个 Task 并行，先选择目标')
    expect(wrapper.get('.parallel-tasks').text()).toContain('进行中')
    await wrapper.findAll('.parallel-tasks button').find(button => button.text().includes('编写集成测试'))!.trigger('click')
    expect(onOpenTask).toHaveBeenCalledWith('task-b')

    const silent = mountDrawer(props({ taskSelectionRequired: false, parallelTasks: [{ id: 'task-a', objective: '搭建登录页', status: 'ACTIVE' }] }))
    expect(silent.find('.parallel-tasks').exists()).toBe(false)
    silent.unmount()
    wrapper.unmount()
  })

  it('keeps drafts on command failure and validates empty collaboration forms', async () => {
    const onTransition = vi.fn().mockRejectedValue(new Error('conflict'))
    const onAddComment = vi.fn().mockRejectedValue(new Error('comment failed'))
    const onLinkResource = vi.fn().mockRejectedValue(new Error('resource failed'))
    const wrapper = mountDrawer(props({ onTransition, onAddComment, onLinkResource }))

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
    const loading = mountDrawer(props({ phase: 'loading', details: null }))
    expect(loading.text()).toContain('正在加载')
    loading.unmount()

    const onRetry = vi.fn()
    const failed = mountDrawer(props({ phase: 'error', details: null, errorMessage: '读取失败', onRetry }))
    expect(failed.text()).toContain('读取失败')
    await failed.get('button:not([aria-label="关闭工作项详情"])').trigger('click')
    expect(onRetry).toHaveBeenCalled()
    failed.unmount()

    const externalDetails = structuredClone(fixtureWorkItemDetails)
    externalDetails.workItem.source = 'JIRA'
    externalDetails.workItem.status = 'BLOCKED'
    externalDetails.resourceLinks.push({ id: 'safe', workItemId: externalDetails.workItem.id, resourceType: 'EXTERNAL_URL', resourceReference: 'https://example.com/path', label: null, createdAt: '2026-08-08T04:00:00Z', createdByPrincipalId: null })
    externalDetails.resourceLinks.push({ id: 'unsafe', workItemId: externalDetails.workItem.id, resourceType: 'EXTERNAL_URL', resourceReference: 'javascript:alert(1)', label: '危险引用', createdAt: '2026-08-08T04:00:00Z', createdByPrincipalId: null })
    const external = mountDrawer(props({
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
    }))
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
    const archived = mountDrawer(props({ details: archivedDetails, availableTransitions: [] }))
    expect(archived.text()).toContain('当前状态没有后续动作')
    expect(archived.find('.comment-form').exists()).toBe(false)
    archived.unmount()
  })
  it('never widens the action list beyond the server verdict and never submits a blocked action', async () => {
    const onTransition = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountDrawer(props({
      onTransition,
      availableTransitions: [transition({
        actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', reversible: true,
      }), transition({
        actionId: 'complete', targetStatus: 'DONE', label: '标记完成', enabled: false,
        reason: 'REVIEWER_REQUIRED', reasonMessage: '需要先指派 Reviewer',
        remedyLabel: '指派 Reviewer', remedyRoute: '/work?panel=responsibility',
      })],
    }))

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
    const wrapper = mountDrawer(props({
      onTransition,
      availableTransitions: [transition({
        actionId: 'cancel', targetStatus: 'CANCELLED', label: '取消工作项', strength: 'DANGER',
      })],
    }))

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
    const wrapper = mountDrawer(props({
      availabilityPhase: 'error',
      availabilityErrorMessage: '暂时无法加载可执行动作',
      availableTransitions: [],
      onRetryAvailability,
    }))

    expect(wrapper.find('.transition-control').exists()).toBe(false)
    await wrapper.get('.transition-section button').trigger('click')
    expect(onRetryAvailability).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the comment as a scoped browser draft across re-opens and clears it once submitted', async () => {
    clearF05UserData()
    activateF05Identity('00000000-0000-0000-0000-000000000901')
    const onAddComment = vi.fn().mockResolvedValue(undefined)
    const principal = {
      id: '00000000-0000-0000-0000-000000000101',
      accountId: '00000000-0000-0000-0000-000000000901',
      displayName: '张凯旋', role: 'Team Member',
      organizationId: '00000000-0000-0000-0000-000000000001', organization: 'CrewScope',
      permissions: new Set<string>(),
    }
    const mounted = () => mountDrawer(props({ onAddComment }), {
      provide: { [AUTH_PRINCIPAL as symbol]: principal },
    })

    const first = mounted()
    await first.get('#work-item-comment').setValue('跨会话的评论草稿')
    first.unmount()
    const draftScope = { organizationId: '00000000-0000-0000-0000-000000000001', teamId: '00000000-0000-0000-0000-000000000201', projectId: fixtureWorkItemDetails.workItem.projectId }
    // Closing kept the draft in the scoped namespace rather than dropping it.
    expect(readWorkItemCommentDraft(draftScope, fixtureWorkItemDetails.workItem.id, principal)?.content).toBe('跨会话的评论草稿')

    const second = mounted()
    await flushPromises()
    expect((second.get('#work-item-comment').element as HTMLTextAreaElement).value).toBe('跨会话的评论草稿')
    await second.get('.comment-form').trigger('submit')
    await flushPromises()
    expect(onAddComment).toHaveBeenCalledWith({ content: '跨会话的评论草稿' })
    // A successful submit cleared the stored draft.
    expect(readWorkItemCommentDraft(draftScope, fixtureWorkItemDetails.workItem.id, principal)).toBeNull()
    second.unmount()
    clearF05UserData()
  })

  it('turns remote work-item changes into a banner while editing and a silent re-read otherwise', async () => {
    const { store, gateway } = createScriptedCollaborationStore()
    const onRetry = vi.fn()
    const wrapper = mountDrawer(props({ onRetry }), {
      provide: { [COLLABORATION_STORE as symbol]: store },
    })
    await flushPromises()

    // Open the channel exactly like the server would: welcome, subscribe ack, then signals.
    const scope = {
      organization: '00000000-0000-0000-0000-000000000001',
      team: '00000000-0000-0000-0000-000000000201',
      resourceType: 'work_item' as const,
      resourceId: fixtureWorkItemDetails.workItem.id,
    }
    gateway.deliver({ type: 'welcome', connectionId: 'conn-1', principalId: 'account:alice', typingWindowSeconds: 600, maxInboundSignalsPerSecond: 30 })
    gateway.deliver({ type: 'subscribed', subscriptionId: 'handle-1', scope })
    await flushPromises()

    // Presence and typing render off the same work-item subscription.
    gateway.deliver({ type: 'presence_delta', subscriptionId: 'handle-1', scope, action: 'enter', principalId: 'account:bob', displayName: 'Bob', connections: 1 })
    gateway.deliver({ type: 'typing', subscriptionId: 'handle-1', scope, principalId: 'account:bob', displayName: 'Bob', state: 'started' })
    await flushPromises()
    expect(wrapper.get('.presence-group').attributes('aria-label')).toBe('在场：Bob')
    expect(wrapper.get('.typing-indicator').text()).toContain('Bob 正在输入…')

    // A clean drawer re-reads the changed facts silently — no banner (fixture item is v0).
    gateway.deliver({ type: 'resource_changed', subscriptionId: 'handle-1', scope, version: 1 })
    await flushPromises()
    expect(onRetry).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.change-banner').exists()).toBe(false)

    // A dirty editor is never overwritten: the change becomes a banner until the member
    // chooses to refresh (ADR-032 — the interruption red line).
    await wrapper.get('.content-editor button').trigger('click')
    await wrapper.findAll('.content-editor input')[0]!.setValue('标题被并行修改时的本地输入')
    gateway.deliver({ type: 'resource_changed', subscriptionId: 'handle-1', scope, version: 2 })
    await flushPromises()
    expect(onRetry).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.change-banner').text()).toContain('该工作项已被更新')
    expect(wrapper.findAll<HTMLInputElement>('.content-editor input')[0]!.element.value).toBe('标题被并行修改时的本地输入')

    await wrapper.get('.change-banner button').trigger('click')
    expect(onRetry).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.change-banner').exists()).toBe(false)
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

/**
 * Builds the drawer's three faces from one flat fixture (M12b-F05): raw work-item/agent/link state
 * lands in faked stores, the store-owned commands land on the work-item store, and page-owned
 * verdicts stay props. Overrides keep the historical flat prop names, so the cases read as before
 * the migration.
 */
function props(overrides: Record<string, unknown> = {}) {
  const flat = {
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
    onOpenTask: vi.fn(),
    parallelTasks: undefined as Array<{ id: string, objective: string, status: TaskStatus }> | undefined,
    taskSelectionRequired: undefined as boolean | undefined,
    ...overrides,
  }

  // Raw state faces: the stores are readonly from the outside, so the fixture builds the exact
  // slice the drawer subscribes to as plain reactives, with the store-owned commands attached.
  const workItemStore = {
    state: reactive({
      detailPhase: flat.phase,
      detail: flat.details,
      detailErrorMessage: flat.errorMessage,
      detailCommandPending: flat.commandPending,
      detailCommandErrorMessage: flat.commandErrorMessage,
      versionConflict: flat.versionConflict,
      availabilityPhase: flat.availabilityPhase,
      availableTransitions: flat.availableTransitions,
      availabilityErrorMessage: flat.availabilityErrorMessage,
      responsibilityPhase: flat.responsibilityPhase,
      responsibilities: flat.responsibilities,
      responsibilityErrorMessage: flat.responsibilityErrorMessage,
      responsibilityCommandPending: flat.responsibilityCommandPending,
      responsibilityCommandErrorMessage: flat.responsibilityCommandErrorMessage,
      timelinePhase: flat.timelinePhase,
      timeline: flat.timeline,
      timelineNextCursor: flat.timelineNextCursor,
      timelineLoadingMore: flat.timelineLoadingMore,
      timelineErrorMessage: flat.timelineErrorMessage,
    }),
    addComment: flat.onAddComment,
    linkResource: flat.onLinkResource,
    replaceOwner: flat.onReplaceOwner,
    assignExecutor: flat.onAssignExecutor,
    assignGateReviewer: flat.onAssignGateReviewer,
    assignAdvisoryReviewer: flat.onAssignAdvisoryReviewer,
    releaseResponsibility: flat.onReleaseResponsibility,
    loadTimelineMore: flat.onLoadTimelineMore,
  } as unknown as WorkItemStore
  const agentStore = {
    state: reactive({
      agents: {
        phase: flat.responsibilityAgentPhase,
        errorMessage: flat.responsibilityAgentErrorMessage,
        loadingMore: flat.responsibilityAgentLoadingMore,
        nextOffset: flat.responsibilityAgentHasMore ? 'offset-1' : null,
      },
    }),
  } as unknown as AgentStore
  const linkStore = {
    state: reactive({
      phase: flat.associationPhase,
      associations: flat.associations,
      errorMessage: flat.associationErrorMessage,
    }),
  } as unknown as ConversationWorkItemLinkStore

  return {
    workItemStore,
    agentStore,
    linkStore,
    drawerProps: {
      scope: flat.scope,
      canParticipate: flat.canParticipate,
      canDelegate: flat.canDelegate,
      canManageResponsibility: flat.canManageResponsibility,
      responsibilityCandidates: flat.responsibilityCandidates,
      responsibilityAgentCandidates: flat.responsibilityAgentCandidates,
      onRetry: flat.onRetry,
      onTransition: flat.onTransition,
      onRetryAvailability: flat.onRetryAvailability,
      onRetryResponsibilityAgents: flat.onRetryResponsibilityAgents,
      onLoadMoreResponsibilityAgents: flat.onLoadMoreResponsibilityAgents,
      onRetryAssociations: flat.onRetryAssociations,
      parallelTasks: flat.parallelTasks,
      taskSelectionRequired: flat.taskSelectionRequired,
      onOpenTask: flat.onOpenTask,
    },
  }
}
