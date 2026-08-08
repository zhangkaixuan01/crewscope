import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  CommandReceipt,
  TeamMemberSummary,
  TeamSummary,
  WorkProjectPage,
} from './types'

export interface ScopeGateway {
  listTeams(organizationId: string, signal?: AbortSignal): Promise<TeamSummary[]>
  listWorkProjects(organizationId: string, teamId: string, signal?: AbortSignal): Promise<WorkProjectPage>
  listMembers(organizationId: string, teamId: string, signal?: AbortSignal): Promise<TeamMemberSummary[]>
  addMember(organizationId: string, teamId: string, principalId: string, idempotencyKey: string): Promise<CommandReceipt>
}

/** HTTP adapter for the M1 Team and WorkProject API contracts. */
export class HttpScopeGateway implements ScopeGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  listTeams(organizationId: string, signal?: AbortSignal): Promise<TeamSummary[]> {
    return this.client.get(`/organizations/${segment(organizationId)}/teams`, { signal })
  }

  listWorkProjects(organizationId: string, teamId: string, signal?: AbortSignal): Promise<WorkProjectPage> {
    return this.client.get(
      `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/work-projects?limit=100`,
      { signal },
    )
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
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
