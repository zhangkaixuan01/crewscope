<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkItemCreateDialog from '../../src/components/domain/WorkItemCreateDialog.vue'
import WorkItemContentEditor from '../../src/components/domain/WorkItemContentEditor.vue'
import CreationRecoveryPanel from '../../src/components/feedback/CreationRecoveryPanel.vue'
import { HttpWorkItemGateway } from '../../src/domains/workitem/gateway'
import type { CreateWorkItemInput, WorkItemSummary } from '../../src/domains/workitem/types'
import { secureId } from '../../src/api/secureId'

const scope = (window as unknown as { a01Scope: { organizationId: string; teamId: string; projectId: string } }).a01Scope
const gateway = new HttpWorkItemGateway()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const pending = ref(false)
const error = ref<string | null>(null)
const item = ref<WorkItemSummary | null>(null)
let key = ''
function show() { key = secureId(); open.value = true }
async function submit(input: CreateWorkItemInput) {
  pending.value = true
  try {
    const receipt = await gateway.createWorkItem(scope, input, key)
    await router.replace({ query: { team: scope.teamId, project: scope.projectId, workItem: receipt.creation!.resourceId } })
    open.value = false
  } catch { error.value = '结果待确认' }
  finally { pending.value = false }
}
async function load() {
  const id = route.query.workItem
  if (typeof id === 'string') item.value = (await gateway.getWorkItem(scope, id)).workItem
}
watch(() => route.query.workItem, load, { immediate: true })
</script>
<template>
  <CreationRecoveryPanel />
  <button @click="show">新建工作项</button>
  <WorkItemCreateDialog v-if="open" project-key="CRW" :scope="{ organizationId: scope.organizationId, teamId: scope.teamId }" :submitting="pending" :error-message="error" @close="open = false" @submit="submit" />
  <section v-if="item" aria-label="准确结果"><h1>{{ item.title }}</h1><p>{{ item.key }}</p><code>{{ item.id }}</code>
    <WorkItemContentEditor :key="item.id" :item="item" :can-participate="true" @saved="load" />
  </section>
</template>
