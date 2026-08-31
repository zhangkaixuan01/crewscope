import { ref, type Ref } from 'vue'
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
    try {
      const created = await store.createWorkProject(input, idempotencyKey)
      const target = {
        query: {
          ...route.query,
          team: store.state.selectedTeamId ?? undefined,
          project: created.id,
          workItem: undefined,
          focus: undefined,
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
