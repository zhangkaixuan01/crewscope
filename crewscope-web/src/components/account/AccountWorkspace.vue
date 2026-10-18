<script setup lang="ts">
import { KeyRound, LogOut, MonitorSmartphone, ShieldCheck, UserRound } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import BaseTable from '../base/BaseTable.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { AccountProblem } from '../../domains/account/presentation'
import { loginIdentityProviderLabel, loginIdentityStatusLabels } from '../../domains/identity/labels'
import { platformRoleLabels } from '../../domains/principal/labels'
import { enumLabel } from '../../domains/shared/labels'
import type { AccountOperation } from '../../domains/account/store'
import type { AuthLoginIdentity, BrowserSessionSummary } from '../../domains/identity/types'
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
  sessions: BrowserSessionSummary[]
  sessionsPhase: 'loading' | 'ready' | 'error'
  deletingSessionId: string | null
  sessionsProblem: string | null
  loginIdentities: AuthLoginIdentity[]
}>()

const emit = defineEmits<{
  saveProfile: [input: AccountProfileUpdateInput]
  changePassword: [input: AccountPasswordChangeInput]
  revokeSessions: [input: AccountSessionRevocationInput]
  revokeSession: [session: BrowserSessionSummary]
  retrySessions: []
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
const revokeError = ref<HTMLElement | null>(null)

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

function openRevoke(): void {
  fieldProblem.value = ''
  revokeOpen.value = true
}

function closeRevoke(): void {
  if (pending.value) return
  revokePassword.value = ''
  revokeOpen.value = false
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

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value))
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

/** Deleting the current browser session is a logout equivalent (M12-I01 §2), so it asks first. */
const signOutTarget = ref<BrowserSessionSummary | null>(null)

function sessionBusy(session: BrowserSessionSummary): boolean {
  return props.deletingSessionId === session.sessionId
}

function askRevokeSession(session: BrowserSessionSummary): void {
  if (!session.current) {
    emit('revokeSession', session)
    return
  }
  signOutTarget.value = session
}

function closeSignOut(): void {
  if (props.deletingSessionId) return
  signOutTarget.value = null
}

