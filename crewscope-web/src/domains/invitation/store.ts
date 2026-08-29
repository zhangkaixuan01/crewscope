import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { registrationInvitationFromHash } from '../identity/invitation'
import type { AuthCsrfCoordinate } from '../identity/types'
import type { InvitationGateway } from './gateway'
import {
  InvitationRequestTimeoutError,
  presentInvitationProblem,
  type InvitationProblem,
} from './presentation'
import type {
  InvitationCreationInput,
  InvitationCreationResult,
  InvitationPreview,
  TeamInvitationSummary,
} from './types'

export type InvitationManagementPhase = 'idle' | 'loading' | 'ready' | 'error'
export type InvitationPublicPhase = 'idle' | 'previewing' | 'available' | 'expired' | 'unavailable' | 'accepting' | 'accepted' | 'error'

interface InvitationStoreState {
  managementPhase: InvitationManagementPhase
  items: TeamInvitationSummary[]
  nextCursor: string | null
  managementProblem: InvitationProblem | null
  managementErrorGeneration: number
  commandPhase: 'idle' | 'pending' | 'success' | 'error'
  commandKind: 'create' | 'revoke' | null
  commandProblem: InvitationProblem | null
  commandGeneration: number
  publicPhase: InvitationPublicPhase
  preview: InvitationPreview | null
  publicProblem: InvitationProblem | null
  publicErrorGeneration: number
}

export interface InvitationStore {
  state: Readonly<InvitationStoreState>
  loadManagement(organizationId: string, teamId: string, append?: boolean): Promise<boolean>
  createInvitation(
    organizationId: string,
    teamId: string,
    input: InvitationCreationInput,
    csrf: AuthCsrfCoordinate,
  ): Promise<InvitationCreationResult | null>
  revokeInvitation(
    organizationId: string,
    teamId: string,
    invitationId: string,
    csrf: AuthCsrfCoordinate,
  ): Promise<boolean>
  previewProof(hash?: string): Promise<boolean>
  hasProof(): boolean
  registrationProof(): string | null
  acceptInvitation(csrf: AuthCsrfCoordinate): Promise<boolean>
  clearCommand(): void
  pausePublic(): void
  clearProof(): void
  resetManagement(): void
  reset(): void
}

export interface InvitationStoreOptions {
  queryTimeoutMs?: number
  commandTimeoutMs?: number
}

export const INVITATION_STORE: InjectionKey<InvitationStore> = Symbol('crewscope-invitation-store')

