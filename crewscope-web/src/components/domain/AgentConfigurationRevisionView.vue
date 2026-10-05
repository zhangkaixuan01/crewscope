<script setup lang="ts">
import { computed } from 'vue'
import RevisionDiffView from '../settings/RevisionDiffView.vue'
import BaseButton from '../base/BaseButton.vue'
import type { AgentConfigurationHistoryItem, AgentExecutionScope } from '../../domains/agent/types'

const props = defineProps<{
  selectedRevision: number | null
  currentRevision: number | null
  selectedHistory: AgentConfigurationHistoryItem | null
  history: AgentConfigurationHistoryItem[]
  selectedHistoryConfiguration: Record<string, unknown> | null
  selectedPreviousConfiguration: Record<string, unknown> | null
  compareRevision: number | null
  copiedHash: string | null
}>()

const emit = defineEmits<{
  updateCompareRevision: [revision: number | null]
  copyHash: [hash: string, key: string]
  returnCurrent: [revision: number]
}>()

// Revision payloads are immutable. Copy, compare, and return actions are delegated to the container.

const viewingCurrent = computed(() => props.selectedRevision === null || props.selectedRevision === props.currentRevision)
const compareRevisionLabel = computed(() => props.compareRevision ? `Revision ${props.compareRevision}` : '初始版本')
const selectedRevisionLabel = computed(() => props.selectedHistory ? `Revision ${props.selectedHistory.revision}` : '当前版本')
const compareCandidates = computed(() => props.history.filter(item => item.revision !== props.selectedHistory?.revision))

/** The approved-key snapshot is the part of the payload a member audits by key, not by hash. */
const selectedSkillKeys = computed(() => skillKeysOf(props.selectedHistoryConfiguration))
const previousSkillKeys = computed(() => skillKeysOf(props.selectedPreviousConfiguration))
const addedSkillKeys = computed(() => selectedSkillKeys.value.filter(key => !previousSkillKeys.value.includes(key)))
const removedSkillKeys = computed(() => previousSkillKeys.value.filter(key => !selectedSkillKeys.value.includes(key)))

function skillKeysOf(configuration: Record<string, unknown> | null): string[] {
  const keys = configuration?.approvedSkillKeys
  return Array.isArray(keys) && keys.every(key => typeof key === 'string') ? keys : []
}

function historyBinding(item: AgentConfigurationHistoryItem, scope: AgentExecutionScope): string {
  const binding = scope === 'PERSONAL' ? item.personalBinding : item.teamBinding
  if (!binding) return '不适用'
  if (binding.kind === 'INHERIT_TEAM_DEFAULT') return '继承 Team 默认'
  if (binding.kind === 'ORCHESTRATION_ONLY') return '仅编排'
  return binding.primary?.modelId ?? '未解析'
}
</script>

<template>
  <section v-if="!viewingCurrent && selectedHistory" class="historical-view" aria-label="历史 Configuration">
    <div><p class="eyebrow">Immutable history</p><h3>Revision {{ selectedHistory.revision }}</h3><p>历史版本不可编辑。它继续服务于已经固定该 Revision 的 Conversation、Task 和 Retry。</p></div>
    <dl><div><dt>PERSONAL</dt><dd>{{ historyBinding(selectedHistory, 'PERSONAL') }}</dd></div><div><dt>TEAM</dt><dd>{{ historyBinding(selectedHistory, 'TEAM') }}</dd></div><div><dt>Configuration Hash</dt><dd class="mono hash-value">{{ selectedHistory.configurationHash }} <button type="button" class="copy-button" :aria-label="`复制 Revision ${selectedHistory.revision} 配置 Hash`" @click="emit('copyHash', selectedHistory.configurationHash, `revision-${selectedHistory.revision}`)">{{ copiedHash === `revision-${selectedHistory.revision}` ? '已复制' : '复制' }}</button></dd></div></dl>
    <details v-if="selectedHistoryConfiguration" class="skill-snapshot">
      <summary>批准 Skill 快照 <small>{{ selectedSkillKeys.length }} 个</small></summary>
      <ul v-if="selectedSkillKeys.length > 0">
        <li v-for="key in selectedSkillKeys" :key="key" class="mono">{{ key }}<span v-if="addedSkillKeys.includes(key)" class="skill-snapshot__badge">新增</span></li>
      </ul>
      <p v-else>该 Revision 未批准任何 Skill。</p>
      <p v-if="removedSkillKeys.length > 0" class="skill-snapshot__removed">相比{{ compareRevisionLabel }}移除：{{ removedSkillKeys.join('、') }}</p>
    </details>
    <RevisionDiffView
      v-if="selectedHistoryConfiguration"
      :before="selectedPreviousConfiguration"
      :after="selectedHistoryConfiguration"
      :before-label="compareRevisionLabel"
      :after-label="selectedRevisionLabel"
    />
    <label v-if="history.length > 1" class="compare-picker"><span>对比另一个 Revision</span><select :value="compareRevision" @change="emit('updateCompareRevision', ($event.target as HTMLSelectElement).value === '' ? null : Number(($event.target as HTMLSelectElement).value))"><option value="">初始版本</option><option v-for="item in compareCandidates" :key="item.revision" :value="item.revision">Revision {{ item.revision }}</option></select></label>
    <BaseButton variant="secondary" size="small" @click="emit('returnCurrent', currentRevision ?? selectedHistory.revision)">返回当前版本</BaseButton>
  </section>
</template>

<style scoped>
.historical-view { display: grid; gap: var(--cs-space-20); padding: var(--cs-space-24); }.historical-view h3 { margin: var(--cs-space-4) 0; }.historical-view p { color: var(--cs-text-secondary); line-height: var(--cs-leading-normal); }.historical-view dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--cs-space-12); margin: 0; }.historical-view dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.historical-view dd { margin: var(--cs-space-4) 0 0; color: var(--cs-text); font-size: var(--cs-text-sm); }.skill-snapshot { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); }.skill-snapshot > summary { display: flex; align-items: baseline; gap: var(--cs-space-8); padding: var(--cs-space-12); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); cursor: pointer; }.skill-snapshot > summary small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); }.skill-snapshot[open] > summary { border-bottom: 1px solid var(--cs-border); }.skill-snapshot ul { display: grid; gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-12); list-style: none; }.skill-snapshot li { color: var(--cs-text); font-size: var(--cs-text-sm); overflow-wrap: anywhere; }.skill-snapshot p { margin: 0; padding: var(--cs-space-12); font-size: var(--cs-text-xs); }.skill-snapshot__badge { margin-left: var(--cs-space-8); padding: 0 var(--cs-space-4); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-sm); color: var(--cs-text-brand); font-size: var(--cs-text-xs); }.skill-snapshot__removed { color: var(--cs-text-muted); }.hash-value { display: flex; align-items: center; gap: var(--cs-space-8); flex-wrap: wrap; }.copy-button { min-height: 28px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-brand); cursor: pointer; font-size: var(--cs-text-xs); }.compare-picker { display: grid; max-width: 320px; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.compare-picker select { min-height: 36px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); font-size: var(--cs-text-base); }
@media (max-width: 900px) { .historical-view dl { grid-template-columns: 1fr; } }
</style>
