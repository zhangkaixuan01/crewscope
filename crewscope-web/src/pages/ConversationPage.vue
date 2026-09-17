<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Bot,
  ChevronRight,
  LockKeyhole,
  MessageSquarePlus,
  Plus,
} from '@lucide/vue'
import { computed, inject, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import BaseTooltip from '../components/base/BaseTooltip.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import ConversationAgentActionRegion from '../components/domain/ConversationAgentActionRegion.vue'
import ConversationComposer from '../components/domain/ConversationComposer.vue'
import ConversationCreateDialog from '../components/domain/ConversationCreateDialog.vue'
import SafeMarkdown from '../components/domain/SafeMarkdown.vue'
import TaskIntentCard from '../components/domain/TaskIntentCard.vue'
import ConversationWorkItemLinks from '../components/domain/ConversationWorkItemLinks.vue'
import ConversationTaskCards from '../components/domain/ConversationTaskCards.vue'
import TeamObserverWorkspace from '../components/domain/TeamObserverWorkspace.vue'
import ConversationParticipantsPanel from '../components/domain/ConversationParticipantsPanel.vue'
import { formatAbsoluteTime, formatRelativeTime } from '../composables/formatRelativeTime'
import { usePreference } from '../composables/usePreference'
import { useResizablePane } from '../composables/useResizablePane'
import { useVirtualList } from '../composables/useVirtualList'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useConversationMessageStore } from '../domains/conversation/messageStore'
import { useConversationRealtimeStore } from '../domains/conversation/realtimeStore'
import { useConversationStore } from '../domains/conversation/store'
import { useTaskIntentStore } from '../domains/conversation/taskIntentStore'
import type { ConversationWorkItemAssociation } from '../domains/conversation/workItemLinkGateway'
import { useConversationWorkItemLinkStore } from '../domains/conversation/workItemLinkStore'
import type {
  ConversationMessage,
  ConversationMessageScope,
  ConversationParticipant,
  ConversationScope,
  ConversationVisibility,
  TaskIntentRevisionInput,
} from '../domains/conversation/types'
import { useScopeStore } from '../domains/scope/store'
import { principalDisplayName, principalNameDirectory } from '../domains/scope/memberDirectory'
import { useTaskStore } from '../domains/task/store'
import type { TaskAssociationSummary } from '../domains/task/types'
import type { TeamObserverScope } from '../domains/teamobserver/types'
import type { PrincipalScope } from '../domains/principal/types'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const conversationStore = useConversationStore()
const messageStore = useConversationMessageStore()
const realtimeStore = useConversationRealtimeStore()
const taskIntentStore = useTaskIntentStore()
const linkStore = useConversationWorkItemLinkStore()
const taskStore = useTaskStore()
const isOnline = useNetworkStatus()
const createOpen = ref(false)
const createError = ref<string | null>(null)
const detailHeading = ref<HTMLElement | null>(null)
const agentActionRegion = ref<HTMLElement | null>(null)
const messageHistory = ref<HTMLElement | null>(null)
const workspace = ref<HTMLElement | null>(null)
const drafts = reactive(new Map<string, string>())
const leftPane = useResizablePane('cs.pref.conversation.left-pane.v1', 24, { min: 16, max: 36 })
const rightPane = useResizablePane('cs.pref.conversation.right-pane.v1', 22, { min: 16, max: 34 })
const leftPaneRatio = leftPane.ratio
const leftPaneCollapsed = leftPane.collapsed
const rightPaneRatio = rightPane.ratio
const rightPaneCollapsed = rightPane.collapsed
const scrollPositions = usePreference<Record<string, number>>('cs.pref.conversation.scroll.v1', {}, { version: 1 })
const readSequences = usePreference<Record<string, number>>('cs.pref.conversation.read-sequences.v1', {}, { version: 1 })
const persistedMessages = computed(() => messageStore.state.items)
const virtualList = useVirtualList(persistedMessages, 108, 8)
const visibleMessages = virtualList.visibleItems
const virtualTop = virtualList.topPadding
const virtualBottom = virtualList.bottomPadding
const atLatest = ref(true)
let createReturnFocus: HTMLElement | null = null
let conversationReturnFocus: HTMLElement | null = null
let pendingDetailFocus = false
let pendingListFocusConversationId: string | null = null
let synchronizationVersion = 0

const teamName = computed(() => scopeStore.selectedTeam.value?.name ?? '团队工作区')
const observerMode = computed(() => queryValue(route.query.assistant) === 'team-observer')
const observerScope = computed<TeamObserverScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const selected = computed(() => conversationStore.state.details?.conversation ?? null)
const activeParticipants = computed(
  () => conversationStore.state.details?.participants.filter(participant => participant.status === 'ACTIVE') ?? [],
)
const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))
// Subject pickers search the Team directory, so they need the Team scope without the conversation.
const principalScope = computed<PrincipalScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const focus = computed(() => queryValue(route.query.focus))
const pageTitle = computed(() => {
  if (observerMode.value) return 'Team Observer'
  if (selected.value) return selected.value.title
  return focus.value ? `团队对话 · ${focus.value}` : '团队对话'
})
const workspaceClass = computed(() => ({ 'has-selection': Boolean(conversationStore.state.selectedConversationId) }))
const workspaceStyle = computed(() => ({
  '--conversation-left-pane': leftPane.collapsed.value ? '44px' : `${leftPane.ratio.value}%`,
  '--conversation-right-pane': rightPane.collapsed.value ? '44px' : `${rightPane.ratio.value}%`,
}))
const currentDraft = computed({
  get: () => selected.value ? (drafts.get(selected.value.id) ?? '') : '',
  set: value => { if (selected.value) drafts.set(selected.value.id, value) },
})
const canPostMessages = computed(() => Boolean(
  selected.value?.status === 'ACTIVE'
  && principal
  && activeParticipants.value.some(participant => participant.principalId === principal.id && participant.role !== 'AGENT'),
))
const canInvokeAgent = computed(() => Boolean(canPostMessages.value && selected.value?.ownerPrincipalId === principal?.id))
const agentBusy = computed(() => ['connecting', 'running', 'reconnecting', 'cancelling'].includes(realtimeStore.state.invocationPhase))
const agentNeedsRecovery = computed(() => realtimeStore.state.invocationPhase === 'error' && realtimeStore.state.retryable)
const agentAwaitingClarification = computed(() => realtimeStore.state.invocationPhase === 'interrupted')
const sendingMessage = computed(() => agentBusy.value || messageStore.state.pending.some(message => message.status === 'sending'))
const composerPlaceholder = computed(() => {
  if (!canPostMessages.value) return '加入此 Conversation 后才能发送消息'
  if (!isOnline.value) return '当前离线，可继续编辑草稿，联网后发送…'
  return canInvokeAgent.value ? '向 Personal Agent 描述目标或补充上下文…' : '向 Conversation 追加团队消息…'
})
const composerDisabledReason = computed(() => {
  if (!canPostMessages.value) return '当前没有此 Conversation 的发言权限'
  if (!isOnline.value) return '当前离线，恢复网络后才能发送'
  if (agentBusy.value) return 'Personal Agent 正在处理上一条消息'
  if (agentNeedsRecovery.value) return '上一条 Agent 调用失败，请先点击“重新连接”'
  if (agentAwaitingClarification.value) return '请先完成 Personal Agent 需要的补充信息'
  if (messageStore.state.phase === 'loading') return '正在加载消息历史'
  if (messageStore.state.phase === 'error') return '消息历史加载失败，请先重试'
  return null
})
const visibleInvocationMessage = computed(() => {
  const content = realtimeStore.state.submittedContent
  if (!content) return null
  return messageStore.state.items.some(message =>
    message.sequence > realtimeStore.state.baselineSequence
    && message.type === 'USER_MESSAGE'
    && message.authorPrincipalId === realtimeStore.state.submittedAuthorPrincipalId
    && message.content === content,
  ) ? null : content
})
const visibleStreamedReply = computed(() => {
  const content = realtimeStore.state.streamedContent
  return content && !messageStore.state.items.some(message => message.type === 'AGENT_MESSAGE' && message.content === content)
    ? content
    : null
})
const agentStatusText = computed(() => ({
  idle: null,
  connecting: '正在连接 Personal Agent',
  running: 'Personal Agent 正在回复',
  reconnecting: '连接中断，正在安全重连',
  cancelling: '正在取消本次调用',
  interrupted: 'Personal Agent 需要补充信息',
  completed: '回复已完成，正在同步事实',
  cancelled: '本次 Agent 调用已取消',
  error: realtimeStore.state.errorMessage ?? 'Agent 暂时无法回复',
}[realtimeStore.state.invocationPhase]))
const showAgentActionRegion = computed(() => Boolean(
  agentStatusText.value
  || (realtimeStore.state.invocationPhase === 'interrupted' && realtimeStore.state.clarification),
))
const messageAnnouncement = computed(() => {
  if (agentStatusText.value) return ''
  const pending = messageStore.state.pending.at(-1)
  if (pending) return pending.status === 'sending' ? '消息正在发送' : '消息发送失败，可以重试'
  const latest = messageStore.state.items.at(-1)
  return latest ? `消息历史已更新，最新消息来自${messageAuthor(latest)}` : ''
})
const taskAssociationResource = computed(() => selected.value
  ? taskStore.state.associationPages[`conversation:${selected.value.id}`] ?? null
  : null)
