import { getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
import { createRequestScope } from '../../api/requestScope'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'
import type { ScopeStore } from './store'
import type { CreateWorkProjectInput } from './types'

export interface WorkProjectCreationFlow {
  open: Ref<boolean>
  show(): void
  close(): void
  submit(input: CreateWorkProjectInput, idempotencyKey: string): Promise<boolean>
}

/**
 * Keeps every WorkProject creation entry on the same command, retry and URL-selection contract.
 * Pages choose push or replace according to whether creation should add a navigation history entry.
 */
export function createWorkProjectCreationFlow(
  store: ScopeStore,
  router: Router,
  route: RouteLocationNormalizedLoaded,
  navigation: 'push' | 'replace' = 'replace',
): WorkProjectCreationFlow {
  const open = ref(false)
  const coordinate = () => JSON.stringify([route.fullPath, store.state.selectedTeamId])
  const requests = createRequestScope(coordinate)
  const stop = watch(coordinate, () => { requests.invalidate(); open.value = false }, { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(() => { stop(); requests.dispose() })

  function show(): void {
    store.clearProjectCommand()
    open.value = true
  }

  function close(): void {
    if (store.state.projectCommandPending) return
    store.clearProjectCommand()
    open.value = false
  }

  async function submit(input: CreateWorkProjectInput, idempotencyKey: string): Promise<boolean> {
    const owner = requests.capture()
    try {
      const created = await store.createWorkProject(input, idempotencyKey)
      if (!owner.isCurrent()) return false
      const target = {
        query: {
          view: route.query.view === 'board' ? 'board' : undefined,
          team: store.state.selectedTeamId ?? undefined,
          project: created.id,
        },
      }
      if (navigation === 'push') await router.push(target)
      else await router.replace(target)
      open.value = false
      return true
    } catch {
      // The Scope Store keeps the sanitized error and unchanged idempotency coordinate for retry.
      return false
    }
  }

  return { open, show, close, submit }
}
