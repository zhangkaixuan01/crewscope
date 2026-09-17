<script setup lang="ts">
import { KeyRound, LogOut, MonitorSmartphone, ShieldCheck, UserRound } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import type { AccountProblem } from '../../domains/account/presentation'
import { platformRoleLabels } from '../../domains/principal/labels'
import { enumLabel } from '../../domains/shared/labels'
import type { AccountOperation } from '../../domains/account/store'
import type {
  AccountPasswordChangeInput,
  AccountProfile,
  AccountProfileUpdateInput,
  AccountSessionRevocationInput,
} from '../../domains/account/types'

const props = defineProps<{
  profile: AccountProfile
  commandPhase: 'idle' | 'pending' | 'success' | 'error'
  operation: AccountOperation | null
  problem: AccountProblem | null
  commandGeneration: number
  online: boolean
}>()

const emit = defineEmits<{
  saveProfile: [input: AccountProfileUpdateInput]
  changePassword: [input: AccountPasswordChangeInput]
  revokeSessions: [input: AccountSessionRevocationInput]
}>()

const editProfile = ref(false)
const editPassword = ref(false)
const revokeOpen = ref(false)
const username = ref('')
const email = ref('')
const displayName = ref('')
const profilePassword = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const revokePassword = ref('')
const fieldProblem = ref('')
const errorSummary = ref<HTMLElement | null>(null)
const pageHeading = ref<HTMLElement | null>(null)
const revokeDialog = ref<HTMLElement | null>(null)
const revokeInput = ref<HTMLInputElement | null>(null)
const revokeError = ref<HTMLElement | null>(null)
const revokeTrigger = ref<HTMLElement | null>(null)

const pending = computed(() => props.commandPhase === 'pending')
const avatar = computed(() => Array.from(props.profile.displayName.trim())[0] ?? '?')
const identifiersChanged = computed(() => username.value.trim() !== props.profile.username
  || email.value.trim() !== props.profile.email)

watch(() => props.profile, synchronize, { immediate: true })
onMounted(() => nextTick(() => (props.problem || !props.online ? errorSummary.value : pageHeading.value)?.focus()))
onBeforeUnmount(clearSensitiveInputs)
watch(() => props.commandGeneration, async () => {
  if (props.commandPhase === 'error') {
    if (props.operation === 'profile') profilePassword.value = ''
    if (props.operation === 'password') currentPassword.value = ''
    if (props.operation === 'sessions') revokePassword.value = ''
    await nextTick(() => (props.operation === 'sessions' && revokeOpen.value ? revokeError.value : errorSummary.value)?.focus())
    return
  }
  if (props.commandPhase !== 'success') return
  if (props.operation === 'profile') {
    editProfile.value = false
    profilePassword.value = ''
    synchronize()
  }
  if (props.operation === 'password') {
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
  }
  if (props.operation === 'sessions') closeRevoke()
})

function synchronize(): void {
  username.value = props.profile.username
  email.value = props.profile.email
  displayName.value = props.profile.displayName
}

/** Explicitly drops every password proof when the account route leaves the component tree. */
function clearSensitiveInputs(): void {
  profilePassword.value = ''
  currentPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  revokePassword.value = ''
}

function cancelProfile(): void {
  synchronize()
  profilePassword.value = ''
  fieldProblem.value = ''
  editProfile.value = false
}

function submitProfile(): void {
  fieldProblem.value = ''
  const nextUsername = username.value.trim()
  const nextEmail = email.value.trim()
  const nextDisplayName = displayName.value.trim()
  if (nextUsername.length < 3 || nextUsername.length > 64) return invalid('用户名需要包含 3 至 64 个字符')
  if (!/^\S+@\S+\.\S+$/.test(nextEmail) || nextEmail.length > 254) return invalid('请输入有效的邮箱地址')
  if (!nextDisplayName || nextDisplayName.length > 200) return invalid('展示名称需要包含 1 至 200 个字符')
  if (identifiersChanged.value && !profilePassword.value) return invalid('修改用户名或邮箱需要验证当前密码')
  const input: AccountProfileUpdateInput = {}
  if (nextUsername !== props.profile.username) input.username = nextUsername
  if (nextEmail !== props.profile.email) input.email = nextEmail
  if (nextDisplayName !== props.profile.displayName) input.displayName = nextDisplayName
  if (Object.keys(input).length === 0) return invalid('账号资料没有变化')
  if (identifiersChanged.value) {
    input.currentPassword = profilePassword.value
    input.securityVersion = props.profile.securityVersion
  }
  emit('saveProfile', input)
}

