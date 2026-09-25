<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { CrewScopeApiError } from '../../api/client'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { commandFailureMessage, createCommandIntents } from '../../api/commandIntent'
import { useConfirm } from '../../composables/useConfirm'
import { HttpWorkItemContentGateway, type WorkItemContentInput } from '../../domains/workitem/contentGateway'
import {
  clearWorkItemContentDraft,
  clearWorkItemContentDraftIfRevision,
  readWorkItemContentDraft,
  writeWorkItemContentDraft,
} from '../../domains/workitem/contentDraft'
import { HttpWorkItemGateway } from '../../domains/workitem/gateway'
import { workItemPriorities, type WorkItemSummary } from '../../domains/workitem/types'
import { workItemPriorityLabels } from '../../domains/workitem/labels'
import BaseButton from '../base/BaseButton.vue'

const props = defineProps<{ item: WorkItemSummary; canParticipate: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const editing = ref(false)
const pending = ref(false)
const message = ref('')
const conflict = ref<WorkItemSummary | null>(null)
const needsLatest = ref(false)
const baseVersion = ref(0)
const form = ref({ title: '', description: '', priority: 'MEDIUM' as WorkItemSummary['priority'], labels: '', dueAt: '' })
const allowed = computed(() => props.canParticipate && props.item.status !== 'ARCHIVED' && props.item.source === 'CREWSCOPE')
const valid = computed(() => form.value.title.trim().length > 0 && form.value.title.trim().length <= 240
  && (!form.value.dueAt || Number.isFinite(Date.parse(form.value.dueAt))))
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
const gateway = new HttpWorkItemContentGateway()
const reads = new HttpWorkItemGateway()
const intents = createCommandIntents<WorkItemContentInput, unknown>()
const confirmation = useConfirm()
const principal = inject(AUTH_PRINCIPAL)
let alive = true
let baseline = ''
const contentDraftScope = computed(() => ({ organizationId: props.item.organizationId, teamId: props.item.teamId, projectId: props.item.projectId }))
const dirty = computed(() => editing.value && JSON.stringify(form.value) !== baseline)
const titleInput = ref<HTMLInputElement | null>(null)
const reasonId = `${useId()}-save-reason`
const saveHint = computed(() => !allowed.value ? '当前无编辑权限，或工作项已归档。'
  : needsLatest.value || conflict.value ? '请先获取并核对最新内容，再明确确认保存版本。'
    : !valid.value ? '请填写 1–240 字的标题，并检查到期时间。'
      : !dirty.value ? '尚未修改内容。' : '')
function localTime(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
function start() {
  form.value = { title: props.item.title, description: props.item.description ?? '', priority: props.item.priority,
    labels: props.item.labels.join(', '), dueAt: localTime(props.item.dueAt) }
  baseline = JSON.stringify(form.value)
  baseVersion.value = props.item.version
  // Restore the browser draft into the freshly loaded server values; the conflict flow stays
  // the safety net when the draft was written against an older version (M9b-F05).
  const draft = readWorkItemContentDraft(contentDraftScope.value, props.item.id, principal)
  if (draft) form.value = { title: draft.title, description: draft.description, priority: draft.priority, labels: draft.labels, dueAt: draft.dueAt }
  conflict.value = null
  needsLatest.value = false
  message.value = ''
  editing.value = true
  void nextTick(() => titleInput.value?.focus())
}
// Edits persist immediately as a browser draft, so a refresh or navigation never eats them.
watch(form, value => {
  if (!editing.value || !dirty.value || pending.value) return
  writeWorkItemContentDraft(contentDraftScope.value, props.item.id, baseVersion.value, {
    type: props.item.type, title: value.title, description: value.description, priority: value.priority, labels: value.labels, dueAt: value.dueAt,
  }, principal)
}, { deep: true })
async function leave() {
  if (pending.value) return false
  if (!dirty.value) return true
  const discard = await confirmation.confirm({ title: '放弃未保存的修改？', description: '尚未保存的内容会被丢弃。',
    confirmLabel: '放弃修改', cancelLabel: '继续编辑', danger: true })
  if (discard) clearWorkItemContentDraft(contentDraftScope.value, props.item.id, principal)
  return discard
}
async function cancel() { if (await leave()) editing.value = false }
onBeforeRouteLeave(leave)
onBeforeRouteUpdate((to, from) => {
  if (['team', 'project', 'workItem'].some(key => to.query[key] !== from.query[key])) return leave()
})
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { alive = false; intents.clear(); window.removeEventListener('beforeunload', beforeUnload) })
async function save() {
  if (!valid.value || !allowed.value || pending.value || conflict.value || needsLatest.value || !dirty.value) return
  const item = props.item
  const version = baseVersion.value
  const scope = { organizationId: item.organizationId, teamId: item.teamId, projectId: item.projectId }
  // Send only intentional changes. In particular datetime-local must not truncate an untouched
  // server timestamp (seconds/subseconds) or overwrite another editor's untouched fields.
  const original = JSON.parse(baseline) as typeof form.value
  const input: WorkItemContentInput = {}
  if (form.value.title !== original.title) input.title = form.value.title.trim()
  if (form.value.description !== original.description) input.description = form.value.description.trim() || null
  if (form.value.priority !== original.priority) input.priority = form.value.priority
  if (form.value.labels !== original.labels) input.labels = [...new Set(form.value.labels.split(',').map(value => value.trim()).filter(Boolean))]
  if (form.value.dueAt !== original.dueAt) input.dueAt = form.value.dueAt ? new Date(form.value.dueAt).toISOString() : null
  pending.value = true
  message.value = ''
  try {
    await intents.execute({ ...scope, id: item.id, version }, input,
      (snapshot, key) => gateway.update(scope, item.id, snapshot, version, key))
    if (!alive || props.item.id !== item.id) return
    // The server owns the content now; drop the browser draft only if it still holds the
    // version this save was based on, so a concurrent re-edit is never lost.
    clearWorkItemContentDraftIfRevision(scope, item.id, version, principal)
    editing.value = false
    message.value = '修改已保存，不影响历史执行快照。'
    emit('saved')
  } catch (error) {
    if (!alive || props.item.id !== item.id) return
    message.value = commandFailureMessage(error, '暂时无法保存修改，请稍后重试。')
    if (error instanceof CrewScopeApiError && error.status === 409) {
      message.value = '内容已被更新。本地输入已保留，请对照当前内容后再确认。'
      needsLatest.value = true
      await loadLatest()
    }
  } finally { if (alive && props.item.id === item.id) pending.value = false }
}
async function loadLatest() {
  const item = props.item
  try {
    const details = await reads.getWorkItem({ organizationId: item.organizationId, teamId: item.teamId, projectId: item.projectId }, item.id)
    if (alive && props.item.id === item.id) { conflict.value = details.workItem; needsLatest.value = false }
  } catch { if (alive) message.value = '无法读取当前版本，请重试获取最新内容；本地输入已保留。' }
}
function rebase() {
  if (!conflict.value || conflict.value.status === 'ARCHIVED') return
  baseVersion.value = conflict.value.version
  conflict.value = null
  message.value = '已使用当前版本作为保存基础，请确认本地输入后点击保存。'
}
</script>

<template>
  <section class="content-editor" aria-label="工作项内容编辑">
    <BaseButton v-if="!editing && allowed" variant="secondary" size="small" @click="start">编辑内容</BaseButton>
    <p v-else-if="!editing"> {{ item.status === 'ARCHIVED' ? '已归档，内容只读。' : '当前无权编辑此工作项内容。' }}</p>
    <form v-if="editing" @submit.prevent="save">
      <p>编号 {{ item.key }}（只读） · 不修改状态、责任或历史执行。</p>
      <label>标题<input ref="titleInput" v-model="form.title" maxlength="240" :disabled="pending" /></label>
      <label>描述<textarea v-model="form.description" rows="5" :disabled="pending" /></label>
      <label>优先级<select v-model="form.priority" :disabled="pending"><option v-for="priority in workItemPriorities" :key="priority" :value="priority">{{ workItemPriorityLabels[priority] }}</option></select></label>
      <label>标签<input v-model="form.labels" placeholder="用逗号分隔" :disabled="pending" /></label>
      <label>到期时间<input v-model="form.dueAt" type="datetime-local" :disabled="pending" /><small>时区：{{ timezone }}；清空表示不设到期时间。</small></label>
      <section v-if="conflict" class="conflict" aria-label="当前版本与本地输入对照">
        <p>服务端当前版本 v{{ conflict.version }}；上方仍是你的本地输入。</p>
        <dl><dt>当前标题</dt><dd>{{ conflict.title }}</dd><dt>当前描述</dt><dd>{{ conflict.description || '无' }}</dd><dt>当前优先级</dt><dd>{{ workItemPriorityLabels[conflict.priority] }}</dd><dt>当前标签</dt><dd>{{ conflict.labels.join(', ') || '无' }}</dd><dt>当前到期时间</dt><dd>{{ conflict.dueAt ? new Date(conflict.dueAt).toLocaleString() : '未设置' }}</dd></dl>
        <p v-if="conflict.status === 'ARCHIVED'">工作项已归档，不能继续保存；本地输入仍保留供核对。</p>
        <BaseButton v-else variant="secondary" @click="rebase">以当前版本重新确认</BaseButton>
      </section>
      <BaseButton v-if="needsLatest" variant="secondary" :disabled="pending" @click="loadLatest">获取最新内容</BaseButton>
      <p v-if="saveHint" :id="reasonId">{{ saveHint }}</p>
      <div class="actions">
        <BaseButton variant="ghost" :disabled="pending" @click="cancel">取消编辑</BaseButton>
        <BaseButton type="submit" :loading="pending" :aria-describedby="saveHint ? reasonId : undefined" :disabled="Boolean(saveHint)">保存修改</BaseButton>
      </div>
    </form>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.content-editor, form, label { display: grid; gap: var(--cs-space-8); }
.content-editor { padding: var(--cs-space-16); }
input, textarea, select { min-width: 0; width: 100%; padding: var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-md); }
p, small { font-size: var(--cs-text-sm); color: var(--cs-text-muted); }
.actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); justify-content: flex-end; }
.conflict { padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
dd { margin: 0 0 var(--cs-space-8); white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
