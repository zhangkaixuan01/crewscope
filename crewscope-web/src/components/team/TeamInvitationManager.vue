<script setup lang="ts">
import { Check, Clipboard, Clock3, Link2, Mail, ShieldCheck, UserRoundPlus, X } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useNetworkStatus } from '../../app/network'
import { useAuthStore } from '../../domains/identity/store'
import { offlineInvitationProblem } from '../../domains/invitation/presentation'
import { useInvitationStore } from '../../domains/invitation/store'
import type { InvitationRole, TeamInvitationSummary } from '../../domains/invitation/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{ organizationId: string, teamId: string }>()

const store = useInvitationStore()
const authStore = useAuthStore()
const online = useNetworkStatus()
const createOpen = ref(false)
const targetEmail = ref('')
const targetRole = ref<InvitationRole>('MEMBER')
const expiresInMinutes = ref(10_080)
const fieldProblem = ref('')
const shareLink = ref('')
const copyState = ref<'idle' | 'copied' | 'error'>('idle')
const createTrigger = ref<HTMLElement | null>(null)
const emailInput = ref<HTMLInputElement | null>(null)
const errorSummary = ref<HTMLElement | null>(null)
const shareInput = ref<HTMLInputElement | null>(null)
const managerHeading = ref<HTMLElement | null>(null)
const revokeTarget = ref<TeamInvitationSummary | null>(null)
const revokeTrigger = ref<HTMLElement | null>(null)
const revokeDialog = ref<HTMLElement | null>(null)
const revokeError = ref<HTMLElement | null>(null)

const commandPending = computed(() => store.state.commandPhase === 'pending')
const pageProblem = computed(() => !online.value
  ? offlineInvitationProblem()
  : store.state.commandProblem ?? store.state.managementProblem)

watch(() => [props.organizationId, props.teamId] as const, async ([organizationId, teamId]) => {
  store.resetManagement()
  clearOneTimeLink()
  closeCreate(false)
  revokeTarget.value = null
  if (organizationId && teamId) await store.loadManagement(organizationId, teamId)
}, { immediate: true })

watch(() => store.state.commandGeneration, async () => {
  if (store.state.commandPhase !== 'error') return
  await nextTick()
  ;(revokeTarget.value ? revokeError.value : errorSummary.value)?.focus()
})

onBeforeUnmount(() => {
  clearOneTimeLink()
  store.resetManagement()
})

async function openCreate(event: MouseEvent): Promise<void> {
  if (event.currentTarget instanceof HTMLElement) createTrigger.value = event.currentTarget
  createOpen.value = true
  store.clearCommand()
  await nextTick(() => emailInput.value?.focus())
}

function closeCreate(restore = true): void {
  if (commandPending.value) return
  createOpen.value = false
  targetEmail.value = ''
  targetRole.value = 'MEMBER'
  expiresInMinutes.value = 10_080
  fieldProblem.value = ''
  clearOneTimeLink()
  store.clearCommand()
  if (restore) void nextTick(() => createTrigger.value?.focus())
}

async function createInvitation(): Promise<void> {
  fieldProblem.value = ''
  copyState.value = 'idle'
  const email = targetEmail.value.trim()
  if (email && (!/^\S+@\S+\.\S+$/.test(email) || email.length > 254)) return invalid('请输入有效且不超过 254 个字符的邮箱')
  if (!Number.isInteger(expiresInMinutes.value) || expiresInMinutes.value < 1 || expiresInMinutes.value > 43_200) {
    return invalid('邀请有效期需要在 1 分钟至 30 天之间')
  }
  if (!online.value) return invalid('恢复网络后再创建邀请')
  const csrf = authStore.state.session?.csrf
  if (!csrf) return invalid('当前安全会话不可用，请重新登录')
  const result = await store.createInvitation(props.organizationId, props.teamId, {
    ...(email ? { targetEmail: email } : {}), targetRole: targetRole.value,
    expiresInMinutes: expiresInMinutes.value,
  }, csrf)
  if (!result) return
  targetEmail.value = ''
  if (result.token) {
    shareLink.value = invitationUrl(result.token)
    await nextTick(() => shareInput.value?.focus())
  } else {
    await store.loadManagement(props.organizationId, props.teamId)
    fieldProblem.value = '邀请已经创建，但一次性链接无法再次显示。请撤销后创建新邀请。'
    await nextTick(() => errorSummary.value?.focus())
  }
}

async function copyLink(): Promise<void> {
  if (!shareLink.value) return
  try {
    await navigator.clipboard.writeText(shareLink.value)
    copyState.value = 'copied'
  } catch {
    copyState.value = 'error'
    await nextTick(() => errorSummary.value?.focus())
  }
}

