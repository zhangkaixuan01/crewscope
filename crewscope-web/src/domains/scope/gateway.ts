import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { createAndLocate } from '../../api/creationRecovery'
import type {
  CommandReceipt,
  CreateWorkProjectInput,
  HandoverJobView,
  MemberResponsibility,
  TeamMemberSummary,
  TeamSummary,
  WorkProjectKeyAvailability,
  WorkProjectPage,
  WorkProjectSummary,
} from './types'

export interface CreateHandoverInput {
  sourceMemberId: string
  targetPrincipalId: string
  role: string
}

export interface ScopeGateway {
  getWorkProject(organizationId: string, teamId: string, projectId: string, signal?: AbortSignal): Promise<WorkProjectSummary>
  listTeams(organizationId: string, signal?: AbortSignal): Promise<TeamSummary[]>
  listWorkProjects(organizationId: string, teamId: string, signal?: AbortSignal): Promise<WorkProjectPage>
  checkWorkProjectKey(organizationId: string, teamId: string, key: string, signal?: AbortSignal): Promise<WorkProjectKeyAvailability>
  createWorkProject(organizationId: string, teamId: string, input: CreateWorkProjectInput, idempotencyKey: string): Promise<CommandReceipt>
  listMembers(organizationId: string, teamId: string, signal?: AbortSignal): Promise<TeamMemberSummary[]>
  addMember(organizationId: string, teamId: string, principalId: string, idempotencyKey: string): Promise<CommandReceipt>
  suspendMember(organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt>
  activateMember(organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt>
  removeMember(organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt>
  leaveTeam(organizationId: string, teamId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt>
  grantRole(organizationId: string, teamId: string, memberId: string, memberVersion: number, roleKey: string, idempotencyKey: string): Promise<CommandReceipt>
  revokeRole(organizationId: string, teamId: string, memberId: string, memberVersion: number, grantId: string, idempotencyKey: string): Promise<CommandReceipt>
  transferOwnership(organizationId: string, teamId: string, targetMemberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt>
  listMemberResponsibilities(organizationId: string, teamId: string, memberId: string, role?: string, signal?: AbortSignal): Promise<MemberResponsibility[]>
  createHandover(organizationId: string, teamId: string, input: CreateHandoverInput, idempotencyKey: string): Promise<{ receipt: CommandReceipt, job: HandoverJobView }>
  processHandover(organizationId: string, teamId: string, jobId: string, idempotencyKey: string): Promise<HandoverJobView>
  getHandover(organizationId: string, teamId: string, jobId: string, signal?: AbortSignal): Promise<HandoverJobView>
  cancelHandover(organizationId: string, teamId: string, jobId: string, idempotencyKey: string): Promise<HandoverJobView>
}

/** HTTP adapter for the M1 Team and WorkProject API contracts. */
export class HttpScopeGateway implements ScopeGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  getWorkProject(organizationId: string, teamId: string, projectId: string, signal?: AbortSignal): Promise<WorkProjectSummary> {
    return this.client.get(`/organizations/${segment(organizationId)}/teams/${segment(teamId)}/work-projects/${segment(projectId)}`, { signal })
  }

  listTeams(organizationId: string, signal?: AbortSignal): Promise<TeamSummary[]> {
    return this.client.get(`/organizations/${segment(organizationId)}/teams`, { signal })
  }

  listWorkProjects(organizationId: string, teamId: string, signal?: AbortSignal): Promise<WorkProjectPage> {
    return this.client.get(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/work-projects?limit=100`,
      { signal },
    )
  }

  checkWorkProjectKey(
    organizationId: string,
    teamId: string,
    key: string,
    signal?: AbortSignal,
  ): Promise<WorkProjectKeyAvailability> {
    return this.client.get(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/work-projects/keys/${segment(key)}`,
      { signal },
    )
  }

  createWorkProject(
    organizationId: string,
    teamId: string,
    input: CreateWorkProjectInput,
    idempotencyKey: string,
  ): Promise<CommandReceipt> {
    return createAndLocate(this.client, { organizationId, teamId }, 'WORK_PROJECT', idempotencyKey, () => this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/work-projects`,
      input,
      { idempotencyKey },
    ))
  }

  listMembers(organizationId: string, teamId: string, signal?: AbortSignal): Promise<TeamMemberSummary[]> {
    return this.client.get(`/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members`, { signal })
  }

  addMember(
    organizationId: string,
    teamId: string,
    principalId: string,
    idempotencyKey: string,
  ): Promise<CommandReceipt> {
    return this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members`,
      { userPrincipalId: principalId },
      { idempotencyKey },
    )
  }

  /**
   * ADR-038 lifecycle commands. Each carries the target member's optimistic version as a
   * strong If-Match ETag plus a caller-generated Idempotency-Key; all return the shared
   * 202 receipt and the member list is re-read afterwards.
   */
  suspendMember(organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return this.memberLifecycle(organizationId, teamId, memberId, 'suspend', memberVersion, idempotencyKey)
  }

  activateMember(organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return this.memberLifecycle(organizationId, teamId, memberId, 'activate', memberVersion, idempotencyKey)
  }

  removeMember(organizationId: string, teamId: string, memberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return this.memberLifecycle(organizationId, teamId, memberId, 'remove', memberVersion, idempotencyKey)
  }

  leaveTeam(organizationId: string, teamId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members/me/leave`,
      {},
      { idempotencyKey, expectedVersion: memberVersion },
    )
  }

  grantRole(organizationId: string, teamId: string, memberId: string, memberVersion: number, roleKey: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members/${segment(memberId)}/roles`,
      { roleKey },
      { idempotencyKey, expectedVersion: memberVersion },
    )
  }

  revokeRole(organizationId: string, teamId: string, memberId: string, memberVersion: number, grantId: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members/${segment(memberId)}/roles/${segment(grantId)}/revoke`,
      {},
      { idempotencyKey, expectedVersion: memberVersion },
    )
  }

  transferOwnership(organizationId: string, teamId: string, targetMemberId: string, memberVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/transfer-ownership`,
      { targetMemberId },
      { idempotencyKey, expectedVersion: memberVersion },
    )
  }

  listMemberResponsibilities(organizationId: string, teamId: string, memberId: string, role?: string, signal?: AbortSignal): Promise<MemberResponsibility[]> {
    const suffix = role ? `?role=${segment(role)}` : ''
    return this.client.get(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members/${segment(memberId)}/responsibilities${suffix}`,
      { signal },
    )
  }

  /** A07 responsibility handover: 202 returns the receipt plus the queued job for processing. */
  async createHandover(
    organizationId: string,
    teamId: string,
    input: CreateHandoverInput,
    idempotencyKey: string,
  ): Promise<{ receipt: CommandReceipt, job: HandoverJobView }> {
    const value = record(await this.client.post<unknown>(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/responsibility-handovers`,
      { sourceMemberId: input.sourceMemberId, targetPrincipalId: input.targetPrincipalId, role: input.role },
      { idempotencyKey },
    ))
    return { receipt: mapReceipt(value.command), job: mapHandoverJob(value.job) }
  }

  /** The wire carries `jobId`/`itemId`; every job response is normalized to the view shape. */
  async processHandover(organizationId: string, teamId: string, jobId: string, idempotencyKey: string): Promise<HandoverJobView> {
    return mapHandoverJob(await this.client.post<unknown>(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/responsibility-handovers/${segment(jobId)}/process`,
      {},
      { idempotencyKey },
    ))
  }

  async getHandover(organizationId: string, teamId: string, jobId: string, signal?: AbortSignal): Promise<HandoverJobView> {
    return mapHandoverJob(await this.client.get<unknown>(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/responsibility-handovers/${segment(jobId)}`,
      { signal },
    ))
  }

  async cancelHandover(organizationId: string, teamId: string, jobId: string, idempotencyKey: string): Promise<HandoverJobView> {
    return mapHandoverJob(await this.client.post<unknown>(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/responsibility-handovers/${segment(jobId)}/cancel`,
      {},
      { idempotencyKey },
    ))
  }

  private memberLifecycle(
    organizationId: string,
    teamId: string,
    memberId: string,
    action: 'suspend' | 'activate' | 'remove',
    memberVersion: number,
    idempotencyKey: string,
  ): Promise<CommandReceipt> {
    return this.client.post(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/members/${segment(memberId)}/${action}`,
      {},
      { idempotencyKey, expectedVersion: memberVersion },
    )
  }
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function mapReceipt(input: unknown): CommandReceipt {
  const value = record(input)
  return {
    commandId: requiredString(value.commandId),
    domainEventId: requiredString(value.domainEventId),
    committedVersion: requiredInteger(value.committedVersion),
    correlationId: requiredString(value.correlationId),
  }
}

function mapHandoverJob(input: unknown): HandoverJobView {
  const value = record(input)
  if (!Array.isArray(value.items)) throw new TypeError('Handover job items are invalid')
  return {
    id: requiredString(value.jobId),
    role: requiredString(value.role),
    status: requiredString(value.status),
    sourceMemberId: requiredString(value.sourceMemberId),
    targetPrincipalId: requiredString(value.targetPrincipalId),
    sourceAuthorizationVersion: requiredInteger(value.sourceAuthorizationVersion),
    version: requiredInteger(value.version),
    items: value.items.map((item: unknown) => {
      const entry = record(item)
      return {
        id: requiredString(entry.itemId),
        workItemId: requiredString(entry.workItemId),
        assignmentId: requiredString(entry.assignmentId),
        state: requiredString(entry.state),
        resultAssignmentId: entry.resultAssignmentId == null ? null : requiredString(entry.resultAssignmentId),
        errorCode: entry.errorCode == null ? null : requiredString(entry.errorCode),
      }
    }),
  }
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('Scope response object is invalid')
  return input as Record<string, unknown>
}

function requiredString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Scope response string is invalid')
  return input
}

function requiredInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) throw new TypeError('Scope response integer is invalid')
  return input
}
