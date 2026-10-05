import type { InjectionReferenceStage, InjectionReferenceType } from './types'

/**
 * User-facing text for the I02c evidence enums. The three zones must read as facts, not
 * judgements: a CANDIDATE row was trimmed by the budget before the Prompt left — the wording
 * must never blame the source for not being injected.
 */
export const injectionReferenceTypeLabels: Record<InjectionReferenceType, string> = {
  SKILL_INSTRUCTION: 'Skill 指令',
  KNOWLEDGE_ENTRY: '知识条目',
  REPOSITORY_CHUNK: '仓库片段',
  MEMORY_PREFERENCE: '记忆偏好',
}

export const injectionReferenceStageLabels: Record<InjectionReferenceStage, string> = {
  INJECTED: '已注入',
  CANDIDATE: '候选（预算裁剪）',
}

/**
 * The three closed degradation constants plus a fallback: the wire shape is an open vocabulary,
 * so an unknown code renders verbatim instead of being swallowed or mangled.
 */
export const injectionDegradationLabels: Record<string, string> = {
  RETRIEVAL_DISABLED: '检索开关未开启',
  NO_MATCHING_GENERATION: '目标提交没有可用的索引代',
  EMBEDDING_PROVIDER_UNAVAILABLE: 'Embedding Provider 暂不可用',
}

export function injectionDegradationLabel(code: string): string {
  return injectionDegradationLabels[code] ?? code
}

/** Trim layers reuse the reference-type vocabulary; open on the wire, verbatim fallback. */
const injectionTrimLayerLabels: Record<string, string> = {
  SKILL_INSTRUCTION: 'Skill 指令',
  KNOWLEDGE_ENTRY: '知识条目',
  REPOSITORY_CHUNK: '仓库片段',
  MEMORY_PREFERENCE: '记忆偏好',
}

export function injectionTrimLayerLabel(layer: string): string {
  return injectionTrimLayerLabels[layer] ?? layer
}

/** The two planner reasons (I02b contract §2); future reasons fall back verbatim. */
const injectionTrimReasonLabels: Record<string, string> = {
  'layer budget exceeded': '超出该层预算',
  'total budget exceeded': '超出总预算',
}

export function injectionTrimReasonLabel(reason: string): string {
  return injectionTrimReasonLabels[reason] ?? reason
}
