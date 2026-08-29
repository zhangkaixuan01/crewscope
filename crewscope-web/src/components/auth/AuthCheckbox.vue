<script setup lang="ts">
import { useId } from 'vue'

const model = defineModel<boolean>({ default: false })
const props = withDefaults(defineProps<{
  label: string
  description?: string
  id?: string
  disabled?: boolean
}>(), {
  description: undefined,
  id: undefined,
  disabled: false,
})

const generatedId = useId()
const inputId = props.id ?? `auth-checkbox-${generatedId}`
</script>

<template>
  <label class="auth-checkbox" :class="{ 'auth-checkbox--disabled': disabled }" :for="inputId">
    <input :id="inputId" v-model="model" type="checkbox" :disabled="disabled">
    <span><strong>{{ label }}</strong><small v-if="description">{{ description }}</small></span>
  </label>
</template>

<style scoped>
.auth-checkbox {
  display: inline-flex;
  min-height: var(--cs-auth-control-height);
  align-items: center;
  gap: 9px;
  color: var(--cs-text-secondary);
  cursor: pointer;
}
.auth-checkbox input { width: 16px; height: 16px; margin: 0; accent-color: var(--cs-brand-600); }
.auth-checkbox span,
.auth-checkbox small { display: block; }
.auth-checkbox strong { font-size: 10px; }
.auth-checkbox small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }
.auth-checkbox--disabled { cursor: not-allowed; opacity: .6; }
</style>
