import { inject, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { createRequestScope } from '../api/requestScope'
import { AUTH_PRINCIPAL } from '../app/auth'
import { AUTH_STORE } from '../domains/identity/store'
import { SCOPE_STORE } from '../domains/scope/store'

/** Protects continuations owned by a mounted page, including A → B → A and identity changes. */
export function usePageRequestScope(selection?: () => unknown) {
  const route = useRoute()
  const auth = inject(AUTH_STORE, null)
  const principal = inject(AUTH_PRINCIPAL, null)
  const scope = inject(SCOPE_STORE, null)
  const identityCoordinate = () => JSON.stringify([auth?.state.session?.account?.accountId,
    auth?.state.session?.account?.securityVersion, principal?.id, principal?.organizationId,
    route.name])
  const identity = createRequestScope(identityCoordinate)
  const coordinate = () => JSON.stringify([identityCoordinate(),
    scope?.state.selectedTeamId, scope?.state.selectedProjectId, selection?.()])
  const requests = createRequestScope(coordinate)
  const selectionCoordinate = () => JSON.stringify([coordinate(), route.fullPath])
  const selected = createRequestScope(selectionCoordinate)
  watch(coordinate, requests.invalidate, { flush: 'sync' })
  watch(identityCoordinate, identity.invalidate, { flush: 'sync' })
  watch(selectionCoordinate, selected.invalidate, { flush: 'sync' })
  onBeforeUnmount(() => { requests.dispose(); selected.dispose(); identity.dispose() })
  return { ...requests, captureSelection: selected.capture, captureIdentity: identity.capture }
}
