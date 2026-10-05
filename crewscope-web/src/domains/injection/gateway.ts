import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { injectionReferenceStages, injectionReferenceTypes } from './types'
import type {
  InjectionFeedbackInput,
  InjectionReference,
  InjectionReferenceKey,
  InjectionReferenceStage,
  InjectionReferenceType,
  InjectionReferences,
  InjectionScope,
} from './types'

export interface InjectionGateway {
  list(scope: InjectionScope, taskId: string, executionId: string, signal?: AbortSignal): Promise<InjectionReferences>
  submitFeedback(scope: InjectionScope, taskId: string, executionId: string, input: InjectionFeedbackInput): Promise<InjectionReferenceKey>
}

/** M10-I02c HTTP adapter: reads the sealed evidence view, files the "not applicable" feedback. */
export class HttpInjectionGateway implements InjectionGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async list(
    scope: InjectionScope,
    taskId: string,
    executionId: string,
    signal?: AbortSignal,
  ): Promise<InjectionReferences> {
    const value = await this.client.get<InjectionReferences>(root(scope, taskId, executionId), { signal })
    return mapReferences(value)
  }

  async submitFeedback(
    scope: InjectionScope,
    taskId: string,
    executionId: string,
    input: InjectionFeedbackInput,
  ): Promise<InjectionReferenceKey> {
    // Contract §3: structurally idempotent on (execution × quadruple × member) — no
    // Idempotency-Key, no If-Match; a replay returns the first stored row. The request
    // serialises `version` as a string while every response carries it as a number.
    const value = await this.client.post<InjectionReferenceKey>(`${root(scope, taskId, executionId)}/feedback`, {
      type: input.type,
      sourceId: input.sourceId,
      version: String(input.version),
      contentHash: input.contentHash,
    })
    return mapKey(value)
  }
}

function root(scope: InjectionScope, taskId: string, executionId: string): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/tasks/${segment(taskId)}/attempts/${segment(executionId)}/injection-references`
}

function mapReferences(value: InjectionReferences): InjectionReferences {
  return {
    executionId: value.executionId,
    taskId: value.taskId,
    attempts: value.attempts.map(attempt => ({
      manifestId: attempt.manifestId,
      attempt: attempt.attempt,
      createdAt: attempt.createdAt,
      budget: { ...attempt.budget },
      // Open vocabulary: the manifest may carry degradation codes this build does not know.
      degradations: [...attempt.degradations],
      trims: attempt.trims.map(trim => ({ ...trim })),
      references: attempt.references.map(mapReference),
      claimed: attempt.claimed === null ? null : attempt.claimed.map(mapKey),
    })),
  }
}

function mapReference(value: InjectionReference): InjectionReference {
  return {
    type: referenceType(value.type),
    sourceId: value.sourceId,
    version: value.version,
    contentHash: value.contentHash,
    stage: referenceStage(value.stage),
    notApplicable: Boolean(value.notApplicable),
  }
}

function mapKey(value: InjectionReferenceKey): InjectionReferenceKey {
  return {
    type: referenceType(value.type),
    sourceId: value.sourceId,
    version: value.version,
    contentHash: value.contentHash,
  }
}

function referenceType(value: string): InjectionReferenceType {
  if ((injectionReferenceTypes as readonly string[]).includes(value)) return value as InjectionReferenceType
  throw new TypeError('Injection reference type is invalid')
}

function referenceStage(value: string): InjectionReferenceStage {
  if ((injectionReferenceStages as readonly string[]).includes(value)) return value as InjectionReferenceStage
  throw new TypeError('Injection reference stage is invalid')
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
