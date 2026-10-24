<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { enumLabel } from '../../domains/shared/labels'
import { useTaskStore } from '../../domains/task/store'
import { taskExecutionStatusLabels } from '../../domains/task/labels'
import type { TaskExecutionOption } from '../../domains/task/types'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'

/**
 * M13b-F03（A11）：「先选最近执行」的选择器——任务列表的当前执行投影（TaskStore
 * 的只读快照查询），替代蒸馏对话框里手抄 UUID 的唯一入口。手粘仍保留，收进「高级」。
 */
const props = defineProps<{
  teamId: string
  disabled?: boolean
  modelValue: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [taskExecutionId: string | null]
  select: [option: TaskExecutionOption]
}>()

const principal = inject(AUTH_PRINCIPAL, null)
const taskStore = useTaskStore()

const phase = ref<'loading' | 'ready' | 'error'>('loading')
const options = ref<TaskExecutionOption[]>([])
const errorMessage = ref<string | null>(null)
const requestId = ref(0)

const selectOptions = computed<BaseSelectOption[]>(() => [
  { label: options.value.length ? '选择一次执行' : '暂无可选执行', value: '' },
  ...options.value.map(option => ({
    label: `${optionLabel(option)}`,
    value: option.taskExecutionId,
  })),
])

const selected = computed({
  get: () => props.modelValue ?? '',
  set: (value: string | null) => {
    const option = options.value.find(item => item.taskExecutionId === value)
    if (option) emit('select', option)
    emit('update:modelValue', value || null)
  },
})

function optionLabel(option: TaskExecutionOption): string {
  const objective = option.objective.length > 32 ? `${option.objective.slice(0, 32)}…` : option.objective
  const status = enumLabel(option.executionStatus, taskExecutionStatusLabels)
  return `${objective} · ${status}`
}

async function load(): Promise<void> {
  const organizationId = principal?.organizationId
  if (!organizationId || !props.teamId) {
    options.value = []
    phase.value = 'ready'
    return
  }
  const version = ++requestId.value
  phase.value = 'loading'
  errorMessage.value = null
  try {
    const items = await taskStore.listRecentExecutionOptions({ organizationId, teamId: props.teamId })
    if (requestId.value !== version) return
    options.value = items
    phase.value = 'ready'
  } catch {
    if (requestId.value !== version) return
    phase.value = 'error'
    errorMessage.value = '最近执行暂时不可用，可稍后重试，或在「高级」中粘贴执行 ID。'
  }
}

onMounted(load)
watch(() => props.teamId, () => { void load() })
defineExpose({ reload: load })
</script>

<template>
  <div class="execution-source-picker">
    <BaseSelect
      v-model="selected"
      class="execution-source-picker__select"
      :options="selectOptions"
      :disabled="disabled"
      :invalid="phase === 'error'"
      :aria-describedby="phase === 'error' ? 'execution-source-picker-error' : undefined"
      aria-label="选择一次执行"
    />
    <small v-if="phase === 'loading'" class="execution-source-picker__hint">正在读取最近执行…</small>
    <small v-else-if="phase === 'ready' && !options.length" class="execution-source-picker__hint">最近没有带执行的任务——可从 Task 详情的执行区一键带入，或在「高级」中粘贴执行 ID。</small>
    <small v-else-if="phase === 'error'" id="execution-source-picker-error" class="execution-source-picker__error" role="alert">{{ errorMessage }} <button type="button" @click="load">重试</button></small>
  </div>
</template>

<style scoped>
.execution-source-picker { display: grid; gap: var(--cs-space-4); }
.execution-source-picker__hint { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.execution-source-picker__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.execution-source-picker__error button { color: inherit; text-decoration: underline; cursor: pointer; }
</style>
