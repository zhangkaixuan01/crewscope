import { createCommandGateway } from '../../api/commandGateway'
import { acknowledgeCreation } from '../../api/creationRecovery'
import { secureId } from '../../api/secureId'
import {
  computed,
  inject,
  reactive,
  readonly,
  type App,
  type ComputedRef,
  type InjectionKey,
} from 'vue'
import type { AuthenticatedPrincipal } from '../../app/auth'
import { CrewScopeApiError } from '../../api/client'
import type { CreateHandoverInput, ScopeGateway } from './gateway'
import type {
  CreateWorkProjectInput,
  HandoverJobView,
  MemberResponsibility,
  TeamMemberSummary,
  TeamSummary,
  WorkProjectSummary,
} from './types'

export type ScopePhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

interface ScopeState {
  phase: ScopePhase
  teams: TeamSummary[]
  projects: WorkProjectSummary[]
  members: TeamMemberSummary[]
  selectedTeamId: string | null
  selectedProjectId: string | null
  projectsTeamId: string | null
  membersTeamId: string | null
  membersLoading: boolean
  memberCommandPending: boolean
  handoverJob: HandoverJobView | null
  handoverPending: boolean
  handoverErrorMessage: string | null
  projectCommandPending: boolean
  projectCommandRetryable: boolean
  errorMessage: string | null
  membersErrorMessage: string | null
  projectCommandErrorMessage: string | null
}

export interface ScopeSelection {
  teamId: string | null
  projectId: string | null
}

export interface ScopeStore {
  state: Readonly<ScopeState>
  selectedTeam: ComputedRef<TeamSummary | null>
  selectedProject: ComputedRef<WorkProjectSummary | null>
  synchronize(teamId?: string | null, projectId?: string | null): Promise<ScopeSelection>
  reload(): Promise<ScopeSelection>
  loadMembers(force?: boolean): Promise<void>
  addMember(principalId: string): Promise<void>
  suspendMember(memberId: string): Promise<void>
  activateMember(memberId: string): Promise<void>
  removeMember(memberId: string): Promise<void>
  leaveTeam(): Promise<void>
  grantRole(memberId: string, roleKey: string): Promise<void>
  revokeRole(memberId: string, grantId: string): Promise<void>
  transferOwnership(targetMemberId: string): Promise<void>
  previewResponsibilities(memberId: string, role?: string): Promise<MemberResponsibility[]>
  createHandover(input: CreateHandoverInput): Promise<HandoverJobView>
  processHandover(jobId: string): Promise<HandoverJobView>
  getHandover(jobId: string): Promise<HandoverJobView>
  cancelHandover(jobId: string): Promise<HandoverJobView>
  clearHandover(): void
  checkWorkProjectKey(key: string, signal?: AbortSignal): Promise<boolean>
  createWorkProject(input: CreateWorkProjectInput, idempotencyKey: string): Promise<WorkProjectSummary>
  clearProjectCommand(): void
  reset(): void
}

export const SCOPE_STORE: InjectionKey<ScopeStore> = Symbol('crewscope-scope-store')

