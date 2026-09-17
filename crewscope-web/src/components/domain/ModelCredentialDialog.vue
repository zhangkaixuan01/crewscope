<script setup lang="ts">
import { Building2, KeyRound, RotateCw, ShieldCheck, UserRound, X } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type {
  CreateModelConnectionInput,
  ModelConnectionOwnerType,
  ModelConnectionSummary,
  ModelProviderSummary,
} from '../../domains/model/types'
import BaseButton from '../base/BaseButton.vue'
import AuthPasswordField from '../auth/AuthPasswordField.vue'
import { useDirtyForm } from '../../composables/useDirtyForm'

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
const ownerType = ref<ModelConnectionOwnerType>('USER')
const providerKey = ref(props.providers.find(provider => provider.status === 'ACTIVE')?.key ?? '')
const region = ref('')
const expiration = ref('')
const apiKey = ref('')
const submitted = ref(false)
let attemptKey = ''
const nowForDateTimeInput = computed(() => {
  const date = new Date(); date.setMinutes(date.getMinutes() - date.getTimezoneOffset())
  return date.toISOString().slice(0, 16)
})
const dirtyForm = useDirtyForm(computed(() => ({ ownerType: ownerType.value, providerKey: providerKey.value, region: region.value, expiration: expiration.value, apiKey: apiKey.value })))

const activeProviders = computed(() => props.providers.filter(provider => provider.status === 'ACTIVE'))
const selectedProvider = computed(() => props.providers.find(provider => provider.key === providerKey.value) ?? null)
const availableRegions = computed(() => selectedProvider.value?.availableRegions ?? [])
/** Provider / Region 目录为空时创建表单没有可选项，禁用态要说明缺的是目录而不是输入。 */
const providerCatalogReason = computed(() => (activeProviders.value.length === 0 ? '当前作用域没有 ACTIVE 的 Provider，无法选择 Endpoint。' : ''))
const regionCatalogReason = computed(() => (availableRegions.value.length === 0 ? '所选 Provider 没有可用 Region，请先在平台侧开通。' : ''))
const expirationValid = computed(() => {
  if (!expiration.value) return true
  const timestamp = new Date(expiration.value).valueOf()
  return !Number.isNaN(timestamp) && timestamp > Date.now()
})
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
  dirtyForm.markDirty()
})

watch(() => [props.connection?.id, props.connection?.version, props.connection?.credentialVersion], () => {
  // A conflict refresh changes the authoritative command coordinates and therefore starts a new request.
  attemptKey = ''
})

onBeforeUnmount(clearSecret)

function canCreateOwner(value: ModelConnectionOwnerType): boolean {
  if (value === 'USER') return true
  if (value === 'TEAM') return props.canManageTeam
  return props.canManageOrganization
}

async function requestClose(): Promise<void> {
  if (props.submitting) return
  await dirtyForm.closeWithGuard(() => { clearSecret(); emit('close') })
}

