<script setup lang="ts">
import { ChevronRight, Clock3 } from '@lucide/vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { AgentConfigurationHistoryItem } from '../../domains/agent/types'
import type { AgentPageResource } from '../../domains/agent/store'

defineProps<{
  historyResource: AgentPageResource<AgentConfigurationHistoryItem> | undefined
  history: AgentConfigurationHistoryItem[]
  selectedRevision: number | null
  currentRevision: number | null
}>()

const emit = defineEmits<{
  selectRevision: [revision: number]
  retry: []
  loadMore: []
}>()

// The container owns pagination and retries; this view only exposes explicit user intents.
</script>

<template>
  <aside class="revision-rail" aria-label="Configuration 历史">
    <h3>配置版本</h3>
    <StatePanel v-if="historyResource?.phase === 'loading' || historyResource?.phase === 'idle'" state="loading" compact />
    <StatePanel v-else-if="historyResource?.phase === 'error'" state="error" compact :description="historyResource.errorMessage ?? undefined" @retry="emit('retry')" />
    <p v-else-if="history.length === 0" class="revision-empty">尚未创建 Configuration。首次保存将生成 Revision 1。</p>
    <button
      v-for="item in history"
      :key="item.revision"
      type="button"
      :class="{ active: (selectedRevision ?? currentRevision) === item.revision }"
      @click="emit('selectRevision', item.revision)"
    >
      <Clock3 :size="14" /><span><strong>Revision {{ item.revision }}</strong><small>{{ item.createdAt.slice(0, 10) }} · {{ item.templateKey }}@{{ item.templateVersion }}</small></span><ChevronRight :size="14" />
    </button>
    <button
      v-if="historyResource?.nextOffset !== null && historyResource?.phase === 'ready'"
      type="button"
      :disabled="historyResource.loadingMore"
      @click="emit('loadMore')"
    >
      <Clock3 :size="14" /><span><strong>{{ historyResource.loadingMore ? '正在加载…' : '加载更早版本' }}</strong><small>继续读取不可变历史</small></span><ChevronRight :size="14" />
    </button>
  </aside>
</template>

<style scoped>
.revision-rail { display: grid; align-content: start; gap: var(--cs-space-4); padding: var(--cs-space-16); border-right: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }
.revision-rail h3 { margin: 0 0 var(--cs-space-8); color: var(--cs-text); font-size: var(--cs-text-sm); }
.revision-rail > button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); min-height: 52px; padding: var(--cs-space-8); border: 1px solid transparent; border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text-secondary); text-align: left; cursor: pointer; }
.revision-rail > button:hover, .revision-rail > button.active { border-color: var(--cs-border-strong); background: var(--cs-surface); color: var(--cs-text-brand); }
.revision-rail strong, .revision-rail small { display: block; }.revision-rail strong { font-size: var(--cs-text-xs); }.revision-rail small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.revision-empty { margin: var(--cs-space-8) 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
@media (max-width: 900px) { .revision-rail { display: flex; overflow-x: auto; align-items: center; gap: var(--cs-space-4); border-right: 0; border-bottom: 1px solid var(--cs-border); }.revision-rail h3 { flex: 0 0 auto; padding: 0 var(--cs-space-8); }.revision-rail > button { min-width: 150px; }.revision-empty { margin: 0; } }
</style>
