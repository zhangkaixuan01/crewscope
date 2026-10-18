<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import type { CreateWorkProjectInput } from '../../domains/scope/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import { useIdempotentCommand } from '../../composables/useIdempotentCommand'

const props = defineProps<{
  teamName: string
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
  checkKey?: (key: string, signal?: AbortSignal) => Promise<boolean>
}>()
const emit = defineEmits<{ close: []; submit: [input: CreateWorkProjectInput, idempotencyKey: string]; inputChanged: [] }>()
const name = ref('')
const formId = useId()
const valid = computed(() => name.value.trim().length > 0 && name.value.trim().length <= 200)
/** M12b-F04：统一到输入指纹幂等（原实现是 secureId 随机键的惰性变体——未改输入的显式重试
 *  现在与其余表单命令 dialog 一样复用同一键；输入变更仍换新键并通知父级清空重试面）。 */
const { keyFor } = useIdempotentCommand(() => [name.value], () => { emit('inputChanged') })
function submit() {
  if (!valid.value || props.submitting) return
  const attemptKey = keyFor([name.value.trim()])
  if (attemptKey === null) return
  emit('submit', { name: name.value.trim() }, attemptKey)
}
</script>

<template>
  <BaseDialog open title="创建项目" :close-on-backdrop="!submitting" @close="!submitting && emit('close')">
    <form :id="formId" class="project-create-dialog" @submit.prevent="submit">
      <p>所属团队：{{ teamName }}</p>
      <label>项目名称
        <input v-model="name" autofocus maxlength="200" autocomplete="off" placeholder="例如：客户服务平台" :disabled="submitting" />
      </label>
      <p>项目代号由系统自动生成。创建后选中该项目，不会自动启动执行。</p>
      <p v-if="errorMessage" role="alert">{{ errorMessage }}</p>
    </form>
    <template #footer>
      <BaseButton variant="ghost" :disabled="submitting" @click="emit('close')">取消</BaseButton>
      <BaseButton type="submit" :form="formId" :loading="submitting" :disabled="!valid">{{ retryable ? '再次确认结果' : '创建并选中' }}</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
form, label { display: grid; gap: var(--cs-space-8); }
input { min-width: 0; width: 100%; min-height: 44px; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-md); }
p { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
[role='alert'] { color: var(--cs-danger); }
</style>
