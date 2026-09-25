<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { secureId } from '../../api/secureId'
import type { CreateWorkProjectInput } from '../../domains/scope/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'

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
let attemptKey = ''
watch(name, () => { attemptKey = ''; emit('inputChanged') })
watch(() => [props.errorMessage, props.retryable], () => {
  if (props.errorMessage && !props.retryable) attemptKey = ''
})
function submit() {
  if (!valid.value || props.submitting) return
  if (!attemptKey) attemptKey = secureId()
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
