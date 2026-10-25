<script setup lang="ts">
import { useCreationEntry } from '../composables/useCreationEntry'
import { useToast } from '../composables/useToast'
import CopyButton from '../components/base/CopyButton.vue'
import {
  ArrowLeft,
  ArrowRight,
  Bot,
  ChevronRight,
  LockKeyhole,
  Maximize2,
  MessageSquarePlus,
  Plus,
  UsersRound,
} from '@lucide/vue'
import { computed, inject, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { queryValue } from '../utils/queryParams'
import { AUTH_PRINCIPAL } from '../app/auth'
import { subscribeF05Epoch } from '../app/f05Storage'
import { clearConversationCreateDraft } from '../domains/conversation/createDraft'
import { clearConversationMessageDraft, readConversationMessageDraft, writeConversationMessageDraft } from '../domains/conversation/messageDraft'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
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
import PresenceAvatarGroup from '../components/domain/PresenceAvatarGroup.vue'
import TypingIndicator from '../components/domain/TypingIndicator.vue'
import RelativeTime from '../components/base/RelativeTime.vue'
import { useCollaborationPresence } from '../composables/useCollaborationPresence'
import { useConversationParticipants } from '../composables/useConversationParticipants'
import { useConversationReadingPosition } from '../composables/useConversationReadingPosition'
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
import { useCollaborationStore } from '../domains/collaboration/store'
import { type CollaborationScope } from '../domains/collaboration/types'
import { presenceLabels } from '../domains/collaboration/labels'
import { useTaskStore } from '../domains/task/store'
import type { TaskAssociationSummary } from '../domains/task/types'
import type { TeamObserverScope } from '../domains/teamobserver/types'
import type { PrincipalScope } from '../domains/principal/types'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

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
const collaborationStore = useCollaborationStore()
const createOpen = ref(false)
useCreationEntry('conversation', () => scopeStore.state.phase === 'ready'
  ? scopeStore.state.selectedTeamId : null, openCreate)
const createError = ref<string | null>(null)
const detailHeading = ref<HTMLElement | null>(null)
const composer = ref<InstanceType<typeof ConversationComposer> | null>(null)
let createdConversationFocusId: string | null = null
const agentActionRegion = ref<HTMLElement | null>(null)
const messageHistory = ref<HTMLElement | null>(null)
const workspace = ref<HTMLElement | null>(null)
const drafts = reactive(new Map<string, string>())
// A sealed epoch (sign-out or account switch in any tab) empties the in-memory drafts too.
const unsubscribeEpoch = subscribeF05Epoch(epoch => { if (epoch === null) drafts.clear() })
onUnmounted(unsubscribeEpoch)
/*
 * M13b-F09（R51）：偏好版本 v1→v2——侧栏默认从 24%/22% 收窄到 20%/18%，把宽度还给消息列。
 * 旧 v1 的个人比例随版本升级一并作废（usePreference 版本不匹配即回退 fallback），用户重新
 * 拖动过的比例会以 v2 存回。
 */
// M13f：初始 20% 在 1440 视口只有 ~213px，会话标题被时间列挤到不可读（与 1280 断点的
// 220px 下限倒挂）；23% 让标题列回到可读宽度，消息列在 f09 量测视口留 ~20px 余量
// 满足 R51 的 ≥700px 预算（26% 会压到 689px）。
const leftPane = useResizablePane('cs.pref.conversation.left-pane.v2', 23, { min: 16, max: 36, side: 'left' })
const rightPane = useResizablePane('cs.pref.conversation.right-pane.v2', 18, { min: 16, max: 34, side: 'right' })
const leftPaneRatio = leftPane.ratio
const leftPaneCollapsed = leftPane.collapsed
const rightPaneRatio = rightPane.ratio
/*
 * ≤1440 视口下参与者面板默认让位（R51）：它是静态名单，挤占消息列不值得。用户点过一次
 * 「展开参与者」后本会话内不再自动折叠（pinned），偏好里显式保存的展开态同样优先。
 */
const narrowViewport = ref(false)
const rightPanePinnedOpen = ref(false)
const rightPaneCollapsed = computed(() => {
  if (rightPane.collapsed.value) return true // 用户显式折叠（偏好持久）
  if (rightPanePinnedOpen.value) return false // 本会话点过「展开参与者」
  return narrowViewport.value // ≤1440 默认让位给消息列
})
onMounted(() => {
  // 测试 DOM 没有 matchMedia（useConversationReadingPosition 同款防御）。
  if (typeof window.matchMedia !== 'function') return
  const media = window.matchMedia('(max-width: 1440px)')
  narrowViewport.value = media.matches
  const listener = (event: MediaQueryListEvent) => { narrowViewport.value = event.matches }
  media.addEventListener('change', listener)
  onUnmounted(() => media.removeEventListener('change', listener))
})
function pinRightPaneOpen(): void { rightPanePinnedOpen.value = true }
/*
 * 参与者面板的「展开/折叠」语义随自动折叠态分叉：自动折叠态下的「展开」只钉住本会话
 * （不写偏好）；显式折叠后的窄视口展开顺手 pin，避免偏好翻转后被自动态再压回去。
 */
function handleRightPaneToggle(): void {
  if (narrowViewport.value) {
    if (!rightPane.collapsed.value && !rightPanePinnedOpen.value) {
      pinRightPaneOpen()
      return
    }
    pinRightPaneOpen()
  }
  rightPane.toggle()
}
/* 右栏 resizer 的 Enter/Space 与面板按钮同一分叉语义；方向键与 Home 仍走原逻辑。 */
function handleRightResizerKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    handleRightPaneToggle()
    return
  }
  rightPane.handleKeydown(event)
}
/*
 * 专注模式（R51）：⌘⇧F / Ctrl⇧F 进入或退出，Esc 只负责退出——Esc 在对话框与抽屉内
 * 仍归它们自己（先判 closest），专注模式不抢那类关闭。aria-pressed 的切换钮在消息区头部。
 */
