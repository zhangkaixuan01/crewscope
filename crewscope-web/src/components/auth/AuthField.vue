<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId } from 'vue'

defineOptions({ inheritAttrs: false })

const model = defineModel<string>({ default: '' })
const props = withDefaults(defineProps<{
  label: string
  name: string
  id?: string
  type?: 'text' | 'email' | 'password'
  autocomplete?: string
  inputmode?: 'text' | 'email' | 'search' | 'tel' | 'url' | 'none' | 'numeric' | 'decimal'
  placeholder?: string
  hint?: string
  error?: string
  disabled?: boolean
  required?: boolean
  minlength?: number
  maxlength?: number
  focusOnMount?: boolean
}>(), {
  id: undefined,
  type: 'text',
  autocomplete: undefined,
  inputmode: undefined,
  placeholder: undefined,
  hint: undefined,
  error: undefined,
  disabled: false,
  required: false,
  minlength: undefined,
  maxlength: undefined,
  focusOnMount: false,
})

const generatedId = useId()
const inputId = computed(() => props.id ?? `auth-field-${generatedId}`)
const hintId = computed(() => `${inputId.value}-hint`)
const errorId = computed(() => `${inputId.value}-error`)
const describedBy = computed(() => [props.hint ? hintId.value : null, props.error ? errorId.value : null].filter(Boolean).join(' ') || undefined)
const input = ref<HTMLInputElement | null>(null)

function focus(): void {
  input.value?.focus()
}

// Pages opt in explicitly so loading/error states can own initial focus instead.
onMounted(() => {
  if (props.focusOnMount) void nextTick(focus)
})

defineExpose({ focus, input })
</script>

<template>
  <div class="auth-field" :class="{ 'auth-field--invalid': Boolean(error), 'auth-field--disabled': disabled }">
    <label :for="inputId">{{ label }}<span v-if="required" aria-hidden="true"> *</span></label>
    <span class="auth-field__frame">
      <span v-if="$slots.leading" class="auth-field__adornment" aria-hidden="true"><slot name="leading" /></span>
      <input
        :id="inputId"
        ref="input"
        v-model="model"
        v-bind="$attrs"
        :name="name"
        :type="type"
        :autocomplete="autocomplete"
        :inputmode="inputmode"
        :placeholder="placeholder"
        :disabled="disabled"
        :required="required"
        :minlength="minlength"
        :maxlength="maxlength"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="describedBy"
      >
      <span v-if="$slots.trailing" class="auth-field__action"><slot name="trailing" /></span>
    </span>
    <p v-if="hint" :id="hintId" class="auth-field__hint">{{ hint }}</p>
    <p v-if="error" :id="errorId" class="auth-field__error">{{ error }}</p>
  </div>
</template>

<style scoped>
.auth-field > label {
  display: block;
  margin-bottom: 6px;
  color: var(--cs-text-secondary);
  font-size: 10px;
  font-weight: 740;
}
.auth-field__frame {
  display: grid;
  min-height: var(--cs-auth-control-height);
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border: 1px solid var(--cs-border-strong);
  border-radius: 10px;
  background: var(--cs-auth-input-surface);
  color: var(--cs-text-muted);
  transition: border-color var(--cs-auth-transition), box-shadow var(--cs-auth-transition);
}
.auth-field__frame:focus-within { border-color: var(--cs-brand-400); box-shadow: var(--cs-focus-ring); }
.auth-field--invalid .auth-field__frame { border-color: var(--cs-danger); }
.auth-field--disabled { opacity: .6; }
.auth-field__frame input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  font-size: 12px;
}
.auth-field__frame input::placeholder { color: #89968e; }
.auth-field__adornment,
.auth-field__action { display: grid; place-items: center; }
.auth-field__hint,
.auth-field__error { margin: 5px 0 0; font-size: 9px; }
.auth-field__hint { color: var(--cs-text-muted); }
.auth-field__error { color: var(--cs-danger); }
</style>
