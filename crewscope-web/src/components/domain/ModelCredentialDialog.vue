<script setup lang="ts">
import { Building2, KeyRound, RotateCw, ShieldCheck, UserRound, X } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type {
  CreateModelConnectionInput,
  ModelConnectionOwnerType,
  ModelConnectionSummary,
  ModelProviderSummary,
} from '../../domains/model/types'
import BaseButton from '../base/BaseButton.vue'

const props = defineProps<{
  mode: 'create' | 'rotate'
  providers: ModelProviderSummary[]
  connection: ModelConnectionSummary | null
  teamId: string
  canManageTeam: boolean
  canManageOrganization: boolean
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
}>()

const emit = defineEmits<{
  close: []
  create: [input: CreateModelConnectionInput, idempotencyKey: string]
  rotate: [connectionId: string, credentialVersion: number, apiKey: string, idempotencyKey: string]
}>()

const dialog = useTemplateRef<HTMLElement>('dialog')
const apiKeyInput = useTemplateRef<HTMLInputElement>('apiKeyInput')
const ownerType = ref<ModelConnectionOwnerType>('USER')
const providerKey = ref(props.providers.find(provider => provider.status === 'ACTIVE')?.key ?? '')
const region = ref('')
const expiration = ref('')
const apiKey = ref('')
const submitted = ref(false)
let attemptKey = ''

const activeProviders = computed(() => props.providers.filter(provider => provider.status === 'ACTIVE'))
const selectedProvider = computed(() => props.providers.find(provider => provider.key === providerKey.value) ?? null)
const availableRegions = computed(() => selectedProvider.value?.availableRegions ?? [])
const expirationValid = computed(() => !expiration.value || !Number.isNaN(new Date(expiration.value).valueOf()))
const valid = computed(() => props.mode === 'rotate'
  ? Boolean(props.connection && apiKey.value.trim() && apiKey.value.length <= 1_048_576)
  : Boolean(selectedProvider.value
    && availableRegions.value.includes(region.value)
    && apiKey.value.trim()
    && apiKey.value.length <= 1_048_576
    && expirationValid.value
    && canCreateOwner(ownerType.value)))

watch(activeProviders, providers => {
  if (!providers.some(provider => provider.key === providerKey.value)) providerKey.value = providers[0]?.key ?? ''
}, { immediate: true })

watch(availableRegions, regions => {
  if (!regions.includes(region.value)) region.value = regions[0] ?? ''
}, { immediate: true })

watch([ownerType, providerKey, region, expiration, apiKey], () => {
  // Editing any command input creates a new logical request; an unchanged retry reuses its key.
  attemptKey = ''
  submitted.value = false
})

watch(() => [props.connection?.id, props.connection?.version, props.connection?.credentialVersion], () => {
  // A conflict refresh changes the authoritative command coordinates and therefore starts a new request.
  attemptKey = ''
})

onMounted(() => void nextTick(() => apiKeyInput.value?.focus()))
onBeforeUnmount(clearSecret)

function canCreateOwner(value: ModelConnectionOwnerType): boolean {
  if (value === 'USER') return true
  if (value === 'TEAM') return props.canManageTeam
  return props.canManageOrganization
}

function requestClose(): void {
  if (props.submitting) return
  clearSecret()
  emit('close')
}

function submit(): void {
  submitted.value = true
  if (!valid.value) return
  if (!attemptKey) attemptKey = crypto.randomUUID()
  if (props.mode === 'rotate') {
    const connection = props.connection
    if (connection) emit('rotate', connection.id, connection.credentialVersion, apiKey.value, attemptKey)
    return
  }
  emit('create', {
    providerKey: providerKey.value,
    ownerType: ownerType.value,
    teamId: ownerType.value === 'TEAM' ? props.teamId : null,
    region: region.value,
    apiKey: apiKey.value,
    credentialExpiresAt: expiration.value ? new Date(expiration.value).toISOString() : null,
  }, attemptKey)
}

function clearSecret(): void {
  // The API Key only exists in this component and is cleared on every exit path.
  apiKey.value = ''
  attemptKey = ''
}

function handleKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(dialog.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled)',
  )]
  const first = controls[0]
  const last = controls.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <div class="credential-backdrop" @click.self="requestClose">
    <form ref="dialog" class="credential-dialog panel" role="dialog" aria-modal="true" :aria-labelledby="mode === 'create' ? 'credential-create-title' : 'credential-rotate-title'" tabindex="-1" @submit.prevent="submit" @keydown="handleKeydown">
      <header>
        <span class="dialog-icon"><KeyRound v-if="mode === 'create'" :size="20" /><RotateCw v-else :size="20" /></span>
        <div>
          <p class="eyebrow">One-way credential input</p>
          <h2 :id="mode === 'create' ? 'credential-create-title' : 'credential-rotate-title'">{{ mode === 'create' ? '创建模型连接' : '轮换模型凭证' }}</h2>
          <span>{{ mode === 'create' ? '选择受信 Provider 和归属范围，再单向提交 API Key。' : `${connection?.providerKey ?? ''} · Credential Version ${connection?.credentialVersion ?? ''}` }}</span>
        </div>
        <button type="button" :aria-label="mode === 'create' ? '关闭创建模型连接' : '关闭轮换模型凭证'" :disabled="submitting" @click="requestClose"><X :size="18" /></button>
      </header>

      <div class="credential-content">
        <fieldset v-if="mode === 'create'" class="owner-picker">
          <legend>Connection 归属</legend>
          <button type="button" :class="{ active: ownerType === 'USER' }" @click="ownerType = 'USER'"><UserRound :size="18" /><span><strong>我的连接</strong><small>只由你管理，可供你的 Personal 与 Specialist Agent 使用。</small></span></button>
          <button type="button" :disabled="!canManageTeam" :class="{ active: ownerType === 'TEAM' }" @click="ownerType = 'TEAM'"><Building2 :size="18" /><span><strong>团队连接</strong><small>{{ canManageTeam ? '团队成员可查看，Provider Manager 可管理。' : '需要 Team Provider 管理权限。' }}</small></span></button>
          <button v-if="canManageOrganization" type="button" :class="{ active: ownerType === 'ORGANIZATION' }" @click="ownerType = 'ORGANIZATION'"><ShieldCheck :size="18" /><span><strong>组织连接</strong><small>仅平台管理员可创建和管理。</small></span></button>
        </fieldset>

        <div v-if="mode === 'create'" class="field-grid">
          <label><span>Provider</span><select v-model="providerKey" aria-label="Provider" :disabled="submitting || activeProviders.length === 0"><option v-if="activeProviders.length === 0" value="" disabled>暂无可用 Provider</option><option v-for="provider in activeProviders" :key="provider.key" :value="provider.key">{{ provider.displayName }}</option></select></label>
          <label><span>Region</span><select v-model="region" aria-label="Region" :disabled="submitting || availableRegions.length === 0"><option v-if="availableRegions.length === 0" value="" disabled>暂无可用 Region</option><option v-for="value in availableRegions" :key="value" :value="value">{{ value }}</option></select></label>
          <label class="wide"><span>凭证过期时间（可选）</span><input v-model="expiration" type="datetime-local" :disabled="submitting" /></label>
        </div>

        <label class="secret-field">
          <span>API Key</span>
          <input ref="apiKeyInput" v-model="apiKey" type="password" autocomplete="new-password" autocapitalize="off" spellcheck="false" maxlength="1048576" placeholder="仅在本次提交中使用" :disabled="submitting" :aria-invalid="submitted && !apiKey.trim()" />
          <small>浏览器不会保存、回显或记录此 Key；关闭或成功后立即清空。失败后可保留在当前表单中显式重试。</small>
        </label>

        <section class="secret-boundary" aria-label="凭证安全边界"><ShieldCheck :size="18" /><div><strong>服务端托管</strong><span>Endpoint、Credential ID、加密存储引用与 Provider 原始响应都不会进入浏览器。</span></div></section>
        <p v-if="errorMessage" class="command-error" role="alert">{{ errorMessage }}</p>
      </div>

      <footer>
        <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
        <BaseButton type="submit" :loading="submitting" :disabled="!valid">{{ retryable ? '使用原请求重试' : mode === 'create' ? '创建并安全存储' : '轮换凭证' }}</BaseButton>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.credential-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 20px; background: rgb(21 35 29 / 38%); backdrop-filter: blur(3px); }.credential-dialog { width: min(680px, 100%); max-height: calc(100dvh - 40px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.credential-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: 11px; padding: 20px; border-bottom: 1px solid var(--cs-border); }.dialog-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.credential-dialog h2 { margin: 0 0 3px; font-size: 18px; }.credential-dialog header div > span { color: var(--cs-text-muted); font-size: 10px; }.credential-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.credential-content { display: grid; gap: 16px; padding: 18px 20px 4px; }.owner-picker { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; padding: 0; border: 0; }.owner-picker legend { grid-column: 1 / -1; color: var(--cs-text-secondary); font-size: 10px; font-weight: 750; }.owner-picker button { display: grid; grid-template-columns: 20px 1fr; gap: 9px; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }.owner-picker button.active { border-color: var(--cs-brand-300); background: var(--cs-brand-50); box-shadow: 0 0 0 2px rgb(83 173 107 / 8%); }.owner-picker button:disabled { cursor: not-allowed; opacity: .52; }.owner-picker strong, .owner-picker small { display: block; }.owner-picker strong { font-size: 11px; }.owner-picker small { margin-top: 3px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.45; }.field-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }.field-grid label, .secret-field { display: grid; gap: 6px; }.field-grid label.wide { grid-column: 1 / -1; }.field-grid label > span, .secret-field > span { color: var(--cs-text-secondary); font-size: 10px; font-weight: 750; }.field-grid select, .field-grid input, .secret-field input { width: 100%; min-height: 40px; padding: 0 10px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); }.secret-field input[aria-invalid='true'] { border-color: var(--cs-danger); }.secret-field small { color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.secret-boundary { display: flex; gap: 9px; padding: 11px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-brand-700); }.secret-boundary svg { flex: 0 0 auto; }.secret-boundary strong, .secret-boundary span { display: block; }.secret-boundary strong { font-size: 10px; }.secret-boundary span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.command-error { margin: 0; color: var(--cs-danger); font-size: 10px; }.credential-dialog > footer { display: flex; justify-content: flex-end; gap: 8px; padding: 17px 20px 20px; }
@media (max-width: 767px) { .credential-backdrop { align-items: end; padding: 0; }.credential-dialog { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }.credential-dialog > header { padding: 17px 16px; }.credential-content { padding-inline: 16px; }.owner-picker, .field-grid { grid-template-columns: 1fr; }.owner-picker legend, .field-grid label.wide { grid-column: 1; }.field-grid input, .field-grid select, .secret-field input { font-size: 16px; }.credential-dialog > footer { display: grid; padding-inline: 16px; }.credential-dialog > footer > * { width: 100%; } }
</style>
