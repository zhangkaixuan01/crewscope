import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { createCommandGateway } from '../../api/commandGateway'
import type { OrganizationGateway } from './gateway'
import type {
  OrganizationCommandReceipt,
  OrganizationProfile,
  OrganizationRole,
  OrganizationRoleGrant,
  OrganizationSettings,
} from './types'

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
  operation: 'update-settings' | 'grant-role' | 'revoke-role' | null
  receipt: OrganizationCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
}

export interface OrganizationStoreState {
  profile: OrganizationResource<OrganizationProfile>
  settings: OrganizationResource<OrganizationSettings>
  roleGrants: OrganizationResource<OrganizationRoleGrant[]>
  settingsCommand: OrganizationCommandState
  roleCommand: OrganizationCommandState
}

export interface OrganizationStore {
  state: Readonly<OrganizationStoreState>
  loadProfile(organizationId: string, force?: boolean): Promise<void>
  loadSettings(organizationId: string, force?: boolean): Promise<void>
  loadRoleGrants(organizationId: string, force?: boolean): Promise<void>
  updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean>
  grantRole(
    organizationId: string,
    input: { principalId: string, role: OrganizationRole, reason?: string },
    idempotencyKey: string,
  ): Promise<boolean>
  revokeRoleGrant(
    organizationId: string,
    grantId: string,
    input: { reason?: string },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean>
  clearSettingsCommand(): void
  clearRoleCommand(): void
  reset(): void
}

export const ORGANIZATION_STORE: InjectionKey<OrganizationStore> = Symbol('crewscope-organization-store')

export function createOrganizationStore(gateway: OrganizationGateway): OrganizationStore {
  // The numbers are the idempotencyKey parameter index of each gateway method — the
  // commandInventory gate verifies them against the interface signatures by AST.
  const commandIntents = createCommandGateway(gateway, { updateSettings: 3, grantRole: 2, revokeRoleGrant: 4 })
  gateway = commandIntents.gateway
  const state = reactive<OrganizationStoreState>(initialState())
  // One generation counter per resource: the page loads profile for every member but settings
  // only for audit-read holders, and the two mounts race — a shared counter would let the
  // profile load void the in-flight settings response (or vice versa) and strand it loading.
  let profileGeneration = 0
  let settingsGeneration = 0
  let roleGrantsGeneration = 0
  let profileController: AbortController | null = null
  let settingsController: AbortController | null = null
  let roleGrantsController: AbortController | null = null

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
      recordCommandFailure(state.settingsCommand, error)
      if (error instanceof CrewScopeApiError && error.status === 409) {
        await loadSettingsResource(organizationId, true, ++settingsGeneration)
      }
      return false
    }
  }

  async function loadRoleGrants(organizationId: string, force = false): Promise<void> {
    if (!force && state.roleGrants.phase === 'ready') return
    const current = ++roleGrantsGeneration
    roleGrantsController?.abort()
    const controller = new AbortController()
    roleGrantsController = controller
    state.roleGrants.phase = 'loading'
    state.roleGrants.errorMessage = null
    try {
      const value = await gateway.roleGrants(organizationId, controller.signal)
      if (current !== roleGrantsGeneration || controller.signal.aborted) return
      state.roleGrants.phase = 'ready'
      state.roleGrants.value = value
    } catch (error) {
      if (current !== roleGrantsGeneration || controller.signal.aborted) return
      state.roleGrants.phase = phaseOf(error)
      state.roleGrants.errorMessage = messageOf(error)
    }
  }

  async function grantRole(
    organizationId: string,
    input: { principalId: string, role: OrganizationRole, reason?: string },
    idempotencyKey: string,
  ): Promise<boolean> {
    return runRoleCommand('grant-role', organizationId,
      () => gateway.grantRole(organizationId, input, idempotencyKey))
  }

  async function revokeRoleGrant(
    organizationId: string,
    grantId: string,
    input: { reason?: string },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean> {
    return runRoleCommand('revoke-role', organizationId,
      () => gateway.revokeRoleGrant(organizationId, grantId, input, expectedVersion, idempotencyKey))
  }

  /**
   * Grants are re-read after the receipt (and after a 409: the last-owner protection and the
   * optimistic-lock conflict both leave the list authoritative for the next attempt). The page
   * refreshes the session on success so a self-revoked role converges immediately (D2).
   */
  async function runRoleCommand(
    operation: 'grant-role' | 'revoke-role',
    organizationId: string,
    dispatch: () => Promise<OrganizationCommandReceipt>,
  ): Promise<boolean> {
    state.roleCommand.phase = 'pending'
    state.roleCommand.operation = operation
    state.roleCommand.errorMessage = null
    state.roleCommand.errorStatus = null
    state.roleCommand.errorCode = null
    try {
      const receipt = await dispatch()
      state.roleCommand.phase = 'success'
      state.roleCommand.receipt = receipt
      await loadRoleGrants(organizationId, true)
      return true
    } catch (error) {
      recordCommandFailure(state.roleCommand, error)
      if (error instanceof CrewScopeApiError && error.status === 409) {
        await loadRoleGrants(organizationId, true)
      }
      return false
    }
  }

  function clearSettingsCommand(): void {
    state.settingsCommand = commandState()
  }

  function clearRoleCommand(): void {
    state.roleCommand = commandState()
  }

  function reset(): void {
    profileGeneration += 1
    settingsGeneration += 1
    roleGrantsGeneration += 1
    profileController?.abort()
    settingsController?.abort()
    roleGrantsController?.abort()
    commandIntents.clear()
    Object.assign(state, initialState())
  }

  return {
    state: readonly(state) as Readonly<OrganizationStoreState>,
    loadProfile,
    loadSettings,
    loadRoleGrants,
    updateSettings,
    grantRole,
    revokeRoleGrant,
    clearSettingsCommand,
    clearRoleCommand,
    reset,
  }
}

function recordCommandFailure(command: OrganizationCommandState, error: unknown): void {
  command.phase = 'error'
  command.errorMessage = messageOf(error)
  command.errorStatus = error instanceof CrewScopeApiError ? error.status : null
  command.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
}

function initialState(): OrganizationStoreState {
  return {
    profile: resource(),
    settings: resource(),
    roleGrants: resource(),
    settingsCommand: commandState(),
    roleCommand: commandState(),
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
