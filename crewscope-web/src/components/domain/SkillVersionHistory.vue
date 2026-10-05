<script setup lang="ts">
import { computed } from 'vue'
import type { SkillVersion } from '../../domains/skill/types'
import type { Etagged } from '../../domains/settings/types'
import type { SkillResource, SkillVersionPageResource } from '../../domains/skill/store'
import BaseButton from '../base/BaseButton.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  history: SkillVersionPageResource
  versionResource: SkillResource<Etagged<SkillVersion>> | null | undefined
  effective: SkillResource<Etagged<SkillVersion>> | null | undefined
  effectiveRevision: number | null
  selectedRevision: number | null
  online: boolean
  canManage: boolean
  canRollback: boolean
  rollingBack: boolean
}>()

const emit = defineEmits<{
  select: [revision: number]
  loadMore: []
  retry: []
  rollback: [revision: number]
}>()

const versions = computed(() => props.history.value ?? [])
const initialLoading = computed(() => (props.history.phase === 'idle' || props.history.phase === 'loading') && versions.value.length === 0)
const failed = computed(() => props.history.phase === 'error')
const selected = computed(() => props.versionResource?.value?.value ?? null)
const selectedPending = computed(() => props.selectedRevision !== null && (props.versionResource == null || props.versionResource.phase === 'loading'))
const effectiveVersion = computed(() => props.effective?.value?.value ?? null)

function shortHash(version: SkillVersion): string {
  return version.contentHash.slice(0, 8)
}

/** Rollback appends a new revision (contract §3): any non-effective historical row is a legal target. */
function rollbackAllowed(revision: number): boolean {
  return props.canManage
    && props.canRollback
    && props.online
    && !props.rollingBack
    && props.effectiveRevision !== null
    && revision !== props.effectiveRevision
}
</script>

<template>
  <div class="version-history">
    <StatePanel v-if="initialLoading" state="loading" title="正在读取版本历史" description="正在读取这个 Skill 的已发布版本。" />
    <StatePanel v-else-if="failed" state="error" :description="history.errorMessage ?? undefined" @retry="emit('retry')" />
    <StatePanel v-else-if="versions.length === 0" state="empty" title="尚未发布生效版本" description="保存草稿并发布后，每次发布都会在这里留下一个不可变版本。" />

    <template v-else>
      <ol class="version-history__list">
        <li v-for="version in versions" :key="version.revision" :class="{ selected: selectedRevision === version.revision }">
          <div class="version-history__row">
            <button
              type="button"
              class="version-history__row-main"
              :aria-current="selectedRevision === version.revision ? 'true' : undefined"
              @click="emit('select', version.revision)"
            >
              <span class="version-history__revision">r{{ version.revision }}</span>
              <span v-if="effectiveRevision === version.revision" class="version-history__effective">
                <StatusBadge tone="success">生效</StatusBadge>
              </span>
              <span class="version-history__meta">
                <span class="version-history__hash" :title="version.contentHash">{{ shortHash(version) }}</span>
                <RelativeTime :value="version.createdAt" />
              </span>
            </button>
            <BaseButton
              v-if="rollbackAllowed(version.revision)"
              variant="secondary"
              size="small"
              :loading="rollingBack"
              @click="emit('rollback', version.revision)"
            >回滚到此版本</BaseButton>
          </div>
        </li>
      </ol>

      <footer v-if="history.nextAfter !== null" class="version-history__more">
        <BaseButton variant="secondary" size="small" :loading="history.loadingMore" :disabled="!online" @click="emit('loadMore')">读取更早版本</BaseButton>
      </footer>

      <StatePanel v-if="selectedPending" compact state="loading" title="正在读取版本内容" />
      <StatePanel v-else-if="versionResource?.phase === 'error'" compact state="error" :description="versionResource.errorMessage ?? undefined" />
      <section v-else-if="selected" class="version-history__content" :aria-label="`版本 r${selected.revision} 内容`">
        <header class="version-history__content-head">
          <h4>修订 r{{ selected.revision }}</h4>
          <span class="version-history__meta">
            <span class="version-history__hash" :title="selected.contentHash">{{ shortHash(selected) }}</span>
            <RelativeTime :value="selected.createdAt" />
          </span>
        </header>
        <pre class="version-history__body">{{ selected.content }}</pre>
      </section>

      <section v-if="effectiveVersion" class="version-history__effective-card" aria-label="生效版本">
        <header>
          <h4>生效版本 r{{ effectiveVersion.revision }}</h4>
          <RelativeTime :value="effectiveVersion.createdAt" />
        </header>
        <pre class="version-history__body">{{ effectiveVersion.content }}</pre>
      </section>
      <p v-else class="version-history__no-effective" role="note">当前没有生效版本：Skill 草稿未发布或已被禁用。</p>
    </template>
  </div>
</template>

<style scoped>
.version-history { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.version-history__list { display: flex; margin: 0; padding: 0; list-style: none; flex-direction: column; gap: var(--cs-space-4); }
.version-history__list li.selected > .version-history__row { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }
.version-history__row { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.version-history__row-main { display: flex; min-width: 0; flex: 1; align-items: center; gap: var(--cs-space-8); border: none; background: none; color: var(--cs-text); text-align: left; cursor: pointer; }
.version-history__row-main:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.version-history__revision { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.version-history__effective { display: flex; }
.version-history__meta { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.version-history__hash { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.version-history__more { display: grid; justify-items: center; }
.version-history__content, .version-history__effective-card { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.version-history__effective-card { border-color: var(--cs-border-accent); }
.version-history__content-head, .version-history__effective-card > header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--cs-space-8); }
.version-history__content-head h4, .version-history__effective-card h4 { margin: 0; font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.version-history__body { max-height: 24rem; margin: 0; overflow: auto; padding: var(--cs-space-8); border-radius: var(--cs-radius-sm, 4px); background: var(--cs-surface-inset, var(--cs-surface)); color: var(--cs-text); font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); white-space: pre-wrap; overflow-wrap: anywhere; }
.version-history__no-effective { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