/** Keeps one-time proofs and idempotency keys in private process memory, outside reactive state. */
export function createInvitationStore(
  gateway: InvitationGateway,
  options: InvitationStoreOptions = {},
): InvitationStore {
  const state = reactive<InvitationStoreState>({
    managementPhase: 'idle', items: [], nextCursor: null, managementProblem: null, managementErrorGeneration: 0,
    commandPhase: 'idle', commandKind: null, commandProblem: null, commandGeneration: 0,
    publicPhase: 'idle', preview: null, publicProblem: null, publicErrorGeneration: 0,
  })
  const queryTimeoutMs = options.queryTimeoutMs ?? 10_000
  const commandTimeoutMs = options.commandTimeoutMs ?? 20_000
  let managementGeneration = 0
  let managementController: AbortController | null = null
  let publicGeneration = 0
  let publicController: AbortController | null = null
  let proof: string | null = null
  let createKey: string | null = null
  let createFingerprint: string | null = null
  const revokeKeys = new Map<string, string>()
  let acceptKey: string | null = null

  async function loadManagement(organizationId: string, teamId: string, append = false): Promise<boolean> {
    const requestGeneration = beginManagement()
    state.managementPhase = 'loading'
    state.managementProblem = null
    const cursor = append ? state.nextCursor : null
    try {
      const page = await timed(managementController!, signal => gateway.list(
        organizationId, teamId, cursor, signal,
      ), queryTimeoutMs)
      if (requestGeneration !== managementGeneration) return false
      state.items = append ? merge(state.items, page.items) : page.items
      state.nextCursor = page.nextCursor
      state.managementPhase = 'ready'
      return true
    } catch (error) {
      if (requestGeneration !== managementGeneration || isAbort(error)) return false
      state.managementProblem = presentInvitationProblem(error)
      state.managementErrorGeneration += 1
      state.managementPhase = 'error'
      return false
    } finally {
      finishManagement(requestGeneration)
    }
  }

  async function createInvitation(
    organizationId: string,
    teamId: string,
    input: InvitationCreationInput,
    csrf: AuthCsrfCoordinate,
  ): Promise<InvitationCreationResult | null> {
    const fingerprint = JSON.stringify([organizationId, teamId, input.targetEmail ?? null, input.targetRole, input.expiresInMinutes])
    if (!createKey || createFingerprint !== fingerprint) {
      createKey = crypto.randomUUID()
      createFingerprint = fingerprint
    }
    const requestGeneration = beginManagement()
    beginCommand('create')
    try {
      const result = await timed(managementController!, signal => gateway.create(
        organizationId, teamId, input, { csrf, idempotencyKey: createKey! }, signal,
      ), commandTimeoutMs)
      if (requestGeneration !== managementGeneration) return null
      if (result.invitation) state.items = merge([result.invitation], state.items)
      clearCreateIntent()
      commandSuccess()
      state.managementPhase = 'ready'
      return result
    } catch (error) {
      if (requestGeneration !== managementGeneration || isAbort(error)) return null
      if (!keepsIdempotency(error)) clearCreateIntent()
      commandFailure(error)
      return null
    } finally {
      finishManagement(requestGeneration)
    }
  }

  async function revokeInvitation(
    organizationId: string,
    teamId: string,
    invitationId: string,
    csrf: AuthCsrfCoordinate,
  ): Promise<boolean> {
    const key = revokeKeys.get(invitationId) ?? crypto.randomUUID()
    revokeKeys.set(invitationId, key)
    const requestGeneration = beginManagement()
    beginCommand('revoke')
    try {
      await timed(managementController!, signal => gateway.revoke(
        organizationId, teamId, invitationId, { csrf, idempotencyKey: key }, signal,
      ), commandTimeoutMs)
      if (requestGeneration !== managementGeneration) return false
      revokeKeys.delete(invitationId)
      state.items = state.items.map(item => item.id === invitationId ? { ...item, status: 'REVOKED' } : item)
      commandSuccess()
      state.managementPhase = 'ready'
      return true
    } catch (error) {
      if (requestGeneration !== managementGeneration || isAbort(error)) return false
      if (!keepsIdempotency(error)) revokeKeys.delete(invitationId)
      commandFailure(error)
      return false
    } finally {
      finishManagement(requestGeneration)
    }
  }

  async function previewProof(hash?: string): Promise<boolean> {
    if (hash !== undefined) {
      const parsed = registrationInvitationFromHash(hash)
      if (parsed.kind !== 'valid') {
        clearProof()
        state.publicPhase = 'unavailable'
        state.preview = unavailablePreview()
        return false
      }
      proof = parsed.token
      acceptKey = null
    }
    if (!proof) {
      state.publicPhase = 'unavailable'
      state.preview = unavailablePreview()
      return false
    }
    const requestGeneration = beginPublic()
    state.publicPhase = 'previewing'
    state.publicProblem = null
    try {
      const preview = await timed(publicController!, signal => gateway.preview(proof!, signal), queryTimeoutMs)
      if (requestGeneration !== publicGeneration) return false
      state.preview = preview
      state.publicPhase = preview.state === 'AVAILABLE'
        ? 'available'
        : preview.state === 'EXPIRED' ? 'expired' : 'unavailable'
      return preview.state === 'AVAILABLE'
    } catch (error) {
      if (requestGeneration !== publicGeneration || isAbort(error)) return false
      state.publicProblem = presentInvitationProblem(error)
      state.publicErrorGeneration += 1
      state.publicPhase = 'error'
      return false
    } finally {
      finishPublic(requestGeneration)
    }
  }

  function hasProof(): boolean {
    return proof !== null
  }

  function registrationProof(): string | null {
    return proof
  }

  async function acceptInvitation(csrf: AuthCsrfCoordinate): Promise<boolean> {
    if (!proof) return false
    acceptKey ??= crypto.randomUUID()
    const requestGeneration = beginPublic()
    state.publicPhase = 'accepting'
    state.publicProblem = null
    try {
      await timed(publicController!, signal => gateway.accept(
        proof!, { csrf, idempotencyKey: acceptKey! }, signal,
      ), commandTimeoutMs)
      if (requestGeneration !== publicGeneration) return false
      proof = null
      acceptKey = null
      state.publicPhase = 'accepted'
      return true
    } catch (error) {
      if (requestGeneration !== publicGeneration || isAbort(error)) return false
      if (!keepsIdempotency(error)) acceptKey = null
      state.publicProblem = presentInvitationProblem(error)
      state.publicErrorGeneration += 1
      state.publicPhase = 'error'
      return false
    } finally {
      finishPublic(requestGeneration)
    }
  }

  function beginManagement(): number {
    managementGeneration += 1
    managementController?.abort()
    managementController = new AbortController()
    return managementGeneration
  }

  function finishManagement(requestGeneration: number): void {
    if (requestGeneration === managementGeneration) managementController = null
  }

  function beginPublic(): number {
    publicGeneration += 1
    publicController?.abort()
    publicController = new AbortController()
    return publicGeneration
  }

  function finishPublic(requestGeneration: number): void {
    if (requestGeneration === publicGeneration) publicController = null
  }

  function beginCommand(kind: 'create' | 'revoke'): void {
    state.commandPhase = 'pending'
    state.commandKind = kind
    state.commandProblem = null
  }

  function commandSuccess(): void {
    state.commandPhase = 'success'
    state.commandGeneration += 1
  }

  function commandFailure(error: unknown): void {
    state.commandProblem = presentInvitationProblem(error)
    state.commandPhase = 'error'
    state.commandGeneration += 1
  }

  function clearCommand(): void {
    state.commandPhase = 'idle'
    state.commandKind = null
    state.commandProblem = null
  }

  function pausePublic(): void {
    publicGeneration += 1
    publicController?.abort()
    publicController = null
  }

  function clearProof(): void {
    pausePublic()
    proof = null
    acceptKey = null
    state.publicPhase = 'idle'
    state.preview = null
    state.publicProblem = null
    state.publicErrorGeneration = 0
  }

  function resetManagement(): void {
    managementGeneration += 1
    managementController?.abort()
    managementController = null
    clearCreateIntent()
    revokeKeys.clear()
    state.managementPhase = 'idle'
    state.items = []
    state.nextCursor = null
    state.managementProblem = null
    state.managementErrorGeneration = 0
    state.commandGeneration = 0
    clearCommand()
  }

  function reset(): void {
    resetManagement()
    clearProof()
  }

  function clearCreateIntent(): void {
    createKey = null
    createFingerprint = null
  }

  return {
    state: readonly(state) as Readonly<InvitationStoreState>,
    loadManagement, createInvitation, revokeInvitation, previewProof, hasProof, registrationProof,
    acceptInvitation, clearCommand, pausePublic, clearProof, resetManagement, reset,
  }
}

