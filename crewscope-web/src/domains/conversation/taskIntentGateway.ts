import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  ConversationMessageScope,
  TaskIntentConfirmationPreview,
  TaskIntentRevisionInput,
  VersionedTaskIntent,
  VersionedTaskIntentConfirmationPreview,
} from './types'

export interface TaskIntentGateway {
  get(scope: ConversationMessageScope, taskIntentId: string, signal?: AbortSignal): Promise<VersionedTaskIntent>
  revise(
    scope: ConversationMessageScope,
    taskIntentId: string,
    input: TaskIntentRevisionInput,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<void>
  previewConfirmation(
    scope: ConversationMessageScope,
    taskIntentId: string,
    expectedVersion: number,
    signal?: AbortSignal,
  ): Promise<VersionedTaskIntentConfirmationPreview>
  confirm(
    scope: ConversationMessageScope,
    taskIntentId: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<void>
  reject(
    scope: ConversationMessageScope,
    taskIntentId: string,
    reason: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<void>
}

/** HTTP adapter that keeps strong ETags and never infers facts from command receipts. */
export class HttpTaskIntentGateway implements TaskIntentGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async get(
    scope: ConversationMessageScope,
    taskIntentId: string,
    signal?: AbortSignal,
  ): Promise<VersionedTaskIntent> {
    const response = await this.client.open(path(scope, taskIntentId), { method: 'GET', signal })
    const value = await response.json() as VersionedTaskIntent['value']
    return { value, etag: requireStrongEtag(response, value.version) }
  }

  async revise(
    scope: ConversationMessageScope,
    taskIntentId: string,
    input: TaskIntentRevisionInput,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<void> {
    await this.client.open(`${path(scope, taskIntentId)}/revisions`, {
      method: 'POST', body: input, expectedVersion, idempotencyKey,
    })
  }

  async previewConfirmation(
    scope: ConversationMessageScope,
    taskIntentId: string,
    expectedVersion: number,
    signal?: AbortSignal,
  ): Promise<VersionedTaskIntentConfirmationPreview> {
    const response = await this.client.open(`${path(scope, taskIntentId)}/confirmation-previews`, {
      method: 'POST', expectedVersion, signal,
    })
    const value = await response.json() as TaskIntentConfirmationPreview
    return { value, etag: requireStrongEtag(response, value.version) }
  }

  async confirm(
    scope: ConversationMessageScope,
    taskIntentId: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<void> {
    // Confirmation intentionally has no body and therefore no Content-Type header.
    await this.client.open(`${path(scope, taskIntentId)}/confirmations`, {
      method: 'POST', expectedVersion, idempotencyKey,
    })
  }

  async reject(
    scope: ConversationMessageScope,
    taskIntentId: string,
    reason: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<void> {
    await this.client.open(`${path(scope, taskIntentId)}/rejections`, {
      method: 'POST', body: { reason }, expectedVersion, idempotencyKey,
    })
  }
}

function path(scope: ConversationMessageScope, taskIntentId: string): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/conversations/${segment(scope.conversationId)}/task-intents/${segment(taskIntentId)}`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function requireStrongEtag(response: Response, version: number): string {
  const etag = response.headers.get('ETag')
  if (etag !== `"${version}"`) throw new TypeError('TaskIntent response has an invalid ETag')
  return etag
}
