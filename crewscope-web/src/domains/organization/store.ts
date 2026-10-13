import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { createCommandGateway } from '../../api/commandGateway'
import type { OrganizationGateway } from './gateway'
import type { OrganizationCommandReceipt, OrganizationProfile, OrganizationSettings } from './types'

/**
 * M12-F01 organization store. Resources are org-grain (no Team scope): the page passes the
 * session organizationId. A 403 maps to the 'forbidden' phase so the page renders the
 * role-gated notice instead of a retryable error.
 */
export type OrganizationResourcePhase = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden'

export interface OrganizationResource<T> {
  phase: OrganizationResourcePhase
  value: T | null
  errorMessage: string | null
}

export interface OrganizationCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error'
  operation: 'update-settings' | null
  receipt: OrganizationCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
}

export interface OrganizationStoreState {
  profile: OrganizationResource<OrganizationProfile>
  settings: OrganizationResource<OrganizationSettings>
  settingsCommand: OrganizationCommandState
}

export interface OrganizationStore {
  state: Readonly<OrganizationStoreState>
  loadProfile(organizationId: string, force?: boolean): Promise<void>
  loadSettings(organizationId: string, force?: boolean): Promise<void>
  updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean>
  clearCommand(): void
  reset(): void
}

export const ORGANIZATION_STORE: InjectionKey<OrganizationStore> = Symbol('crewscope-organization-store')

export function createOrganizationStore(gateway: OrganizationGateway): OrganizationStore {
  const commandIntents = createCommandGateway(gateway, { updateSettings: 3 })
  gateway = commandIntents.gateway
  const state = reactive<OrganizationStoreState>(initialState())
  // One generation counter per resource: the page loads profile for every member but settings
  // only for audit-read holders, and the two mounts race — a shared counter would let the
  // profile load void the in-flight settings response (or vice versa) and strand it loading.
  let profileGeneration = 0
  let settingsGeneration = 0
  let profileController: AbortController | null = null
  let settingsController: AbortController | null = null

  async function loadProfile(organizationId: string, force = false): Promise<void> {
    if (!force && state.profile.phase === 'ready') return
    const current = ++profileGeneration
    profileController?.abort()
    const controller = new AbortController()
    profileController = controller
    state.profile.phase = 'loading'
    state.profile.errorMessage = null
    try {
      const value = await gateway.profile(organizationId, controller.signal)
      if (current !== profileGeneration || controller.signal.aborted) return
      state.profile.phase = 'ready'
      state.profile.value = value
    } catch (error) {
      if (current !== profileGeneration || controller.signal.aborted) return
      state.profile.phase = phaseOf(error)
      state.profile.errorMessage = messageOf(error)
    }
  }

  async function loadSettings(organizationId: string, force = false): Promise<void> {
    await loadSettingsResource(organizationId, force, ++settingsGeneration)
  }

  async function loadSettingsResource(organizationId: string, force: boolean, current: number): Promise<void> {
    if (!force && state.settings.phase === 'ready') return
    settingsController?.abort()
    const controller = new AbortController()
    settingsController = controller
    state.settings.phase = 'loading'
    state.settings.errorMessage = null
    try {
      const value = await gateway.settings(organizationId, controller.signal)
      if (current !== settingsGeneration || controller.signal.aborted) return
      state.settings.phase = 'ready'
      state.settings.value = value
    } catch (error) {
      if (current !== settingsGeneration || controller.signal.aborted) return
      state.settings.phase = phaseOf(error)
      state.settings.errorMessage = messageOf(error)
    }
  }

  async function updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean> {
    state.settingsCommand.phase = 'pending'
    state.settingsCommand.operation = 'update-settings'
    state.settingsCommand.errorMessage = null
    state.settingsCommand.errorStatus = null
    state.settingsCommand.errorCode = null
    try {
      const receipt = await gateway.updateSettings(organizationId, input, expectedVersion, idempotencyKey)
      state.settingsCommand.phase = 'success'
      state.settingsCommand.receipt = receipt
      // A 202 receipt means the command was accepted, not that the read model moved —
      // re-read the settings so the next edit starts from the persisted version.
      await loadSettingsResource(organizationId, true, ++settingsGeneration)
      return true
    } catch (error) {
      state.settingsCommand.phase = 'error'
      state.settingsCommand.errorMessage = messageOf(error)
      state.settingsCommand.errorStatus = error instanceof CrewScopeApiError ? error.status : null
      state.settingsCommand.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
      if (error instanceof CrewScopeApiError && error.status === 409) {
        await loadSettingsResource(organizationId, true, ++settingsGeneration)
      }
      return false
    }
  }

  function clearCommand(): void {
    state.settingsCommand = commandState()
  }

  function reset(): void {
    profileGeneration += 1
    settingsGeneration += 1
    profileController?.abort()
    settingsController?.abort()
    commandIntents.clear()
    Object.assign(state, initialState())
  }

  return {
    state: readonly(state) as Readonly<OrganizationStoreState>,
    loadProfile,
    loadSettings,
    updateSettings,
    clearCommand,
    reset,
  }
}

function initialState(): OrganizationStoreState {
  return {
    profile: resource(),
    settings: resource(),
    settingsCommand: commandState(),
  }
}

function resource<T>(): OrganizationResource<T> {
  return { phase: 'idle', value: null, errorMessage: null }
}

function commandState(): OrganizationCommandState {
  return { phase: 'idle', operation: null, receipt: null, errorMessage: null, errorStatus: null, errorCode: null }
}

function messageOf(error: unknown): string {
  if (error instanceof CrewScopeApiError) return error.envelope.message
  return '暂时无法完成该操作，请稍后重试。'
}

/** A 403 is a role fact, not a retriable failure — the page renders the gated notice for it. */
function phaseOf(error: unknown): OrganizationResourcePhase {
  return error instanceof CrewScopeApiError && error.status === 403 ? 'forbidden' : 'error'
}

export function installOrganizationStore(app: App, gateway: OrganizationGateway): OrganizationStore {
  const store = createOrganizationStore(gateway)
  app.provide(ORGANIZATION_STORE, store)
  return store
}

export function useOrganizationStore(): OrganizationStore {
  const store = inject(ORGANIZATION_STORE)
  if (!store) throw new Error('CrewScope Organization Store is not installed')
  return store
}
