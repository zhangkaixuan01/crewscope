<script setup lang="ts">
import { computed, ref } from 'vue'

export interface BaseSelectOption { label: string; value: string | number | null; disabled?: boolean }

const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  options?: readonly BaseSelectOption[]
  disabled?: boolean
  invalid?: boolean
}>(), { modelValue: '', options: () => [], disabled: false, invalid: false })

const emit = defineEmits<{ 'update:modelValue': [value: string | number | null] }>()

/**
 * 内层走原生 v-model：读的是 option 的绑定值（数字、null 的「不设置」项），不是 DOM 字符串
 * ——slot 里的 `:value` 语义与迁移前的裸 select 完全一致（M12b-F03 全站 select 收敛的前提）。
 */
const proxy = computed({
  get: () => props.modelValue,
  set: (value: string | number | null) => emit('update:modelValue', value),
})

// 包住原生控件就要继承它的命令式 API——模板 ref 从元素变成组件后 focus 不能断。
const select = ref<HTMLSelectElement | null>(null)
defineExpose({ focus: (options?: FocusOptions) => select.value?.focus(options) })
</script>

<template>
  <select ref="select" v-model="proxy" class="base-select" :class="{ 'base-select--invalid': invalid }" :disabled="disabled" :aria-invalid="invalid || undefined">
    <option v-for="option in options" :key="String(option.value)" :value="option.value" :disabled="option.disabled">{{ option.label }}</option>
    <slot />
  </select>
</template>

<style scoped>
.base-select { width: 100%; min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-12); border:  1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.base-select:focus { border-color: var(--cs-focus); box-shadow: var(--cs-focus-ring); outline: none; }
.base-select--invalid { border-color: var(--cs-danger); }
.base-select:disabled { cursor: not-allowed; opacity: .6; }
</style>
