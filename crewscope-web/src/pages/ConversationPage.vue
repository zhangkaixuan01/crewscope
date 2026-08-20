<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Bot,
  ChevronRight,
  CircleStop,
  LockKeyhole,
  MessageSquarePlus,
  Plus,
  UsersRound,
  X,
} from '@lucide/vue'
import { computed, inject, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import ConversationComposer from '../components/domain/ConversationComposer.vue'
import ClarificationCard from '../components/domain/ClarificationCard.vue'
import SafeMarkdown from '../components/domain/SafeMarkdown.vue'
import TaskIntentCard from '../components/domain/TaskIntentCard.vue'
import ConversationWorkItemLinks from '../components/domain/ConversationWorkItemLinks.vue'
import ConversationTaskCards from '../components/domain/ConversationTaskCards.vue'
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
import { useTaskStore } from '../domains/task/store'
import type { TaskAssociationSummary } from '../domains/task/types'

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
const createTitle = ref('')
const createVisibility = ref<ConversationVisibility>('PRIVATE')
const createError = ref<string | null>(null)
const createDialog = ref<HTMLElement | null>(null)
const createTitleInput = ref<HTMLInputElement | null>(null)
const detailHeading = ref<HTMLElement | null>(null)
const drafts = reactive(new Map<string, string>())
let createReturnFocus: HTMLElement | null = null
let conversationReturnFocus: HTMLElement | null = null
let pendingDetailFocus = false
let pendingListFocusConversationId: string | null = null
let synchronizationVersion = 0

const teamName = computed(() => scopeStore.selectedTeam.value?.name ?? '团队工作区')
const selected = computed(() => conversationStore.state.details?.conversation ?? null)
const activeParticipants = computed(
  () => conversationStore.state.details?.participants.filter(participant => participant.status === 'ACTIVE') ?? [],
)
const focus = computed(() => queryValue(route.query.focus))
const pageTitle = computed(() => {
  if (selected.value) return selected.value.title
  return focus.value ? `团队对话 · ${focus.value}` : '团队对话'
})
const workspaceClass = computed(() => ({ 'has-selection': Boolean(conversationStore.state.selectedConversationId) }))
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
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, route.query.conversation] as const,
  async ([phase, teamId, conversation]) => {
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
    const version = ++synchronizationVersion
    const scope = { organizationId: principal.organizationId, teamId }
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
  createTitle.value = ''
  createVisibility.value = 'PRIVATE'
  createError.value = null
  createOpen.value = true
  void nextTick(() => createTitleInput.value?.focus())
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

function handleCreateDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeCreate()
    return
  }
  if (event.key !== 'Tab' || !createDialog.value) return
  const focusable = [...createDialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter(element => !element.hasAttribute('hidden'))
  if (focusable.length === 0) return
  const first = focusable[0]
  const last = focusable.at(-1)
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

async function submitCreate(): Promise<void> {
  const title = createTitle.value.trim()
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
      visibility: createVisibility.value,
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
  if (!invokesAgent && !sent && selected.value?.id === conversationId && !currentDraft.value) currentDraft.value = content
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
  if (participant.principalId === principal?.id) return principal.displayName
  if (participant.role === 'AGENT') return 'Personal Agent'
  return `成员 ${participant.principalId.slice(0, 8)}`
}

function participantRole(participant: ConversationParticipant): string {
  return ({ OWNER: 'Owner', MEMBER: '参与者', AGENT: 'Personal Agent' })[participant.role]
}

function messageAuthor(message: ConversationMessage): string {
  if (message.type === 'SYSTEM_NOTICE') return 'CrewScope'
  if (message.authorPrincipalId === principal?.id) return '你'
  if (message.authorPrincipalId === selected.value?.personalAgentPrincipalId) return 'Personal Agent'
  const participant = activeParticipants.value.find(item => item.principalId === message.authorPrincipalId)
  return participant ? participantName(participant) : `成员 ${message.authorPrincipalId?.slice(0, 8) ?? '未知'}`
}

function isOwnMessage(message: ConversationMessage): boolean {
  return message.type === 'USER_MESSAGE' && message.authorPrincipalId === principal?.id
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(value))
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
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
</script>

<template>
  <AppShell :eyebrow="`Conversation · ${teamName}`" :title="pageTitle">
    <template #actions>
      <BaseButton size="small" @click="openCreate">
        <template #icon><Plus :size="14" aria-hidden="true" /></template>
        新建对话
      </BaseButton>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'today', query: route.query }">
        <BaseButton variant="secondary" size="small" @click="navigate">
          在工作台查看<ArrowRight :size="14" aria-hidden="true" />
        </BaseButton>
      </RouterLink>
    </template>

    <div class="conversation-workspace" :class="workspaceClass">
      <section class="panel conversation-list-panel" aria-label="对话列表">
        <header class="conversation-list-header">
          <div>
            <p class="eyebrow">Collaborate</p>
            <h2>{{ teamName }}</h2>
            <span>选择一个对话继续协作</span>
          </div>
          <button type="button" aria-label="新建对话" @click="openCreate"><MessageSquarePlus :size="18" /></button>
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
              <time :datetime="conversation.updatedAt">{{ formatDate(conversation.updatedAt) }}</time>
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
            <StatusBadge tone="success">活跃</StatusBadge>
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
            <div v-else class="message-history">
              <div
                v-if="agentStatusText"
                class="agent-live-status"
                :class="{
                  error: realtimeStore.state.invocationPhase === 'error',
                  cancelled: realtimeStore.state.invocationPhase === 'cancelled',
                }"
                :role="realtimeStore.state.invocationPhase === 'error' ? 'alert' : 'status'"
                :aria-live="realtimeStore.state.invocationPhase === 'error' ? 'assertive' : 'polite'"
                aria-atomic="true"
              >
                <span>
                  <CircleStop v-if="realtimeStore.state.invocationPhase === 'cancelled'" :size="14" aria-hidden="true" />
                  <Bot v-else :size="14" aria-hidden="true" />
                  {{ agentStatusText }}
                </span>
                <button
                  v-if="(agentBusy || agentAwaitingClarification) && realtimeStore.state.invocationId"
                  type="button"
                  :disabled="realtimeStore.state.invocationPhase === 'cancelling' || !isOnline"
                  @click="cancelAgentInvocation"
                >取消</button>
                <button
                  v-else-if="agentNeedsRecovery"
                  type="button"
                  :disabled="!isOnline"
                  @click="retryAgentInvocation"
                >重新连接</button>
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
              <ClarificationCard
                v-if="realtimeStore.state.invocationPhase === 'interrupted' && realtimeStore.state.clarification"
                :request="realtimeStore.state.clarification"
                @submit="submitClarification"
              />
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
              <TaskIntentCard
                v-else-if="taskIntentStore.state.intent && principal"
                :intent="taskIntentStore.state.intent"
                :current-principal-id="principal.id"
                :pending="taskIntentStore.state.commandPending"
                :error-message="taskIntentStore.state.commandErrorMessage"
                :version-conflict="taskIntentStore.state.versionConflict"
                @revise="reviseTaskIntent"
                @reject="rejectTaskIntent"
                @confirm="confirmTaskIntent"
              />
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
              <ConversationTaskCards
                :phase="taskAssociationResource?.phase ?? 'idle'"
                :associations="taskAssociations"
                :live-tasks="taskStore.state.liveTasks"
                :error-message="taskAssociationResource?.errorMessage ?? null"
                :current-principal-id="principal?.id ?? ''"
                @open-task="openAssociatedTask"
                @open-work-item="openTaskWorkItem"
                @retry="retryTasks"
              />
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
                <li
                  v-for="message in messageStore.state.items"
                  :key="message.id"
                  class="message-row"
                  :class="{ own: isOwnMessage(message), agent: message.type === 'AGENT_MESSAGE', system: message.type === 'SYSTEM_NOTICE' }"
                >
                  <div v-if="message.type !== 'SYSTEM_NOTICE'" class="message-avatar">
                    <Bot v-if="message.type === 'AGENT_MESSAGE'" :size="15" aria-hidden="true" />
                    <template v-else>{{ messageAuthor(message).slice(0, 1) }}</template>
                  </div>
                  <article>
                    <header><strong>{{ messageAuthor(message) }}</strong><time :datetime="message.createdAt">{{ formatTime(message.createdAt) }}</time><span>#{{ message.sequence }}</span></header>
                    <SafeMarkdown :content="message.content" />
                  </article>
                </li>
                <li
                  v-for="message in messageStore.state.pending"
                  :key="message.clientId"
                  class="message-row own pending"
                  :class="{ failed: message.status === 'failed' }"
                >
                  <div class="message-avatar">你</div>
                  <article>
                    <header><strong>你</strong><time :datetime="message.createdAt">{{ formatTime(message.createdAt) }}</time><span>{{ message.status === 'sending' ? '发送中' : '发送失败' }}</span></header>
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
                      <time v-if="realtimeStore.state.submittedAt" :datetime="realtimeStore.state.submittedAt">{{ formatTime(realtimeStore.state.submittedAt) }}</time>
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
            </div>
          </div>
          <ConversationComposer
            v-model="currentDraft"
            :disabled="!canPostMessages || sendingMessage || agentNeedsRecovery || agentAwaitingClarification || messageStore.state.phase === 'loading' || messageStore.state.phase === 'error'"
            :submit-disabled="!isOnline"
            :offline="!isOnline"
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

      <aside class="panel participant-panel" aria-label="对话参与者">
        <header>
          <p class="eyebrow">Current facts</p>
          <h2>参与者</h2>
          <span>{{ selected ? `${activeParticipants.length} 个当前主体` : '选择对话后查看' }}</span>
        </header>
        <ul v-if="selected">
          <li v-for="participant in activeParticipants" :key="participant.id">
            <span :class="{ agent: participant.role === 'AGENT' }">
              <Bot v-if="participant.role === 'AGENT'" :size="15" />
              <template v-else>{{ participantName(participant).slice(0, 1) }}</template>
            </span>
            <div><strong>{{ participantName(participant) }}</strong><small>{{ participantRole(participant) }}</small></div>
            <StatusBadge :tone="participant.role === 'AGENT' ? 'agent' : 'neutral'">在线范围</StatusBadge>
          </li>
        </ul>
        <div v-else class="participant-placeholder">
          <UsersRound :size="22" aria-hidden="true" />
          <span>Owner、Personal Agent 和显式参与者将在这里展示。</span>
        </div>
      </aside>
    </div>

    <div v-if="createOpen" class="dialog-backdrop" @keydown="handleCreateDialogKeydown">
      <section ref="createDialog" class="create-dialog" role="dialog" aria-modal="true" aria-labelledby="create-conversation-title">
        <header>
          <div><p class="eyebrow">New conversation</p><h2 id="create-conversation-title">新建对话</h2></div>
          <button type="button" aria-label="关闭新建对话" @click="closeCreate()"><X :size="18" /></button>
        </header>
        <form @submit.prevent="submitCreate">
          <label>
            <span>标题</span>
            <input ref="createTitleInput" v-model="createTitle" maxlength="200" autocomplete="off" placeholder="例如：规划 GitHub Provider 接入" />
          </label>
          <fieldset>
            <legend>可见范围</legend>
            <label :class="{ active: createVisibility === 'PRIVATE' }">
              <input v-model="createVisibility" type="radio" value="PRIVATE" />
              <LockKeyhole :size="17" /><span><strong>私有对话</strong><small>仅 Owner、Personal Agent 与显式参与者可见</small></span>
            </label>
            <label :class="{ active: createVisibility === 'TEAM' }">
              <input v-model="createVisibility" type="radio" value="TEAM" />
              <UsersRound :size="17" /><span><strong>团队对话</strong><small>当前 Team 成员可发现，写入仍需 Participant 资格</small></span>
            </label>
          </fieldset>
          <p v-if="createError" class="form-error" role="alert">{{ createError }}</p>
          <footer>
            <BaseButton variant="secondary" @click="closeCreate()">取消</BaseButton>
            <BaseButton type="submit" :loading="conversationStore.state.commandPending">创建对话</BaseButton>
          </footer>
        </form>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.conversation-workspace { display: grid; min-height: calc(100vh - 176px); grid-template-columns: 310px minmax(440px, 1fr) 280px; gap: 14px; }
