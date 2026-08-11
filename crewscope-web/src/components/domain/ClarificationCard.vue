<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { CircleHelp, SendHorizontal } from '@lucide/vue'
import type { ClarificationRequest } from '../../domains/conversation/types'
import BaseButton from '../base/BaseButton.vue'

const props = defineProps<{
  request: ClarificationRequest
  submitting?: boolean
}>()

const emit = defineEmits<{ submit: [answers: Record<string, string>] }>()
const answers = reactive<Record<string, string>>({})
const error = ref<string | null>(null)
const answeredCount = computed(() => Object.values(answers).filter(value => value?.trim()).length)

watch(() => props.request, request => {
  Object.keys(answers).forEach(key => delete answers[key])
  request.questions.forEach(question => { answers[question.fieldKey] = '' })
  error.value = null
}, { immediate: true })

function submit(): void {
  const missing = props.request.questions.find(question => question.required && !answers[question.fieldKey]?.trim())
  if (missing) {
    error.value = `请回答“${missing.question}”`
    return
  }
  const normalized = Object.fromEntries(
    Object.entries(answers).map(([key, value]) => [key, value.trim()]).filter(([, value]) => value),
  )
  if (Object.keys(normalized).length === 0) {
    error.value = '请至少提供一个回答'
    return
  }
  error.value = null
  emit('submit', normalized)
}
</script>

<template>
  <section class="clarification-card" aria-labelledby="clarification-title">
    <header>
      <span><CircleHelp :size="17" aria-hidden="true" /></span>
      <div>
        <p>Clarification</p>
        <h3 id="clarification-title">Personal Agent 需要补充信息</h3>
      </div>
      <small>{{ answeredCount }}/{{ request.questions.length }}</small>
    </header>
    <p class="summary">{{ request.summary }}</p>
    <form @submit.prevent="submit">
      <fieldset v-for="question in request.questions" :key="question.fieldKey">
        <legend>{{ question.question }}<em v-if="question.required">必填</em></legend>
        <p v-if="question.context">{{ question.context }}</p>
        <div v-if="question.choices.length" class="choices">
          <label v-for="choice in question.choices" :key="choice">
            <input v-model="answers[question.fieldKey]" type="radio" :name="question.fieldKey" :value="choice" />
            <span>{{ choice }}</span>
          </label>
        </div>
        <textarea
          v-else
          v-model="answers[question.fieldKey]"
          rows="2"
          maxlength="1000"
          :aria-label="question.question"
          :placeholder="`填写 ${question.fieldKey}`"
        />
      </fieldset>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <footer>
        <span>回答只会按已声明字段提交</span>
        <BaseButton type="submit" size="small" :loading="submitting">
          <template #icon><SendHorizontal :size="14" aria-hidden="true" /></template>
          提交并继续
        </BaseButton>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.clarification-card { max-width: 740px; padding: 14px; margin: 0 auto 14px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-md); background: #f7fcf8; box-shadow: 0 6px 18px rgb(27 75 48 / 6%); }
.clarification-card > header { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 10px; }.clarification-card > header > span { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.clarification-card header p { margin: 0 0 2px; color: var(--cs-brand-700); font-size: 8px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }.clarification-card h3 { margin: 0; font-size: 12px; }.clarification-card header small { color: var(--cs-text-muted); font-size: 9px; }.summary { margin: 11px 0; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.55; }
.clarification-card form { display: grid; gap: 10px; }.clarification-card fieldset { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: white; }.clarification-card legend { padding: 0 4px; font-size: 10px; font-weight: 750; }.clarification-card legend em { padding: 2px 5px; margin-left: 6px; border-radius: 999px; background: var(--cs-warning-soft); color: #875718; font-size: 7px; font-style: normal; }.clarification-card fieldset > p { margin: 0; color: var(--cs-text-muted); font-size: 9px; line-height: 1.45; }.clarification-card textarea { width: 100%; resize: vertical; padding: 8px 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); color: var(--cs-text); font: 10px/1.5 var(--cs-font-sans); }.clarification-card textarea:focus { border-color: var(--cs-brand-400); outline: 3px solid var(--cs-brand-100); }.choices { display: flex; flex-wrap: wrap; gap: 6px; }.choices label { cursor: pointer; }.choices input { position: absolute; opacity: 0; }.choices span { display: block; padding: 6px 9px; border: 1px solid var(--cs-border); border-radius: 999px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: 9px; }.choices input:checked + span { border-color: var(--cs-brand-300); background: var(--cs-brand-100); color: var(--cs-brand-800); }.choices input:focus-visible + span { outline: 3px solid var(--cs-brand-100); }.error { margin: 0; color: var(--cs-danger); font-size: 9px; }.clarification-card footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.clarification-card footer > span { color: var(--cs-text-muted); font-size: 8px; }
</style>