async function openRevoke(invitation: TeamInvitationSummary, event: MouseEvent): Promise<void> {
  if (event.currentTarget instanceof HTMLElement) revokeTrigger.value = event.currentTarget
  revokeTarget.value = invitation
  store.clearCommand()
  await nextTick(() => revokeDialog.value?.querySelector<HTMLElement>('button')?.focus())
}

function closeRevoke(afterRemoval = false): void {
  if (commandPending.value) return
  revokeTarget.value = null
  store.clearCommand()
  void nextTick(() => (afterRemoval ? managerHeading.value : revokeTrigger.value)?.focus())
}

async function confirmRevoke(): Promise<void> {
  if (!revokeTarget.value || !online.value) return
  const csrf = authStore.state.session?.csrf
  if (!csrf) return
  if (await store.revokeInvitation(props.organizationId, props.teamId, revokeTarget.value.id, csrf)) {
    closeRevoke(true)
  } else if (store.state.commandProblem?.code === 'invitation_not_pending') {
    await store.loadManagement(props.organizationId, props.teamId)
  }
}

function trapDialog(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeRevoke()
    return
  }
  if (event.key !== 'Tab' || !revokeDialog.value) return
  const items = [...revokeDialog.value.querySelectorAll<HTMLElement>('button:not(:disabled)')]
  if (!items.length) return
  const first = items[0]!
  const last = items.at(-1)!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault(); last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus()
  }
}

function invalid(message: string): void {
  fieldProblem.value = message
  void nextTick(() => errorSummary.value?.focus())
}

function invitationUrl(token: string): string {
  const url = new URL('/invite', window.location.origin)
  url.hash = new URLSearchParams({ token }).toString()
  return url.toString()
}

function clearOneTimeLink(): void {
  shareLink.value = ''
  copyState.value = 'idle'
}

function roleLabel(role: InvitationRole): string {
  return { TEAM_ADMIN: 'Team Admin', TEAM_LEAD: 'Team Lead', MEMBER: 'Member', AUDITOR: 'Auditor' }[role]
}

function statusTone(status: TeamInvitationSummary['status']): 'success' | 'warning' | 'danger' | 'neutral' {
  return status === 'ACCEPTED' ? 'success' : status === 'PENDING' ? 'warning' : status === 'REVOKED' ? 'danger' : 'neutral'
}

