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
import type { ScopeGateway } from './gateway'
import type { TeamMemberSummary, TeamSummary, WorkProjectSummary } from './types'

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
  errorMessage: string | null
  membersErrorMessage: string | null
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
}

export const SCOPE_STORE: InjectionKey<ScopeStore> = Symbol('crewscope-scope-store')

export function createScopeStore(gateway: ScopeGateway, principal: AuthenticatedPrincipal): ScopeStore {
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
    errorMessage: null,
    membersErrorMessage: null,
  })

  let teamsLoaded = false
  let teamsRequest: Promise<void> | null = null
  let synchronizationVersion = 0

  const selectedTeam = computed(
    () => state.teams.find(team => team.id === state.selectedTeamId) ?? null,
  )
  const selectedProject = computed(
    () => state.projects.find(project => project.id === state.selectedProjectId) ?? null,
  )

  async function ensureTeams(force = false): Promise<void> {
    if (teamsLoaded && !force) return
    if (teamsRequest && !force) return teamsRequest

    teamsRequest = gateway.listTeams(principal.organizationId).then(teams => {
      state.teams = teams.filter(team => team.status === 'ACTIVE')
      teamsLoaded = true
    }).finally(() => {
      teamsRequest = null
    })
    return teamsRequest
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
      const teamChanged = state.selectedTeamId !== team.id
      state.selectedTeamId = team.id
      if (teamChanged) {
        state.members = []
        state.membersTeamId = null
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

      const project = state.projects.find(candidate => candidate.id === projectId) ?? state.projects[0] ?? null
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
    state.membersLoading = true
    state.membersErrorMessage = null
    try {
      const members = await gateway.listMembers(principal.organizationId, teamId)
      if (state.selectedTeamId !== teamId) return
      state.members = members
      state.membersTeamId = teamId
    } catch (error) {
      // A slow response from the previous Team must not overwrite the newly selected Team state.
      if (state.selectedTeamId === teamId) state.membersErrorMessage = presentError(error)
    } finally {
      if (state.selectedTeamId === teamId) state.membersLoading = false
    }
  }

  async function addMember(principalId: string): Promise<void> {
    const teamId = state.selectedTeamId
    if (!teamId) throw new Error('No Team is selected')
    state.memberCommandPending = true
    state.membersErrorMessage = null
    try {
      await gateway.addMember(principal.organizationId, teamId, principalId.trim(), crypto.randomUUID())
      state.membersTeamId = null
      await loadMembers(true)
    } catch (error) {
      state.membersErrorMessage = presentError(error)
      throw error
    } finally {
      state.memberCommandPending = false
    }
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

  return {
    state: readonly(state) as Readonly<ScopeState>,
    selectedTeam,
    selectedProject,
    synchronize,
    reload,
    loadMembers,
    addMember,
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