const taskAssociations = computed(() => taskAssociationResource.value?.value?.items ?? [])
const canConfigureConfirmedCoding = computed(() => Boolean(
  principal
  && (taskIntentStore.state.intent?.status === 'CONFIRMED'
    ? taskIntentStore.state.intent.proposal.owner.principalId === principal.id
    : selected.value?.ownerPrincipalId === principal.id),
))
const unreadCount = computed(() => {
  const selectedId = selected.value?.id
  if (!selectedId) return 0
  const readSequence = readSequences.value.value[selectedId] ?? newestMessageSequence()
  return messageStore.state.items.filter(message => message.sequence > readSequence).length
})
const firstUnreadSequence = computed(() => {
  const selectedId = selected.value?.id
  if (!selectedId) return null
  const readSequence = readSequences.value.value[selectedId] ?? newestMessageSequence()
  return messageStore.state.items.find(message => message.sequence > readSequence)?.sequence ?? null
})

watch(
  () => [realtimeStore.state.invocationPhase, Boolean(realtimeStore.state.clarification)] as const,
  async ([phase, hasClarification]) => {
    if (phase !== 'error' && !(phase === 'interrupted' && hasClarification)) return
    await nextTick()
    const history = messageHistory.value
    if (!history || !agentActionRegion.value) return
    // 只滚动消息历史；scrollIntoView 会连带滚动页面并把窄屏表单放到固定底栏下面。
    history.scrollTo({ top: history.scrollHeight, behavior: 'smooth' })
  },
  { flush: 'post', immediate: true },
)

watch(messageHistory, element => {
  virtualList.container.value = element
  if (element && selected.value) {
    element.scrollTop = scrollPositions.value.value[selected.value.id] ?? element.scrollHeight
    virtualList.onScroll()
  }
}, { flush: 'post' })

watch(() => selected.value?.id, async conversationId => {
  if (!conversationId) return
  await nextTick()
  if (!messageHistory.value) return
  messageHistory.value.scrollTop = scrollPositions.value.value[conversationId] ?? messageHistory.value.scrollHeight
  virtualList.reset()
  atLatest.value = isNearLatest()
})

watch(() => [selected.value?.id, messageStore.state.phase] as const, ([conversationId, phase]) => {
  if (!conversationId || phase !== 'ready' || readSequences.value.value[conversationId] !== undefined) return
  // The initial history is considered read; subsequent realtime facts become the unread boundary.
  readSequences.value.value = { ...readSequences.value.value, [conversationId]: newestMessageSequence() }
})

watch(() => messageStore.state.items.length, async () => {
  await nextTick()
  if (atLatest.value && messageHistory.value) jumpToLatest()
})

watch(() => realtimeStore.state.streamedContent.length, async () => {
  await nextTick()
  if (atLatest.value && messageHistory.value) jumpToLatest()
})

onMounted(() => {
  virtualList.container.value = messageHistory.value
})

watch(
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, route.query.conversation, route.query.assistant] as const,
  async ([phase, teamId, conversation, assistant]) => {
    if (phase !== 'ready' || !teamId || !principal) {
      if (phase === 'empty') {
        conversationStore.reset()
        messageStore.reset()
        realtimeStore.reset()
        taskIntentStore.reset()
        taskStore.reset()
      }
      return
    }
    if (queryValue(assistant) === 'team-observer') {
      // Team Observer owns a dedicated Session/Invocation state machine, never a Personal Conversation aggregate.
      // Invalidate the whole Personal Conversation synchronization chain before clearing its stores.
      synchronizationVersion += 1
      conversationStore.reset()
      messageStore.reset()
      realtimeStore.reset()
      taskIntentStore.reset()
      linkStore.reset()
      taskStore.reset()
      return
    }
    const version = ++synchronizationVersion
    const scope = { organizationId: principal.organizationId, teamId }
    void scopeStore.loadMembers()
    const conversationId = queryValue(conversation)
    await conversationStore.synchronize(scope, conversationId)
    if (version !== synchronizationVersion) return
    if (conversationId && conversationStore.state.detailPhase === 'ready') {
      const messageScope = { ...scope, conversationId }
      await Promise.all([
        messageStore.synchronize(messageScope),
        linkStore.loadByConversation(messageScope),
        synchronizeConversationTasks(scope, conversationId),
      ])
      if (version !== synchronizationVersion) return
      realtimeStore.synchronize(messageScope)
      await taskIntentStore.synchronize(messageScope, realtimeStore.state.latestTaskIntentId)
      realtimeStore.reconcile(messageStore.state.items)
    } else {
      messageStore.reset()
      realtimeStore.reset()
      taskIntentStore.reset()
      linkStore.reset()
      taskStore.reset()
    }
    if (version !== synchronizationVersion) return
    if (isForbidden()) {
      await router.replace({ name: 'access-denied', query: { from: route.fullPath } })
      return
    }
    if (pendingDetailFocus && selected.value?.id === conversationId) {
      await nextTick()
      detailHeading.value?.focus()
      pendingDetailFocus = false
    }
  },
  { immediate: true },
)