function submit(): void {
  submitted.value = true
  // Trim only at the boundary; the untrimmed secret never leaves this form.
  apiKey.value = apiKey.value.trim()
  if (!valid.value) return
  dirtyForm.markClean()
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
          <button type="button" :disabled="!canManageTeam" aria-label="团队连接" aria-describedby="model-owner-team-reason" :class="{ active: ownerType === 'TEAM' }" @click="ownerType = 'TEAM'"><Building2 :size="18" /><span><strong>团队连接</strong><small id="model-owner-team-reason">{{ canManageTeam ? '团队成员可查看，Provider Manager 可管理。' : '需要 Team Provider 管理权限。' }}</small></span></button>
          <button v-if="canManageOrganization" type="button" :class="{ active: ownerType === 'ORGANIZATION' }" @click="ownerType = 'ORGANIZATION'"><ShieldCheck :size="18" /><span><strong>组织连接</strong><small>仅平台管理员可创建和管理。</small></span></button>
        </fieldset>

        <div v-if="mode === 'create'" class="field-grid">
          <label><span>Provider</span><select v-model="providerKey" aria-label="Provider" :disabled="submitting || activeProviders.length === 0" :aria-describedby="providerCatalogReason ? 'model-provider-reason' : undefined"><option v-if="activeProviders.length === 0" value="" disabled>暂无可用 Provider</option><option v-for="provider in activeProviders" :key="provider.key" :value="provider.key">{{ provider.displayName }}</option></select></label>
          <label><span>Region</span><select v-model="region" aria-label="Region" :disabled="submitting || availableRegions.length === 0" :aria-describedby="regionCatalogReason ? 'model-region-reason' : undefined"><option v-if="availableRegions.length === 0" value="" disabled>暂无可用 Region</option><option v-for="value in availableRegions" :key="value" :value="value">{{ value }}</option></select></label>
          <label class="wide"><span>凭证过期时间（可选）</span><input v-model="expiration" type="datetime-local" :min="nowForDateTimeInput" :disabled="submitting" /><small v-if="submitted && !expirationValid" class="field-error">过期时间必须是有效的未来时间。</small></label>
        </div>

        <p id="model-provider-reason" class="sr-only">{{ providerCatalogReason }}</p>
        <p id="model-region-reason" class="sr-only">{{ regionCatalogReason }}</p>
        <AuthPasswordField v-model="apiKey" label="API Key" name="model-api-key" id="model-api-key" autocomplete="new-password" placeholder="仅在本次提交中使用" :disabled="submitting" :maxlength="1048576" :error="submitted && !apiKey.trim() ? '请输入 API Key。' : undefined" focus-on-mount />
        <p class="secret-hint">浏览器不会保存、回显或记录此 Key；关闭或成功后立即清空。失败后可保留在当前表单中显式重试。</p>

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
.credential-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }.credential-dialog { width: min(680px, 100%); max-height: calc(100dvh - 40px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.credential-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.dialog-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.credential-dialog h2 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-lg); }.credential-dialog header div > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.credential-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.credential-content { display: grid; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-20) var(--cs-space-4); }.owner-picker { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--cs-space-8); padding: 0; border: 0; }.owner-picker legend { grid-column: 1 / -1; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.owner-picker button { display: grid; grid-template-columns: 20px 1fr; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }.owner-picker button.active { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: 0 0 0 2px var(--cs-ring-brand); }.owner-picker button:disabled { cursor: not-allowed; opacity: .52; }.owner-picker strong, .owner-picker small { display: block; }.owner-picker strong { font-size: var(--cs-text-sm); }.owner-picker small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.field-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--cs-space-12); }.field-grid label, .secret-field { display: grid; gap: var(--cs-space-8); }.field-grid label.wide { grid-column: 1 / -1; }.field-grid label > span, .secret-field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.field-grid select, .field-grid input, .secret-field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); }.secret-field input[aria-invalid='true'] { border-color: var(--cs-danger); }.secret-field small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.secret-boundary { display: flex; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-brand); }.secret-boundary svg { flex: 0 0 auto; }.secret-boundary strong, .secret-boundary span { display: block; }.secret-boundary strong { font-size: var(--cs-text-sm); }.secret-boundary span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }.credential-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }
@media (max-width: 767px) { .credential-backdrop { align-items: end; padding: 0; }.credential-dialog { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }.credential-dialog > header { padding: var(--cs-space-16) var(--cs-space-16); }.credential-content { padding-inline: var(--cs-space-16); }.owner-picker, .field-grid { grid-template-columns: 1fr; }.owner-picker legend, .field-grid label.wide { grid-column: 1; }.field-grid input, .field-grid select, .secret-field input { font-size: var(--cs-text-md); }.credential-dialog > footer { display: grid; padding-inline: var(--cs-space-16); }.credential-dialog > footer > * { width: 100%; } }
.secret-hint, .field-error { margin: 0; font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.secret-hint { color: var(--cs-text-muted); }.field-error { color: var(--cs-danger); }
.field-grid label > span, .owner-picker legend, .secret-field > span { font-size: var(--cs-text-sm); }.field-grid input, .field-grid select { font-size: var(--cs-text-base); }
</style>