function confirmSignOut(): void {
  if (signOutTarget.value) emit('revokeSession', signOutTarget.value)
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
          <div v-if="loginIdentities.length" class="login-identities">
            <h4>登录方式</h4>
            <ul>
              <li v-for="identity in loginIdentities" :key="identity.provider" :data-testid="`login-identity-${identity.provider}`">
                <span class="login-identities__provider">{{ loginIdentityProviderLabel(identity.provider) }}</span>
                <span :class="['login-identities__status', { 'login-identities__status--active': identity.status === 'ACTIVE' }]">{{ enumLabel(identity.status, loginIdentityStatusLabels) }}</span>
                <span class="login-identities__time">{{ identity.lastAuthenticatedAt ? `最近登录 ${formatDateTime(identity.lastAuthenticatedAt)}` : '尚未在本部署登录' }}</span>
              </li>
            </ul>
          </div>
        </section>

        <section id="sessions" class="account-panel" aria-labelledby="sessions-title">
          <header><div><h3 id="sessions-title">登录会话</h3><p>这里列出本账号的全部浏览器会话；撤销其他设备不会影响当前登录。</p></div><BaseButton variant="danger" size="small" :disabled="!online" @click="openRevoke"><template #icon><LogOut :size="15" /></template>退出全部设备</BaseButton></header>
          <p v-if="sessionsProblem" class="account-alert account-alert--warning" role="alert"><strong>会话操作未完成</strong><span>{{ sessionsProblem }}</span></p>
          <StatePanel v-if="sessionsPhase === 'loading'" state="loading" />
          <StatePanel v-else-if="sessionsPhase === 'error'" state="error" description="暂时无法读取登录会话。" @retry="emit('retrySessions')" />
          <p v-else-if="sessions.length === 0" class="section-fact"><MonitorSmartphone :size="16" />当前没有活跃的浏览器会话。</p>
          <BaseTable v-else bare class="sessions-table" caption="本账号的浏览器会话，最近活动在前">
            <thead><tr><th scope="col">设备</th><th scope="col">最近活动</th><th scope="col">登录时间</th><th scope="col"><span class="sr-only">操作</span></th></tr></thead>
            <tbody>
              <tr v-for="session in sessions" :key="session.sessionId" :data-testid="`session-row-${session.current ? 'current' : 'other'}`">
                <th scope="row"><span class="session-device"><MonitorSmartphone :size="15" aria-hidden="true" />{{ session.current ? '当前设备' : '其他设备' }}</span><span v-if="session.current" class="session-badge">本机</span></th>
                <td>{{ formatDateTime(session.lastAccessedAt) }}</td>
                <td>{{ formatDateTime(session.createdAt) }}</td>
                <td class="sessions-table__actions"><BaseButton variant="ghost" size="small" :disabled="!online || deletingSessionId !== null" :loading="sessionBusy(session)" @click="askRevokeSession(session)">{{ session.current ? '退出本设备' : '撤销' }}</BaseButton></td>
              </tr>
            </tbody>
          </BaseTable>
        </section>
      </div>
    </div>

    <BaseDialog :open="revokeOpen" role="alertdialog" title="退出全部设备？" eyebrow="Security confirmation" width="min(440px, 100%)" :close-disabled="pending" close-label="取消操作" @close="closeRevoke">
      <template #icon><LogOut :size="20" /></template>
      <div class="account-dialog__body">
        <p class="account-dialog__description">所有浏览器 Session 都会失效，包括当前设备。之后需要重新登录。</p>
        <div v-if="fieldProblem || (operation === 'sessions' && problem)" ref="revokeError" class="account-alert" role="alert" tabindex="-1"><strong>{{ fieldProblem ? '请检查输入' : problem?.title }}</strong><span>{{ fieldProblem || problem?.message }}</span></div>
        <label>当前密码<input v-model="revokePassword" name="revokeCurrentPassword" type="password" autocomplete="current-password" maxlength="512" :disabled="pending" autofocus></label>
      </div>
      <template #footer>
        <!-- 危险确认的初始焦点必须落在安全侧（取消），而不是 header 的关闭钮或破坏性主钮；此框的可聚焦输入（密码）先于 footer，承接初始焦点。 -->
        <BaseButton variant="ghost" :disabled="pending" @click="closeRevoke">取消</BaseButton>
        <BaseButton variant="danger" :loading="pending && operation === 'sessions'" @click="submitRevoke">确认退出全部设备</BaseButton>
      </template>
    </BaseDialog>

    <BaseDialog :open="signOutTarget !== null" role="alertdialog" title="退出本设备？" eyebrow="Session confirmation" width="min(440px, 100%)" :close-disabled="deletingSessionId !== null" close-label="取消操作" @close="closeSignOut">
      <template #icon><LogOut :size="20" /></template>
      <div class="account-dialog__body">
        <p class="account-dialog__description">撤销当前设备的会话等同于退出登录，之后需要重新登录。</p>
      </div>
      <template #footer>
        <!-- 危险确认的初始焦点必须落在安全侧（取消），而不是 header 的关闭钮或破坏性主钮。 -->
        <BaseButton variant="ghost" autofocus :disabled="deletingSessionId !== null" @click="closeSignOut">取消</BaseButton>
        <BaseButton variant="danger" :loading="deletingSessionId !== null" @click="confirmSignOut">退出本设备</BaseButton>
      </template>
    </BaseDialog>
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
/* sessions-table 类经 fallthrough 落在 BaseTable 的 wrap 上：overflow-x 与宽度归 wrap，字号随之继承。 */
.sessions-table { margin-top: var(--cs-space-20); font-size: var(--cs-text-sm); }
.sessions-table th, .sessions-table td { padding: var(--cs-space-12) var(--cs-space-8); border-bottom: 1px solid var(--cs-border); text-align: left; vertical-align: middle; }
.sessions-table thead th { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.sessions-table__actions { text-align: right; white-space: nowrap; }
.session-device { display: inline-flex; align-items: center; gap: var(--cs-space-8); font-weight: var(--cs-weight-semibold); }
.session-badge { display: inline-flex; margin-left: var(--cs-space-8); padding: 0 var(--cs-space-8); border-radius: var(--cs-radius-pill); background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.login-identities { padding-top: var(--cs-space-20); margin-top: var(--cs-space-20); border-top: 1px solid var(--cs-border); }
.login-identities h4 { margin: 0 0 var(--cs-space-12); font-size: var(--cs-text-sm); }
.login-identities ul { display: grid; gap: var(--cs-space-8); margin: 0; padding: 0; list-style: none; }
.login-identities li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8) var(--cs-space-16); font-size: var(--cs-text-sm); }
.login-identities__provider { font-weight: var(--cs-weight-semibold); }
.login-identities__status { color: var(--cs-text-muted); }
.login-identities__status--active { color: var(--cs-text-secondary); }
.login-identities__time { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.account-form { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-16); padding-top: var(--cs-space-20); margin-top: var(--cs-space-20); border-top: 1px solid var(--cs-border); }
.account-form--password { grid-template-columns: 1fr; }
.account-form label, .account-dialog__body label { display: grid; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.account-form input, .account-dialog__body input { width: 100%; min-width: 0; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: 9px; outline: 0; background: var(--cs-surface); font-size: var(--cs-text-base); font-weight: var(--cs-weight-medium); }
.account-form input:focus, .account-dialog__body input:focus { border-color: var(--cs-border-accent-strong); box-shadow: var(--cs-focus-ring); }
.account-form small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); }
.account-form__wide { grid-column: 1 / -1; }.form-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.account-alert { display: grid; gap: var(--cs-space-2); padding: var(--cs-space-12) var(--cs-space-16); border: 1px solid var(--cs-danger-border); border-radius: 10px; background: var(--cs-danger-soft); color: var(--cs-danger); }
.account-alert--warning { border-color: var(--cs-warning-border); background: var(--cs-warning-soft); color: var(--cs-warning); }
.account-alert strong { font-size: var(--cs-text-sm); }.account-alert span { font-size: var(--cs-text-sm); }
.account-dialog__body { display: grid; gap: var(--cs-space-16); }
.account-dialog__description { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
@media (max-width: 767px) {
  .account-heading { align-items: flex-start; padding-inline: var(--cs-space-2); }.account-heading h2 { font-size: var(--cs-text-xl); }.account-avatar { width: 46px; height: 46px; }
  .account-layout { grid-template-columns: 1fr; }.account-layout > nav { position: static; grid-template-columns: repeat(3, 1fr); overflow-x: auto; }
  .account-layout > nav a { justify-content: center; padding-inline: var(--cs-space-8); white-space: nowrap; }
  .account-panel { padding: var(--cs-space-16); }.account-panel > header { align-items: stretch; flex-direction: column; }.account-panel > header button { width: 100%; }
  .account-form, .profile-facts { grid-template-columns: 1fr; }.account-form__wide { grid-column: auto; }.form-actions { display: grid; grid-template-columns: 1fr; }
}
</style>