watch(
  () => conversationStore.state.selectedConversationId,
  async conversationId => {
    if (conversationId || !pendingListFocusConversationId) return
    await nextTick()
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()))
    const returnTarget = conversationReturnFocus?.isConnected
      ? conversationReturnFocus
      : document.querySelector<HTMLButtonElement>(`[data-conversation-id="${pendingListFocusConversationId}"]`)
    returnTarget?.focus({ preventScroll: true })
    conversationReturnFocus = null
    pendingListFocusConversationId = null
  },
)

// Leaving Conversation closes only browser subscriptions; the server-side invocation keeps running.
onUnmounted(() => {
  realtimeStore.reset()
  taskIntentStore.reset()
  linkStore.reset()
  taskStore.reset()
  messageStore.reset()
  conversationStore.reset()
})

watch(
  () => realtimeStore.state.messageRefreshVersion,
  async version => {
    if (version === 0) return
    const scope = currentMessageScope()
    if (!scope) return
    try {
      await messageStore.refresh(scope)
      realtimeStore.reconcile(messageStore.state.items)
    } catch {
      // The stores retain the safe status; route-level authorization still needs immediate handling.
    }
    await redirectIfForbidden()
  },
)

watch(
  () => taskStore.state.liveRefreshVersion,
  async version => {
    if (version === 0 || !selected.value) return
    await taskStore.loadByConversation(selected.value.id, false, true)
    synchronizeTaskStreams()
    await redirectIfForbidden()
  },
)

watch(
  () => [realtimeStore.state.taskIntentRefreshVersion, realtimeStore.state.latestTaskIntentId] as const,
  async ([, taskIntentId]) => {
    const scope = currentMessageScope()
    if (!scope || !taskIntentId) return
    await taskIntentStore.load(scope, taskIntentId, true)
    if (taskIntentStore.state.intent?.status === 'CONFIRMED') {
      await linkStore.loadByConversation(scope, true)
    }
    await redirectIfForbidden()
  },
)

async function selectConversation(conversationId: string): Promise<void> {
  conversationReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  pendingDetailFocus = true
  await router.push({ query: { ...route.query, conversation: conversationId } })
  if (selected.value?.id === conversationId && conversationStore.state.detailPhase === 'ready') {
    await nextTick()
    detailHeading.value?.focus()
    pendingDetailFocus = false
  }
}

async function clearConversation(): Promise<void> {
  const conversationId = conversationStore.state.selectedConversationId
  pendingListFocusConversationId = conversationId
  const query = { ...route.query }
  delete query.conversation
  await router.push({ query })
}

function openCreate(): void {
  createReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  createError.value = null
  createOpen.value = true
}

function closeCreate(restoreFocus = true): void {
  createOpen.value = false
  if (!restoreFocus) {
    createReturnFocus = null
    return
  }
  const returnTarget = createReturnFocus
  createReturnFocus = null
  void nextTick(() => returnTarget?.focus())
}

async function submitCreate(payload: { title: string, visibility: ConversationVisibility }): Promise<void> {
  const title = payload.title.trim()
  if (!title) {
    createError.value = '请输入对话标题'
    return
  }
  const scope = currentScope()
  if (!scope) return
  createError.value = null
  try {
    const conversationId = await conversationStore.create(scope, {
      title,
      visibility: payload.visibility,
    })
    closeCreate(false)
    if (conversationId) {
      pendingDetailFocus = true
      await router.replace({ query: { ...route.query, conversation: conversationId } })
    }
  } catch {
    createError.value = conversationStore.state.commandErrorMessage
  }
}

async function retryCollection(): Promise<void> {
  const scope = currentScope()
  if (scope) await conversationStore.load(scope, true)
}

async function retryDetails(): Promise<void> {
  const scope = currentScope()
  const conversationId = conversationStore.state.selectedConversationId
  if (scope && conversationId) await conversationStore.select(scope, conversationId)
}

async function retryMessages(): Promise<void> {
  const scope = currentMessageScope()
  if (!scope) return
  await messageStore.load(scope, true)
  await redirectIfForbidden()
}

async function submitMessage(content: string): Promise<void> {
  const scope = currentMessageScope()
  if (!scope || !principal || !canPostMessages.value || !isOnline.value) return
  const conversationId = scope.conversationId
  currentDraft.value = ''
  const invokesAgent = canInvokeAgent.value
  const sent = invokesAgent
    ? await realtimeStore.invoke(scope, content, principal.id, newestMessageSequence())
    : await messageStore.send(scope, content, principal.id)
  // Keep the original draft available when either transport rejects the submission. In
  // particular, an Agent invocation can fail before a user-visible pending row is committed.
  if (!sent && selected.value?.id === conversationId && !currentDraft.value) currentDraft.value = content
  realtimeStore.reconcile(messageStore.state.items)
  await redirectIfForbidden()
}

async function retryPendingMessage(clientId: string): Promise<void> {
  const scope = currentMessageScope()
  if (!scope) return
  await messageStore.retry(scope, clientId)
  await redirectIfForbidden()
}

async function cancelAgentInvocation(): Promise<void> {
  const scope = currentMessageScope()
  if (!scope) return
  await realtimeStore.cancel(scope)
  await redirectIfForbidden()
}

async function retryAgentInvocation(): Promise<void> {
  const scope = currentMessageScope()
  if (!scope) return
  await realtimeStore.retry(scope)
  realtimeStore.reconcile(messageStore.state.items)
  await redirectIfForbidden()
}

async function submitClarification(answers: Record<string, string>): Promise<void> {
  const scope = currentMessageScope()
  if (!scope || !principal) return
  await realtimeStore.resume(scope, answers, principal.id, newestMessageSequence())
  realtimeStore.reconcile(messageStore.state.items)
  await redirectIfForbidden()
}

async function reviseTaskIntent(input: TaskIntentRevisionInput): Promise<void> {
  await taskIntentStore.revise(input)
  await redirectIfForbidden()
}

async function rejectTaskIntent(reason: string): Promise<void> {
  await taskIntentStore.reject(reason)
  await redirectIfForbidden()
}

async function confirmTaskIntent(): Promise<void> {
  const confirmed = await taskIntentStore.confirm()
  const scope = currentMessageScope()
  // Confirmation returns a receipt; the association query is the source of the created WorkItem identity.
  if (confirmed && scope) {
    await Promise.all([
      linkStore.loadByConversation(scope, true),
      taskStore.loadByConversation(scope.conversationId, false, true),
    ])
    synchronizeTaskStreams()
  }
  await redirectIfForbidden()
}

async function retryLinks(): Promise<void> {
  const scope = currentMessageScope()
  if (scope) await linkStore.loadByConversation(scope, true)
  await redirectIfForbidden()
}