export function installInvitationStore(app: App, store: InvitationStore): InvitationStore {
  app.provide(INVITATION_STORE, store)
  return store
}

export function useInvitationStore(): InvitationStore {
  const store = inject(INVITATION_STORE)
  if (!store) throw new Error('CrewScope Invitation Store is not installed')
  return store
}

export function useOptionalInvitationStore(): InvitationStore | null {
  return inject(INVITATION_STORE, null)
}

async function timed<T>(
  controller: AbortController,
  operation: (signal: AbortSignal) => Promise<T>,
  timeoutMs: number,
): Promise<T> {
  let timedOut = false
  const timeout = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  try {
    return await operation(controller.signal)
  } catch (error) {
    if (timedOut) throw new InvitationRequestTimeoutError()
    throw error
  } finally {
    window.clearTimeout(timeout)
  }
}

function merge(existing: TeamInvitationSummary[], incoming: TeamInvitationSummary[]): TeamInvitationSummary[] {
  const seen = new Set<string>()
  return [...existing, ...incoming].filter(item => !seen.has(item.id) && Boolean(seen.add(item.id)))
}

function keepsIdempotency(error: unknown): boolean {
  if (error instanceof InvitationRequestTimeoutError) return true
  return error instanceof CrewScopeApiError
    && ['network_unavailable', 'invitation_unavailable', 'csrf_rejected'].includes(error.envelope.code)
}

function unavailablePreview(): InvitationPreview {
  return {
    state: 'UNAVAILABLE', invitationId: null, teamName: null,
    targetRole: null, expiresAt: null, targetRestricted: false,
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
