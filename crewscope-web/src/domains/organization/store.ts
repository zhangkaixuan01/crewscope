import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { createCommandGateway } from '../../api/commandGateway'
import type { OrganizationGateway } from './gateway'
import type {
  CreationResult,
  DeletionJob,
  DeletionPrecheck,
  ExportArtifacts,
  ExportJob,
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

/**
 * The in-session state of one governance job panel: jobs have no list endpoint, so the panel
 * tracks the single job this browser session created or re-loaded. The process response IS
 * the terminal state (the server settles every PENDING item within the request), so every
 * step replaces the job wholesale; there is no polling and no SSE.
 */
export interface OrganizationFlowState<T> {
  job: T | null
  busy: 'create' | 'process' | 'cancel' | 'confirm' | 'load' | 'download' | null
  replayed: boolean
  notice: string | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
}

export type ExportFlowState = OrganizationFlowState<ExportJob>
export type DeletionFlowState = OrganizationFlowState<DeletionJob>

export interface OrganizationStoreState {
  profile: OrganizationResource<OrganizationProfile>
  settings: OrganizationResource<OrganizationSettings>
  roleGrants: OrganizationResource<OrganizationRoleGrant[]>
  deletionPrecheck: OrganizationResource<DeletionPrecheck>
  settingsCommand: OrganizationCommandState
  roleCommand: OrganizationCommandState
  exportFlow: ExportFlowState
  deletionFlow: DeletionFlowState
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
  createExport(
    organizationId: string,
    input: { includeVectors: boolean },
    idempotencyKey: string,
  ): Promise<boolean>
  loadExportJob(organizationId: string, jobId?: string): Promise<void>
  /** Audit-bearing read of the frozen artifacts document (server audits every download). */
  fetchExportArtifacts(organizationId: string): Promise<ExportArtifacts | null>
  processExport(organizationId: string): Promise<boolean>
  cancelExport(organizationId: string): Promise<boolean>
  precheckDeletion(organizationId: string, accountId: string, force?: boolean): Promise<void>
  createDeletion(
    organizationId: string,
    input: { accountId: string, reason: string },
    idempotencyKey: string,
  ): Promise<boolean>
  loadDeletionJob(organizationId: string, jobId?: string): Promise<void>
  confirmDeletion(organizationId: string, confirmationToken: string): Promise<boolean>
  processDeletion(organizationId: string): Promise<boolean>
  cancelDeletion(organizationId: string): Promise<boolean>
  clearSettingsCommand(): void
  clearRoleCommand(): void
  clearExportFlow(): void
  clearDeletionFlow(): void
  reset(): void
}

export const ORGANIZATION_STORE: InjectionKey<OrganizationStore> = Symbol('crewscope-organization-store')

export function createOrganizationStore(gateway: OrganizationGateway): OrganizationStore {
  // The numbers are the idempotencyKey parameter index of each gateway method — the
  // commandInventory gate verifies them against the interface signatures by AST.
  const commandIntents = createCommandGateway(gateway, {
    updateSettings: 3,
    grantRole: 2,
    revokeRoleGrant: 4,
    createExport: 2,
    createDeletion: 2,
  })
  gateway = commandIntents.gateway
  const state = reactive<OrganizationStoreState>(initialState())
  // One generation counter per resource: the page loads profile for every member but settings
  // only for audit-read holders, and the two mounts race — a shared counter would let the
  // profile load void the in-flight settings response (or vice versa) and strand it loading.
  let profileGeneration = 0
  let settingsGeneration = 0
  let roleGrantsGeneration = 0
  let precheckGeneration = 0
  let profileController: AbortController | null = null
  let settingsController: AbortController | null = null
  let roleGrantsController: AbortController | null = null
  let precheckController: AbortController | null = null

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

  async function createExport(
    organizationId: string,
    input: { includeVectors: boolean },
    idempotencyKey: string,
  ): Promise<boolean> {
    return runCreation(state.exportFlow, 'create', async () => {
      const result = await gateway.createExport(organizationId, input, idempotencyKey)
      return afterCreation(state.exportFlow, result)
    })
  }

  async function loadExportJob(organizationId: string, jobId?: string): Promise<void> {
    const id = jobId ?? state.exportFlow.job?.id
    if (!id) return
    await runJobStep(state.exportFlow, 'load',
      () => gateway.exportJob(organizationId, id),
      same => gateway.exportJob(organizationId, same))
  }

  async function fetchExportArtifacts(organizationId: string): Promise<ExportArtifacts | null> {
    const id = state.exportFlow.job?.id
    if (!id) return null
    state.exportFlow.busy = 'download'
    state.exportFlow.errorMessage = null
    state.exportFlow.errorStatus = null
    state.exportFlow.errorCode = null
    try {
      const artifacts = await gateway.openExportArtifacts(organizationId, id)
      // The download is a read: success must hand the panel back its buttons, or one
      // download would leave every export action disabled for the rest of the session.
      state.exportFlow.busy = null
      return artifacts
    } catch (error) {
      recordFlowFailure(state.exportFlow, error)
      return null
    }
  }

  async function processExport(organizationId: string): Promise<boolean> {
    const id = state.exportFlow.job?.id
    if (!id) return false
    return runJobStep(state.exportFlow, 'process',
      () => gateway.processExport(organizationId, id),
      same => gateway.exportJob(organizationId, same))
  }

  async function cancelExport(organizationId: string): Promise<boolean> {
    const id = state.exportFlow.job?.id
    if (!id) return false
    return runJobStep(state.exportFlow, 'cancel',
      () => gateway.cancelExport(organizationId, id),
      same => gateway.exportJob(organizationId, same))
  }

  async function precheckDeletion(organizationId: string, accountId: string, force = false): Promise<void> {
    if (!force && state.deletionPrecheck.phase === 'ready'
      && state.deletionPrecheck.value?.accountId === accountId) return
    const current = ++precheckGeneration
    precheckController?.abort()
    const controller = new AbortController()
    precheckController = controller
    state.deletionPrecheck.phase = 'loading'
    state.deletionPrecheck.errorMessage = null
    try {
      const value = await gateway.deletionPrecheck(organizationId, accountId, controller.signal)
      if (current !== precheckGeneration || controller.signal.aborted) return
      state.deletionPrecheck.phase = 'ready'
      state.deletionPrecheck.value = value
    } catch (error) {
      if (current !== precheckGeneration || controller.signal.aborted) return
      state.deletionPrecheck.phase = phaseOf(error)
      state.deletionPrecheck.errorMessage = messageOf(error)
    }
  }

  async function createDeletion(
    organizationId: string,
    input: { accountId: string, reason: string },
    idempotencyKey: string,
  ): Promise<boolean> {
    return runCreation(state.deletionFlow, 'create', async () => {
      const result = await gateway.createDeletion(organizationId, input, idempotencyKey)
      return afterCreation(state.deletionFlow, result)
    })
  }

  async function loadDeletionJob(organizationId: string, jobId?: string): Promise<void> {
    const id = jobId ?? state.deletionFlow.job?.id
    if (!id) return
    await runJobStep(state.deletionFlow, 'load',
      () => gateway.deletionJob(organizationId, id),
      same => gateway.deletionJob(organizationId, same))
  }

  async function confirmDeletion(organizationId: string, confirmationToken: string): Promise<boolean> {
    const job = state.deletionFlow.job
    if (!job) return false
    return runJobStep(state.deletionFlow, 'confirm',
      () => gateway.confirmDeletion(organizationId, job.id, confirmationToken, job.version),
      same => gateway.deletionJob(organizationId, same))
  }

  async function processDeletion(organizationId: string): Promise<boolean> {
    const id = state.deletionFlow.job?.id
    if (!id) return false
    return runJobStep(state.deletionFlow, 'process',
      () => gateway.processDeletion(organizationId, id),
      same => gateway.deletionJob(organizationId, same))
  }

  async function cancelDeletion(organizationId: string): Promise<boolean> {
    const id = state.deletionFlow.job?.id
    if (!id) return false
    return runJobStep(state.deletionFlow, 'cancel',
      () => gateway.cancelDeletion(organizationId, id),
      same => gateway.deletionJob(organizationId, same))
  }

  /**
   * A create replay carries no job body (the original 202 job was answered to an earlier
   * request): surface the receipt notice and keep the previous job (or none) — the audit
   * center remains the authoritative history.
   */
  async function afterCreation<T>(flow: OrganizationFlowState<T>, result: CreationResult<T>): Promise<T | null> {
    flow.replayed = result.replayed
    flow.notice = result.replayed
      ? '该命令此前已受理（幂等重放），本次未返回作业详情。作业历史以审计中心为准。'
      : null
    if (result.job) flow.job = result.job
    return result.job
  }

  async function runCreation<T>(
    flow: OrganizationFlowState<T>,
    operation: 'create',
    dispatch: () => Promise<T | null>,
  ): Promise<boolean> {
    flow.busy = operation
    flow.notice = null
    flow.errorMessage = null
    flow.errorStatus = null
    flow.errorCode = null
    try {
      await dispatch()
      flow.busy = null
      return true
    } catch (error) {
      recordFlowFailure(flow, error)
      return false
    }
  }

  /**
   * Every step answer replaces the job wholesale (the process response settles all items).
   * A 409 means the moved machine disagrees with the panel: re-read so the panel catches up.
   */
  async function runJobStep<T>(
    flow: OrganizationFlowState<T>,
    operation: OrganizationFlowState<T>['busy'],
    dispatch: () => Promise<T>,
    reload: (jobId: string) => Promise<T>,
  ): Promise<boolean> {
    if (!operation) return false
    flow.busy = operation
    flow.notice = null
    flow.errorMessage = null
    flow.errorStatus = null
    flow.errorCode = null
    try {
      const job = await dispatch()
      flow.job = job
      flow.busy = null
      return true
    } catch (error) {
      recordFlowFailure(flow, error)
      const jobId = (flow.job as { id: string } | null)?.id
      if (error instanceof CrewScopeApiError && error.status === 409 && jobId) {
        flow.busy = 'load'
        try {
          flow.job = await reload(jobId)
        } catch {
          // Best effort: the recorded 409 coordinates already explain the failure.
        } finally {
          flow.busy = null
        }
      }
      return false
    }
  }

  function recordFlowFailure<T>(flow: OrganizationFlowState<T>, error: unknown): void {
    flow.busy = null
    flow.errorMessage = messageOf(error)
    flow.errorStatus = error instanceof CrewScopeApiError ? error.status : null
    flow.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
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

  function clearExportFlow(): void {
    state.exportFlow = flowState()
  }

  function clearDeletionFlow(): void {
    state.deletionFlow = flowState()
  }

  function reset(): void {
    profileGeneration += 1
    settingsGeneration += 1
    roleGrantsGeneration += 1
    precheckGeneration += 1
    profileController?.abort()
    settingsController?.abort()
    roleGrantsController?.abort()
    precheckController?.abort()
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
    createExport,
    loadExportJob,
    fetchExportArtifacts,
    processExport,
    cancelExport,
    precheckDeletion,
    createDeletion,
    loadDeletionJob,
    confirmDeletion,
    processDeletion,
    cancelDeletion,
    clearSettingsCommand,
    clearRoleCommand,
    clearExportFlow,
    clearDeletionFlow,
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
    deletionPrecheck: resource(),
    settingsCommand: commandState(),
    roleCommand: commandState(),
    exportFlow: flowState(),
    deletionFlow: flowState(),
  }
}

function resource<T>(): OrganizationResource<T> {
  return { phase: 'idle', value: null, errorMessage: null }
}

function flowState<T>(): OrganizationFlowState<T> {
  return { job: null, busy: null, replayed: false, notice: null, errorMessage: null, errorStatus: null, errorCode: null }
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