const focusMode = ref(false)
function handleFocusModeKeys(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.shiftKey && event.code === 'KeyF') {
    event.preventDefault()
    focusMode.value = !focusMode.value
    return
  }
  if (event.key === 'Escape' && focusMode.value) {
    const target = event.target
    if (target instanceof Element && target.closest('dialog, [role="dialog"], [role="drawer"]')) return
    event.preventDefault()
    focusMode.value = false
  }
}
onMounted(() => document.addEventListener('keydown', handleFocusModeKeys))
onUnmounted(() => document.removeEventListener('keydown', handleFocusModeKeys))
const persistedMessages = computed(() => messageStore.state.items)
const virtualList = useVirtualList(persistedMessages, { keyOf: message => message.id, estimate: 108, overscan: 8, rowGap: 16 })
const visibleMessages = virtualList.visibleItems
const virtualTop = virtualList.topPadding
const virtualBottom = virtualList.bottomPadding
const isFollowing = virtualList.isFollowing
// The folded TaskIntent pre-message card expands for this conversation only, never persisted (R19).
const taskIntentExpanded = ref(false)
// M13b-F03（R02）：确认提案后自动展开关联区——「配置 Coding Task」是确认后的下一跳，
// M9b-Q01 的默认收起决策做出时折叠区还不是唯一的委托入口。
const workItemSectionOpen = ref(false)
const toast = useToast()
let createReturnFocus: HTMLElement | null = null
let conversationReturnFocus: HTMLElement | null = null
let pendingDetailFocus = false
let pendingListFocusConversationId: string | null = null
let synchronizationVersion = 0
// M13d-A21: every mount re-reads the collection once (stale-while-refresh); later watch
// firings within the same visit reuse the cached list.
let firstSynchronizeOfThisMount = true

const teamName = computed(() => scopeStore.selectedTeam.value?.name ?? '团队工作区')
const observerMode = computed(() => queryValue(route.query.assistant) === 'team-observer')
const observerScope = computed<TeamObserverScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const selected = computed(() => conversationStore.state.details?.conversation ?? null)
const activeParticipants = computed(
  () => conversationStore.state.details?.participants.filter(participant => participant.status === 'ACTIVE') ?? [],
)
// M11-F01: presence and typing ride the collaboration signal channel for the selected
// conversation. Acquire follows the selection; the last release drops the subscription.
const conversationCollabScope = computed<CollaborationScope | null>(() => {
  const conversation = selected.value
  const teamId = scopeStore.state.selectedTeamId
  if (!conversation || !principal || !teamId) return null
  return {
    organization: principal.organizationId,
    team: teamId,
    resourceType: 'conversation',
    resourceId: conversation.id,
  }
})
const { presence: conversationPresence, typers: conversationTypers, degraded: conversationDegraded } = useCollaborationPresence(conversationCollabScope)
// REST participant ids are bare UUIDs while the signal channel prefixes `account:`.
const presentParticipantIds = computed(
  () => new Set(conversationPresence.value.map(entry => entry.principalId.replace(/^account:/, ''))))
const { principalNames, participantName, participantRole, participantKind, messageAuthor, isOwnMessage } = useConversationParticipants({ principal, selected, activeParticipants, scopeStore })
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
const workspaceClass = computed(() => ({
  'has-selection': Boolean(conversationStore.state.selectedConversationId),
  'conversation-workspace--focus': focusMode.value,
}))
const workspaceStyle = computed(() => ({
  '--conversation-left-pane': leftPane.collapsed.value ? '44px' : `${leftPane.ratio.value}%`,
  '--conversation-right-pane': rightPaneCollapsed.value ? '44px' : `${rightPane.ratio.value}%`,
}))
const currentDraft = computed({
  get: () => {
    const conversation = selected.value
    if (!conversation) return ''
    // The in-memory map is the live copy; the scoped browser draft fills it lazily on first
    // visit, so re-entering a conversation restores what this browser kept (M9b-F05).
    if (!drafts.has(conversation.id)) {
      const scope = currentScope()
      drafts.set(conversation.id, scope ? readConversationMessageDraft(scope, conversation.id, principal)?.content ?? '' : '')
    }
    return drafts.get(conversation.id) ?? ''
  },
  set: value => {
    const conversation = selected.value
    if (!conversation) return
    drafts.set(conversation.id, value)
    const scope = currentScope()
    if (!scope) return
    if (value.trim()) writeConversationMessageDraft(scope, conversation.id, value, principal)
    else clearConversationMessageDraft(scope, conversation.id, principal)
  },
})
const canPostMessages = computed(() => Boolean(
  selected.value?.status === 'ACTIVE'
  && principal
  && activeParticipants.value.some(participant => participant.principalId === principal.id && participant.role !== 'AGENT'),
))
const canInvokeAgent = computed(() => Boolean(canPostMessages.value && selected.value?.ownerPrincipalId === principal?.id))
// The composer stays untouched: typing rides the draft value itself, so the anchor
// window lives in the store and a restore-into-draft cannot double-fire.
watch(currentDraft, (next, previous) => {
  const scope = conversationCollabScope.value
  if (!scope) return
  if (next.trim() && next !== previous) collaborationStore.signalTyping(scope, true)
  else if (!next.trim()) collaborationStore.signalTyping(scope, false)
})
const agentBusy = computed(() => ['connecting', 'running', 'reconnecting', 'cancelling'].includes(realtimeStore.state.invocationPhase))
const agentNeedsRecovery = computed(() => realtimeStore.state.invocationPhase === 'error' && realtimeStore.state.retryable)
const agentAwaitingClarification = computed(() => realtimeStore.state.invocationPhase === 'interrupted')
const sendingMessage = computed(() => agentBusy.value || messageStore.state.pending.some(message => message.status === 'sending'))
const composerPlaceholder = computed(() => {
  if (!canPostMessages.value) return '加入此 Conversation 后才能发送消息'
  if (!isOnline.value) return '当前离线，可继续编辑草稿，联网后发送…'
  return canInvokeAgent.value ? '向 Personal Agent 描述目标或补充上下文…' : '向 Conversation 追加团队消息…'
})
// 首句建议：所有者尚未发言、对话里已有 Agent 消息（starter 欢迎正是这个形态）且草稿为空时，
// 把欢迎消息里的示例目标降为一键。文案需与 StarterConversationProvisioningService.WELCOME_MARKDOWN
// 中的示例保持一致，两处同步修改。
const STARTER_SUGGESTION = '帮我规划一次版本发布的检查清单'
const starterSuggestion = computed(() => {
  if (!canInvokeAgent.value || agentBusy.value || agentAwaitingClarification.value) return null
  if (currentDraft.value.trim()) return null
  const items = messageStore.state.items
  if (items.length === 0) return null
  return items.some(message => message.type === 'USER_MESSAGE') ? null : STARTER_SUGGESTION
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
watch(
  () => [realtimeStore.state.invocationPhase, Boolean(realtimeStore.state.clarification)] as const,
  async ([phase, hasClarification]) => {
    const pageOwner = pageRequests.capture()
    if (phase !== 'error' && !(phase === 'interrupted' && hasClarification)) return
    await nextTick()
    if (!pageOwner.isCurrent()) return
    const history = messageHistory.value
    if (!history || !agentActionRegion.value) return
    // 只滚动消息历史；scrollIntoView 会连带滚动页面并把窄屏表单放到固定底栏下面。
    history.scrollTo({ top: history.scrollHeight, behavior: 'smooth' })
  },
  { flush: 'post', immediate: true },
)

// The history element binds through a string ref (`ref="messageHistory"` in the template): it only
// fires when the element mounts/unmounts. An inline function ref would re-run on every render, and
// calling onScroll from inside the render effect spins Vue's flush loop forever (reading layout
// synchronously re-triggers rendering) — the render-loop hang.
watch(messageHistory, element => {
  virtualList.container.value = element
  if (element) virtualList.onScroll()
})

function setMessageListElement(element: unknown): void {
  virtualList.listElement.value = element instanceof HTMLElement ? element : null
}

/** M13b-F04（R22 跟随修复）：结构区（提案/关联/注入卡）异步挂载或用户展开只改
 * scrollHeight——容器盒不变（containerObserver 不触发）、行高不动（rowObserver 不涉及）、
 * 也没有 scroll 事件。跟随者合同「贴住最新」在此补偿：结构区高度变化即重贴底；非跟随
 * 不碰（阅读锚点补偿归 useVirtualList 的 anchor 路径管）。 */
const structureBlock = ref<HTMLElement | null>(null)
const structureObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => {
  if (isFollowing.value && messageHistory.value) messageHistory.value.scrollTop = messageHistory.value.scrollHeight
}) : null
watch(structureBlock, element => {
  structureObserver?.disconnect()
  if (element && structureObserver) structureObserver.observe(element)
})
onUnmounted(() => structureObserver?.disconnect())

// Reading position, unread bookkeeping, and the restore chain live in the composable (M12b-F05);
// its watches keep this page's registration order. The folded pre-message card reset stays here:
// it is TaskIntent presentation, not reading state (R19).
const { unreadCount, firstUnreadSequence, handleHistoryScroll, jumpToLatest, newestMessageSequence } = useConversationReadingPosition({
  principal, scopeStore, messageStore, realtimeStore,
  selectedId: computed(() => selected.value?.id ?? null),
  virtualList, messageHistory, pageRequests,
})

watch(() => selected.value?.id, conversationId => {
  if (conversationId) taskIntentExpanded.value = false
})

watch(
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, route.query.conversation, route.query.assistant] as const,
  async ([phase, teamId, conversation, assistant]) => {
    const pageOwner = pageRequests.capture()
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
    await conversationStore.synchronize(scope, conversationId, firstSynchronizeOfThisMount)
    firstSynchronizeOfThisMount = false
    if (!pageOwner.isCurrent()) return
    if (version !== synchronizationVersion) return
    if (conversationId && conversationStore.state.detailPhase === 'ready') {
      const messageScope = { ...scope, conversationId }
      await Promise.all([
        messageStore.synchronize(messageScope),
        linkStore.loadByConversation(messageScope),
        synchronizeConversationTasks(scope, conversationId),
      ])
      if (!pageOwner.isCurrent()) return
      if (version !== synchronizationVersion) return
      realtimeStore.synchronize(messageScope)
      await taskIntentStore.synchronize(messageScope, realtimeStore.state.latestTaskIntentId)
      if (!pageOwner.isCurrent()) return
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
      if (!pageOwner.isCurrent()) return
      return
    }
    // M13i 首跑手感：恰好一个对话的 Team（首日即 starter 欢迎对话）直接打开它——列表里
    // 明明有答案时，详情区不该用空态再向 Owner 要一次点击。带 conversation 参数重进同步
    // 链，刷新/深链自然保持。
    if (!conversationId && conversationStore.state.phase === 'ready'
        && conversationStore.state.items.length === 1) {
      await router.replace({ query: { ...route.query, conversation: conversationStore.state.items[0]!.id } })
      return
    }
    if (pendingDetailFocus && selected.value?.id === conversationId) {
      await nextTick()
      if (!pageOwner.isCurrent()) return
      if (createdConversationFocusId === conversationId && canPostMessages.value) composer.value?.focus()
      else detailHeading.value?.focus()
      createdConversationFocusId = null
      pendingDetailFocus = false
    }
  },
  { immediate: true },
)