async function retryTasks(): Promise<void> {
  const scope = currentMessageScope()
  if (!scope) return
  taskStore.activateScope(scope)
  await taskStore.loadByConversation(scope.conversationId, false, true)
  synchronizeTaskStreams()
  await redirectIfForbidden()
}

async function synchronizeConversationTasks(scope: ConversationScope, conversationId: string): Promise<void> {
  taskStore.activateScope(scope)
  taskStore.stopLiveTasks()
  await taskStore.loadByConversation(conversationId, false, true)
  synchronizeTaskStreams()
}

function synchronizeTaskStreams(): void {
  const activeTaskIds = taskAssociations.value
    .filter(association => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(association.task.status))
    .map(association => association.task.id)
  taskStore.synchronizeLiveTasks(activeTaskIds)
}

function openLinkedWorkItem(association: ConversationWorkItemAssociation): void {
  void router.push({
    name: 'work',
    query: {
      ...route.query,
      conversation: association.conversation.id,
      project: association.workItem.projectId,
      workItem: association.workItem.id,
      focus: association.workItem.key,
      sourceMessage: latestTaskSourceMessageId(),
    },
  })
}

function openCodingDelegation(association: ConversationWorkItemAssociation): void {
  void router.push({
    name: 'work',
    query: {
      ...route.query,
      conversation: association.conversation.id,
      project: association.workItem.projectId,
      workItem: association.workItem.id,
      focus: association.workItem.key,
      sourceMessage: latestTaskSourceMessageId(),
      delegate: 'coding',
    },
  })
}

function openAssociatedTask(association: TaskAssociationSummary): void {
  void router.push({
    name: 'work',
    query: {
      ...route.query,
      conversation: selected.value?.id,
      project: association.task.projectId,
      workItem: association.task.workItemId,
      task: association.task.id,
    },
  })
}

function openTaskWorkItem(association: TaskAssociationSummary): void {
  void router.push({
    name: 'work',
    query: {
      ...route.query,
      conversation: selected.value?.id,
      project: association.task.projectId,
      workItem: association.task.workItemId,
      task: undefined,
      sourceMessage: latestTaskSourceMessageId(),
    },
  })
}

function newestMessageSequence(): number {
  return messageStore.state.items.reduce((latest, message) => Math.max(latest, message.sequence), 0)
}

function latestTaskSourceMessageId(): string | undefined {
  return [...messageStore.state.items]
    .sort((left, right) => right.sequence - left.sequence)
    .find(message => message.type === 'USER_MESSAGE')?.id
}

function currentScope(): ConversationScope | null {
  if (!principal || !scopeStore.state.selectedTeamId) return null
  return { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
}

function currentMessageScope(): ConversationMessageScope | null {
  const scope = currentScope()
  const conversationId = selected.value?.id
  return scope && conversationId ? { ...scope, conversationId } : null
}

function participantName(participant: ConversationParticipant): string {
  return participant.displayName?.trim()
    || principalDisplayName(
      principalNames.value,
      participant.principalId,
      participant.role === 'AGENT' ? 'Agent' : '成员',
    )
}

function participantRole(participant: ConversationParticipant): string {
  if (participant.role === 'OWNER') return '对话创建者 · OWNER'
  if (participant.role === 'MEMBER') return '团队参与者 · MEMBER'
  if (participant.principalType === 'PERSONAL_AGENT') {
    const ownerName = participant.ownerDisplayName?.trim()
      || (participant.ownerPrincipalId
        ? principalDisplayName(principalNames.value, participant.ownerPrincipalId)
        : null)
    return ownerName ? `${ownerName}的 Personal Agent · AGENT` : 'Personal Agent · AGENT'
  }
  if (participant.principalType === 'TEAM_AGENT') return '团队 Agent · AGENT'
  return '执行 Agent · AGENT'
}

function participantKind(participant: ConversationParticipant): string {
  if (participant.role !== 'AGENT') return '团队成员'
  return ({
    PERSONAL_AGENT: '个人 Agent',
    TEAM_AGENT: '团队 Agent',
    SPECIALIST_AGENT: '专项 Agent',
    USER: '执行主体',
    SERVICE: '服务主体',
  })[participant.principalType]
}

function messageAuthor(message: ConversationMessage): string {
  if (message.type === 'SYSTEM_NOTICE') return 'CrewScope'
  if (message.authorPrincipalId === principal?.id) return '你'
  if (message.authorPrincipalId === selected.value?.personalAgentPrincipalId) return 'Personal Agent'
  const participant = activeParticipants.value.find(item => item.principalId === message.authorPrincipalId)
  return participant
    ? participantName(participant)
    : message.authorPrincipalId
      ? principalDisplayName(principalNames.value, message.authorPrincipalId)
      : '未知成员'
}

function isOwnMessage(message: ConversationMessage): boolean {
  return message.type === 'USER_MESSAGE' && message.authorPrincipalId === principal?.id
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(value))
}

function isForbidden(): boolean {
  return conversationStore.state.errorStatus === 403
    || conversationStore.state.detailErrorStatus === 403
    || messageStore.state.errorStatus === 403
    || messageStore.state.commandErrorStatus === 403
    || realtimeStore.state.errorStatus === 403
    || taskIntentStore.state.errorStatus === 403
    || taskIntentStore.state.commandErrorStatus === 403
    || linkStore.state.errorStatus === 403
    || taskAssociationResource.value?.errorStatus === 403
}

async function redirectIfForbidden(): Promise<void> {
  if (isForbidden()) await router.replace({ name: 'access-denied', query: { from: route.fullPath } })
}

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function handleHistoryScroll(): void {
  virtualList.onScroll()
  const history = messageHistory.value
  if (!history || !selected.value) return
  scrollPositions.value.value = { ...scrollPositions.value.value, [selected.value.id]: history.scrollTop }
  atLatest.value = isNearLatest()
  if (atLatest.value) {
    readSequences.value.value = { ...readSequences.value.value, [selected.value.id]: newestMessageSequence() }
  }
}

function isNearLatest(): boolean {
  const history = messageHistory.value
  return !history || history.scrollHeight - history.scrollTop - history.clientHeight < 48
}

