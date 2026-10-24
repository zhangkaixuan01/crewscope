<script setup lang="ts">
import { teamMemberUnresolvedDescription } from '../../domains/shared/stateCopy'
import { ChevronRight, ListChecks } from '@lucide/vue'
import { skillStatusLabels } from '../../domains/skill/labels'
import { skillStatuses, type SkillStatus, type SkillSummary } from '../../domains/skill/types'
import type { SkillResourcePhase } from '../../domains/skill/store'
import BaseButton from '../base/BaseButton.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import BaseListShell from '../shared/BaseListShell.vue'

/** Skill twin of the list shell: the provenance note, the status filter and the row facts. */
defineProps<{
  phase: SkillResourcePhase
  items: SkillSummary[]
  nextAfter: string | null
  loadingMore: boolean
  errorMessage: string | null
  errorStatus: number | null
  online: boolean
  statusFilter: SkillStatus | 'ALL'
  canManage: boolean
  selectedSkillId: string | null
}>()

const emit = defineEmits<{
  select: [skillId: string]
  changeStatus: [value: SkillStatus | 'ALL']
  loadMore: []
  create: []
  distill: []
  retry: []
}>()

const statusOptions: BaseSelectOption[] = [
  { label: '全部状态', value: 'ALL' },
  ...skillStatuses.map(status => ({ label: skillStatusLabels[status], value: status })),
]

function statusTone(status: SkillStatus): 'neutral' | 'info' | 'danger' {
  if (status === 'PUBLISHED') return 'info'
  if (status === 'DISABLED') return 'danger'
  return 'neutral'
}
</script>

<template>
  <BaseListShell
    :phase="phase"
    :items="items"
    :next-after="nextAfter"
    :loading-more="loadingMore"
    :error-message="errorMessage"
    :error-status="errorStatus"
    :online="online"
    label="Skill 目录列表"
    loading-title="正在读取 Skill 目录"
    forbidden-title="无权读取 Skill 目录"
    :forbidden-description="teamMemberUnresolvedDescription"
    offline-empty-title="离线时没有可用 Skill"
    offline-empty-description="联网后会回读当前 Team 的 Skill 资产。"
    empty-title="暂无 Skill"
    empty-description="团队创建与从执行提炼的 Skill 会沉淀在这里，供任务执行配置引用。"
    compact-offline-title="正在展示最近读取的 Skill"
    compact-offline-description="离线期间管理命令保持关闭。"
    load-more-label="读取更多 Skill"
    @retry="emit('retry')"
    @load-more="emit('loadMore')"
  >
    <template #filters>
      <header class="skill-list__filters">
        <BaseSelect
          :model-value="statusFilter"
          :options="statusOptions"
          aria-label="按状态筛选"
          @update:model-value="emit('changeStatus', $event as SkillStatus | 'ALL')"
        />
      </header>
    </template>

    <template #lead>
      <!-- Acceptance "sources stay explicit": this catalog lists team-created and distilled
           skills only; built-in skills ride the templates and never appear as fake rows here. -->
      <p class="skill-list__provenance" role="note">
        本目录只收录团队创建与提炼的 Skill；内置 Coding Skill（如 java-spring-v1）随模板提供，在 Agent 配置的「批准 Skill」中可见。
      </p>
    </template>

    <template #empty-action>
      <BaseButton v-if="canManage" variant="secondary" size="small" @click="emit('create')">创建 Skill</BaseButton>
      <!-- Distillation is the execution creator's own right (contract §8), not a skill:manage
           affordance — the entry stays for every member and a 403 explains the boundary. -->
      <BaseButton variant="secondary" size="small" @click="emit('distill')">从执行蒸馏</BaseButton>
      <BaseButton v-if="statusFilter !== 'ALL'" variant="ghost" size="small" @click="emit('changeStatus', 'ALL')">清除筛选</BaseButton>
    </template>

    <template #items>
      <li v-for="skill in items" :key="skill.id" :class="{ selected: selectedSkillId === skill.id }">
        <button type="button" class="skill-list__row" :aria-current="selectedSkillId === skill.id ? 'true' : undefined" @click="emit('select', skill.id)">
          <ListChecks :size="16" aria-hidden="true" />
          <span class="skill-list__main">
            <!-- M13b-F10（L04）：主文本用草稿名称；key 降为副行 mono 小字（发布态回退 key）。 -->
            <strong class="skill-list__title">{{ skill.draft?.name?.trim() || skill.skillKey }}</strong>
            <small v-if="skill.draft?.name?.trim()" class="skill-list__key">{{ skill.skillKey }}</small>
          </span>
          <span class="skill-list__badges">
            <StatusBadge :tone="statusTone(skill.status)">{{ skillStatusLabels[skill.status] }}</StatusBadge>
          </span>
          <span class="skill-list__meta">
            <span v-if="skill.effectiveRevision != null">生效 r{{ skill.effectiveRevision }}</span>
            <span>最新 r{{ skill.latestRevision }}</span>
            <RelativeTime :value="skill.updatedAt" />
          </span>
          <ChevronRight :size="14" aria-hidden="true" />
        </button>
      </li>
    </template>
  </BaseListShell>
</template>

<style scoped>
.skill-list__filters { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--cs-space-8); }
.skill-list__provenance { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
li.selected > .skill-list__row { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }
.skill-list__row { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8) var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }
.skill-list__row:hover { border-color: var(--cs-border-strong); }
.skill-list__row:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.skill-list__main { display: grid; min-width: 0; gap: var(--cs-space-4); }
.skill-list__title { min-width: 0; overflow-wrap: anywhere; font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.skill-list__key { min-width: 0; overflow-wrap: anywhere; font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); color: var(--cs-text-muted); }
.skill-list__badges { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); }
.skill-list__meta { display: flex; grid-column: 2; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