watch(
  () => conversationStore.state.selectedConversationId,
  async conversationId => {
    const pageOwner = pageRequests.capture()
    if (conversationId || !pendingListFocusConversationId) return
    await nextTick()
    if (!pageOwner.isCurrent()) return
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()))
    if (!pageOwner.isCurrent()) return
    const returnTarget = conversationReturnFocus?.isConnected
      ? conversationReturnFocus
      : document.querySelector<HTMLButtonElement>(`[data-conversation-id="${pendingListFocusConversationId}"]`)
    returnTarget?.focus({ preventScroll: true })
    conversationReturnFocus = null
    pendingListFocusConversationId = null
  },
)

// M13d-A21: leaving Conversation only closes the browser's SSE subscription (the server-side
// invocation keeps running). The other stores stay cached on the app, so coming back reuses
// the list/detail/messages instead of refetching everything; scope changes and sign-out reset
// them through the session boundary and activateScope epoch guards.
onUnmounted(() => {
  realtimeStore.reset()
})

watch(
  () => realtimeStore.state.messageRefreshVersion,
  async version => {
    const pageOwner = pageRequests.capture()
    if (version === 0) return
    const scope = currentMessageScope()
    if (!scope) return
    try {
      await messageStore.refresh(scope)
      if (!pageOwner.isCurrent()) return
      realtimeStore.reconcile(messageStore.state.items)
    } catch {
      // The stores retain the safe status; route-level authorization still needs immediate handling.
    }
    await redirectIfForbidden()
    if (!pageOwner.isCurrent()) return
  },
)

watch(
  () => taskStore.state.liveRefreshVersion,
  async version => {
    const pageOwner = pageRequests.capture()
    if (version === 0 || !selected.value) return
    await taskStore.loadByConversation(selected.value.id, false, true)
    if (!pageOwner.isCurrent()) return
    synchronizeTaskStreams()
    await redirectIfForbidden()
    if (!pageOwner.isCurrent()) return
  },
)

watch(
  () => [realtimeStore.state.taskIntentRefreshVersion, realtimeStore.state.latestTaskIntentId] as const,
  async ([, taskIntentId]) => {
    const pageOwner = pageRequests.capture()
    const scope = currentMessageScope()
    if (!scope || !taskIntentId) return
    await taskIntentStore.load(scope, taskIntentId, true)
    if (!pageOwner.isCurrent()) return
    if (taskIntentStore.state.intent?.status === 'CONFIRMED') {
      await linkStore.loadByConversation(scope, true)
      if (!pageOwner.isCurrent()) return
    }
    await redirectIfForbidden()
    if (!pageOwner.isCurrent()) return
  },
)