function submitPassword(): void {
  fieldProblem.value = ''
  if (!currentPassword.value) return invalid('请输入当前密码')
  const codePoints = Array.from(newPassword.value).length
  const bytes = new TextEncoder().encode(newPassword.value).length
  if (codePoints < 12 || codePoints > 128 || bytes > 512) return invalid('新密码需要包含 12 至 128 个字符，且不超过 512 字节')
  if (newPassword.value === currentPassword.value) return invalid('新密码不能与当前密码相同')
  if (newPassword.value !== confirmPassword.value) return invalid('两次输入的新密码不一致')
  emit('changePassword', {
    currentPassword: currentPassword.value,
    newPassword: newPassword.value,
    securityVersion: props.profile.securityVersion,
  })
}

function cancelPassword(): void {
  currentPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  fieldProblem.value = ''
  editPassword.value = false
}

async function openRevoke(event: MouseEvent): Promise<void> {
  fieldProblem.value = ''
  if (event.currentTarget instanceof HTMLElement) revokeTrigger.value = event.currentTarget
  revokeOpen.value = true
  await nextTick(() => revokeInput.value?.focus())
}

function closeRevoke(): void {
  if (pending.value) return
  revokePassword.value = ''
  revokeOpen.value = false
  void nextTick(() => revokeTrigger.value?.focus())
}

function submitRevoke(): void {
  fieldProblem.value = ''
  if (!revokePassword.value) return invalid('请输入当前密码以确认退出全部设备')
  emit('revokeSessions', {
    currentPassword: revokePassword.value,
    securityVersion: props.profile.securityVersion,
  })
}

function invalid(message: string): void {
  fieldProblem.value = message
  void nextTick(() => (revokeOpen.value ? revokeError.value : errorSummary.value)?.focus())
}

function trapDialog(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeRevoke()
    return
  }
  if (event.key !== 'Tab' || !revokeDialog.value) return
  const items = Array.from(revokeDialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)'))
  if (items.length === 0) return
  const first = items[0]!
  const last = items.at(-1)!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
}
</script>

