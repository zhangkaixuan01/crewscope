<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { permissions } from '../../app/auth'
import { useAgentMemoryStore } from '../../domains/agent/memory-store'
import { agentMemoryDegradedLabels } from '../../domains/agent/labels'
import { agentMemoryStateOf } from '../../domains/agent/types'
import BaseButton from '../base/BaseButton.vue'
import BaseCheckbox from '../base/BaseCheckbox.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

// D7: the runtime view of this member's own agent memory — reading it needs no configuration
// rights, so the section sits beside (not inside) the editing form.
const props = defineProps<{
  profileId: string
}>()

const store = useAgentMemoryStore()

const resource = computed(() => store.state.views[props.profileId] ?? null)
const view = computed(() => resource.value?.value ?? null)
const memoryState = computed(() => (view.value === null ? null : agentMemoryStateOf(view.value)))

const loading = computed(() => !resource.value || resource.value.phase === 'idle' || resource.value.phase === 'loading')
const forbidden = computed(() => resource.value?.phase === 'error' && resource.value.errorStatus === 403)
const hardError = computed(() => resource.value?.phase === 'error' && !forbidden.value)

// The clear slot is one shared command — only the profile it names shows the banner.
const clear = computed(() => (store.state.clear.profileId === props.profileId ? store.state.clear : null))
const clearPending = computed(() => clear.value?.phase === 'pending')
const clearHint = computed(() => (!confirmed.value ? '需要先勾选确认。' : null))

const confirmed = ref(false)
watch(() => store.state.clear.phase, phase => {
  if (phase === 'success') confirmed.value = false
})

watch(() => props.profileId, profileId => {
  confirmed.value = false
  void store.load(profileId)
}, { immediate: true })

function retry(): void {
  void store.load(props.profileId, true)
}

function clearMemory(): void {
  void store.clearMemory(props.profileId)
}
</script>

