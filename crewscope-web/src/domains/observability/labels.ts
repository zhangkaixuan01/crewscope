import type {
  ObservabilityCostSource,
  ObservabilityCostStatus,
  ObservabilityUsageRole,
} from './types'

/**
 * User-facing text for the observability read surface. The source lanes explain where the money
 * went (embedding the knowledge base, distilling entries, or executing work); the role labels
 * stay one level more detailed because a detail row is exactly where "which model, doing what"
 * becomes an actionable question.
 */
export const observabilityCostSourceLabels: Record<ObservabilityCostSource, string> = {
  EXECUTION: '执行与对话',
  EMBEDDING: '知识嵌入',
  DISTILLATION: '知识提炼',
}

export const observabilityUsageRoleLabels: Record<ObservabilityUsageRole, string> = {
  CHAT_PRIMARY: '对话主模型',
  CHAT_FALLBACK: '对话备用模型',
  COMPACTION: '上下文压缩',
  EMBEDDING: '知识嵌入',
  DISTILLATION: '知识提炼',
}

export const observabilityCostStatusLabels: Record<ObservabilityCostStatus, string> = {
  PRICED: '已计价',
  UNPRICED: '未计价',
}

/**
 * The rollup's 'XXX' sentinel currency: tokens were counted but no catalog price could be
 * resolved at projection time — the contract forbids reading that as a zero cost.
 */
export const OBSERVABILITY_UNPRICED_CURRENCY = 'XXX'
export const OBSERVABILITY_UNPRICED_HINT = '存在未能解析价格的用量：按 token 计量，不计为 0 成本'
