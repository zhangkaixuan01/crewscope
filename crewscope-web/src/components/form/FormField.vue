<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  id?: string
  hint?: string
  error?: string | null
  min?: number | string
  max?: number | string
  step?: number | string
  required?: boolean
}>(), { id: undefined, hint: undefined, error: null, min: undefined, max: undefined, step: undefined, required: false })
</script>

<template>
  <label class="form-field" :for="id">
    <span class="form-field__label">{{ label }}<b v-if="required" aria-hidden="true"> *</b></span>
    <span v-if="hint" :id="`${id}-hint`" class="form-field__hint">{{ hint }}</span>
    <slot :aria-describedby="error ? `${id}-error` : hint ? `${id}-hint` : undefined" :aria-invalid="Boolean(error)" :min="min" :max="max" :step="step" />
    <span v-if="error" :id="`${id}-error`" class="form-field__error" role="alert">{{ error }}</span>
  </label>
</template>

<style scoped>
.form-field { display: grid; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.form-field__label { font-size: var(--cs-text-sm); }
.form-field__label b { color: var(--cs-danger); }
.form-field__hint, .form-field__error { font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); line-height: var(--cs-leading-normal); }
.form-field__hint { color: var(--cs-text-muted); }
.form-field__error { color: var(--cs-danger); }
</style>
