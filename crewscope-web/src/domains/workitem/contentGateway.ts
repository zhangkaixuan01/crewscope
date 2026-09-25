import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { CommandReceipt } from '../scope/types'
import type { WorkItemPriority, WorkItemScope } from './types'

export interface WorkItemContentInput {
  title?: string
  description?: string | null
  priority?: WorkItemPriority
  labels?: string[] | null
  dueAt?: string | null
}

export class HttpWorkItemContentGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}
  update(scope: WorkItemScope, workItemId: string, input: WorkItemContentInput,
    expectedVersion: number, idempotencyKey: string): Promise<CommandReceipt> {
    const segment = encodeURIComponent
    return this.client.request(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/work-projects/${segment(scope.projectId)}/work-items/${segment(workItemId)}`,
      { method: 'PATCH', body: input, expectedVersion, idempotencyKey })
  }
}