function jumpToLatest(): void {
  const history = messageHistory.value
  if (!history) return
  if (typeof history.scrollTo === 'function') history.scrollTo({ top: history.scrollHeight, behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
  else history.scrollTop = history.scrollHeight
  atLatest.value = true
  if (selected.value) readSequences.value.value = { ...readSequences.value.value, [selected.value.id]: newestMessageSequence() }
}

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
}
</script>

<template>
  <AppShell :eyebrow="`对话 · ${teamName}`" :title="pageTitle">
    <template #actions>
      <RouterLink v-if="observerMode" v-slot="{ navigate }" custom :to="{ name: 'conversation', query: { ...route.query, assistant: undefined } }">
        <BaseButton variant="secondary" size="small" @click="navigate"><Bot :size="14" />Personal Agent 对话</BaseButton>
      </RouterLink>
      <RouterLink v-else v-slot="{ navigate }" custom :to="{ name: 'conversation', query: { ...route.query, conversation: undefined, assistant: 'team-observer' } }">
        <BaseButton variant="secondary" size="small" @click="navigate"><UsersRound :size="14" />Team Observer</BaseButton>
      </RouterLink>
      <BaseButton v-if="!observerMode" size="small" @click="openCreate">
        <template #icon><Plus :size="14" aria-hidden="true" /></template>
        新建对话
      </BaseButton>
      <RouterLink v-slot="{ navigate }" custom :to="observerMode ? { name: 'team-observer', query: route.query } : { name: 'today', query: route.query }">
        <BaseButton variant="secondary" size="small" @click="navigate">
          {{ observerMode ? '查看团队摘要' : '在工作台查看' }}<ArrowRight :size="14" aria-hidden="true" />
        </BaseButton>
      </RouterLink>
    </template>

    <TeamObserverWorkspace v-if="observerMode && observerScope" :scope="observerScope" :team-name="teamName" :online="isOnline" variant="conversation" />
    <div v-else ref="workspace" class="conversation-workspace" :class="workspaceClass" :style="workspaceStyle">
      <section class="panel conversation-list-panel" :class="{ collapsed: leftPaneCollapsed }" aria-label="对话列表">
        <header class="conversation-list-header">
          <div>
            <p class="eyebrow">Collaborate</p>
            <h2>{{ teamName }}</h2>
            <span>选择一个对话继续协作</span>
          </div>
          <div class="conversation-list-header__actions">
            <button type="button" aria-label="新建对话" @click="openCreate"><MessageSquarePlus :size="18" /></button>
            <button class="pane-collapse touch-target" type="button" aria-label="折叠对话列表" @click="leftPane.toggle">{{ leftPaneCollapsed ? '展开' : '折叠' }}</button>
          </div>
        </header>

        <StatePanel
          v-if="scopeStore.state.phase === 'loading' || conversationStore.state.phase === 'loading'"
          state="loading"
          title="正在加载对话"
          description="正在恢复当前 Team 的可见对话。"
        />
        <StatePanel
          v-else-if="scopeStore.state.phase === 'empty'"
          state="empty"
          title="暂无可用 Team"
          description="加入 Team 后即可创建对话。"
        />
        <StatePanel
          v-else-if="conversationStore.state.phase === 'error'"
          state="error"
          title="无法加载对话"
          :description="conversationStore.state.errorMessage ?? undefined"
          @retry="retryCollection"
        />
        <StatePanel
          v-else-if="conversationStore.state.phase === 'empty'"
          state="empty"
          title="这个 Team 还没有对话"
          description="创建一个 PRIVATE 或 TEAM 对话，从自然语言目标开始协作。"
        >
          <template #action>
            <BaseButton size="small" @click="openCreate">创建第一个对话</BaseButton>
          </template>
        </StatePanel>
        <ul v-else class="conversation-list">
          <li
            v-for="conversation in conversationStore.state.items"
            :key="conversation.id"
          >
            <button
              class="conversation-item"
              type="button"
              :data-conversation-id="conversation.id"
              :class="{ active: conversation.id === conversationStore.state.selectedConversationId }"
              :aria-label="`打开对话 ${conversation.title}`"
              @click="selectConversation(conversation.id)"
            >
              <span class="conversation-list__icon" :class="{ team: conversation.visibility === 'TEAM' }">
                <UsersRound v-if="conversation.visibility === 'TEAM'" :size="15" aria-hidden="true" />
                <LockKeyhole v-else :size="15" aria-hidden="true" />
              </span>
              <span class="conversation-list__copy">
                <strong>{{ conversation.title }}</strong>
                <small>
                  {{ conversation.visibility === 'TEAM' ? '团队可见' : '私有对话' }}
                  <template v-if="conversation.lastMessageSequence !== null"> · {{ conversation.lastMessageSequence }} 条消息</template>
                </small>
              </span>
              <BaseTooltip :text="formatAbsoluteTime(conversation.updatedAt)"><time :datetime="conversation.updatedAt">{{ formatRelativeTime(conversation.updatedAt) }}</time></BaseTooltip>
              <ChevronRight :size="15" aria-hidden="true" />
            </button>
          </li>
          <li v-if="conversationStore.state.nextCursor" class="load-more-item">
            <BaseButton
              class="load-more"
              variant="ghost"
              size="small"
              :loading="conversationStore.state.loadingMore"
              @click="conversationStore.loadMore"
            >加载更多对话</BaseButton>
          </li>
        </ul>
      </section>

      <button
        class="pane-resizer pane-resizer--left"
        type="button"
        role="separator"
        aria-label="调整对话列表宽度，使用左右方向键"
        :aria-valuenow="leftPaneRatio"
        aria-valuemin="16"
        aria-valuemax="36"
        @pointerdown="workspace && leftPane.startResize($event, workspace)"
        @keydown="leftPane.handleKeydown"
      />

      <section class="panel conversation-detail" aria-label="对话详情">
        <button v-if="conversationStore.state.selectedConversationId" class="mobile-back" type="button" @click="clearConversation">
          <ArrowLeft :size="16" aria-hidden="true" />返回对话列表
        </button>
        <StatePanel
          v-if="conversationStore.state.detailPhase === 'loading'"
          state="loading"
          title="正在恢复对话"
          description="正在读取服务端当前会话与参与者事实。"
        />
        <StatePanel
          v-else-if="conversationStore.state.detailPhase === 'error'"
          state="error"
          title="无法打开这个对话"
          :description="conversationStore.state.detailErrorMessage ?? undefined"
          @retry="retryDetails"
        />
        <template v-else-if="selected">
          <header class="conversation-detail__header">
            <div>
              <span class="conversation-kind">
                <UsersRound v-if="selected.visibility === 'TEAM'" :size="14" />
                <LockKeyhole v-else :size="14" />
                {{ selected.visibility === 'TEAM' ? 'TEAM Conversation' : 'PRIVATE Conversation' }}
              </span>
              <h2 ref="detailHeading" tabindex="-1">{{ selected.title }}</h2>
              <p>创建于 {{ formatDate(selected.createdAt) }} · 当前事实版本 v{{ selected.version }}</p>
            </div>
            <div class="detail-header-actions">
              <StatusBadge tone="success">活跃</StatusBadge>
              <button class="pane-collapse touch-target" type="button" aria-label="折叠参与者面板" @click="rightPane.toggle">{{ rightPaneCollapsed ? '展开参与者' : '折叠参与者' }}</button>
            </div>
          </header>
          <div
            class="message-stage"
            :aria-busy="messageStore.state.phase === 'loading' || agentBusy"
          >
            <StatePanel
              v-if="messageStore.state.phase === 'loading'"
              state="loading"
              title="正在加载消息"
              description="正在按服务端 Sequence 恢复最新会话历史。"
            />
            <StatePanel
              v-else-if="messageStore.state.phase === 'error'"
              state="error"
              title="无法加载消息"
              :description="messageStore.state.errorMessage ?? undefined"
              @retry="retryMessages"
            />
            <div v-else ref="messageHistory" class="message-history" tabindex="0" @scroll="handleHistoryScroll">
              <div v-if="!atLatest || unreadCount" class="latest-jump" role="status">
                <span v-if="unreadCount">{{ unreadCount }} 条新消息</span>
                <button type="button" @click="jumpToLatest">跳到最新</button>
              </div>
              <div v-if="messageStore.state.nextCursor" class="older-messages">
                <BaseButton
                  variant="ghost"
                  size="small"
                  :loading="messageStore.state.loadingOlder"
                  @click="messageStore.loadOlder"
                >加载更早消息</BaseButton>
                <span v-if="messageStore.state.olderErrorMessage" role="alert">{{ messageStore.state.olderErrorMessage }}</span>
              </div>
              <StatePanel
                v-if="taskIntentStore.state.phase === 'loading'"
                state="loading"
                title="正在读取任务提案"
                description="正在同步服务端最新 TaskIntent 事实。"
              />
              <StatePanel
                v-else-if="taskIntentStore.state.phase === 'error'"
                state="error"
                title="无法加载任务提案"
                :description="taskIntentStore.state.errorMessage ?? undefined"
                @retry="currentMessageScope() && taskIntentStore.state.taskIntentId && taskIntentStore.load(currentMessageScope()!, taskIntentStore.state.taskIntentId, true)"
              />
              <details v-if="taskIntentStore.state.intent && principal" class="conversation-structure" open>
                <summary>任务意图 <span>可折叠</span></summary>
                <TaskIntentCard
                  :intent="taskIntentStore.state.intent"
                  :current-principal-id="principal.id"
                  :pending="taskIntentStore.state.commandPending"
                  :error-message="taskIntentStore.state.commandErrorMessage"
                  :version-conflict="taskIntentStore.state.versionConflict"
                  :principal-names="principalNames"
                  :members="scopeStore.state.members"
                  :projects="scopeStore.state.projects"
                  :scope="principalScope"
                  @revise="reviseTaskIntent"
                  @reject="rejectTaskIntent"
                  @confirm="confirmTaskIntent"
                />
              </details>
              <details v-if="linkStore.state.associations.length || linkStore.state.phase !== 'idle'" class="conversation-structure" :open="Boolean(linkStore.state.associations.length)">
                <summary>关联 WorkItem <span>{{ linkStore.state.associations.length }} 项</span></summary>
                <ConversationWorkItemLinks
                  :phase="linkStore.state.phase"
                  :associations="linkStore.state.associations"
                  :error-message="linkStore.state.errorMessage"
                  :can-delegate="canConfigureConfirmedCoding"
                  direction="conversation"
                  @open="openLinkedWorkItem"
                  @delegate="openCodingDelegation"
                  @retry="retryLinks"
                />
              </details>
              <details v-if="taskAssociations.length || (taskAssociationResource?.phase ?? 'idle') !== 'idle'" class="conversation-structure" :open="Boolean(taskAssociations.length)">
                <summary>关联任务 <span>{{ taskAssociations.length }} 项</span></summary>
                <ConversationTaskCards
                  :phase="taskAssociationResource?.phase ?? 'idle'"
                  :associations="taskAssociations"
                  :live-tasks="taskStore.state.liveTasks"
                  :error-message="taskAssociationResource?.errorMessage ?? null"
                  :current-principal-id="principal?.id ?? ''"
                  :principal-names="principalNames"
                  @open-task="openAssociatedTask"
                  @open-work-item="openTaskWorkItem"
                  @retry="retryTasks"
                />
              </details>
              <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ messageAnnouncement }}</p>
              <div
                v-if="messageStore.state.phase === 'empty' && messageStore.state.pending.length === 0 && !visibleInvocationMessage && !visibleStreamedReply && !agentBusy"
                class="message-empty"
              >
                <span><Bot :size="21" aria-hidden="true" /></span>
                <strong>开始这个对话</strong>
                <p>发送第一条消息，向 Personal Agent 描述目标或补充团队上下文。</p>
              </div>
              <ol v-else class="message-list" aria-label="消息历史">
                <li v-if="virtualTop" class="virtual-spacer" :style="{ height: `${virtualTop}px` }" aria-hidden="true" />
                <template v-for="message in visibleMessages" :key="message.id">
                  <li v-if="firstUnreadSequence === message.sequence" class="unread-divider" role="separator">以下是未读消息</li>
                  <li
                    class="message-row"
                    :class="{ own: isOwnMessage(message), agent: message.type === 'AGENT_MESSAGE', system: message.type === 'SYSTEM_NOTICE' }"
                  >
                    <div v-if="message.type !== 'SYSTEM_NOTICE'" class="message-avatar">
                      <Bot v-if="message.type === 'AGENT_MESSAGE'" :size="15" aria-hidden="true" />
                      <template v-else>{{ messageAuthor(message).slice(0, 1) }}</template>
                    </div>
                    <article>
                      <header><strong>{{ messageAuthor(message) }}</strong><BaseTooltip :text="formatAbsoluteTime(message.createdAt)"><time :datetime="message.createdAt">{{ formatRelativeTime(message.createdAt) }}</time></BaseTooltip><span>#{{ message.sequence }}</span></header>
                      <SafeMarkdown :content="message.content" />
                    </article>
                  </li>
                </template>
                <li v-if="virtualBottom" class="virtual-spacer" :style="{ height: `${virtualBottom}px` }" aria-hidden="true" />
                <li
                  v-for="message in messageStore.state.pending"
                  :key="message.clientId"
                  class="message-row own pending"
                  :class="{ failed: message.status === 'failed' }"
                >
                  <div class="message-avatar">你</div>
                  <article>
                    <header><strong>你</strong><BaseTooltip :text="formatAbsoluteTime(message.createdAt)"><time :datetime="message.createdAt">{{ formatRelativeTime(message.createdAt) }}</time></BaseTooltip><span>{{ message.status === 'sending' ? '发送中' : '发送失败' }}</span></header>
                    <SafeMarkdown :content="message.content" />
                    <footer v-if="message.status === 'failed'">
                      <span role="alert">{{ message.errorMessage }}</span>
                      <button type="button" :disabled="sendingMessage" @click="retryPendingMessage(message.clientId)">重试发送</button>
                    </footer>
                  </article>
                </li>
                <li v-if="visibleInvocationMessage" class="message-row own pending invocation-pending">
                  <div class="message-avatar">你</div>
                  <article>
                    <header>
                      <strong>你</strong>
                      <BaseTooltip v-if="realtimeStore.state.submittedAt" :text="formatAbsoluteTime(realtimeStore.state.submittedAt)"><time :datetime="realtimeStore.state.submittedAt">{{ formatRelativeTime(realtimeStore.state.submittedAt) }}</time></BaseTooltip>
                      <span>{{ realtimeStore.state.invocationPhase === 'connecting' ? '提交中' : '已提交 · 等待事实同步' }}</span>
                    </header>
                    <SafeMarkdown :content="visibleInvocationMessage" />
                  </article>
                </li>
                <li
                  v-if="visibleStreamedReply || agentBusy"
                  class="message-row agent streaming"
                  :class="{ reconnecting: realtimeStore.state.invocationPhase === 'reconnecting' }"
                >
                  <div class="message-avatar"><Bot :size="15" aria-hidden="true" /></div>
                  <article>
                    <header>
                      <strong>Personal Agent</strong>
                      <span>{{ realtimeStore.state.invocationPhase === 'reconnecting' ? '重连中' : '实时回复' }}</span>
                    </header>
                    <SafeMarkdown v-if="visibleStreamedReply" :content="visibleStreamedReply" />
                    <p v-else class="stream-placeholder">正在理解目标并准备回复…</p>
                  </article>
                </li>
              </ol>
              <div v-if="showAgentActionRegion" ref="agentActionRegion">
                <ConversationAgentActionRegion
                  :phase="realtimeStore.state.invocationPhase"
                  :status-text="agentStatusText"
                  :invocation-id="realtimeStore.state.invocationId"
                  :online="isOnline"
                  :retryable="realtimeStore.state.retryable"
                  :clarification="realtimeStore.state.clarification"
                  @cancel="cancelAgentInvocation"
                  @retry="retryAgentInvocation"
                  @submit-clarification="submitClarification"
                />
              </div>
            </div>
          </div>
          <ConversationComposer
            v-model="currentDraft"
            :disabled="!canPostMessages || sendingMessage || agentNeedsRecovery || agentAwaitingClarification || messageStore.state.phase === 'loading' || messageStore.state.phase === 'error'"
            :submit-disabled="!isOnline"
            :offline="!isOnline"
            :disabled-reason="composerDisabledReason"
            :sending="sendingMessage"
            :placeholder="composerPlaceholder"
            @submit="submitMessage"
          />
        </template>
        <div v-else class="conversation-welcome">
          <span><MessageSquarePlus :size="26" aria-hidden="true" /></span>
          <p class="eyebrow">Conversation Mode</p>
          <h2>从一个对话开始</h2>
          <p v-if="focus">你正在处理 <strong>{{ focus }}</strong>。选择已有对话，或建立新的协作上下文。</p>
          <p v-else>选择已有对话，或创建一个新对话向 Personal Agent 表达目标。</p>
          <BaseButton @click="openCreate">
            <template #icon><Plus :size="15" /></template>
            新建对话
          </BaseButton>
        </div>
      </section>

      <button
        class="pane-resizer pane-resizer--right"
        type="button"
        role="separator"
        aria-label="调整参与者面板宽度，使用左右方向键"
        :aria-valuenow="rightPaneRatio"
        aria-valuemin="16"
        aria-valuemax="34"
        @pointerdown="workspace && rightPane.startResize($event, workspace)"
        @keydown="rightPane.handleKeydown"
      />

      <ConversationParticipantsPanel
        :selected="Boolean(selected)"
        :collapsed="rightPaneCollapsed"
        :participants="activeParticipants"
        :participant-name="participantName"
        :participant-role="participantRole"
        :participant-kind="participantKind"
        @toggle="rightPane.toggle"
      />
    </div>

    <ConversationCreateDialog
      v-if="createOpen && !observerMode"
      :pending="conversationStore.state.commandPending"
      :error="createError"
      @close="closeCreate()"
      @submit="submitCreate"
    />
  </AppShell>
