import { computed, nextTick, watch, type ComputedRef, type Ref } from 'vue'
import { useScopedUserState } from './useScopedUserState'
import type { VirtualListResult } from './useVirtualList'
import type { usePageRequestScope } from './usePageRequestScope'
import type { useConversationMessageStore } from '../domains/conversation/messageStore'
import type { useConversationRealtimeStore } from '../domains/conversation/realtimeStore'
import type { useScopeStore } from '../domains/scope/store'
import type { ConversationMessage } from '../domains/conversation/types'

/** Reading position persistence (R19): the anchor is "first visible message + offset", which survives
 * measured-height virtualisation; the legacy scrollTop numbers are not migrated because an absolute
 * pixel offset means nothing once heights are measured instead of estimated. */
interface StoredConversationAnchor {
  messageId: string
  offsetWithin: number
  sequence: number
  savedAt: string
}

/**
 * Reading position and unread bookkeeping for the conversation timeline (R19): scoped anchors,
 * the restore chain (persisted anchor → first unread → latest), and the follow/jump affordance.
 */
export function useConversationReadingPosition(options: {
  principal: { accountId: string, id: string, organizationId: string } | null | undefined
  scopeStore: ReturnType<typeof useScopeStore>
  messageStore: ReturnType<typeof useConversationMessageStore>
  realtimeStore: ReturnType<typeof useConversationRealtimeStore>
  selectedId: ComputedRef<string | null>
  virtualList: VirtualListResult<ConversationMessage>
  messageHistory: Ref<HTMLElement | null>
  pageRequests: ReturnType<typeof usePageRequestScope>
}) {
  const { principal, scopeStore, messageStore, realtimeStore, selectedId, virtualList, messageHistory, pageRequests } = options
  // Reading marks live under the scoped `reading` kind, never in device-level keys, so they
  // cannot cross accounts or Teams (M9b-F05).
  const readingScope = () => (principal && principal.accountId && scopeStore.state.selectedTeamId
    ? {
        accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId,
        teamId: scopeStore.state.selectedTeamId, projectId: null, objectId: null,
      }
    : null)
  const conversationAnchors = useScopedUserState<Record<string, StoredConversationAnchor>>({ scope: readingScope, name: 'conversation-anchor', fallback: {}, validate: isConversationAnchorRecord })
  const readSequences = useScopedUserState<Record<string, number>>({ scope: readingScope, name: 'conversation-read-sequences', fallback: {}, validate: isNumberRecord })
  const isFollowing = virtualList.isFollowing

  function newestMessageSequence(): number {
    return messageStore.state.items.reduce((latest, message) => Math.max(latest, message.sequence), 0)
  }

  const unreadCount = computed(() => {
    const selected = selectedId.value
    if (!selected) return 0
    const readSequence = readSequences.value.value[selected] ?? newestMessageSequence()
    return messageStore.state.items.filter(message => message.sequence > readSequence).length
  })
  const firstUnreadSequence = computed(() => {
    const selected = selectedId.value
    if (!selected) return null
    const readSequence = readSequences.value.value[selected] ?? newestMessageSequence()
    return messageStore.state.items.find(message => message.sequence > readSequence)?.sequence ?? null
  })

  let restoredForConversation: string | null = null

  watch(selectedId, conversationId => {
    if (!conversationId) return
    // A switched-to conversation restarts the list: foreign measurements go and the reading
    // position is decided by the restore chain once history is ready.
    virtualList.reset()
    restoredForConversation = null
  })

  // Once a conversation's history is ready, put the member back where reading was: the persisted
  // anchor first, then the first unread message, and only then the latest (R19 fallback order).
  watch(() => [selectedId.value, messageStore.state.phase, messageStore.state.items.length] as const, async ([conversationId, phase]) => {
    const pageOwner = pageRequests.capture()
    if (!conversationId || phase !== 'ready' || messageStore.state.items.length === 0) return
    await nextTick()
    if (!pageOwner.isCurrent() || restoredForConversation === conversationId) return
    restoredForConversation = conversationId
    restoreReadingAnchor(conversationId)
  })

  watch(() => [selectedId.value, messageStore.state.phase] as const, ([conversationId, phase]) => {
    if (!conversationId || phase !== 'ready' || readSequences.value.value[conversationId] !== undefined) return
    // The initial history is considered read; subsequent realtime facts become the unread boundary.
    readSequences.value.value = { ...readSequences.value.value, [conversationId]: newestMessageSequence() }
  })

  watch(() => realtimeStore.state.streamedContent.length, async () => {
    const pageOwner = pageRequests.capture()
    await nextTick()
    if (!pageOwner.isCurrent()) return
    if (isFollowing.value && messageHistory.value) jumpToLatest()
  })

  function handleHistoryScroll(): void {
    virtualList.onScroll()
    const conversation = selectedId.value
    if (!conversation) return
    const anchor = virtualList.currentAnchor()
    if (anchor) {
      conversationAnchors.value.value = { ...conversationAnchors.value.value, [conversation]: {
        messageId: anchor.key,
        offsetWithin: Math.round(anchor.offsetWithin),
        sequence: sequenceOfMessage(anchor.key),
        savedAt: new Date().toISOString(),
      } }
    }
    if (isFollowing.value) {
      readSequences.value.value = { ...readSequences.value.value, [conversation]: newestMessageSequence() }
    }
  }

  /** Restore chain (R19): persisted anchor → first unread message → latest. */
  function restoreReadingAnchor(conversationId: string): void {
    if (!messageHistory.value) return
    const stored = conversationAnchors.value.value[conversationId]
    if (stored && virtualList.scrollToAnchor({ key: stored.messageId, offsetWithin: stored.offsetWithin })) return
    const firstUnread = messageStore.state.items.find(message => message.sequence === firstUnreadSequence.value)
    if (firstUnread && virtualList.scrollToAnchor({ key: firstUnread.id, offsetWithin: 0 })) return
    // Restoring a reading position is not a user-driven scroll: an animated jump leaves a window
    // where the mid-flight offset flips isFollowing off and the jump pill flashes on load.
    jumpToLatest('auto')
  }

  function sequenceOfMessage(messageId: string): number {
    return messageStore.state.items.find(message => message.id === messageId)?.sequence ?? 0
  }

  function jumpToLatest(behavior: ScrollBehavior = prefersReducedMotion() ? 'auto' : 'smooth'): void {
    const history = messageHistory.value
    if (!history) return
    // The follow state flips immediately so the jump affordance disappears with the click, not a
    // smooth-scroll later; the scroll events that follow re-derive it either way.
    isFollowing.value = true
    if (typeof history.scrollTo === 'function') history.scrollTo({ top: history.scrollHeight, behavior })
    else history.scrollTop = history.scrollHeight
    if (selectedId.value) readSequences.value.value = { ...readSequences.value.value, [selectedId.value]: newestMessageSequence() }
  }

  function prefersReducedMotion(): boolean {
    return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
  }

  return { unreadCount, firstUnreadSequence, handleHistoryScroll, jumpToLatest, newestMessageSequence }
}

function isNumberRecord(value: unknown): value is Record<string, number> {
  return Boolean(value && typeof value === 'object' && Object.values(value).every(item => typeof item === 'number' && Number.isFinite(item)))
}

function isConversationAnchorRecord(value: unknown): value is Record<string, StoredConversationAnchor> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.values(value).every(item =>
    Boolean(item && typeof item === 'object'
      && typeof item.messageId === 'string'
      && typeof item.offsetWithin === 'number' && Number.isFinite(item.offsetWithin)
      && typeof item.sequence === 'number' && Number.isFinite(item.sequence)
      && typeof item.savedAt === 'string')))
}
