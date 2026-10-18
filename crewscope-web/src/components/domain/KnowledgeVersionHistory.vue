<script setup lang="ts">
import { knowledgeIndexStatusHints, knowledgeIndexStatusLabels } from '../../domains/knowledge/labels'
import type { KnowledgeVersion } from '../../domains/knowledge/types'
import type { KnowledgeResource, KnowledgeVersionPageResource } from '../../domains/knowledge/store'
import type { Etagged } from '../../domains/settings/types'
import StatusBadge from '../base/StatusBadge.vue'
import BaseVersionHistory from '../shared/BaseVersionHistory.vue'

/** Knowledge twin of the version history: the shared shell plus index-status vocabulary. */
defineProps<{
  history: KnowledgeVersionPageResource
  versionResource: KnowledgeResource<Etagged<KnowledgeVersion>> | null | undefined
  effective: KnowledgeResource<Etagged<KnowledgeVersion>> | null | undefined
  effectiveRevision: number | null
  selectedRevision: number | null
  online: boolean
}>()

const emit = defineEmits<{
  select: [revision: number]
  loadMore: []
  retry: []
}>()

function indexTone(indexStatus: string): 'neutral' | 'success' | 'warning' {
  if (indexStatus === 'INDEXED') return 'success'
  if (indexStatus === 'FAILED') return 'warning'
  return 'neutral'
}
</script>

<template>
  <BaseVersionHistory
    :history="history"
    :version-resource="versionResource"
    :effective="effective"
    :effective-revision="effectiveRevision"
    :selected-revision="selectedRevision"
    :online="online"
    subject="条目"
    no-effective-note="当前没有生效版本：条目草稿未发布或已废弃。"
    @select="revision => emit('select', revision)"
    @load-more="emit('loadMore')"
    @retry="emit('retry')"
  >
    <template #version-badge="{ version }">
      <StatusBadge :tone="indexTone(version.indexStatus)">{{ knowledgeIndexStatusLabels[version.indexStatus] }}</StatusBadge>
    </template>
    <template #version-hint="{ version }">
      {{ knowledgeIndexStatusHints[version.indexStatus] }}
    </template>
  </BaseVersionHistory>
</template>