</template>

<style scoped>
.conversation-workspace { display: grid; min-height: calc(100vh - 176px); grid-template-columns: 310px minmax(440px, 1fr) 280px; gap: var(--cs-space-16); }
.conversation-list-panel, .conversation-detail, .participant-panel { min-height: 640px; overflow: hidden; }.conversation-list-panel { display: flex; height: calc(100vh - 176px); flex-direction: column; }
.conversation-list-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }
.conversation-list-header h2, .participant-panel h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-md); }.conversation-list-header span, .participant-panel header > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.conversation-list-header__actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.conversation-list-header button { display: grid; width: var(--cs-density-control-height); height: var(--cs-density-control-height); place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent); color: var(--cs-text-brand); cursor: pointer; }
.conversation-list { display: grid; overflow-y: auto; align-content: start; padding: var(--cs-space-8); margin: 0; list-style: none; }
.conversation-item { display: grid; width: 100%; min-height: 67px; grid-template-columns: 34px 1fr auto 15px; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid transparent; border-radius: var(--cs-radius-md); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }
.conversation-item:hover { background: var(--cs-surface-subtle); }.conversation-item.active { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); }
.conversation-list__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 10px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.conversation-list__icon.team { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.conversation-list__copy { min-width: 0; }.conversation-list__copy strong, .conversation-list__copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.conversation-list__copy strong { font-size: var(--cs-text-base); }.conversation-list__copy small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.conversation-list time { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.conversation-item > svg { color: var(--cs-text-muted); }.load-more-item { margin-top: var(--cs-space-8); }
.conversation-detail { display: grid; height: calc(100vh - 176px); grid-template-rows: auto minmax(0, 1fr) auto; }.conversation-detail__header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-24) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.conversation-detail__header h2 { margin: var(--cs-space-8) 0 var(--cs-space-4); border-radius: 4px; font-size: var(--cs-text-lg); }.conversation-detail__header h2:focus-visible { outline: 3px solid var(--cs-ring-brand); outline-offset: 3px; }.conversation-detail__header p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.conversation-kind { display: inline-flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .06em; text-transform: uppercase; }
.message-stage { min-height: 0; overflow: hidden; background: linear-gradient(180deg, var(--cs-surface-subtle) 0%, var(--cs-surface-accent) 100%); }.message-stage > :deep(.state-panel) { height: 100%; }.message-history { height: 100%; overflow-y: auto; padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-24); }.older-messages { display: flex; align-items: center; justify-content: center; gap: var(--cs-space-12); min-height: 32px; margin-bottom: var(--cs-space-8); }.older-messages > span { color: var(--cs-danger); font-size: var(--cs-text-xs); }.message-list { display: grid; gap: var(--cs-space-16); max-width: 740px; padding: 0; margin: 0 auto; list-style: none; }.message-row { display: grid; grid-template-columns: 30px minmax(0, 1fr); align-items: start; gap: var(--cs-space-8); justify-self: start; max-width: min(82%, 620px); }.message-row.own { grid-template-columns: minmax(0, 1fr) 30px; justify-self: end; }.message-row.own .message-avatar { grid-column: 2; }.message-row.own article { grid-column: 1; grid-row: 1; border-color: var(--cs-border-accent); background: var(--cs-surface-accent-strong); }.message-avatar { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 50%; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.message-row.own .message-avatar { background: var(--cs-brand-600); color: var(--cs-text-on-dark); }.message-row article { min-width: 0; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 5px 13px 13px; background: var(--cs-surface); font-size: var(--cs-text-sm); box-shadow: var(--cs-shadow-raised); }.message-row.own article { border-radius: 13px 5px 13px 13px; }.message-row article > header { display: flex; align-items: center; gap: var(--cs-space-8); margin-bottom: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.message-row article > header strong { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.message-row article > header span { margin-left: auto; }.message-row.system { display: block; justify-self: stretch; max-width: none; text-align: center; }.message-row.system article { display: inline-block; padding: var(--cs-space-8) var(--cs-space-12); border: 0; border-radius: 999px; background: var(--cs-surface-subtle); box-shadow: none; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.message-row.system article > header { justify-content: center; margin-bottom: var(--cs-space-2); }.message-row.pending article { opacity: .72; }.message-row.failed article { border-color: var(--cs-danger-border); background: var(--cs-danger-soft); opacity: 1; }.message-row article > footer { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-8); color: var(--cs-danger); font-size: var(--cs-text-xs); }.message-row article > footer button { margin-left: auto; border: 0; background: transparent; color: var(--cs-danger); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }.message-empty { display: grid; max-width: 360px; place-items: center; gap: var(--cs-space-8); margin: var(--cs-space-64) auto 0; text-align: center; }.message-empty > span, .conversation-welcome > span { display: grid; width: 46px; height: 46px; place-items: center; border: 1px solid var(--cs-agent-border); border-radius: 15px; background: var(--cs-agent-soft); color: var(--cs-agent); }.message-empty strong { font-size: var(--cs-text-base); }.message-empty p { color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.message-row.streaming article { border-color: var(--cs-agent-border); background: var(--cs-agent-soft); transition: opacity var(--cs-motion-base) var(--cs-ease-out), transform var(--cs-motion-base) var(--cs-ease-out); }.message-row.streaming.reconnecting article { border-style: dashed; }.stream-placeholder { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.conversation-welcome { display: grid; max-width: 470px; place-items: center; align-self: center; justify-self: center; padding: var(--cs-space-64) var(--cs-space-24); text-align: center; }.conversation-welcome > span { margin-bottom: var(--cs-space-20); }.conversation-welcome h2 { margin-bottom: var(--cs-space-8); font: var(--cs-text-xl) var(--cs-font-display); }.conversation-welcome > p:not(.eyebrow) { margin-bottom: var(--cs-space-20); color: var(--cs-text-secondary); font-size: var(--cs-text-base); line-height: var(--cs-leading-relaxed); }
.participant-panel header { padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.participant-panel ul { padding: var(--cs-space-8); margin: 0; list-style: none; }.participant-panel li { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12); border-bottom: 1px solid var(--cs-border); }.participant-panel li:last-child { border: 0; }.participant-panel li > span:first-child { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.participant-panel li > span.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.participant-panel li strong, .participant-panel li small { display: block; }.participant-panel li strong { font-size: var(--cs-text-sm); }.participant-panel li small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.participant-placeholder { display: grid; place-items: center; gap: var(--cs-space-12); padding: var(--cs-space-48) var(--cs-space-32); color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); text-align: center; }
.mobile-back { display: none; }
@media (max-width: 1280px) { .conversation-workspace { grid-template-columns: 290px minmax(420px, 1fr); }.participant-panel { grid-column: 1 / -1; min-height: auto; }.participant-panel ul { display: grid; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 900px) { .conversation-workspace { grid-template-columns: 270px 1fr; }.participant-panel { display: none; } }
@media (max-width: 767px) { .conversation-workspace { display: block; min-height: calc(100dvh - 208px); }.conversation-list-panel, .conversation-detail { min-height: calc(100dvh - 208px); }.conversation-list-panel { height: calc(100dvh - 208px); }.conversation-detail { display: none; height: calc(100dvh - 208px); }.conversation-workspace.has-selection .conversation-list-panel { display: none; }.conversation-workspace.has-selection .conversation-detail { display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; }.mobile-back { display: flex; align-items: center; gap: var(--cs-space-8); width: 100%; min-height: 42px; padding: 0 var(--cs-space-16); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); cursor: pointer; }.conversation-detail__header { padding: var(--cs-space-16) var(--cs-space-16); }.message-history { padding: var(--cs-space-12) var(--cs-space-12) var(--cs-space-20); }.message-row { max-width: 90%; } }

/* Conversation mode keeps the center timeline fluid while side panes can be adjusted or folded. */
.conversation-workspace { grid-template-columns: var(--conversation-left-pane, 24%) 8px minmax(0, 1fr) 8px var(--conversation-right-pane, 22%); }
.conversation-list-panel.collapsed > :not(header), .participant-panel.collapsed > :not(header) { display: none; }
.conversation-list-panel.collapsed .conversation-list-header, .participant-panel.collapsed > header { padding-inline: var(--cs-space-8); }
.conversation-list-panel.collapsed .conversation-list-header > div:first-child, .participant-panel.collapsed > header > :not(.pane-collapse) { display: none; }
.pane-resizer { position: relative; z-index: 2; width: 8px; min-height: 100%; padding: 0; border: 0; background: transparent; cursor: col-resize; }
.pane-resizer::after { position: absolute; inset: 0 3px; content: ''; background: var(--cs-border); opacity: .65; transition: opacity var(--cs-motion-fast) var(--cs-ease-out); }
.pane-resizer:hover::after, .pane-resizer:focus-visible::after { background: var(--cs-brand-400); opacity: 1; }
.pane-resizer:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: -2px; }
.detail-header-actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.pane-collapse { min-height: 28px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); cursor: pointer; }
.latest-jump { position: sticky; z-index: 3; top: 4px; display: flex; align-items: center; justify-content: center; gap: var(--cs-space-8); width: fit-content; margin: 0 auto var(--cs-space-8); padding: var(--cs-space-4) var(--cs-space-8); border: 1px solid var(--cs-border-accent); border-radius: 999px; background: var(--cs-surface); color: var(--cs-text-brand); font-size: var(--cs-text-xs); box-shadow: var(--cs-shadow-raised); }
.latest-jump button { border: 0; background: transparent; color: inherit; font-size: inherit; font-weight: var(--cs-weight-semibold); cursor: pointer; }
.unread-divider { grid-column: 1 / -1; padding: var(--cs-space-4) 0; border-top: 1px solid var(--cs-border-accent); color: var(--cs-text-brand); font-size: var(--cs-text-xs); text-align: center; }
.virtual-spacer { pointer-events: none; }
.conversation-structure { max-width: 740px; margin: 0 auto var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-glass); }
.conversation-structure summary { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-8) var(--cs-space-12); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.conversation-structure summary span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); }
.conversation-structure > :deep(*) { margin-inline: var(--cs-space-8); }
@media (max-width: 1280px) { .conversation-workspace { grid-template-columns: minmax(220px, 290px) 8px minmax(0, 1fr) 0 minmax(0, 280px); }.participant-panel { grid-column: auto; }.pane-resizer--right { display: none; } }
@media (max-width: 900px) { .conversation-workspace { grid-template-columns: minmax(220px, 290px) 8px minmax(0, 1fr); }.participant-panel, .pane-resizer--right { display: none; } }
@media (max-width: 767px) { .conversation-workspace { display: block; }.pane-resizer { display: none; }.conversation-list-panel.collapsed, .participant-panel.collapsed { display: none; } }
@media (prefers-reduced-motion: reduce) { .message-row.streaming article, .pane-resizer::after { transition: none; } }
</style>
