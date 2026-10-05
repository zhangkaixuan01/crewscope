import type {
  KnowledgeCategory,
  KnowledgeEntryStatus,
  KnowledgeIndexJobSource,
  KnowledgeIndexJobStatus,
  KnowledgeIndexStatus,
} from './types'

/**
 * User-facing text for the knowledge entry enums. `indexStatus` in particular must never read
 * as "not saved" — PENDING means the background index has not caught up yet, and this hint is
 * the single authoritative wording (contract §5: clients must not read PENDING as unsaved).
 */
export const knowledgeCategoryLabels: Record<KnowledgeCategory, string> = {
  CONVENTION: '团队约定',
  RUNBOOK: '操作手册',
  DECISION: '决策记录',
  GUIDE: '指南',
  OTHER: '其他',
}

export const knowledgeEntryStatusLabels: Record<KnowledgeEntryStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  RETIRED: '已废弃',
  DELETED: '已删除',
}

export const knowledgeIndexStatusLabels: Record<KnowledgeIndexStatus, string> = {
  PENDING: '待索引',
  INDEXED: '已索引',
  FAILED: '索引失败',
}

/**
 * Accompanying explanation for the index badge. Components reference this constant instead of
 * re-wording it: "已保存，等待索引" is the whole point — PENDING never means the draft was lost.
 */
export const knowledgeIndexStatusHints: Partial<Record<KnowledgeIndexStatus, string>> = {
  PENDING: '索引状态与保存状态无关：内容已保存，等待后台建立索引',
  FAILED: '最近一次索引作业失败，内容仍已保存；可在索引作业面重试',
}

/** M10-I01c job vocabulary (repository-index contract §1): two sources, seven states. */
export const knowledgeIndexJobSourceLabels: Record<KnowledgeIndexJobSource, string> = {
  KNOWLEDGE_ENTRY: '知识条目',
  REPOSITORY: '仓库',
}

export const knowledgeIndexJobStatusLabels: Record<KnowledgeIndexJobStatus, string> = {
  QUEUED: '排队中',
  CHUNKING: '分片中',
  EMBEDDING: '嵌入中',
  ACTIVATING: '激活中',
  READY: '已就绪',
  FAILED: '失败',
  CANCELLED: '已取消',
}

/**
 * The nine closed failure constants (contract §6) plus a fallback: the column also carries
 * sanitized model-connection health codes, an open vocabulary the UI must not mangle — unknown
 * codes render verbatim through `knowledgeFailureCodeLabel()`.
 */
export const knowledgeFailureCodeLabels: Record<string, string> = {
  CHUNK_TOO_LARGE: '单个分片超出嵌入输入上限',
  MODEL_DRIFT: '嵌入模型与索引键冻结版本不一致',
  CHUNK_LIMIT_EXCEEDED: '分片数量超出单代预算',
  REPOSITORY_TOO_LARGE: '仓库内容超出读取上限',
  REPOSITORY_UNAVAILABLE: '受管仓库不可用或不包含该提交',
  REPOSITORY_READ_FAILED: '仓库内容读取失败',
  GENERATION_CONFLICT: '代激活竞争失败，可重新入队',
  INTERNAL: '内部错误',
  CANCELLED: '已取消',
}

export function knowledgeFailureCodeLabel(code: string): string {
  return knowledgeFailureCodeLabels[code] ?? code
}
