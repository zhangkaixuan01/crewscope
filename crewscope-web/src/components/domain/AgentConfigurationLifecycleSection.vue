<script setup lang="ts">
import { Power } from '@lucide/vue'
import BaseButton from '../base/BaseButton.vue'

const props = defineProps<{
  visible: boolean
  platformManaged: boolean
  status: string
  defaultProfile: boolean
  pending: boolean
  confirmation: 'activate' | 'disable' | 'archive' | null
}>()

const emit = defineEmits<{
  transition: [value: 'activate' | 'disable' | 'archive']
}>()

// The container owns the command/idempotency flow; this component only renders the
// confirmation state and emits the user's intent.
function label(value: 'activate' | 'disable' | 'archive'): string {
  if (props.confirmation === value) return value === 'archive' ? '确认永久归档' : `确认${value === 'activate' ? '启用' : '禁用'}`
  return value === 'activate' ? '启用' : value === 'disable' ? '禁用' : '归档'
}
</script>

<template>
  <section v-if="visible && !defaultProfile && !platformManaged" class="lifecycle-section">
    <div><p class="eyebrow">Lifecycle</p><h3>Agent 生命周期</h3><span>禁用可恢复；归档是不可逆终态。服务端同步 Principal 与 Profile 并使用强版本校验。</span></div>
    <div class="lifecycle-actions">
      <BaseButton v-if="status === 'DISABLED'" variant="secondary" size="small" :loading="pending" @click="emit('transition', 'activate')"><Power :size="14" />{{ label('activate') }}</BaseButton>
      <BaseButton v-if="status === 'ACTIVE'" variant="secondary" size="small" :loading="pending" @click="emit('transition', 'disable')">{{ label('disable') }}</BaseButton>
      <BaseButton v-if="status !== 'ARCHIVED'" variant="danger" size="small" :loading="pending" @click="emit('transition', 'archive')">{{ label('archive') }}</BaseButton>
    </div>
  </section>
  <section v-else-if="visible && platformManaged" class="lifecycle-section">
    <div><p class="eyebrow">Managed lifecycle</p><h3>平台托管生命周期</h3><span>Team Observer 不支持重复创建或通用归档。有效 TEAM Configuration 通过 Preflight 后，专用运行时在首次调用时完成就绪激活。</span></div>
  </section>
</template>

<style scoped>
.lifecycle-section { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-16); margin-top: var(--cs-space-12); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); }.lifecycle-section p, .lifecycle-section h3, .lifecycle-section span { display: block; }.lifecycle-section p { margin: 0 0 var(--cs-space-4); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.lifecycle-section h3 { margin: 0; font-size: var(--cs-text-base); }.lifecycle-section span { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.lifecycle-actions { display: flex; flex: 0 0 auto; gap: var(--cs-space-8); }
@media (max-width: 600px) { .lifecycle-section { align-items: stretch; flex-direction: column; }.lifecycle-actions { display: grid; } }
</style>