async function selectConversation(conversationId: string): Promise<void> {
  const pageOwner = pageRequests.capture()
  conversationReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  pendingDetailFocus = true
  await router.push({ query: { ...route.query, conversation: conversationId } })
  if (!pageOwner.isCurrent()) return
  if (selected.value?.id === conversationId && conversationStore.state.detailPhase === 'ready') {
    await nextTick()
    if (!pageOwner.isCurrent()) return
    detailHeading.value?.focus()
    pendingDetailFocus = false
  }
}

async function clearConversation(): Promise<void> {
  const pageOwner = pageRequests.capture()
  const conversationId = conversationStore.state.selectedConversationId
  pendingListFocusConversationId = conversationId
  const query = { ...route.query }
  delete query.conversation
  await router.push({ query })
  if (!pageOwner.isCurrent()) return
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
  const pageOwner = pageRequests.captureSelection()
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
    if (!pageOwner.isCurrent()) return
    // Only a successful create discards the browser draft; failures keep it for retry.
    clearConversationCreateDraft(scope, principal)
    closeCreate(false)
    if (conversationId) {
      createdConversationFocusId = conversationId
      pendingDetailFocus = true
      await router.replace({ query: { ...route.query, conversation: conversationId } })
      if (!pageOwner.isCurrent()) return
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
  const pageOwner = pageRequests.captureSelection()
  const scope = currentMessageScope()
  if (!scope) return
  await messageStore.load(scope, true)
  if (!pageOwner.isCurrent()) return
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function submitMessage(content: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const scope = currentMessageScope()
  if (!scope || !principal || !canPostMessages.value || !isOnline.value) return
  const conversationId = scope.conversationId
  currentDraft.value = ''
  const invokesAgent = canInvokeAgent.value
  const sent = invokesAgent
    ? await realtimeStore.invoke(scope, content, principal.id, newestMessageSequence())
    : await messageStore.send(scope, content, principal.id)
  if (!pageOwner.isCurrent()) return
  realtimeStore.reconcile(messageStore.state.items)
  // Keep the original draft available when either transport rejects the submission. In
  // particular, an Agent invocation can fail before a user-visible pending row is committed.
  // The server may also have committed the invocation's user message before the stream failed
  // (真栈判例：#2 已落库而 invoke 返回 false)——恢复草稿会诱发一键重复发送，已落库就不还原。
  if (!sent && selected.value?.id === conversationId && !currentDraft.value) {
    const landed = messageStore.state.items.some(message =>
      message.type === 'USER_MESSAGE'
      && message.authorPrincipalId === principal.id
      && message.content === content)
    if (!landed) currentDraft.value = content
  }
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function retryPendingMessage(clientId: string): Promise<void> {
  const pageOwner = pageRequests.capture()
  const scope = currentMessageScope()
  if (!scope) return
  await messageStore.retry(scope, clientId)
  if (!pageOwner.isCurrent()) return
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function cancelAgentInvocation(): Promise<void> {
  const pageOwner = pageRequests.capture()
  const scope = currentMessageScope()
  if (!scope) return
  await realtimeStore.cancel(scope)
  if (!pageOwner.isCurrent()) return
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function retryAgentInvocation(): Promise<void> {
  const pageOwner = pageRequests.capture()
  const scope = currentMessageScope()
  if (!scope) return
  await realtimeStore.retry(scope)
  if (!pageOwner.isCurrent()) return
  realtimeStore.reconcile(messageStore.state.items)
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function submitClarification(answers: Record<string, string>): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const scope = currentMessageScope()
  if (!scope || !principal) return
  await realtimeStore.resume(scope, answers, principal.id, newestMessageSequence())
  if (!pageOwner.isCurrent()) return
  realtimeStore.reconcile(messageStore.state.items)
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function reviseTaskIntent(input: TaskIntentRevisionInput): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  await taskIntentStore.revise(input)
  if (!pageOwner.isCurrent()) return
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function rejectTaskIntent(reason: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  await taskIntentStore.reject(reason)
  if (!pageOwner.isCurrent()) return
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function confirmTaskIntent(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const confirmed = await taskIntentStore.confirm()
  if (!pageOwner.isCurrent()) return
  const scope = currentMessageScope()
  // Confirmation returns a receipt; the association query is the source of the created WorkItem identity.
  if (confirmed && scope) {
    await Promise.all([
      linkStore.loadByConversation(scope, true),
      taskStore.loadByConversation(scope.conversationId, false, true),
    ])
    if (!pageOwner.isCurrent()) return
    synchronizeTaskStreams()
    // R02：确认成功不再是无反馈的一跳——展开关联区并用 toast 指到委托入口。
    workItemSectionOpen.value = true
    toast.show('已确认任务提案并创建 WorkItem，可在「关联 WorkItem」中配置 Coding Task', {
      tone: 'success',
      action: { label: '查看关联', onClick: () => { workItemSectionOpen.value = true } },
    })
  }
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function retryLinks(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const scope = currentMessageScope()
  if (scope) await linkStore.loadByConversation(scope, true)
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function retryTasks(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const scope = currentMessageScope()
  if (!scope) return
  taskStore.activateScope(scope)
  await taskStore.loadByConversation(scope.conversationId, false, true)
  if (!pageOwner.isCurrent()) return
  synchronizeTaskStreams()
  await redirectIfForbidden()
  if (!pageOwner.isCurrent()) return
}

async function synchronizeConversationTasks(scope: ConversationScope, conversationId: string): Promise<void> {
  const pageOwner = pageRequests.capture()
  taskStore.activateScope(scope)
  taskStore.stopLiveTasks()
  await taskStore.loadByConversation(conversationId, false, true)
  if (!pageOwner.isCurrent()) return
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

/** M13b-F04（R22）：对话面的知识注入入口——注入证据封存在任务详情的「注入与引用」区，
 * 深链带 #ws-injection 锚，抽屉在详情就绪后定位该区（页面级 scrollBehavior 不滚 hash）。 */
function openTaskInjectionEvidence(association: TaskAssociationSummary): void {
  void router.push({
    name: 'work',
    query: {
      ...route.query,
      conversation: selected.value?.id,
      project: association.task.projectId,
      workItem: association.task.workItemId,
      task: association.task.id,
    },
    hash: '#ws-injection',
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
</script>

<template>
  <AppShell :eyebrow="`对话 · ${teamName}`" :title="pageTitle" fill>
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

            <h2>{{ teamName }}</h2>
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
        />
        <StatePanel
          v-else-if="scopeStore.state.phase === 'empty'"
          state="empty"
          title="暂无可用 Team"
          description="创建 Team 后即可创建对话；收到邀请链接？直接打开即可加入。"
        >
          <template #action>
            <RouterLink :to="{ name: 'onboarding' }"><BaseButton size="small">创建 Team</BaseButton></RouterLink>
          </template>
        </StatePanel>
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
            <div class="empty-actions">
              <BaseButton size="small" @click="openCreate">创建第一个对话</BaseButton>
              <!-- F02：对话不是唯一入口——需要 Coding 链路的成员可先去配置，配置完回到这里。 -->
              <RouterLink
                class="empty-actions__secondary"
                :to="{ name: 'setup', query: { from: 'conversation', ...(scopeStore.state.selectedTeamId ? { team: scopeStore.state.selectedTeamId } : {}) } }"
              >需要 Coding？先完成配置</RouterLink>
            </div>
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
              :aria-label="`打开对话 ${conversation.title}${conversation.unreadCount > 0 ? `，${conversation.unreadCount} 条未读` : ''}`"
              @click="selectConversation(conversation.id)"
            >
              <span class="conversation-list__icon" :class="{ team: conversation.visibility === 'TEAM' }">
                <UsersRound v-if="conversation.visibility === 'TEAM'" :size="15" aria-hidden="true" />
                <LockKeyhole v-else :size="15" aria-hidden="true" />
              </span>
              <span class="conversation-list__copy">
                <span class="conversation-list__title-row">
                  <strong>{{ conversation.title }}</strong>
                  <!-- M13b-F06（R12）：查看者视角未读徽标；计数并入按钮 aria-label，徽标本体对读屏隐藏。 -->
                  <span v-if="conversation.unreadCount > 0" class="conversation-unread" aria-hidden="true">{{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}</span>
                </span>
                <small>
                  {{ conversation.visibility === 'TEAM' ? '团队可见' : '私有对话' }}
                  <template v-if="conversation.lastMessageSequence !== null"> · {{ conversation.lastMessageSequence }} 条消息</template>
                </small>
              </span>
              <RelativeTime :value="conversation.updatedAt" />
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
        aria-label="调整对话列表宽度，方向键调整，回车折叠，Home 恢复默认"
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
              <button
                class="focus-mode-toggle touch-target"
                type="button"
                :aria-pressed="focusMode"
                :aria-label="focusMode ? '退出专注模式（Esc）' : '进入专注模式（⌘⇧F）'"
                @click="focusMode = !focusMode"
              ><Maximize2 :size="14" aria-hidden="true" /></button>
              <button class="pane-collapse touch-target" type="button" aria-label="折叠参与者面板" @click="handleRightPaneToggle">{{ rightPaneCollapsed ? '展开参与者' : '折叠参与者' }}</button>
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
            />
            <StatePanel
              v-else-if="messageStore.state.phase === 'error'"
              state="error"
              title="无法加载消息"
              :description="messageStore.state.errorMessage ?? undefined"
              @retry="retryMessages"
            />
            <div v-else ref="messageHistory" class="message-history" tabindex="0" @scroll="handleHistoryScroll">
              <!-- M13b-F04（R22 跟随修复）：结构区长在滚动容器内、虚拟列表之外，包一层只为给
                   ResizeObserver 一个高度锚点——它们的异步挂载/展开只改 scrollHeight（容器盒
                   不变、行高不动、无 scroll 事件），跟随者合同在这里补上重贴底。 -->
              <div ref="structureBlock" class="conversation-structures">
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
              />
              <StatePanel
                v-else-if="taskIntentStore.state.phase === 'error'"
                state="error"
                title="无法加载任务提案"
                :description="taskIntentStore.state.errorMessage ?? undefined"
                @retry="currentMessageScope() && taskIntentStore.state.taskIntentId && taskIntentStore.load(currentMessageScope()!, taskIntentStore.state.taskIntentId, true)"
              />
              <!-- M13b-F09（R51）：结构块默认折叠为单行摘要；待确认（READY）的提案在摘要行
                   保留「待确认」状态词，M9b-Q01 的例外突出语义从「默认展开」降为「单行可见」。 -->
              <details v-if="taskIntentStore.state.intent && principal" class="conversation-structure">
                <summary>任务意图 <span v-if="taskIntentStore.state.intent.status === 'READY'">待确认</span></summary>
                <TaskIntentCard
                  :intent="taskIntentStore.state.intent"
                  :current-principal-id="principal.id"
                  :variant="taskIntentExpanded ? 'full' : 'summary'"
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
                  @expand="taskIntentExpanded = true"
                />
              </details>
              <!-- M9b-Q01（主计划 4.5「关联卡默认摘要，参与者按需展开」）：默认收起，摘要行的
                   计数就是状态；待确认的任务提案卡在上方保持例外突出。F03（R02）：确认提案后
                   程序化展开一次，用户手动收起不被覆盖（toggle 回写）。 -->
              <details v-if="linkStore.state.associations.length || linkStore.state.phase !== 'idle'" class="conversation-structure" :open="workItemSectionOpen" @toggle="workItemSectionOpen = ($event.target as HTMLDetailsElement).open">
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
              <details v-if="taskAssociations.length || (taskAssociationResource?.phase ?? 'idle') !== 'idle'" class="conversation-structure">
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
              <!-- M13b-F04（R22）：注入证据入口卡——不拉新数据，直接链到各关联任务详情的锚点。 -->
              <details v-if="taskAssociations.length" class="conversation-structure">
                <summary>知识注入 <span>{{ taskAssociations.length }} 项任务</span></summary>
                <p class="conversation-injection-note">每次执行送入模型的知识、技能与记忆证据，封存在对应任务详情的「注入与引用」区。</p>
                <ul class="conversation-injection-list">
                  <li v-for="association in taskAssociations" :key="association.task.id">
                    <button type="button" @click="openTaskInjectionEvidence(association)">{{ association.task.objective }}</button>
                  </li>
                </ul>
              </details>
              </div>
              <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ messageAnnouncement }}</p>
              <div
                v-if="messageStore.state.phase === 'empty' && messageStore.state.pending.length === 0 && !visibleInvocationMessage && !visibleStreamedReply && !agentBusy"
                class="message-empty"
              >
                <span><Bot :size="21" aria-hidden="true" /></span>
                <strong>开始这个对话</strong>
                <p>发送第一条消息，向 Personal Agent 描述目标或补充团队上下文。</p>
              </div>
              <ol v-else class="message-list" :ref="setMessageListElement" aria-label="消息历史">
                <li v-if="virtualTop" class="virtual-spacer" :style="{ height: `${virtualTop}px` }" aria-hidden="true" />
                <template v-for="message in visibleMessages" :key="message.id">
                  <li v-if="firstUnreadSequence === message.sequence" class="unread-divider" role="separator">以下是未读消息</li>
                  <li
                    class="message-row"
                    :class="{ own: isOwnMessage(message), agent: message.type === 'AGENT_MESSAGE', system: message.type === 'SYSTEM_NOTICE' }"
                    :data-virtual-key="message.id"
                  >
                    <div v-if="message.type !== 'SYSTEM_NOTICE'" class="message-avatar">
                      <Bot v-if="message.type === 'AGENT_MESSAGE'" :size="15" aria-hidden="true" />
                      <template v-else>{{ messageAuthor(message).slice(0, 1) }}</template>
                    </div>
                    <article>
                      <!-- M13b-F11（C3）：复制反馈统一走 CopyButton——失败显式「无法复制」，成功有播报。 -->
                      <header><strong>{{ messageAuthor(message) }}</strong><RelativeTime :value="message.createdAt" /><span>#{{ message.sequence }}</span><CopyButton class="message-copy" icon-only :value="message.content" :copy-key="message.id" :label="`复制第 ${message.sequence} 条消息的原文`" /></header>
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
                    <header><strong>你</strong><RelativeTime :value="message.createdAt" /><span>{{ message.status === 'sending' ? '发送中' : '发送失败' }}</span></header>
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
                      <RelativeTime v-if="realtimeStore.state.submittedAt" :value="realtimeStore.state.submittedAt" />
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
            <!-- The jump pill overlays the stage instead of living in the scroll content: an
                 in-flow (even sticky) pill pushes the whole list down the moment the follow state
                 flips, and that layout shift is invisible to the anchor compensation. -->
            <div v-if="!isFollowing || unreadCount" class="latest-jump" role="status">
              <span v-if="unreadCount">{{ unreadCount }} 条新消息</span>
              <button type="button" @click="jumpToLatest()">跳到最新</button>
            </div>
          </div>
          <div v-if="conversationDegraded" class="collaboration-strip collaboration-strip--degraded">
            {{ presenceLabels.unavailable }}
          </div>
          <div
            v-else-if="conversationPresence.length > 0 || conversationTypers.length > 0"
            class="collaboration-strip"
          >
            <PresenceAvatarGroup :entries="conversationPresence" />
            <TypingIndicator :typers="conversationTypers" />
          </div>
          <ConversationComposer
            ref="composer"
            v-model="currentDraft"
            :disabled="!canPostMessages || sendingMessage || agentNeedsRecovery || agentAwaitingClarification || messageStore.state.phase === 'loading' || messageStore.state.phase === 'error'"
            :submit-disabled="!isOnline"
            :offline="!isOnline"
            :disabled-reason="composerDisabledReason"
            :sending="sendingMessage"
            :placeholder="composerPlaceholder"
            :suggestion="starterSuggestion"
            @submit="submitMessage"
          />
        </template>
        <div v-else class="conversation-welcome">
          <span><MessageSquarePlus :size="26" aria-hidden="true" /></span>

          <h2>从一个对话开始</h2>
          <p v-if="focus">你正在处理 <strong>{{ focus }}</strong>。</p>
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
        aria-label="调整参与者面板宽度，方向键调整，回车折叠，Home 恢复默认"
        :aria-valuenow="rightPaneRatio"
        aria-valuemin="16"
        aria-valuemax="34"
        @pointerdown="workspace && rightPane.startResize($event, workspace)"
        @keydown="handleRightResizerKeydown"
      />

      <ConversationParticipantsPanel
        :selected="Boolean(selected)"
        :collapsed="rightPaneCollapsed"
        :participants="activeParticipants"
        :present-principal-ids="presentParticipantIds"
        :participant-name="participantName"
        :participant-role="participantRole"
        :participant-kind="participantKind"
        @toggle="handleRightPaneToggle"
      />
    </div>

    <ConversationCreateDialog
      v-if="createOpen && !observerMode"
      :scope="currentScope()"
      :pending="conversationStore.state.commandPending"
      :error="createError"
      @close="closeCreate()"
      @submit="submitCreate"
    />
  </AppShell>
</template>

<style scoped>
/* L03: AppShell fill hands this grid the real remaining viewport height; the panels size from the
 * container, not from a `100vh - guessed chrome` subtraction that drifts whenever the chrome changes.
 * .message-history disables overflow-anchor: the virtual list swaps its rows and spacers wholesale,
 * and the browser's own scroll anchoring re-anchors onto whatever survives — jumping the reading
 * position to unexamined offsets (once straight into the follow zone, flipping isFollowing). The
 * composable's message anchors own that compensation exclusively (§4.4). */
.conversation-workspace { display: grid; flex: 1 1 auto; min-height: min(640px, 100%); grid-template-columns: 310px minmax(440px, 1fr) 280px; gap: var(--cs-space-16); }
.conversation-list-panel, .conversation-detail, .participant-panel { min-height: 0; overflow: hidden; }.conversation-list-panel { display: flex; flex-direction: column; }
.conversation-list-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-20); }
.conversation-list-header h2, .participant-panel h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-md); }.conversation-list-header span, .participant-panel header > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.conversation-list-header__actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.conversation-list-header button { display: grid; width: var(--cs-density-control-height); height: var(--cs-density-control-height); place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent); color: var(--cs-text-brand); cursor: pointer; }
.conversation-list { display: grid; overflow-y: auto; align-content: start; padding: var(--cs-space-8); margin: 0; list-style: none; }
.conversation-item { display: grid; width: 100%; min-height: 67px; grid-template-columns: 34px 1fr auto 15px; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid transparent; border-radius: var(--cs-radius-md); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }
.conversation-item:hover { background: var(--cs-surface-subtle); }.conversation-item.active { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); }
.conversation-list__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 10px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.conversation-list__icon.team { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.conversation-list__copy { min-width: 0; }.conversation-list__copy strong, .conversation-list__copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.conversation-list__copy strong { font-size: var(--cs-text-base); }.conversation-list__copy small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
/* M13b-F06（R12）：未读徽标与标题同行，标题保留收缩省略；徽标不收缩。 */
.conversation-list__title-row { display: flex; min-width: 0; align-items: center; gap: var(--cs-space-8); }
.conversation-list__title-row strong { flex: 1 1 auto; min-width: 0; }
.conversation-unread { flex: 0 0 auto; display: inline-grid; min-width: 20px; height: 20px; place-items: center; padding: 0 var(--cs-space-4); border-radius: 999px; background: var(--cs-action-primary); color: var(--cs-action-primary-text); font: var(--cs-weight-semibold) var(--cs-text-xs) var(--cs-font-sans); }
.conversation-list time { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.conversation-item > svg { color: var(--cs-text-muted); }.load-more-item { margin-top: var(--cs-space-8); }
/* M13f：时间列是 auto 轨道，长相对文案会挤爆 1fr 标题列（213px 栏内标题只剩 45px）；封顶 76px 语义不变。 */
.conversation-item time { max-width: 76px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-detail { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; }.conversation-detail__header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-24) var(--cs-space-16); }.conversation-detail__header h2 { margin: var(--cs-space-8) 0 var(--cs-space-4); border-radius: 4px; font-size: var(--cs-text-lg); }.conversation-detail__header h2:focus-visible { outline: 3px solid var(--cs-ring-brand); outline-offset: 3px; }.conversation-detail__header p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.conversation-kind { display: inline-flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .06em; text-transform: uppercase; }
.message-stage { position: relative; min-height: 0; overflow: hidden; background: linear-gradient(180deg, var(--cs-surface-subtle) 0%, var(--cs-surface-accent) 100%); }.message-stage > :deep(.state-panel) { height: 100%; }.message-history { height: 100%; overflow-y: auto; overflow-anchor: none; padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-24); }.older-messages { display: flex; align-items: center; justify-content: center; gap: var(--cs-space-12); min-height: 32px; margin-bottom: var(--cs-space-8); }.older-messages > span { color: var(--cs-danger); font-size: var(--cs-text-xs); }.message-list { display: grid; gap: var(--cs-space-16); max-width: 900px; padding: 0; margin: 0 auto; list-style: none; }.message-row { display: grid; grid-template-columns: 30px minmax(0, 1fr); align-items: start; gap: var(--cs-space-8); justify-self: start; max-width: min(88%, 760px); }.message-row.own { grid-template-columns: minmax(0, 1fr) 30px; justify-self: end; }.message-row.own .message-avatar { grid-column: 2; }.message-row.own article { grid-column: 1; grid-row: 1; border-color: var(--cs-border-accent); background: var(--cs-surface-accent-strong); }.message-avatar { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 50%; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.message-row.own .message-avatar { background: var(--cs-brand-600); color: var(--cs-text-on-dark); }.message-row article { min-width: 0; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 5px 13px 13px; background: var(--cs-surface); font-size: var(--cs-text-sm); box-shadow: var(--cs-shadow-raised); }.message-row.own article { border-radius: 13px 5px 13px 13px; }.message-row article > header { display: flex; align-items: center; gap: var(--cs-space-8); margin-bottom: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.message-row article > header strong { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.message-row article > header span { margin-left: auto; }.message-copy { display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; margin: calc(var(--cs-space-8) * -1); border: 0; border-radius: 9px; background: transparent; color: var(--cs-text-muted); cursor: pointer; }.message-copy:hover, .message-copy:focus-visible { background: var(--cs-surface-subtle); color: var(--cs-text-brand); }.message-row.system { display: block; justify-self: stretch; max-width: none; text-align: center; }.message-row.system article { display: inline-block; padding: var(--cs-space-8) var(--cs-space-12); border: 0; border-radius: 999px; background: var(--cs-surface-subtle); box-shadow: none; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.message-row.system article > header { justify-content: center; margin-bottom: var(--cs-space-2); }.message-row.pending article { opacity: .72; }.message-row.failed article { border-color: var(--cs-danger-border); background: var(--cs-danger-soft); opacity: 1; }.message-row article > footer { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-8); color: var(--cs-danger); font-size: var(--cs-text-xs); }.message-row article > footer button { margin-left: auto; border: 0; background: transparent; color: var(--cs-danger); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }.message-empty { display: grid; max-width: 360px; place-items: center; gap: var(--cs-space-8); margin: var(--cs-space-64) auto 0; text-align: center; }.message-empty > span, .conversation-welcome > span { display: grid; width: 46px; height: 46px; place-items: center; border: 1px solid var(--cs-agent-border); border-radius: 15px; background: var(--cs-agent-soft); color: var(--cs-agent); }.message-empty strong { font-size: var(--cs-text-base); }.message-empty p { color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.message-row.streaming article { border-color: var(--cs-agent-border); background: var(--cs-agent-soft); transition: opacity var(--cs-motion-base) var(--cs-ease-out), transform var(--cs-motion-base) var(--cs-ease-out); }.message-row.streaming.reconnecting article { border-style: dashed; }.stream-placeholder { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.conversation-welcome { display: grid; max-width: 470px; place-items: center; align-self: center; justify-self: center; padding: var(--cs-space-64) var(--cs-space-24); text-align: center; }.conversation-welcome > span { margin-bottom: var(--cs-space-20); }.conversation-welcome h2 { margin-bottom: var(--cs-space-8); font: var(--cs-text-xl) var(--cs-font-display); }.conversation-welcome > p:not(.eyebrow) { margin-bottom: var(--cs-space-20); color: var(--cs-text-secondary); font-size: var(--cs-text-base); line-height: var(--cs-leading-relaxed); }
.participant-panel header { padding: var(--cs-space-20); }.participant-panel ul { padding: var(--cs-space-8); margin: 0; list-style: none; }.participant-panel li { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12); }.participant-panel li:last-child { border: 0; }.participant-panel li > span:first-child { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.participant-panel li > span.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.participant-panel li strong, .participant-panel li small { display: block; }.participant-panel li strong { font-size: var(--cs-text-sm); }.participant-panel li small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.participant-placeholder { display: grid; place-items: center; gap: var(--cs-space-12); padding: var(--cs-space-48) var(--cs-space-32); color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); text-align: center; }
.mobile-back { display: none; }
@media (max-width: 767px) { .conversation-workspace { display: block; min-height: 0; }.conversation-list-panel, .conversation-detail { min-height: 0; height: 100%; }.conversation-list-panel { display: flex; }.conversation-detail { display: none; }.conversation-workspace.has-selection .conversation-list-panel { display: none; }.conversation-workspace.has-selection .conversation-detail { display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; }.mobile-back { display: flex; align-items: center; gap: var(--cs-space-8); width: 100%; min-height: 42px;  color: var(--cs-text-secondary); font-size: var(--cs-text-sm); cursor: pointer; }.conversation-detail__header { padding: var(--cs-space-16) var(--cs-space-16); }.message-history { padding: var(--cs-space-12) var(--cs-space-12) var(--cs-space-20); }.message-row { max-width: 90%; } }

/* Conversation mode keeps the center timeline fluid while side panes can be adjusted or folded.
 * M13b-F09（R51）：CSS fallback 与 v2 偏好默认同步为 20%/18%。 */
.conversation-workspace { grid-template-columns: var(--conversation-left-pane, 20%) 8px minmax(0, 1fr) 8px var(--conversation-right-pane, 18%); }
/* 专注模式（R51）：左右栏与两个 resizer 整体退场，消息列独占全宽；限定 ≥768 是因为
 * ≤767 的移动互斥布局本来就没有双栏，focus 规则在那里只会误伤列表面板。 */
@media (min-width: 768px) {
  .conversation-workspace--focus { grid-template-columns: minmax(0, 1fr); }
  .conversation-workspace--focus .conversation-list-panel,
  .conversation-workspace--focus .participant-panel,
  .conversation-workspace--focus .pane-resizer { display: none; }
}
.focus-mode-toggle { display: grid; width: 28px; height: 28px; place-items: center; border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); cursor: pointer; }
.focus-mode-toggle[aria-pressed='true'] { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); color: var(--cs-text-brand); }
.focus-mode-toggle:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: 2px; }
.conversation-list-panel.collapsed > :not(header) { display: none; }
.conversation-list-panel.collapsed .conversation-list-header { padding-inline: var(--cs-space-4); }
.conversation-list-panel.collapsed .conversation-list-header > div:first-child { display: none; }
/* M13g：折叠竖条 44px 内，两个动作钮横向放不下会把折叠钮挤出面板（压到拖拽条与内容区）；
   改为纵向堆叠并撑满竖条宽度（宽度即触达面积），内边距收窄到 4px 让「展开」文案完整可读。 */
.conversation-list-panel.collapsed .conversation-list-header__actions { flex-direction: column; align-items: stretch; width: 100%; gap: var(--cs-space-4); }
.conversation-list-panel.collapsed .pane-collapse { width: 100%; min-width: 0; padding-inline: var(--cs-space-4); }
.pane-resizer { position: relative; z-index: 2; width: 8px; min-height: 100%; padding: 0; border: 0; background: transparent; cursor: col-resize; }
.pane-resizer::after { position: absolute; inset: 0 3px; content: ''; background: var(--cs-border); opacity: .65; transition: opacity var(--cs-motion-fast) var(--cs-ease-out); }
.pane-resizer:hover::after, .pane-resizer:focus-visible::after { background: var(--cs-brand-400); opacity: 1; }
.pane-resizer:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: -2px; }
.detail-header-actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.pane-collapse { min-height: 28px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); cursor: pointer; }
/* Overlays the stage (not the scroll content) so its appearance never shifts the reading rows. It
 * sits at the stage's bottom, above the composer: "jump to latest" points down toward the new
 * messages, and the top center — where the "load older" button lives — must stay clickable. */
.latest-jump { position: absolute; z-index: 3; bottom: 4px; left: 50%; transform: translateX(-50%); display: flex; align-items: center; justify-content: center; gap: var(--cs-space-8); width: fit-content; padding: var(--cs-space-4) var(--cs-space-8); border: 1px solid var(--cs-border-accent); border-radius: 999px; background: var(--cs-surface); color: var(--cs-text-brand); font-size: var(--cs-text-xs); box-shadow: var(--cs-shadow-raised); }
.latest-jump button { border: 0; background: transparent; color: inherit; font-size: inherit; font-weight: var(--cs-weight-semibold); cursor: pointer; }
.unread-divider { grid-column: 1 / -1; padding: var(--cs-space-4) 0; border-top: 1px solid var(--cs-border-accent); color: var(--cs-text-brand); font-size: var(--cs-text-xs); text-align: center; }
/* Spacers are grid items, so the row gap also separates a spacer from the edge rows; the padding
 * maths in useVirtualList already subtracts it — here they only occupy pure height. */
.virtual-spacer { pointer-events: none; }
.conversation-structure { max-width: 900px; margin: 0 auto var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-glass); }
.conversation-injection-note { margin: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.conversation-injection-list { display: grid; gap: var(--cs-space-4); margin: 0 var(--cs-space-8) var(--cs-space-8); padding: 0; list-style: none; }
.conversation-injection-list button { padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }
.conversation-injection-list button:hover { background: var(--cs-surface-subtle); border-color: var(--cs-border-accent); }
.conversation-structure summary { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-8) var(--cs-space-12); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.conversation-structure summary span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); }
.conversation-structure > :deep(*) { margin-inline: var(--cs-space-8); }
/* ≤1280: the five-column grid starves the message stage (a 908px container still owes 290+8+280
   plus gaps), and hiding the right resizer strands the participant panel in the dead 0-width
   column. Drop to three columns and move participants to a capped, scrollable bottom row: the
   row gets what one header plus one roster row needs, never the panel's full content height. */
@media (max-width: 1280px) {
  .conversation-workspace { grid-template-columns: minmax(220px, 290px) 8px minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; }
  .conversation-detail__header h2 { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .conversation-detail__header p { display: none; }
  .participant-panel { grid-column: 1 / -1; min-height: 0; max-height: 112px; overflow-y: auto; }
  .participant-panel > :deep(header) { padding-block: var(--cs-space-8); }
  .participant-panel > :deep(header) > :deep(.eyebrow) { display: none; }
  .pane-resizer--right { display: none; }
}
@media (max-width: 900px) { .conversation-workspace { grid-template-columns: minmax(220px, 290px) 8px minmax(0, 1fr); }.participant-panel, .pane-resizer--right { display: none; } }
@media (max-width: 767px) { .conversation-workspace { display: block; }.pane-resizer { display: none; }.conversation-list-panel.collapsed, .participant-panel.collapsed { display: none; }
  /* A stacked document, not the desktop-with-bottom-roster grid: the header caps the ≤1280 rules
   * added (single-line title, hidden version line) are re-opened here — this block sits after the
   * ≤1280 one, so it wins at phone widths. The participant panel itself stays display:none via
   * the ≤900 rule above; only its collapsed class keeps pairing with the list panel's. */
  .conversation-detail__header h2 { overflow: visible; text-overflow: clip; white-space: normal; }.conversation-detail__header p { display: block; } }
@media (prefers-reduced-motion: reduce) { .message-row.streaming article, .pane-resizer::after { transition: none; } }
.empty-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: var(--cs-space-12); }
.empty-actions__secondary { display: inline-flex; align-items: center; min-height: 44px; padding: 0 var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-sm); text-decoration: underline; text-underline-offset: 3px; }
.empty-actions__secondary:hover, .empty-actions__secondary:focus-visible { color: var(--cs-text-brand); }
.collaboration-strip { display: flex; min-height: 26px; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-4) var(--cs-space-8) 0; }
.collaboration-strip--degraded { justify-content: flex-start; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