<template>
  <section class="memory-section" aria-label="辅助记忆">
    <div><p class="eyebrow">Memory</p><h3>辅助记忆</h3><span>这里只显示你本人的记忆偏好；清除立即生效且不可恢复。</span></div>

    <StatePanel v-if="loading" state="loading" title="正在读取辅助记忆" description="正在读取当前身份在这名 Agent 上的记忆。" />
    <StatePanel v-else-if="forbidden" state="forbidden" title="无权读取辅助记忆" description="服务端没有为当前身份解析出这个 Team 的活动成员。">
      <template #action>
        <RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.scopeRead } }">
          <BaseButton variant="secondary" size="small">查看权限说明</BaseButton>
        </RouterLink>
      </template>
    </StatePanel>
    <StatePanel v-else-if="hardError" state="error" :description="resource?.errorMessage ?? undefined" @retry="retry" />

    <template v-else-if="memoryState">
      <!-- Unconfigured: the policy switch lives in the configuration form above, not here. -->
      <p v-if="memoryState.kind === 'unconfigured'" class="memory-section__note">当前 Agent 配置未启用辅助记忆。在上方配置里选择记忆策略后，模型写入的偏好会出现在这里。</p>

      <template v-else>
        <!-- The three view states never blur: degraded means the policy cannot be resolved, and
             the server answers no entries in that state — this is not 「暂无记忆」. -->
        <p v-if="memoryState.kind === 'degraded'" class="memory-section__note">
          <StatusBadge tone="warning">{{ agentMemoryDegradedLabels.POLICY_UNAVAILABLE }}</StatusBadge>
          配置引用了记忆策略 <span class="mono">{{ view?.policyReference?.id.slice(0, 8) }}</span>@v{{ view?.policyReference?.version }}，但该策略当前不可解析。策略不可用期间无法读取条目，清除仍然可用。
        </p>
        <dl v-else class="memory-section__policy">
          <div><dt>策略</dt><dd class="mono">{{ view?.policy?.policyId.slice(0, 8) }}@v{{ view?.policy?.version }}</dd></div>
          <div><dt>保留期</dt><dd>{{ view?.policy?.ttlDays }} 天</dd></div>
          <div><dt>条目上限</dt><dd>{{ view?.policy?.maxEntriesPerOwner }} 条</dd></div>
          <div><dt>单条上限</dt><dd>{{ view?.policy?.valueMaxBytes }} 字节</dd></div>
          <div><dt>清空代际</dt><dd>{{ view?.clearanceGeneration }}</dd></div>
        </dl>

        <p
          v-if="clear?.message ?? clear?.errorMessage"
          class="memory-banner"
          :class="{ 'memory-banner--error': clear?.phase === 'error' }"
          :role="clear?.phase === 'error' ? 'alert' : 'status'"
        >{{ clear?.errorMessage ?? clear?.message }}</p>

        <table class="memory-section__entries">
          <caption>记忆条目（{{ view?.entryCount }}）</caption>
          <thead><tr><th scope="col">键</th><th scope="col">内容</th><th scope="col">版本</th><th scope="col">过期</th></tr></thead>
          <tbody>
            <tr v-for="entry in view?.entries ?? []" :key="entry.memoryKey">
              <td class="mono">{{ entry.memoryKey }}</td>
              <td class="memory-section__value">{{ entry.value }}</td>
              <td>v{{ entry.version }}</td>
              <td><RelativeTime :value="entry.expiresAt" /></td>
            </tr>
            <tr v-if="memoryState.kind === 'degraded' || (view?.entries.length ?? 0) === 0">
              <td colspan="4" class="memory-section__note">{{ memoryState.kind === 'degraded' ? '策略不可用期间无法读取条目。' : '还没有记忆条目。模型写入的偏好会出现在这里。' }}</td>
            </tr>
          </tbody>
        </table>

        <div class="memory-section__clear">
          <BaseCheckbox v-model="confirmed" :disabled="clear?.phase === 'pending'">我确认清除本人在此 Agent 上的全部记忆条目</BaseCheckbox>
          <BaseButton
            variant="danger"
            size="small"
            :loading="clearPending"
            :disabled="!confirmed"
            :aria-describedby="clearHint ? 'memory-clear-reason' : undefined"
            @click="clearMemory"
          >清除我的辅助记忆</BaseButton>
          <span v-if="clearHint" id="memory-clear-reason" class="memory-section__note">{{ clearHint }}</span>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.memory-section { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); margin-top: var(--cs-space-12); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); }
.memory-section p, .memory-section h3, .memory-section span { display: block; }
.memory-section .eyebrow { margin: 0 0 var(--cs-space-4); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }
.memory-section h3 { margin: 0; font-size: var(--cs-text-base); }
.memory-section > div:first-child span { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.memory-section__note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.memory-section__note .status-badge { margin-right: var(--cs-space-8); }
.memory-section__policy { display: flex; margin: 0; flex-wrap: wrap; gap: var(--cs-space-8) var(--cs-space-16); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.memory-section__policy div { display: flex; gap: var(--cs-space-4); }
.memory-section__policy dt { margin: 0; }
.memory-section__policy dd { margin: 0; color: var(--cs-text); }
.memory-section__entries { width: 100%; font-size: var(--cs-text-xs); text-align: left; border-collapse: collapse; }
.memory-section__entries caption { caption-side: top; padding-bottom: var(--cs-space-4); color: var(--cs-text-muted); text-align: left; }
.memory-section__entries th, .memory-section__entries td { padding: var(--cs-space-4) var(--cs-space-8) var(--cs-space-4) 0; border-bottom: 1px solid var(--cs-border); font-weight: var(--cs-weight-regular); vertical-align: top; }
.memory-section__entries th { color: var(--cs-text-muted); }
.memory-section__value { max-width: 280px; overflow-wrap: anywhere; }
.memory-section__clear { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8) var(--cs-space-12); }
.memory-banner { margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-xs); }
.memory-banner--error { border-color: var(--cs-danger); color: var(--cs-danger); }
.mono { font-family: var(--cs-font-mono, ui-monospace, monospace); }
@media (max-width: 600px) { .memory-section__value { max-width: none; } .memory-section__clear { flex-direction: column; align-items: stretch; } }
</style>