export function createScopeStore(gateway: ScopeGateway, principal: AuthenticatedPrincipal): ScopeStore {
  const commandIntents = createCommandGateway(gateway, {
    createWorkProject: 3,
    addMember: 3,
    suspendMember: 4,
    activateMember: 4,
    removeMember: 4,
    leaveTeam: 3,
    grantRole: 5,
    revokeRole: 5,
    transferOwnership: 4,
    createHandover: 3,
    processHandover: 3,
    cancelHandover: 3,
  })
  gateway = commandIntents.gateway
  const state = reactive<ScopeState>({
    phase: 'idle',
    teams: [],
    projects: [],
    members: [],
    selectedTeamId: null,
    selectedProjectId: null,
    projectsTeamId: null,
    membersTeamId: null,
    membersLoading: false,
    memberCommandPending: false,
    handoverJob: null,
    handoverPending: false,
    handoverErrorMessage: null,
    projectCommandPending: false,
    projectCommandRetryable: false,
    errorMessage: null,
    membersErrorMessage: null,
    projectCommandErrorMessage: null,
  })

  let teamsLoaded = false
  let teamsRequest: Promise<void> | null = null
  let synchronizationVersion = 0
  let scopeGeneration = 0
  let membersVersion = 0
  const handoverKeys = new Map<string, string>()

  const selectedTeam = computed(
    () => state.teams.find(team => team.id === state.selectedTeamId) ?? null,
  )
  const selectedProject = computed(
    () => state.projects.find(project => project.id === state.selectedProjectId) ?? null,
  )

  async function ensureTeams(force = false): Promise<void> {
    if (teamsLoaded && !force) return
    if (teamsRequest && !force) return teamsRequest

    const requestGeneration = scopeGeneration
    const request = gateway.listTeams(principal.organizationId).then(teams => {
      if (requestGeneration !== scopeGeneration || teamsRequest !== request) return
      state.teams = teams.filter(team => team.status === 'ACTIVE')
      teamsLoaded = true
    }).finally(() => {
      if (teamsRequest === request) teamsRequest = null
    })
    teamsRequest = request
    return request
  }

  async function synchronize(teamId?: string | null, projectId?: string | null): Promise<ScopeSelection> {
    const requestVersion = ++synchronizationVersion
    state.phase = 'loading'
    state.errorMessage = null
    try {
      await ensureTeams()
      if (requestVersion !== synchronizationVersion) return currentSelection()

      if (state.teams.length === 0) {
        clearScope()
        state.phase = 'empty'
        return currentSelection()
      }

      const team = state.teams.find(candidate => candidate.id === teamId) ?? state.teams[0]
      if (teamId && team.id !== teamId) {
        clearScope()
        throw new Error('Requested Team is not accessible')
      }
      const teamChanged = state.selectedTeamId !== team.id
      state.selectedTeamId = team.id
      if (teamChanged) {
        scopeGeneration += 1
        membersVersion += 1
        state.membersLoading = state.memberCommandPending = false
        state.membersErrorMessage = null
        state.members = []
        state.membersTeamId = null
        clearHandover()
        handoverKeys.clear()
        clearProjectCommand()
      }

      if (team.initializationStatus !== 'READY') {
        state.projects = []
        state.projectsTeamId = team.id
        state.selectedProjectId = null
        state.phase = 'ready'
        return currentSelection()
      }

      if (teamChanged || state.projectsTeamId !== team.id) {
        const page = await gateway.listWorkProjects(principal.organizationId, team.id)
        if (requestVersion !== synchronizationVersion) return currentSelection()
        state.projects = page.items.filter(project => project.status === 'ACTIVE')
        state.projectsTeamId = team.id
      }

      let project = state.projects.find(candidate => candidate.id === projectId) ?? null
      if (projectId && !project) {
        state.selectedProjectId = null
        const located = await gateway.getWorkProject(principal.organizationId, team.id, projectId)
        if (requestVersion !== synchronizationVersion) return currentSelection()
        if (located.id !== projectId || located.teamId !== team.id) throw new Error('Project scope mismatch')
        state.projects = [...state.projects, located]
        project = located
      }
      if (!projectId) project = state.projects[0] ?? null
      state.selectedProjectId = project?.id ?? null
      state.phase = 'ready'
      return currentSelection()
    } catch (error) {
      if (requestVersion === synchronizationVersion) {
        state.phase = 'error'
        state.errorMessage = presentError(error)
      }
      return currentSelection()
    }
  }

  async function reload(): Promise<ScopeSelection> {
    teamsLoaded = false
    state.projectsTeamId = null
    return synchronize(state.selectedTeamId, state.selectedProjectId)
  }

  async function loadMembers(force = false): Promise<void> {
    const teamId = state.selectedTeamId
    if (!teamId || (state.membersTeamId === teamId && !force)) return
    const requestGeneration = scopeGeneration
    const version = ++membersVersion
    state.membersLoading = true
    state.membersErrorMessage = null
    try {
      const members = await gateway.listMembers(principal.organizationId, teamId)
      if (requestGeneration !== scopeGeneration || version !== membersVersion || state.selectedTeamId !== teamId) return
      state.members = members
      state.membersTeamId = teamId
    } catch (error) {
      // A slow response from the previous Team must not overwrite the newly selected Team state.
      if (requestGeneration === scopeGeneration && version === membersVersion && state.selectedTeamId === teamId) {
        state.membersErrorMessage = presentError(error)
      }
    } finally {
      if (requestGeneration === scopeGeneration && version === membersVersion && state.selectedTeamId === teamId) {
        state.membersLoading = false
      }
    }
  }

  async function addMember(principalId: string): Promise<void> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    const requestGeneration = scopeGeneration
    state.memberCommandPending = true
    state.membersErrorMessage = null
    try {
      await gateway.addMember(principal.organizationId, teamId, principalId.trim(), secureId())
      if (requestGeneration !== scopeGeneration) return
      state.membersTeamId = null
      await loadMembers(true)
    } catch (error) {
      if (requestGeneration === scopeGeneration) state.membersErrorMessage = presentError(error)
      throw error
    } finally {
      if (requestGeneration === scopeGeneration) state.memberCommandPending = false
    }
  }

  /** Shared ADR-038 lifecycle path: If-Match the member's current version, then re-read the list. */
  async function runMemberLifecycle(
    action: (organizationId: string, teamId: string, member: TeamMemberSummary) => Promise<unknown>,
    targetMemberId?: string,
  ): Promise<void> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    const member = targetMemberId
      ? state.members.find(candidate => candidate.id === targetMemberId)
      : state.members.find(candidate => candidate.userPrincipalId === principal.id)
    if (!member) throw new Error('Target member is not in the current member list')
    const requestGeneration = scopeGeneration
    state.memberCommandPending = true
    state.membersErrorMessage = null
    try {
      await action(principal.organizationId, teamId, member)
      if (requestGeneration !== scopeGeneration) return
      state.membersTeamId = null
      await loadMembers(true)
    } catch (error) {
      if (requestGeneration === scopeGeneration) state.membersErrorMessage = presentError(error)
      throw error
    } finally {
      if (requestGeneration === scopeGeneration) state.memberCommandPending = false
    }
  }

  function suspendMember(memberId: string): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.suspendMember(organizationId, teamId, member.id, member.version, secureId()), memberId)
  }

  function activateMember(memberId: string): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.activateMember(organizationId, teamId, member.id, member.version, secureId()), memberId)
  }

  function removeMember(memberId: string): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.removeMember(organizationId, teamId, member.id, member.version, secureId()), memberId)
  }

  function leaveTeam(): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.leaveTeam(organizationId, teamId, member.version, secureId()))
  }

  function grantRole(memberId: string, roleKey: string): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.grantRole(organizationId, teamId, member.id, member.version, roleKey, secureId()), memberId)
  }

  function revokeRole(memberId: string, grantId: string): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.revokeRole(organizationId, teamId, member.id, member.version, grantId, secureId()), memberId)
  }

  function transferOwnership(targetMemberId: string): Promise<void> {
    return runMemberLifecycle((organizationId, teamId, member) =>
      gateway.transferOwnership(organizationId, teamId, member.id, member.version, secureId()), targetMemberId)
  }

  /** A07 handover: read-only preview of the source member's active assignments. */
  function previewResponsibilities(memberId: string, role?: string): Promise<MemberResponsibility[]> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    return gateway.listMemberResponsibilities(principal.organizationId, teamId, memberId, role)
  }

  /** Handover commands keep one idempotency key per coordinate until they commit. */
  async function runHandoverCommand(
    coordinate: string[],
    command: (idempotencyKey: string) => Promise<HandoverJobView>,
  ): Promise<HandoverJobView> {
    const key = `${coordinate.join(':')}`
    const requestGeneration = scopeGeneration
    state.handoverPending = true
    state.handoverErrorMessage = null
    try {
      const job = await command(handoverKeys.get(key) ?? (handoverKeys.set(key, secureId()), handoverKeys.get(key)!))
      if (requestGeneration === scopeGeneration) {
        handoverKeys.delete(key)
        state.handoverJob = job
      }
      return job
    } catch (error) {
      if (requestGeneration === scopeGeneration) state.handoverErrorMessage = presentError(error)
      throw error
    } finally {
      if (requestGeneration === scopeGeneration) state.handoverPending = false
    }
  }

  function createHandover(input: CreateHandoverInput): Promise<HandoverJobView> {
    return runHandoverCommand(
      [state.selectedTeamId ?? '', input.sourceMemberId, input.targetPrincipalId, input.role],
      idempotencyKey => gateway.createHandover(principal.organizationId, state.selectedTeamId!, input, idempotencyKey).then(result => result.job),
    )
  }

  function processHandover(jobId: string): Promise<HandoverJobView> {
    return runHandoverCommand(
      [state.selectedTeamId ?? '', jobId, 'process'],
      idempotencyKey => gateway.processHandover(principal.organizationId, state.selectedTeamId!, jobId, idempotencyKey),
    )
  }

  function getHandover(jobId: string): Promise<HandoverJobView> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    return gateway.getHandover(principal.organizationId, teamId, jobId)
  }

  function cancelHandover(jobId: string): Promise<HandoverJobView> {
    return runHandoverCommand(
      [state.selectedTeamId ?? '', jobId, 'cancel'],
      idempotencyKey => gateway.cancelHandover(principal.organizationId, state.selectedTeamId!, jobId, idempotencyKey),
    )
  }

  function clearHandover(): void {
    state.handoverJob = null
    state.handoverPending = false
    state.handoverErrorMessage = null
  }

  async function checkWorkProjectKey(key: string, signal?: AbortSignal): Promise<boolean> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    const availability = await gateway.checkWorkProjectKey(
      principal.organizationId,
      teamId,
      key.trim().toUpperCase(),
      signal,
    )
    return availability.available
  }

  async function createWorkProject(
    input: CreateWorkProjectInput,
    idempotencyKey: string,
  ): Promise<WorkProjectSummary> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    const requestGeneration = scopeGeneration
    const normalized = { ...(input.key === undefined ? {} : { key: input.key.trim().toUpperCase() }), name: input.name.trim() }
    state.projectCommandPending = true
    state.projectCommandRetryable = false
    state.projectCommandErrorMessage = null
    try {
      const receipt = await gateway.createWorkProject(principal.organizationId, teamId, normalized, idempotencyKey)
      if (requestGeneration !== scopeGeneration || state.selectedTeamId !== teamId) {
        throw new Error('WorkProject scope changed while the command completed')
      }
      const created = receipt.createdResource as WorkProjectSummary | undefined
      if (!created || !receipt.creation || created.id !== receipt.creation.resourceId) {
        throw new Error('Created WorkProject result is not available')
      }
      state.projects = [...state.projects.filter(project => project.id !== created.id), created]
      state.projectsTeamId = teamId
      state.selectedProjectId = created.id
      acknowledgeCreation(receipt.recoveryKey, principal.organizationId)
      clearProjectCommand()
      return created
    } catch (error) {
      if (requestGeneration === scopeGeneration && state.selectedTeamId === teamId) {
        state.projectCommandRetryable = retryable(error)
        state.projectCommandErrorMessage = presentProjectCommandError(error)
      }
      throw error
    } finally {
      if (requestGeneration === scopeGeneration && state.selectedTeamId === teamId) {
        state.projectCommandPending = false
      }
    }
  }

  function clearProjectCommand(): void {
    state.projectCommandPending = false
    state.projectCommandRetryable = false
    state.projectCommandErrorMessage = null
  }

  function currentSelection(): ScopeSelection {
    return { teamId: state.selectedTeamId, projectId: state.selectedProjectId }
  }

  function clearScope(): void {
    state.selectedTeamId = null
    state.selectedProjectId = null
    state.projects = []
    state.projectsTeamId = null
    state.members = []
    state.membersTeamId = null
  }

  function reset(): void {
    commandIntents.clear()
    scopeGeneration += 1
    synchronizationVersion += 1
    teamsLoaded = false
    teamsRequest = null
    state.phase = 'idle'
    state.teams = []
    state.membersLoading = false
    state.memberCommandPending = false
    handoverKeys.clear()
    clearHandover()
    clearProjectCommand()
    state.errorMessage = null
    state.membersErrorMessage = null
    clearScope()
  }

  return {
    state: readonly(state) as Readonly<ScopeState>,
    selectedTeam,
    selectedProject,
    synchronize,
    reload,
    loadMembers,
    addMember,
    suspendMember,
    activateMember,
    removeMember,
    leaveTeam,
    grantRole,
    revokeRole,
    transferOwnership,
    previewResponsibilities,
    createHandover,
    processHandover,
    getHandover,
    cancelHandover,
    clearHandover,
    checkWorkProjectKey,
    createWorkProject,
    clearProjectCommand,
    reset,
  }
}

export function installScopeStore(
  app: App,
  gateway: ScopeGateway,
  principal: AuthenticatedPrincipal,
): ScopeStore {
  const store = createScopeStore(gateway, principal)
  app.provide(SCOPE_STORE, store)
  return store
}

export function useScopeStore(): ScopeStore {
  const store = inject(SCOPE_STORE)
  if (!store) throw new Error('CrewScope Scope Store is not installed')
  return store
}

function presentError(error: unknown): string {
  if (error instanceof CrewScopeApiError) return error.envelope.message
  return '暂时无法加载团队范围，请稍后重试'
}

function retryable(error: unknown): boolean {
  return !(error instanceof CrewScopeApiError) || error.status === 0 || error.envelope.retryable
}

function presentProjectCommandError(error: unknown): string {
  if (error instanceof CrewScopeApiError) return error.envelope.message
  return '项目创建结果仍待确认，请查询原操作，不要重复新建。'
}