<template>
  <div class="account-workspace">
    <header class="account-heading">
      <div><p class="eyebrow">Account settings</p><h2 ref="pageHeading" tabindex="-1">身份与安全</h2><p>管理个人资料、密码和当前账号的登录会话。</p></div>
      <span class="account-avatar" :aria-label="`${profile.displayName} 的头像回退`">{{ avatar }}</span>
    </header>

    <div class="account-layout">
      <nav aria-label="账号设置导航">
        <a href="#profile"><UserRound :size="16" />个人资料</a>
        <a href="#security"><KeyRound :size="16" />密码与安全</a>
        <a href="#sessions"><MonitorSmartphone :size="16" />登录会话</a>
      </nav>

      <div class="account-sections">
        <div
          v-if="(fieldProblem || problem || !online) && !revokeOpen"
          ref="errorSummary"
          class="account-alert"
          :class="{ 'account-alert--warning': problem?.tone === 'warning' || !online }"
          role="alert"
          tabindex="-1"
        >
          <strong>{{ fieldProblem ? '请检查输入' : !online ? '当前处于离线状态' : problem?.title }}</strong>
          <span>{{ fieldProblem || (!online ? '账号安全操作需要联网，请恢复网络后重试。' : problem?.message) }}</span>
        </div>

        <section id="profile" class="account-panel" aria-labelledby="profile-title">
          <header><div><h3 id="profile-title">个人资料</h3><p>团队成员和 Agent 会看到展示名称。</p></div><BaseButton v-if="!editProfile" variant="secondary" size="small" :disabled="!online" @click="editProfile = true">编辑资料</BaseButton></header>
          <form v-if="editProfile" class="account-form" @submit.prevent="submitProfile">
            <label>展示名称<input v-model="displayName" name="displayName" autocomplete="name" maxlength="200" :disabled="pending"></label>
            <label>用户名<input v-model="username" name="username" autocomplete="username" minlength="3" maxlength="64" :disabled="pending"></label>
            <label class="account-form__wide">邮箱<input v-model="email" name="email" type="email" autocomplete="email" maxlength="254" :disabled="pending"></label>
            <label v-if="identifiersChanged" class="account-form__wide">当前密码<input v-model="profilePassword" name="profileCurrentPassword" type="password" autocomplete="current-password" maxlength="512" :disabled="pending"></label>
            <div class="form-actions account-form__wide"><BaseButton type="submit" :loading="pending && operation === 'profile'" :disabled="!online">保存资料</BaseButton><BaseButton variant="ghost" :disabled="pending" @click="cancelProfile">取消</BaseButton></div>
          </form>
          <dl v-else class="profile-facts">
            <div><dt>展示名称</dt><dd>{{ profile.displayName }}</dd></div>
            <div><dt>用户名</dt><dd>{{ profile.username }}</dd></div>
            <div><dt>邮箱</dt><dd>{{ profile.email }}</dd></div>
            <div><dt>平台角色</dt><dd>{{ enumLabel(profile.platformRole, platformRoleLabels) }}</dd></div>
          </dl>
        </section>

        <section id="security" class="account-panel" aria-labelledby="security-title">
          <header><div><h3 id="security-title">密码与安全</h3><p>修改密码会撤销当前账号的全部浏览器会话。</p></div><BaseButton v-if="!editPassword" variant="secondary" size="small" :disabled="!online" @click="editPassword = true"><template #icon><KeyRound :size="15" /></template>修改密码</BaseButton></header>
          <form v-if="editPassword" class="account-form account-form--password" @submit.prevent="submitPassword">
            <label>当前密码<input v-model="currentPassword" name="currentPassword" type="password" autocomplete="current-password" maxlength="512" :disabled="pending"></label>
            <label>新密码<input v-model="newPassword" name="newPassword" type="password" autocomplete="new-password" minlength="12" maxlength="128" :disabled="pending"><small>12–128 个字符，UTF-8 不超过 512 字节</small></label>
            <label>确认新密码<input v-model="confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" minlength="12" maxlength="128" :disabled="pending"></label>
            <div class="form-actions"><BaseButton type="submit" :loading="pending && operation === 'password'" :disabled="!online">修改密码并重新登录</BaseButton><BaseButton variant="ghost" :disabled="pending" @click="cancelPassword">取消</BaseButton></div>
          </form>
          <p v-else class="section-fact"><ShieldCheck :size="16" />账号安全状态最近更新于 {{ formatDate(profile.updatedAt) }}</p>
        </section>

        <section id="sessions" class="account-panel" aria-labelledby="sessions-title">
          <header><div><h3 id="sessions-title">登录会话</h3><p>退出全部设备会同时撤销当前浏览器和其他设备。</p></div><BaseButton variant="danger" size="small" :disabled="!online" @click="openRevoke"><template #icon><LogOut :size="15" /></template>退出全部设备</BaseButton></header>
          <p class="section-fact"><MonitorSmartphone :size="16" />当前浏览器 Session · 活跃</p>
        </section>
      </div>
    </div>

    <div v-if="revokeOpen" class="dialog-backdrop" @mousedown.self="closeRevoke">
      <section ref="revokeDialog" class="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby="revoke-title" @keydown="trapDialog">
        <div class="confirmation-dialog__icon"><LogOut :size="20" /></div>
        <div><p class="eyebrow">Security confirmation</p><h3 id="revoke-title">退出全部设备？</h3><p>所有浏览器 Session 都会失效，包括当前设备。之后需要重新登录。</p></div>
        <div v-if="fieldProblem || (operation === 'sessions' && problem)" ref="revokeError" class="account-alert" role="alert" tabindex="-1"><strong>{{ fieldProblem ? '请检查输入' : problem?.title }}</strong><span>{{ fieldProblem || problem?.message }}</span></div>
        <label>当前密码<input ref="revokeInput" v-model="revokePassword" name="revokeCurrentPassword" type="password" autocomplete="current-password" maxlength="512" :disabled="pending"></label>
        <div class="form-actions"><BaseButton variant="danger" :loading="pending && operation === 'sessions'" @click="submitRevoke">确认退出全部设备</BaseButton><BaseButton variant="ghost" :disabled="pending" @click="closeRevoke">取消</BaseButton></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.account-workspace { display: grid; width: min(1040px, 100%); margin: 0 auto; gap: var(--cs-space-24); }
