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
.clarification-card { max-width: 740px; padding: var(--cs-space-16); margin: 0 auto var(--cs-space-16); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); box-shadow: var(--cs-shadow-raised); }
.clarification-card > header { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: var(--cs-space-12); }.clarification-card > header > span { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.clarification-card header p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.clarification-card h3 { margin: 0; font-size: var(--cs-text-base); }.clarification-card header small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.summary { margin: var(--cs-space-12) 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.clarification-card form { display: grid; gap: var(--cs-space-12); }.clarification-card fieldset { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }.clarification-card legend { padding: 0 var(--cs-space-4); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.clarification-card legend em { padding: var(--cs-space-2) var(--cs-space-4); margin-left: var(--cs-space-8); border-radius: 999px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); font-style: normal; }.clarification-card fieldset > p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.clarification-card textarea { width: 100%; resize: vertical; padding: var(--cs-space-8) var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); color: var(--cs-text); font: var(--cs-text-base)/var(--cs-leading-normal) var(--cs-font-sans); }.clarification-card textarea:focus { border-color: var(--cs-border-accent-strong); outline: 3px solid var(--cs-ring-brand); }.choices { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }.choices label { position: relative; cursor: pointer; }.choices input { position: absolute; z-index: 1; inset: 0; width: 100%; height: 100%; margin: 0; opacity: 0; cursor: pointer; }.choices span { display: block; padding: var(--cs-space-8) var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 999px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.choices input:checked + span { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent-strong); color: var(--cs-text-brand-strong); }.choices input:focus-visible + span { outline: 3px solid var(--cs-ring-brand); }.error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }.clarification-card footer { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.clarification-card footer > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
