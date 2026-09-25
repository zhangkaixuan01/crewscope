<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRouter } from 'vue-router'
import { CrewScopeApiError } from '../../api/client'
import { acknowledgeCreation, creationRecovery, resolveCreation, stopCreationQueries, type RecoveryEntry } from '../../api/creationRecovery'
import BaseButton from '../base/BaseButton.vue'

const router = useRouter()
const pending = ref<string | null>(null)
const message = ref('')
const names = { WORK_PROJECT: '项目', WORK_ITEM: '工作项', CONVERSATION: '对话' }
onBeforeUnmount(stopCreationQueries)
async function confirm(entry: RecoveryEntry) {
  if (pending.value) return
  pending.value = entry.key
  message.value = '正在确认原创建结果…'
  try {
    const receipt = await resolveCreation(entry)
    const result = receipt.creation
    if (!result || !creationRecovery.entries.some(item => item.key === entry.key)) return
    const failure = await router.push({
      name: result.type === 'CONVERSATION' ? 'conversation' : 'work',
      query: { team: result.teamId, project: result.projectId ?? undefined,
        workItem: result.type === 'WORK_ITEM' ? result.resourceId : undefined,
        conversation: result.type === 'CONVERSATION' ? result.resourceId : undefined },
    })
    if (failure && !isNavigationFailure(failure, NavigationFailureType.duplicated)) {
      message.value = '尚未打开结果；记录仍保留，可稍后再次确认。'; return
    }
    acknowledgeCreation(entry.key, entry.organizationId)
    message.value = '已定位原创建结果。'
  } catch (error) {
    message.value = error instanceof CrewScopeApiError && error.envelope.code === 'creation_projection_pending'
      ? error.envelope.message : error instanceof Error && error.name === 'AbortError'
      ? '已停止本轮查询；原操作仍保留，稍后可以再次确认。'
      : '结果仍待确认，或当前无权访问。请稍后再次确认，不要重复新建。'
  } finally { pending.value = null }
}
</script>

<template>
  <section v-if="creationRecovery.entries.length || creationRecovery.warning" class="creation-recovery" aria-label="创建结果恢复">
    <details>
      <summary>创建结果待确认（{{ creationRecovery.entries.length }}）</summary>
      <p>这里只查询原操作，不会再次创建。关闭页面并不撤销已经提交的操作。</p>
      <p v-if="creationRecovery.warning" role="status">{{ creationRecovery.warning }}</p>
      <ul>
        <li v-for="(entry, index) in creationRecovery.entries" :key="entry.key">
          <span>{{ names[entry.type] }}创建 {{ index + 1 }} · {{ new Date(entry.createdAt).toLocaleString() }}</span>
          <BaseButton size="small" :disabled="Boolean(pending)" @click="confirm(entry)">再次确认并打开</BaseButton>
        </li>
      </ul>
      <p v-if="message" role="status">{{ message }}</p>
    </details>
  </section>
</template>

<style scoped>
.creation-recovery { padding: var(--cs-space-12) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-sm); }
summary { cursor: pointer; }
ul { display: grid; gap: var(--cs-space-8); padding-inline-start: var(--cs-space-16); max-height: 30dvh; overflow-y: auto; }
li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-12); }
</style>
