import type { CreateHandoverInput, ScopeGateway } from '../domains/scope/gateway'
import type {
  CommandReceipt,
  CreateWorkProjectInput,
  HandoverJobView,
  MemberResponsibility,
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
  async getWorkProject(_organizationId: string, teamId: string, projectId: string): Promise<WorkProjectSummary> {
    const value = this.projects[teamId]?.find(project => project.id === projectId)
    if (!value) throw new Error('Project not found')
    return structuredClone(value)
  }
  readonly addedPrincipalIds: string[] = []
  readonly createdProjects: Array<{ input: CreateWorkProjectInput, idempotencyKey?: string }> = []
  readonly lifecycleCommands: Array<{ action: string, memberId: string, memberVersion: number, idempotencyKey: string }> = []
  readonly responsibilities: Record<string, MemberResponsibility[]> = {}

  constructor(
    public teams = structuredClone(fixtureTeams),
    public projects = structuredClone(fixtureProjects),
    public members = structuredClone(fixtureMembers),
    public principalId: string = fixtureIds.principal,
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
    const created = project(crypto.randomUUID(), teamId, team.defaultWorkspaceId, input.key ?? 'AUTO', input.name)
    this.projects[teamId] = [created, ...(this.projects[teamId] ?? [])]
    return { ...receipt(), creation: { type: 'WORK_PROJECT', organizationId: _organizationId, teamId,
      projectId: created.id, resourceId: created.id, committedVersion: 0, stage: 'COMMITTED' }, createdResource: created }
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

  /** A07 lifecycle commands mirror the server: each transition bumps version and authorizationVersion. */
  private transition(
    teamId: string,
    memberId: string,
    memberVersion: number,
    idempotencyKey: string,
    action: string,
    apply: (target: TeamMemberSummary) => TeamMemberSummary,
  ): CommandReceipt {
    this.lifecycleCommands.push({ action, memberId, memberVersion, idempotencyKey })
    const list = this.members[teamId] ?? []
    const index = list.findIndex(candidate => candidate.id === memberId)
    if (index < 0) throw new Error('Member not found')
    const target = list[index]!
    if (target.version !== memberVersion) {
      throw Object.assign(new Error('version conflict'), { name: 'CrewScopeApiError' })
    }
    this.members[teamId] = list.map((candidate, position) =>
      position === index ? apply({ ...candidate, version: candidate.version + 1 }) : candidate)
    return receipt()
  }

  private authorizationBump(candidate: TeamMemberSummary): TeamMemberSummary {
    return { ...candidate, authorizationVersion: (candidate.authorizationVersion ?? 1) + 1 }
  }

  suspendMember(_organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return Promise.resolve(this.transition(teamId, memberId, memberVersion, idempotencyKey, 'suspend', target =>
      this.authorizationBump({ ...target, status: 'SUSPENDED' })))
  }

  activateMember(_organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return Promise.resolve(this.transition(teamId, memberId, memberVersion, idempotencyKey, 'activate', target =>
      this.authorizationBump({ ...target, status: 'ACTIVE', roles: [], grants: [] })))
  }

  removeMember(_organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return Promise.resolve(this.transition(teamId, memberId, memberVersion, idempotencyKey, 'remove', target =>
      this.authorizationBump({ ...target, status: 'REMOVED', roles: [], grants: [] })))
  }

  leaveTeam(_organizationId: string, teamId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    const self = (this.members[teamId] ?? []).find(candidate => candidate.userPrincipalId === this.principalId)
    if (!self) throw new Error('Member not found')
    return Promise.resolve(this.transition(teamId, self.id, memberVersion, idempotencyKey, 'leave', target =>
      this.authorizationBump({ ...target, status: 'LEFT', roles: [], grants: [] })))
  }

  grantRole(_organizationId: string, teamId: string, memberId: string, memberVersion: number, roleKey: string, idempotencyKey: string): Promise<CommandReceipt> {
    return Promise.resolve(this.transition(teamId, memberId, memberVersion, idempotencyKey, 'grantRole', target =>
      this.authorizationBump({
        ...target,
        grants: [...(target.grants ?? []), { id: crypto.randomUUID(), roleKey }],
        roles: [...(target.roles ?? []), roleKey],
      })))
  }

  revokeRole(_organizationId: string, teamId: string, memberId: string, memberVersion: number, grantId: string, idempotencyKey: string): Promise<CommandReceipt> {
    return Promise.resolve(this.transition(teamId, memberId, memberVersion, idempotencyKey, 'revokeRole', target => {
      const grant = (target.grants ?? []).find(candidate => candidate.id === grantId)
      return this.authorizationBump({
        ...target,
        grants: (target.grants ?? []).filter(candidate => candidate.id !== grantId),
        roles: (target.roles ?? []).filter(role => role !== grant?.roleKey),
      })
    }))
  }

  transferOwnership(_organizationId: string, teamId: string, targetMemberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    const receiptFor = this.transition(teamId, targetMemberId, memberVersion, idempotencyKey, 'transferOwnership', target =>
      this.authorizationBump(target))
    const team = this.teams.find(candidate => candidate.id === teamId)
    const previousOwnerId = team?.ownerMemberId ?? null
    if (team && previousOwnerId) {
      team.ownerMemberId = targetMemberId
      this.members[teamId] = (this.members[teamId] ?? []).map(candidate =>
        candidate.id === previousOwnerId
          ? this.authorizationBump({ ...candidate, version: candidate.version + 1, grants: [], roles: [] })
          : candidate)
    }
    return Promise.resolve(receiptFor)
  }

  async listMemberResponsibilities(_organizationId: string, _teamId: string, memberId: string, role?: string): Promise<MemberResponsibility[]> {
    const items = this.responsibilities[memberId] ?? []
    return structuredClone(role ? items.filter(item => item.role === role) : items)
  }

  async createHandover(_organizationId: string, _teamId: string, input: CreateHandoverInput, _idempotencyKey: string): Promise<{ receipt: CommandReceipt, job: HandoverJobView }> {
    const items = (this.responsibilities[input.sourceMemberId] ?? [])
      .filter(item => !input.role || item.role === input.role)
      .map(item => ({ id: crypto.randomUUID(), workItemId: item.workItemId, assignmentId: item.assignmentId, state: 'PENDING' as const }))
    const job: HandoverJobView = {
      id: crypto.randomUUID(), role: input.role, status: 'PENDING',
      sourceMemberId: input.sourceMemberId, targetPrincipalId: input.targetPrincipalId,
      sourceAuthorizationVersion: 1, version: 0, items,
    }
    this.jobs[job.id] = job
    return { receipt: receipt(), job: structuredClone(job) }
  }

  readonly jobs: Record<string, HandoverJobView> = {}

  async processHandover(_organizationId: string, _teamId: string, jobId: string, _idempotencyKey: string): Promise<HandoverJobView> {
    const job = this.requireJob(jobId)
    job.status = 'COMPLETED'
    job.items = job.items.map(item => ({ ...item, state: 'DONE' as const }))
    job.version += 1
    return structuredClone(job)
  }

  async getHandover(_organizationId: string, _teamId: string, jobId: string): Promise<HandoverJobView> {
    return structuredClone(this.requireJob(jobId))
  }

  async cancelHandover(_organizationId: string, _teamId: string, jobId: string, _idempotencyKey: string): Promise<HandoverJobView> {
    const job = this.requireJob(jobId)
    job.status = 'CANCELLED'
    job.version += 1
    return structuredClone(job)
  }

  private requireJob(jobId: string): HandoverJobView {
    const job = this.jobs[jobId]
    if (!job) throw new Error('Handover job not found')
    return job
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
    roles: [],
    grants: [],
    authorizationVersion: 1,
    version: 0,
  }
}
