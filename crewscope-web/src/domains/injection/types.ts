/** M10-I02c: the sealed injection evidence of one execution, read in three zones. */

/** Injection evidence is team-scoped; structurally `SettingsScope`, aliased locally (D1). */
export interface InjectionScope {
  organizationId: string
  teamId: string
}

/** `ManifestSourceType`: what layer a reference came from. `SKILL_INSTRUCTION` is always INJECTED. */
export const injectionReferenceTypes = ['SKILL_INSTRUCTION', 'KNOWLEDGE_ENTRY', 'REPOSITORY_CHUNK', 'MEMORY_PREFERENCE'] as const
export type InjectionReferenceType = typeof injectionReferenceTypes[number]

/** `ManifestSourceStage`: sealed into the Prompt, or a candidate the budget trimmed away. */
export const injectionReferenceStages = ['CANDIDATE', 'INJECTED'] as const
export type InjectionReferenceStage = typeof injectionReferenceStages[number]

/**
 * The degradation codes a manifest can carry (contract §2). Kept an open vocabulary on the wire
 * — the gateway passes them through and the labels read unknown codes verbatim.
 */
export const injectionDegradations = ['RETRIEVAL_DISABLED', 'NO_MATCHING_GENERATION', 'EMBEDDING_PROVIDER_UNAVAILABLE'] as const
export type InjectionDegradation = typeof injectionDegradations[number]

/** The Prompt budget snapshot the manifest was assembled under. */
export interface InjectionBudget {
  totalTokens: number
  knowledgeTokens: number
  chunkTokens: number
  memoryTokens: number
}

/** One budget trim the planner applied — layer, how many rows went, and why. */
export interface InjectionTrim {
  layer: string
  trimmedCount: number
  reason: string
}

/**
 * The stage-free key quadruple that addresses a source in feedback and claims. Responses carry
 * `version` as a number; the feedback request body serialises it as a string (contract §3).
 */
export interface InjectionReferenceKey {
  type: InjectionReferenceType
  sourceId: string
  version: number
  contentHash: string
}

/** A key quadruple as it sits in one attempt's manifest, with its stage and own-view flag. */
export interface InjectionReference extends InjectionReferenceKey {
  stage: InjectionReferenceStage
  /** Own-view only: the authenticated member's "not applicable" judgement; CANDIDATE is always false. */
  notApplicable: boolean
}

/** One sealed assembly. `claimed` distinguishes no receipt (null) from a zero-claim receipt ([]). */
export interface InjectionAttempt {
  manifestId: string
  attempt: number
  createdAt: string
  budget: InjectionBudget
  degradations: string[]
  trims: InjectionTrim[]
  references: InjectionReference[]
  claimed: InjectionReferenceKey[] | null
}

/** The GET view of one execution's evidence: attempts ascending by attempt number. */
export interface InjectionReferences {
  executionId: string
  taskId: string
  attempts: InjectionAttempt[]
}

/** Feedback request input; the gateway serialises `version` as a string on the wire. */
export interface InjectionFeedbackInput {
  type: InjectionReferenceType
  sourceId: string
  version: number
  contentHash: string
}