.account-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-24); padding: var(--cs-space-16) var(--cs-space-8) var(--cs-space-4); }
.account-heading h2 { margin-bottom: var(--cs-space-8); font-family: var(--cs-font-display); font-size: var(--cs-text-2xl); font-weight: var(--cs-weight-semibold); }
.account-heading p:last-child { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-base); }
.account-avatar { display: grid; width: 56px; height: 56px; flex: 0 0 auto; place-items: center; border-radius: 50%; background: var(--cs-brand-600); color: var(--cs-text-on-dark); font-size: var(--cs-text-lg); font-weight: var(--cs-weight-semibold); }
.account-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: var(--cs-space-20); }
.account-layout > nav { display: grid; height: fit-content; gap: var(--cs-space-4); position: sticky; top: 76px; }
.account-layout > nav a { display: flex; min-height: 40px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border-radius: 9px; color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.account-layout > nav a:hover, .account-layout > nav a:focus-visible { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); }
.account-sections { display: grid; min-width: 0; gap: var(--cs-space-16); }
.account-panel { scroll-margin-top: var(--cs-space-16); padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); }
.account-panel > header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); }
.account-panel h3 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-base); }
.account-panel header p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.profile-facts { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-16); padding-top: var(--cs-space-20); margin: var(--cs-space-20) 0 0; border-top: 1px solid var(--cs-border); }
.profile-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.profile-facts dd { margin: var(--cs-space-4) 0 0; overflow-wrap: anywhere; font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.section-fact { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-20) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.account-form { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-16); padding-top: var(--cs-space-20); margin-top: var(--cs-space-20); border-top: 1px solid var(--cs-border); }
.account-form--password { grid-template-columns: 1fr; }
.account-form label, .confirmation-dialog label { display: grid; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.account-form input, .confirmation-dialog input { width: 100%; min-width: 0; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: 9px; outline: 0; background: var(--cs-surface); font-size: var(--cs-text-base); font-weight: var(--cs-weight-medium); }
.account-form input:focus, .confirmation-dialog input:focus { border-color: var(--cs-border-accent-strong); box-shadow: var(--cs-focus-ring); }
.account-form small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); }
.account-form__wide { grid-column: 1 / -1; }.form-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.account-alert { display: grid; gap: var(--cs-space-2); padding: var(--cs-space-12) var(--cs-space-16); border: 1px solid var(--cs-danger-border); border-radius: 10px; background: var(--cs-danger-soft); color: var(--cs-danger); }
.account-alert--warning { border-color: var(--cs-warning-border); background: var(--cs-warning-soft); color: var(--cs-warning); }
.account-alert strong { font-size: var(--cs-text-sm); }.account-alert span { font-size: var(--cs-text-sm); }
.dialog-backdrop { position: fixed; z-index: var(--cs-z-dialog); inset: 0; display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); }
.confirmation-dialog { display: grid; width: min(440px, 100%); gap: var(--cs-space-16); padding: var(--cs-space-24); border-radius: 16px; background: var(--cs-surface); box-shadow: var(--cs-shadow-modal); }
.confirmation-dialog__icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-danger-soft); color: var(--cs-danger); }
.confirmation-dialog h3 { margin-bottom: var(--cs-space-8); font-size: var(--cs-text-lg); }.confirmation-dialog p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
@media (max-width: 767px) {
  .account-heading { align-items: flex-start; padding-inline: var(--cs-space-2); }.account-heading h2 { font-size: var(--cs-text-xl); }.account-avatar { width: 46px; height: 46px; }
  .account-layout { grid-template-columns: 1fr; }.account-layout > nav { position: static; grid-template-columns: repeat(3, 1fr); overflow-x: auto; }
  .account-layout > nav a { justify-content: center; padding-inline: var(--cs-space-8); white-space: nowrap; }
  .account-panel { padding: var(--cs-space-16); }.account-panel > header { align-items: stretch; flex-direction: column; }.account-panel > header button { width: 100%; }
  .account-form, .profile-facts { grid-template-columns: 1fr; }.account-form__wide { grid-column: auto; }.form-actions { display: grid; grid-template-columns: 1fr; }
}
</style>
