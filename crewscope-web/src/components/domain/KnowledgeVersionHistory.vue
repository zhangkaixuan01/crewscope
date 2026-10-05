<script setup lang="ts">
import { computed } from 'vue'
import {
  knowledgeIndexStatusHints,
  knowledgeIndexStatusLabels,
} from '../../domains/knowledge/labels'
import type { KnowledgeVersion } from '../../domains/knowledge/types'
import type { Etagged } from '../../domains/settings/types'
import type { KnowledgeResource, KnowledgeVersionPageResource } from '../../domains/knowledge/store'
import BaseButton from '../base/BaseButton.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
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

const versions = computed(() => props.history.value ?? [])
const initialLoading = computed(() => (props.history.phase === 'idle' || props.history.phase === 'loading') && versions.value.length === 0)
const failed = computed(() => props.history.phase === 'error')
const selected = computed(() => props.versionResource?.value?.value ?? null)
const selectedPending = computed(() => props.selectedRevision !== null && (props.versionResource == null || props.versionResource.phase === 'loading'))
const effectiveVersion = computed(() => props.effective?.value?.value ?? null)

function shortHash(version: KnowledgeVersion): string {
  return version.contentHash.slice(0, 8)
}
</script>

<template>
  <div class="version-history">
    <StatePanel v-if="initialLoading" state="loading" title="正在读取版本历史" description="正在读取这个条目的已发布版本。" />
    <StatePanel v-else-if="failed" state="error" :description="history.errorMessage ?? undefined" @retry="emit('retry')" />
    <StatePanel v-else-if="versions.length === 0" state="empty" title="尚未发布生效版本" description="保存草稿并发布后，每次发布都会在这里留下一个不可变版本。" />

    <template v-else>
      <ol class="version-history__list">
        <li v-for="version in versions" :key="version.revision" :class="{ selected: selectedRevision === version.revision }">
          <button
            type="button"
            class="version-history__row"
            :aria-current="selectedRevision === version.revision ? 'true' : undefined"
            @click="emit('select', version.revision)"
          >
            <span class="version-history__revision">r{{ version.revision }}</span>
            <span class="version-history__title">{{ version.title }}</span>
            <span v-if="effectiveRevision === version.revision" class="version-history__effective">
              <StatusBadge tone="success">生效</StatusBadge>
            </span>
            <span class="version-history__meta">
              <span class="version-history__hash" :title="version.contentHash">{{ shortHash(version) }}</span>
              <RelativeTime :value="version.createdAt" />
            </span>
          </button>
        </li>
      </ol>

      <footer v-if="history.nextAfter !== null" class="version-history__more">
        <BaseButton variant="secondary" size="small" :loading="history.loadingMore" :disabled="!online" @click="emit('loadMore')">读取更早版本</BaseButton>
      </footer>

      <StatePanel v-if="selectedPending" compact state="loading" title="正在读取版本内容" />
      <StatePanel v-else-if="versionResource?.phase === 'error'" compact state="error" :description="versionResource.errorMessage ?? undefined" />
      <section v-else-if="selected" class="version-history__content" :aria-label="`版本 r${selected.revision} 内容`">
        <header class="version-history__content-head">
          <h4>{{ selected.title }}</h4>
          <span class="version-history__meta">
            <StatusBadge :tone="selected.indexStatus === 'INDEXED' ? 'success' : selected.indexStatus === 'FAILED' ? 'warning' : 'neutral'">{{ knowledgeIndexStatusLabels[selected.indexStatus] }}</StatusBadge>
            <span class="version-history__hash" :title="selected.contentHash">{{ shortHash(selected) }}</span>
            <RelativeTime :value="selected.createdAt" />
          </span>
        </header>
        <p v-if="knowledgeIndexStatusHints[selected.indexStatus]" class="version-history__hint">{{ knowledgeIndexStatusHints[selected.indexStatus] }}</p>
        <pre class="version-history__body">{{ selected.content }}</pre>
      </section>

      <section v-if="effectiveVersion" class="version-history__effective-card" aria-label="生效版本">
        <header>
          <h4>生效版本 r{{ effectiveVersion.revision }}</h4>
          <RelativeTime :value="effectiveVersion.createdAt" />
        </header>
        <pre class="version-history__body">{{ effectiveVersion.content }}</pre>
      </section>
      <p v-else class="version-history__no-effective" role="note">当前没有生效版本：条目草稿未发布或已废弃。</p>
    </template>
  </div>
</template>

<style scoped>
.version-history { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.version-history__list { display: flex; margin: 0; padding: 0; list-style: none; flex-direction: column; gap: var(--cs-space-4); }
.version-history__list li.selected > .version-history__row { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }
.version-history__row { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }
.version-history__row:hover { border-color: var(--cs-border-strong); }
.version-history__row:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.version-history__revision { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.version-history__title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--cs-text-sm); }
.version-history__effective { display: flex; }
.version-history__meta { display: flex; grid-column: 1 / -1; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.version-history__hash { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.version-history__more { display: grid; justify-items: center; }
.version-history__content, .version-history__effective-card { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.version-history__effective-card { border-color: var(--cs-border-accent); }
.version-history__content-head, .version-history__effective-card > header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--cs-space-8); }
.version-history__content-head h4, .version-history__effective-card h4 { margin: 0; font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.version-history__hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.version-history__body { max-height: 24rem; margin: 0; overflow: auto; padding: var(--cs-space-8); border-radius: var(--cs-radius-sm, 4px); background: var(--cs-surface-inset, var(--cs-surface)); color: var(--cs-text); font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); white-space: pre-wrap; overflow-wrap: anywhere; }
.version-history__no-effective { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
