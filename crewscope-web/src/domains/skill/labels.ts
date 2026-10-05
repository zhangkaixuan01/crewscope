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