function statusLabel(status: TeamInvitationSummary['status']): string {
  return { PENDING: '待接受', ACCEPTED: '已接受', REVOKED: '已撤销', EXPIRED: '已过期' }[status]
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <section class="panel invitation-manager" aria-labelledby="invitation-manager-title">
    <header class="invitation-manager__heading">
      <div><p class="eyebrow">Team invitations</p><h2 id="invitation-manager-title" ref="managerHeading" tabindex="-1">团队邀请</h2><p>通过一次性链接邀请新成员，接受后由服务端建立 Membership 和目标角色。</p></div>
      <BaseButton v-if="!createOpen" ref="createTrigger" size="small" :disabled="!online" @click="openCreate"><UserRoundPlus :size="14" />创建邀请</BaseButton>
    </header>

    <div
      v-if="fieldProblem || copyState === 'error' || pageProblem"
      ref="errorSummary"
      class="invitation-alert"
      role="alert"
      tabindex="-1"
    >
      <strong>{{ fieldProblem ? '请检查邀请' : copyState === 'error' ? '没有复制邀请链接' : pageProblem?.title }}</strong>
      <span>{{ fieldProblem || (copyState === 'error' ? '请手动选择一次性链接并复制。' : pageProblem?.message) }}</span>
    </div>

    <form v-if="createOpen" class="invitation-form" @submit.prevent="createInvitation">
      <div class="invitation-form__title"><span><Mail :size="17" /></span><div><h3>创建一次性邀请</h3><p>邮箱留空时，任何获得链接的账号都可以接受。</p></div><button type="button" aria-label="关闭创建邀请" :disabled="commandPending" @click="closeCreate()"><X :size="16" /></button></div>
      <label>目标邮箱（可选）<input ref="emailInput" v-model="targetEmail" name="invitationEmail" type="email" autocomplete="off" maxlength="254" :disabled="commandPending" placeholder="member@example.com"></label>
      <label>加入角色<select v-model="targetRole" name="invitationRole" :disabled="commandPending"><option value="MEMBER">Member</option><option value="TEAM_LEAD">Team Lead</option><option value="TEAM_ADMIN">Team Admin</option><option value="AUDITOR">Auditor</option></select></label>
      <label>有效期<select v-model.number="expiresInMinutes" name="invitationExpiry" :disabled="commandPending"><option :value="1_440">1 天</option><option :value="10_080">7 天</option><option :value="20_160">14 天</option><option :value="43_200">30 天</option></select></label>
      <div class="invitation-form__actions"><BaseButton type="submit" :loading="commandPending && store.state.commandKind === 'create'" :disabled="!online">创建邀请链接</BaseButton><BaseButton variant="ghost" :disabled="commandPending" @click="closeCreate()">取消</BaseButton></div>

      <div v-if="shareLink" class="one-time-link" role="status">
        <div><Link2 :size="17" /><span><strong>一次性链接已生成</strong><small>离开或关闭后无法再次查看，请立即复制。</small></span></div>
        <input ref="shareInput" :value="shareLink" readonly aria-label="一次性邀请链接" @focus="($event.target as HTMLInputElement).select()">
        <BaseButton type="button" variant="secondary" @click="copyLink"><template #icon><Check v-if="copyState === 'copied'" :size="15" /><Clipboard v-else :size="15" /></template>{{ copyState === 'copied' ? '已复制' : '复制链接' }}</BaseButton>
      </div>
    </form>

    <StatePanel v-if="store.state.managementPhase === 'idle' || (store.state.managementPhase === 'loading' && !store.state.items.length)" state="loading" />
    <StatePanel v-else-if="store.state.managementPhase === 'error' && !store.state.items.length" state="error" :description="store.state.managementProblem?.message" @retry="store.loadManagement(organizationId, teamId)" />
    <StatePanel v-else-if="!store.state.items.length" state="empty" title="还没有团队邀请" description="创建一次性链接后，邀请状态会显示在这里。" />
    <ul v-else class="invitation-list" aria-label="团队邀请列表">
      <li v-for="invitation in store.state.items" :key="invitation.id" class="invitation-row">
        <span class="invitation-row__icon"><Mail :size="16" /></span>
        <div class="invitation-row__identity"><strong>{{ invitation.targetEmail ?? '不限邮箱' }}</strong><small>{{ roleLabel(invitation.targetRole) }} · 创建于 {{ formatDate(invitation.createdAt) }}</small></div>
        <div class="invitation-row__expiry"><Clock3 :size="13" /><span>{{ invitation.status === 'PENDING' ? `有效至 ${formatDate(invitation.expiresAt)}` : invitation.resolvedAt ? formatDate(invitation.resolvedAt) : '已结束' }}</span></div>
        <StatusBadge :tone="statusTone(invitation.status)" dot>{{ statusLabel(invitation.status) }}</StatusBadge>
        <BaseButton v-if="invitation.status === 'PENDING'" variant="ghost" size="small" :disabled="!online" @click="openRevoke(invitation, $event)">撤销</BaseButton>
      </li>
      <BaseButton v-if="store.state.nextCursor" class="load-more" variant="secondary" :loading="store.state.managementPhase === 'loading'" @click="store.loadManagement(organizationId, teamId, true)">加载更多</BaseButton>
    </ul>

    <footer class="invitation-manager__note"><ShieldCheck :size="15" /><span>邀请链接只显示一次；列表和日志不保存明文 Token。</span></footer>

    <div v-if="revokeTarget" class="dialog-backdrop" @mousedown.self="closeRevoke()">
      <section ref="revokeDialog" class="revoke-dialog" role="dialog" aria-modal="true" aria-labelledby="revoke-invitation-title" @keydown="trapDialog">
        <p class="eyebrow">Invitation control</p>
        <h3 id="revoke-invitation-title">撤销这个邀请？</h3>
        <p>{{ revokeTarget.targetEmail ?? '不限邮箱' }} 将不能再使用该链接加入 Team。这个操作不会移除已经加入的成员。</p>
        <div v-if="store.state.commandPhase === 'error'" ref="revokeError" class="invitation-alert" role="alert" tabindex="-1"><strong>{{ store.state.commandProblem?.title }}</strong><span>{{ store.state.commandProblem?.message }}</span></div>
        <div class="revoke-dialog__actions"><BaseButton variant="ghost" :disabled="commandPending" @click="closeRevoke()">取消</BaseButton><BaseButton variant="danger" :loading="commandPending" :disabled="!online" @click="confirmRevoke">确认撤销</BaseButton></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.invitation-manager { display: grid; gap: 17px; padding: 20px; }.invitation-manager__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.invitation-manager__heading h2 { margin-bottom: 4px; font-size: 15px; }.invitation-manager__heading p:last-child { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.invitation-alert { display: grid; gap: 3px; padding: 11px 13px; border: 1px solid #e6cea3; border-radius: 9px; background: var(--cs-warning-soft); color: #79551d; }.invitation-alert strong { font-size: 10px; }.invitation-alert span { font-size: 9px; }
.invitation-form { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(130px, .7fr) minmax(120px, .6fr); gap: 14px; padding: 17px; border: 1px solid var(--cs-brand-200); border-radius: 13px; background: var(--cs-brand-50); }.invitation-form__title { display: grid; grid-column: 1 / -1; grid-template-columns: 36px 1fr 28px; gap: 10px; }.invitation-form__title > span { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 10px; background: white; color: var(--cs-brand-700); }.invitation-form__title h3 { margin-bottom: 2px; font-size: 12px; }.invitation-form__title p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }.invitation-form__title > button { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 7px; background: transparent; cursor: pointer; }.invitation-form label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 720; }.invitation-form input, .invitation-form select, .one-time-link input { width: 100%; min-width: 0; min-height: 38px; padding: 0 10px; border: 1px solid var(--cs-border-strong); border-radius: 8px; outline: 0; background: white; font-size: 10px; }.invitation-form input:focus, .invitation-form select:focus, .one-time-link input:focus { border-color: var(--cs-brand-400); box-shadow: var(--cs-focus-ring); }.invitation-form__actions { display: flex; grid-column: 1 / -1; gap: 8px; }
.one-time-link { display: grid; grid-column: 1 / -1; grid-template-columns: 1fr auto; gap: 9px; padding: 13px; border: 1px solid var(--cs-brand-200); border-radius: 10px; background: white; }.one-time-link > div { display: flex; grid-column: 1 / -1; align-items: center; gap: 8px; color: var(--cs-brand-700); }.one-time-link span, .one-time-link strong, .one-time-link small { display: block; }.one-time-link strong { font-size: 10px; }.one-time-link small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }
.invitation-list { display: grid; overflow: hidden; padding: 0; margin: 0; border: 1px solid var(--cs-border); border-radius: 11px; list-style: none; }.invitation-row { display: grid; min-height: 64px; grid-template-columns: 34px minmax(180px, 1.2fr) minmax(190px, 1fr) auto 60px; align-items: center; gap: 11px; padding: 10px 13px; border-bottom: 1px solid var(--cs-border); }.invitation-row:last-of-type { border-bottom: 0; }.invitation-row__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.invitation-row__identity strong, .invitation-row__identity small { display: block; overflow-wrap: anywhere; }.invitation-row__identity strong { font-size: 10px; }.invitation-row__identity small { margin-top: 3px; color: var(--cs-text-muted); font-size: 8px; }.invitation-row__expiry { display: flex; align-items: center; gap: 5px; color: var(--cs-text-muted); font-size: 8px; }.load-more { justify-self: center; margin: 10px; }.invitation-manager__note { display: flex; align-items: center; gap: 7px; color: var(--cs-text-muted); font-size: 8px; }
.dialog-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; place-items: center; padding: 18px; background: rgb(15 24 19 / 42%); }.revoke-dialog { display: grid; width: min(420px, 100%); gap: 13px; padding: 23px; border-radius: 15px; background: white; box-shadow: 0 24px 80px rgb(0 0 0 / 20%); }.revoke-dialog h3 { font-size: 18px; }.revoke-dialog > p:not(.eyebrow) { margin: 0; color: var(--cs-text-muted); font-size: 10px; line-height: 1.6; }.revoke-dialog__actions { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 850px) { .invitation-row { grid-template-columns: 34px 1fr auto; }.invitation-row__expiry { grid-column: 2; }.invitation-row > :last-child { grid-column: 3; grid-row: 2; }.invitation-form { grid-template-columns: 1fr 1fr; }.invitation-form label:first-of-type { grid-column: 1 / -1; } }
@media (max-width: 767px) { .invitation-manager { padding: 16px; }.invitation-manager__heading { align-items: stretch; flex-direction: column; }.invitation-manager__heading > button { width: 100%; }.invitation-form { grid-template-columns: 1fr; }.invitation-form label:first-of-type, .invitation-form__title, .invitation-form__actions, .one-time-link { grid-column: auto; }.one-time-link { grid-template-columns: 1fr; }.one-time-link > div { grid-column: auto; }.invitation-row { grid-template-columns: 32px minmax(0, 1fr) auto; padding-inline: 10px; }.invitation-row__expiry { grid-column: 2 / -1; }.revoke-dialog__actions { display: grid; grid-template-columns: 1fr; } }
</style>
