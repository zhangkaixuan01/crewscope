import type { ScopeGateway } from '../domains/scope/gateway'
import type {
  CommandReceipt,
  CreateWorkProjectInput,
  TeamMemberSummary,
  TeamSummary,
  WorkProjectPage,
  WorkProjectKeyAvailability,
  WorkProjectSummary,
} from '../domains/scope/types'

export const fixtureIds = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  secondPrincipal: '00000000-0000-0000-0000-000000000102',
  teamPlatform: '00000000-0000-0000-0000-000000000201',
  teamSecurity: '00000000-0000-0000-0000-000000000202',
  memberOwner: '00000000-0000-0000-0000-000000000301',
  memberSecond: '00000000-0000-0000-0000-000000000302',
  projectCrewScope: '00000000-0000-0000-0000-000000000401',
  projectRuntime: '00000000-0000-0000-0000-000000000402',
  workspacePlatform: '00000000-0000-0000-0000-000000000501',
  workspaceSecurity: '00000000-0000-0000-0000-000000000502',
} as const

export const fixtureTeams: TeamSummary[] = [
  {
    id: fixtureIds.teamPlatform,
    organizationId: fixtureIds.organization,
    name: 'Platform Engineering',
    status: 'ACTIVE',
    initializationStatus: 'READY',
    ownerMemberId: fixtureIds.memberOwner,
    defaultWorkspaceId: fixtureIds.workspacePlatform,
    version: 0,
  },
  {
    id: fixtureIds.teamSecurity,
    organizationId: fixtureIds.organization,
    name: 'Security Engineering',
    status: 'ACTIVE',
    initializationStatus: 'READY',
    ownerMemberId: fixtureIds.memberSecond,
    defaultWorkspaceId: fixtureIds.workspaceSecurity,
    version: 0,
  },
]

export const fixtureProjects: Record<string, WorkProjectSummary[]> = {
  [fixtureIds.teamPlatform]: [project(fixtureIds.projectCrewScope, fixtureIds.teamPlatform, fixtureIds.workspacePlatform, 'CRW', 'CrewScope')],
  [fixtureIds.teamSecurity]: [project(fixtureIds.projectRuntime, fixtureIds.teamSecurity, fixtureIds.workspaceSecurity, 'SEC', 'Runtime Security')],
}

export const fixtureMembers: Record<string, TeamMemberSummary[]> = {
  [fixtureIds.teamPlatform]: [
    member(fixtureIds.memberOwner, fixtureIds.principal, 'CREATED_WITH_TEAM'),
    member(fixtureIds.memberSecond, fixtureIds.secondPrincipal, 'ADDED_BY_MEMBER'),
  ],
  [fixtureIds.teamSecurity]: [member(fixtureIds.memberSecond, fixtureIds.secondPrincipal, 'CREATED_WITH_TEAM')],
}

export class FixtureScopeGateway implements ScopeGateway {
  readonly addedPrincipalIds: string[] = []
  readonly createdProjects: Array<{ input: CreateWorkProjectInput, idempotencyKey?: string }> = []

  constructor(
    public teams = structuredClone(fixtureTeams),
    public projects = structuredClone(fixtureProjects),
    public members = structuredClone(fixtureMembers),
  ) {}

  async listTeams(): Promise<TeamSummary[]> {
    return structuredClone(this.teams)
  }

  async listWorkProjects(_organizationId: string, teamId: string): Promise<WorkProjectPage> {
    return { items: structuredClone(this.projects[teamId] ?? []), nextCursor: null }
  }

  async checkWorkProjectKey(_organizationId: string, teamId: string, key: string): Promise<WorkProjectKeyAvailability> {
    return { key, available: !(this.projects[teamId] ?? []).some(project => project.key === key) }
  }

  async createWorkProject(
    _organizationId: string,
    teamId: string,
    input: CreateWorkProjectInput,
    idempotencyKey?: string,
  ): Promise<CommandReceipt> {
    this.createdProjects.push({ input, idempotencyKey })
    const team = this.teams.find(candidate => candidate.id === teamId)
    if (!team?.defaultWorkspaceId) throw new Error('Team default Workspace is unavailable')
    const created = project(crypto.randomUUID(), teamId, team.defaultWorkspaceId, input.key, input.name)
    this.projects[teamId] = [created, ...(this.projects[teamId] ?? [])]
    return receipt()
  }

  async listMembers(_organizationId: string, teamId: string): Promise<TeamMemberSummary[]> {
    return structuredClone(this.members[teamId] ?? [])
  }

  async addMember(_organizationId: string, teamId: string, principalId: string): Promise<CommandReceipt> {
    this.addedPrincipalIds.push(principalId)
    const nextMember = member(crypto.randomUUID(), principalId, 'ADDED_BY_MEMBER')
    this.members[teamId] = [...(this.members[teamId] ?? []), nextMember]
    return receipt()
  }
}

function receipt(): CommandReceipt {
  return {
    commandId: crypto.randomUUID(),
    domainEventId: crypto.randomUUID(),
    committedVersion: 0,
    correlationId: crypto.randomUUID(),
  }
}

function project(
  id: string,
  teamId: string,
  workspaceId: string,
  key: string,
  name: string,
): WorkProjectSummary {
  return {
    id,
    organizationId: fixtureIds.organization,
    teamId,
    workspaceId,
    key,
    name,
    status: 'ACTIVE',
    version: 0,
    createdAt: '2026-08-08T01:00:00Z',
    createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-08-08T02:00:00Z',
    updatedByPrincipalId: fixtureIds.principal,
  }
}

function member(id: string, principalId: string, joinMethod: string): TeamMemberSummary {
  return {
    id,
    userPrincipalId: principalId,
    displayName: principalId === fixtureIds.principal ? 'Zhang Kaixuan' : 'Lin Chen',
    status: 'ACTIVE',
    joinMethod,
    joinedAt: '2026-08-08T01:00:00Z',
    version: 0,
  }
}
