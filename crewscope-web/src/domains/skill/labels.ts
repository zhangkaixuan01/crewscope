import type { SkillStatus } from './types'

/**
 * User-facing text for the skill enums (M10-A03 §5). Skills have no index status — they never
 * enter the knowledge index — so this vocabulary is deliberately just the head status.
 */
export const skillStatusLabels: Record<SkillStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已禁用',
}

/**
 * M13c（S3）：skill 域内两处逐字同句的复句单源——frontmatter 契约 hint（SkillCreateDialog ↔
 * SkillDraftEditor）与禁用后恢复路径句（SkillsPage ↔ SkillDraftEditor）。改句=同时改两屏语义。
 */
export const skillFrontmatterContractHint
  = 'frontmatter 恰好含 name（必须等于 skillKey）与 description（≤200 字）两字段；正文即运行时注入的指令来源。'

export const skillRepublishRestores = '修改草稿并再次发布即可产出新修订、恢复生效。'
