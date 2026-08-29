<script setup lang="ts">
import { Eye, EyeOff, KeyRound } from '@lucide/vue'
import { ref } from 'vue'
import AuthField from './AuthField.vue'
import AuthPasswordGuidance from './AuthPasswordGuidance.vue'

const model = defineModel<string>({ default: '' })
withDefaults(defineProps<{
  label?: string
  name: string
  id?: string
  autocomplete?: 'current-password' | 'new-password'
  placeholder?: string
  error?: string
  disabled?: boolean
  required?: boolean
  showGuidance?: boolean
  minlength?: number
  maxlength?: number
}>(), {
  label: '密码',
  id: undefined,
  autocomplete: 'current-password',
  placeholder: undefined,
  error: undefined,
  disabled: false,
  required: false,
  showGuidance: false,
  minlength: undefined,
  maxlength: undefined,
})

const visible = ref(false)
</script>

<template>
  <AuthField
    v-model="model"
    :label="label"
    :name="name"
    :id="id"
    :type="visible ? 'text' : 'password'"
    :autocomplete="autocomplete"
    :placeholder="placeholder"
    :error="error"
    :disabled="disabled"
    :required="required"
    :minlength="minlength ?? (showGuidance ? 12 : undefined)"
    :maxlength="maxlength ?? (showGuidance ? 128 : undefined)"
  >
    <template #leading><KeyRound :size="16" /></template>
    <template #trailing>
      <button
        class="auth-password-field__toggle"
        type="button"
        :disabled="disabled"
        :aria-label="visible ? '隐藏密码' : '显示密码'"
        :aria-pressed="visible"
        @click="visible = !visible"
      >
        <EyeOff v-if="visible" :size="16" aria-hidden="true" />
        <Eye v-else :size="16" aria-hidden="true" />
      </button>
    </template>
  </AuthField>
  <AuthPasswordGuidance v-if="showGuidance" :value="model" />
</template>

<style scoped>
.auth-password-field__toggle {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 7px;
  background: transparent;
  color: var(--cs-text-muted);
  cursor: pointer;
}
.auth-password-field__toggle:hover:not(:disabled) { background: var(--cs-brand-50); color: var(--cs-brand-700); }
.auth-password-field__toggle:disabled { cursor: not-allowed; }
</style>
