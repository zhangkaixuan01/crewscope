<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { permissions } from '../../app/auth'
import { useInjectionStore, viewKey } from '../../domains/injection/store'
import type { InjectionFeedbackInput } from '../../domains/injection/types'
import BaseButton from '../base/BaseButton.vue'
import StatePanel from '../feedback/StatePanel.vue'
import InjectionAttemptBlock from './InjectionAttemptBlock.vue'

// D3 self-pulling panel (ActionDeliveryWorkbench precedent): the drawer passes only the execution
// coordinates, the store lives here — TaskDetailDrawer's prop budget stays untouched.
const props = defineProps<{
  taskId: string
  executionId: string | null
  online: boolean
}>()

const store = useInjectionStore()

const currentKey = computed(() => props.executionId === null ? null : viewKey(props.taskId, props.executionId))
const resource = computed(() => (currentKey.value === null ? null : store.state.views[currentKey.value] ?? null))
const attempts = computed(() => resource.value?.value?.attempts ?? [])

const loading = computed(() => !resource.value || resource.value.phase === 'idle' || resource.value.phase === 'loading')
const forbidden = computed(() => resource.value?.phase === 'error' && resource.value.errorStatus === 403)
const hardError = computed(() => resource.value?.phase === 'error' && !forbidden.value)
const ready = computed(() => resource.value?.phase === 'ready')
// The feedback slot is one shared command — only the view it names may show its banner and spinner.
const feedback = computed(() => (store.state.feedback.key === currentKey.value ? store.state.feedback : null))
const pendingReference = computed(() => (feedback.value?.phase === 'pending' ? feedback.value.reference : null))

watch(() => [props.taskId, props.executionId] as const, ([taskId, executionId]) => {
  if (executionId !== null) void store.load(taskId, executionId)
}, { immediate: true })

function retry(): void {
  if (props.executionId !== null) void store.load(props.taskId, props.executionId)
}

function mark(reference: InjectionFeedbackInput): void {
  if (props.executionId !== null) void store.submitFeedback(props.taskId, props.executionId, reference)
}
</script>

<template>
  <section class="injection-panel" aria-label="注入与引用">
    <!-- No execution picked yet: nothing failed, there is simply no coordinate to read. -->
    <p v-if="executionId === null" class="injection-panel__note">选择一次执行后，这里会展示它的注入清单与模型声明引用。</p>

    <template v-else>
      <StatePanel v-if="loading" state="loading" title="正在读取注入清单" description="正在读取这次执行的注入证据。" />
      <StatePanel v-else-if="forbidden" state="forbidden" title="无权读取注入与引用" description="服务端没有为当前身份解析出这个 Team 的活动成员。">
        <template #action>
          <RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.workRead } }">
            <BaseButton variant="secondary" size="small">查看权限说明</BaseButton>
          </RouterLink>
        </template>
      </StatePanel>
      <StatePanel v-else-if="!online && attempts.length === 0" state="offline" title="离线时没有可用注入清单" description="联网后会回读这次执行的注入证据。" />
      <StatePanel v-else-if="hardError && attempts.length === 0" state="error" :description="resource?.errorMessage ?? undefined" @retry="retry" />
      <StatePanel v-else-if="attempts.length === 0" state="empty" title="该执行没有注入清单" description="这次执行没有封存注入清单，证据从有清单的执行开始。" />

      <template v-else>
        <StatePanel v-if="!online" compact state="offline" title="正在展示最近读取的注入清单" description="离线期间「标记不适用」保持关闭。" />

        <p
          v-if="feedback?.message ?? feedback?.errorMessage"
          class="injection-banner"
          :class="{ 'injection-banner--error': feedback?.phase === 'error' }"
          :role="feedback?.phase === 'error' ? 'alert' : 'status'"
        >{{ feedback?.errorMessage ?? feedback?.message }}</p>

        <div class="injection-panel__attempts">
          <InjectionAttemptBlock
            v-for="attempt in attempts"
            :key="attempt.manifestId"
            :attempt="attempt"
            :pending-reference="pendingReference"
            :online="online"
            @mark="mark"
          />
        </div>
      </template>

      <footer class="injection-panel__footnote">「标记不适用」仅本人可见，不影响后续检索与注入；标记后不可撤销。</footer>
    </template>
  </section>
</template>

<style scoped>
.injection-panel { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.injection-panel__note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.injection-panel__attempts { display: flex; flex-direction: column; gap: var(--cs-space-12); }
.injection-banner { margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font-size: var(--cs-text-xs); }
.injection-banner--error { border-color: var(--cs-danger); color: var(--cs-danger); }
.injection-panel__footnote { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