.conversation-list-panel, .conversation-detail, .participant-panel { min-height: 640px; overflow: hidden; }.conversation-list-panel { display: flex; height: calc(100vh - 176px); flex-direction: column; }
.conversation-list-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 18px; border-bottom: 1px solid var(--cs-border); }
.conversation-list-header h2, .participant-panel h2 { margin-bottom: 3px; font-size: 15px; }.conversation-list-header span, .participant-panel header > span { color: var(--cs-text-muted); font-size: 10px; }
.conversation-list-header button { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-700); cursor: pointer; }
.conversation-list { display: grid; overflow-y: auto; align-content: start; padding: 7px; margin: 0; list-style: none; }
.conversation-item { display: grid; width: 100%; min-height: 67px; grid-template-columns: 34px 1fr auto 15px; align-items: center; gap: 9px; padding: 9px; border: 1px solid transparent; border-radius: var(--cs-radius-md); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }
.conversation-item:hover { background: var(--cs-surface-subtle); }.conversation-item.active { border-color: var(--cs-brand-200); background: var(--cs-brand-50); }
.conversation-list__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 10px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.conversation-list__icon.team { background: var(--cs-brand-100); color: var(--cs-brand-700); }
.conversation-list__copy { min-width: 0; }.conversation-list__copy strong, .conversation-list__copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.conversation-list__copy strong { font-size: 12px; }.conversation-list__copy small { margin-top: 3px; color: var(--cs-text-muted); font-size: 9px; }
.conversation-list time { color: var(--cs-text-muted); font-size: 9px; }.conversation-item > svg { color: var(--cs-text-muted); }.load-more-item { margin-top: 8px; }
.conversation-detail { display: grid; height: calc(100vh - 176px); grid-template-rows: auto minmax(0, 1fr) auto; }.conversation-detail__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 18px 22px 15px; border-bottom: 1px solid var(--cs-border); }.conversation-detail__header h2 { margin: 6px 0 4px; border-radius: 4px; font-size: 18px; }.conversation-detail__header h2:focus-visible { outline: 3px solid var(--cs-brand-200); outline-offset: 3px; }.conversation-detail__header p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }
.conversation-kind { display: inline-flex; align-items: center; gap: 6px; color: var(--cs-brand-700); font-size: 9px; font-weight: 750; letter-spacing: .06em; text-transform: uppercase; }
.message-stage { min-height: 0; overflow: hidden; background: linear-gradient(180deg, #fbfdfb 0%, #f7fbf8 100%); }.message-stage > :deep(.state-panel) { height: 100%; }.message-history { height: 100%; overflow-y: auto; padding: 16px 20px 24px; }.older-messages { display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 32px; margin-bottom: 8px; }.older-messages > span { color: var(--cs-danger); font-size: 9px; }.message-list { display: grid; gap: 14px; max-width: 740px; padding: 0; margin: 0 auto; list-style: none; }.message-row { display: grid; grid-template-columns: 30px minmax(0, 1fr); align-items: start; gap: 8px; justify-self: start; max-width: min(82%, 620px); }.message-row.own { grid-template-columns: minmax(0, 1fr) 30px; justify-self: end; }.message-row.own .message-avatar { grid-column: 2; }.message-row.own article { grid-column: 1; grid-row: 1; border-color: #b9ddc5; background: var(--cs-brand-100); }.message-avatar { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 50%; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: 9px; font-weight: 750; }.message-row.own .message-avatar { background: var(--cs-brand-600); color: white; }.message-row article { min-width: 0; padding: 9px 11px; border: 1px solid var(--cs-border); border-radius: 5px 13px 13px; background: white; font-size: 11px; box-shadow: 0 3px 10px rgb(21 35 29 / 4%); }.message-row.own article { border-radius: 13px 5px 13px 13px; }.message-row article > header { display: flex; align-items: center; gap: 7px; margin-bottom: 5px; color: var(--cs-text-muted); font-size: 8px; }.message-row article > header strong { color: var(--cs-text-secondary); font-size: 9px; }.message-row article > header span { margin-left: auto; }.message-row.system { display: block; justify-self: stretch; max-width: none; text-align: center; }.message-row.system article { display: inline-block; padding: 6px 10px; border: 0; border-radius: 999px; background: var(--cs-surface-subtle); box-shadow: none; color: var(--cs-text-muted); font-size: 9px; }.message-row.system article > header { justify-content: center; margin-bottom: 2px; }.message-row.pending article { opacity: .72; }.message-row.failed article { border-color: #ecc7c2; background: #fff6f5; opacity: 1; }.message-row article > footer { display: flex; align-items: center; gap: 8px; margin-top: 8px; color: var(--cs-danger); font-size: 8px; }.message-row article > footer button { margin-left: auto; border: 0; background: transparent; color: var(--cs-danger); font-size: 9px; font-weight: 750; cursor: pointer; }.message-empty { display: grid; max-width: 360px; place-items: center; gap: 7px; margin: 70px auto 0; text-align: center; }.message-empty > span, .conversation-welcome > span { display: grid; width: 46px; height: 46px; place-items: center; border: 1px solid #ddd3ef; border-radius: 15px; background: var(--cs-agent-soft); color: var(--cs-agent); }.message-empty strong { font-size: 14px; }.message-empty p { color: var(--cs-text-muted); font-size: 10px; line-height: 1.55; }
.agent-live-status { display: flex; max-width: 740px; min-height: 32px; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 10px; margin: 0 auto 10px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-800); font-size: 9px; }.agent-live-status > span { display: inline-flex; align-items: center; gap: 6px; }.agent-live-status button { border: 0; background: transparent; color: var(--cs-brand-800); font-size: 9px; font-weight: 750; cursor: pointer; }.agent-live-status button:disabled { cursor: wait; opacity: .55; }.agent-live-status.error { border-color: #ecc7c2; background: #fff6f5; color: var(--cs-danger); }.agent-live-status.cancelled { border-color: #cbd9cf; background: #f3f7f4; color: var(--cs-text-secondary); }.message-row.streaming article { border-color: #d9cfeb; background: #fbf8ff; }.message-row.streaming.reconnecting article { border-style: dashed; }.stream-placeholder { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.conversation-welcome { display: grid; max-width: 470px; place-items: center; align-self: center; justify-self: center; padding: 60px 24px; text-align: center; }.conversation-welcome > span { margin-bottom: 18px; }.conversation-welcome h2 { margin-bottom: 9px; font: 22px var(--cs-font-display); }.conversation-welcome > p:not(.eyebrow) { margin-bottom: 20px; color: var(--cs-text-secondary); font-size: 12px; line-height: 1.65; }
.participant-panel header { padding: 18px; border-bottom: 1px solid var(--cs-border); }.participant-panel ul { padding: 8px; margin: 0; list-style: none; }.participant-panel li { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 9px; padding: 10px; border-bottom: 1px solid var(--cs-border); }.participant-panel li:last-child { border: 0; }.participant-panel li > span:first-child { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 10px; font-weight: 750; }.participant-panel li > span.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.participant-panel li strong, .participant-panel li small { display: block; }.participant-panel li strong { font-size: 10px; }.participant-panel li small { color: var(--cs-text-muted); font-size: 8px; }.participant-placeholder { display: grid; place-items: center; gap: 10px; padding: 54px 28px; color: var(--cs-text-muted); font-size: 10px; line-height: 1.6; text-align: center; }
.mobile-back { display: none; }.dialog-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 20px; background: rgb(21 35 29 / 38%); backdrop-filter: blur(3px); }.create-dialog { width: min(520px, 100%); max-height: calc(100dvh - 40px); overflow: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }.create-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; padding: 20px 22px 15px; border-bottom: 1px solid var(--cs-border); }.create-dialog h2 { margin-bottom: 0; font-size: 18px; }.create-dialog header button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: var(--cs-radius-sm); background: transparent; cursor: pointer; }.create-dialog form { display: grid; gap: 18px; padding: 20px 22px 22px; }.create-dialog form > label > span, .create-dialog legend { display: block; margin-bottom: 7px; font-size: 10px; font-weight: 750; }.create-dialog input[type='text'], .create-dialog form > label > input { width: 100%; min-height: 40px; padding: 0 11px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }.create-dialog fieldset { display: grid; gap: 8px; padding: 0; border: 0; }.create-dialog fieldset label { display: grid; grid-template-columns: 16px 18px 1fr; align-items: start; gap: 9px; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); cursor: pointer; }.create-dialog fieldset label.active { border-color: var(--cs-brand-300); background: var(--cs-brand-50); }.create-dialog fieldset strong, .create-dialog fieldset small { display: block; }.create-dialog fieldset strong { font-size: 11px; }.create-dialog fieldset small { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.create-dialog form footer { display: flex; justify-content: flex-end; gap: 8px; }.form-error { margin: -6px 0 0; color: var(--cs-danger); font-size: 10px; }
@media (max-width: 1280px) { .conversation-workspace { grid-template-columns: 290px minmax(420px, 1fr); }.participant-panel { grid-column: 1 / -1; min-height: auto; }.participant-panel ul { display: grid; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 900px) { .conversation-workspace { grid-template-columns: 270px 1fr; }.participant-panel { display: none; } }
@media (max-width: 767px) { .conversation-workspace { display: block; min-height: calc(100dvh - 208px); }.conversation-list-panel, .conversation-detail { min-height: calc(100dvh - 208px); }.conversation-list-panel { height: calc(100dvh - 208px); }.conversation-detail { display: none; height: calc(100dvh - 208px); }.conversation-workspace.has-selection .conversation-list-panel { display: none; }.conversation-workspace.has-selection .conversation-detail { display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; }.mobile-back { display: flex; align-items: center; gap: 6px; width: 100%; min-height: 42px; padding: 0 14px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: 10px; cursor: pointer; }.conversation-detail__header { padding: 14px 16px; }.message-history { padding: 12px 10px 18px; }.message-row { max-width: 90%; }.dialog-backdrop { align-items: end; padding: 0; }.create-dialog { max-height: calc(100dvh - 12px); padding-bottom: env(safe-area-inset-bottom); border-radius: var(--cs-radius-lg) var(--cs-radius-lg) 0 0; }.create-dialog input[type='text'], .create-dialog form > label > input { font-size: 16px; } }
</style>
