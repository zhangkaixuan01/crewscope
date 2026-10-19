import { computed, onUnmounted, watch, type Ref } from 'vue'
import { useCollaborationStore } from '../domains/collaboration/store'
import { collaborationScopeKey, type CollaborationScope } from '../domains/collaboration/types'

/**
 * M11-F01: presence and typing ride the collaboration signal channel for whatever resource the
 * scope points at — the Work board follows the project scope of the visible view, a Conversation
 * follows its own id. The subscription follows the coordinate itself, every change releases the
 * previous scope so A → B → A navigation cannot leak channels, and the last release drops the
 * subscription.
 */
export function useCollaborationPresence(scope: Ref<CollaborationScope | null>) {
  const collaborationStore = useCollaborationStore()
  const scopeKey = computed(() => {
    const value = scope.value
    return value ? collaborationScopeKey(value) : null
  })
  const presence = computed(() => {
    const key = scopeKey.value
    return key ? collaborationStore.state.presenceByScope[key] ?? [] : []
  })
  const typers = computed(() => {
    const key = scopeKey.value
    return key ? collaborationStore.state.typingByScope[key] ?? [] : []
  })
  const degraded = computed(() => collaborationStore.state.phase === 'degraded')
  watch(scope, (next, previous) => {
    const changed = !previous || !next || collaborationScopeKey(previous) !== collaborationScopeKey(next)
    if (previous && changed) collaborationStore.release(previous)
    if (next && changed) collaborationStore.acquire(next)
  }, { immediate: true })
  onUnmounted(() => {
    const value = scope.value
    if (value) collaborationStore.release(value)
  })
  return { presence, typers, degraded }
}
