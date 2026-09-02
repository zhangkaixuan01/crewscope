<script setup lang="ts">
import { ArrowLeft, ArrowRight, RefreshCw, UsersRound } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AuthCard from '../components/auth/AuthCard.vue'
import AuthErrorSummary from '../components/auth/AuthErrorSummary.vue'
import AuthField from '../components/auth/AuthField.vue'
import AuthLayout from '../components/auth/AuthLayout.vue'
import AuthPasswordField from '../components/auth/AuthPasswordField.vue'
import BaseButton from '../components/base/BaseButton.vue'
import { useNetworkStatus } from '../app/network'
import { useIdentityGateway } from '../domains/identity/gateway'
import { registrationInvitationFromHash, type RegistrationInvitationContext } from '../domains/identity/invitation'
import { useAuthStore } from '../domains/identity/store'
import {
  IdentityRequestTimeoutError,
  offlineRegistrationProblem,
  presentRegistrationProblem,
  presentSessionProblem,
  type RegistrationProblem,
} from '../domains/identity/presentation'
import type { AuthCsrfCoordinate, RegistrationMode } from '../domains/identity/types'
import { useOptionalInvitationStore } from '../domains/invitation/store'

const props = withDefaults(defineProps<{
  sessionTimeoutMs?: number
  registrationTimeoutMs?: number
}>(), {
  sessionTimeoutMs: 10_000,
  registrationTimeoutMs: 20_000,
})

type RegistrationPhase = 'loading' | 'ready' | 'submitting' | 'session-error'

const gateway = useIdentityGateway()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const online = useNetworkStatus()
const invitationStore = useOptionalInvitationStore()
const phase = ref<RegistrationPhase>('loading')
const username = ref('')
const email = ref('')
const displayName = ref('')
const password = ref('')
const fieldErrors = ref<Record<string, string>>({})
const problem = ref<RegistrationProblem | null>(null)
const problemFocusKey = ref(0)
const csrf = ref<AuthCsrfCoordinate | null>(null)
const registrationMode = ref<RegistrationMode | null>(null)
const invitation = ref<RegistrationInvitationContext>({ kind: 'none' })
const submitting = computed(() => phase.value === 'submitting')
const formAllowed = computed(() => {
  if (invitation.value.kind === 'invalid' || registrationMode.value === 'DISABLED') return false
  return registrationMode.value === 'OPEN'
    || (registrationMode.value === 'INVITE_ONLY' && invitation.value.kind === 'valid')
})
const unavailableState = computed(() => {
  if (invitation.value.kind === 'invalid') {
    return { kicker: '邀请不可用', title: '无法继续这次邀请注册', description: '请向团队管理员获取新的邀请链接。' }
  }
  if (registrationMode.value === 'DISABLED') {
    return { kicker: '注册已关闭', title: '当前部署未开放新账号', description: '已有成员仍可返回登录。' }
  }
  return { kicker: '需要团队邀请', title: '通过团队邀请加入 CrewScope', description: '请从团队管理员发送的完整邀请链接进入。' }
})

let activeController: AbortController | null = null
let operationGeneration = 0
let disposed = false
let retryIdempotencyKey: string | null = null

onMounted(initialize)
onBeforeUnmount(() => {
  disposed = true
  operationGeneration += 1
  activeController?.abort()
  password.value = ''
  invitation.value = { kind: 'none' }
  invitationStore?.clearProof()
})

watch([username, email, displayName, password], () => {
  retryIdempotencyKey = null
  fieldErrors.value = {}
})

watch(online, current => {
  if (!current && phase.value === 'ready') setProblem(offlineRegistrationProblem())
})

async function initialize(): Promise<void> {
  invitation.value = registrationInvitationFromHash(route.hash)
  if (invitation.value.kind === 'none') {
    const proof = invitationStore?.registrationProof()
    if (proof) invitation.value = { kind: 'valid', token: proof }
  }
  if (route.hash) {
    // Replace the current history entry so the invitation proof leaves the address bar and Router state.
    await router.replace({ name: 'register' })
    if (disposed) return
  }
  await loadSession()
}

async function loadSession(): Promise<void> {
  phase.value = 'loading'
  problem.value = null
  fieldErrors.value = {}
  if (!online.value) {
    phase.value = 'session-error'
    setProblem(offlineRegistrationProblem())
    return
  }
  try {
    if (authStore.state.phase === 'error') throw new Error('Authentication Session is unavailable')
    await authStore.ensureRestored()
    if (disposed) return
    const session = authStore.state.session
    if (!session) throw new Error('Authentication Session is unavailable')
    csrf.value = session.csrf
    registrationMode.value = session.registrationMode
    if (session.authenticated) {
      await router.replace('/conversation')
      return
    }
    phase.value = 'ready'
  } catch (error) {
    if (disposed || isAbort(error)) return
    phase.value = 'session-error'
    setProblem(authStore.state.errorCode === 'network_unavailable' ? offlineRegistrationProblem() : presentSessionProblem(error))
  }
}

async function retrySession(): Promise<void> {
  await authStore.retry()
  await loadSession()
}

async function submitRegistration(): Promise<void> {
  if (submitting.value || !csrf.value || !formAllowed.value) return
  const input = {
    username: username.value.trim(),
    email: email.value.trim(),
    displayName: displayName.value.trim(),
    password: password.value,
    ...(invitation.value.kind === 'valid' ? { invitationToken: invitation.value.token } : {}),
  }
  const errors = validate(input)
  if (Object.keys(errors).length > 0) {
    fieldErrors.value = errors
    setProblem({ code: 'invalid_input', title: '请检查注册信息', message: '修正标记的字段后再提交。', tone: 'error' })
    return
  }
  problem.value = null
  fieldErrors.value = {}
  if (!online.value) {
    setProblem(offlineRegistrationProblem())
    return
  }

  const idempotencyKey = retryIdempotencyKey ?? window.crypto.randomUUID()
  retryIdempotencyKey = idempotencyKey
  phase.value = 'submitting'
  try {
    const result = await timedRequest(
      signal => gateway.register(input, csrf.value!, idempotencyKey, signal),
      props.registrationTimeoutMs,
    )
    if (disposed) return
    const refreshed = await authStore.refresh()
    if (!refreshed) throw new Error('Registered Session could not be restored')
    if (disposed) return
    retryIdempotencyKey = null
    password.value = ''
    invitation.value = { kind: 'none' }
    invitationStore?.clearProof()
    await router.replace(result.onboardingRequired ? '/onboarding' : '/conversation')
  } catch (error) {
    if (disposed || isAbort(error)) return
    const nextProblem = presentRegistrationProblem(error)
    if (!['registration_session_unavailable', 'request_timeout', 'network_unavailable'].includes(nextProblem.code)) {
      retryIdempotencyKey = null
    }
    phase.value = 'ready'
    setProblem(nextProblem)
  }
}

function validate(input: { username: string, email: string, displayName: string, password: string }): Record<string, string> {
  const errors: Record<string, string> = {}
  const usernameLength = Array.from(input.username).length
  if (usernameLength < 3 || usernameLength > 64) errors.username = '用户名需要包含 3 至 64 个字符'
  if (!input.email || input.email.length > 254) errors.email = '请输入有效且不超过 254 个字符的邮箱'
  const displayNameLength = Array.from(input.displayName).length
  if (displayNameLength < 1 || displayNameLength > 200) errors.displayName = '展示名需要包含 1 至 200 个字符'
  const passwordLength = Array.from(input.password).length
  const passwordBytes = new TextEncoder().encode(input.password).byteLength
  if (passwordLength < 12 || passwordLength > 128 || passwordBytes > 512 || hasUnpairedSurrogate(input.password)) {
    errors.password = '密码需要包含 12 至 128 个字符，且不超过 512 字节'
  }
  return errors
}

function hasUnpairedSurrogate(value: string): boolean {
  return Array.from(value).some(character => {
    const codePoint = character.codePointAt(0) ?? 0
    return character.length === 1 && codePoint >= 0xD800 && codePoint <= 0xDFFF
  })
}

function setProblem(value: RegistrationProblem): void {
  problem.value = value
  problemFocusKey.value += 1
  void nextTick()
}

async function timedRequest<T>(operation: (signal: AbortSignal) => Promise<T>, timeoutMs: number): Promise<T> {
  const generation = ++operationGeneration
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  let timedOut = false
  const timeout = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  try {
    return await operation(controller.signal)
  } catch (error) {
    if (timedOut) throw new IdentityRequestTimeoutError()
    throw error
  } finally {
    window.clearTimeout(timeout)
    if (generation === operationGeneration) activeController = null
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
</script>

<template>
  <AuthLayout>
    <AuthCard
      v-if="phase === 'loading'"
      kicker="正在准备账号入口"
      title="正在确认注册方式"
      description="检查部署策略和安全注册坐标后继续。"
      busy
      focus-on-mount
    >
      <p class="register-page__status" role="status">正在准备安全注册…</p>
    </AuthCard>

    <AuthCard
      v-else-if="phase === 'session-error'"
      kicker="暂时无法连接"
      title="没有完成注册检查"
      description="你的注册信息尚未提交。"
    >
      <AuthErrorSummary
        v-if="problem"
        :title="problem.title"
        :messages="[problem.message]"
        :tone="problem.tone"
        :focus-key="problemFocusKey"
      />
      <BaseButton class="register-page__primary" :disabled="!online" @click="retrySession">
        <template #icon><RefreshCw :size="16" /></template>
        重新检查
      </BaseButton>
      <template #footer>
        <RouterLink class="register-page__back" to="/login"><ArrowLeft :size="14" />返回登录</RouterLink>
      </template>
    </AuthCard>

    <AuthCard
      v-else-if="!formAllowed"
      :kicker="unavailableState.kicker"
      :title="unavailableState.title"
      :description="unavailableState.description"
      focus-on-mount
    >
      <template #footer>
        <RouterLink class="register-page__back" to="/login"><ArrowLeft :size="14" />返回登录</RouterLink>
      </template>
    </AuthCard>

    <AuthCard
      v-else
      size="wide"
      :kicker="invitation.kind === 'valid' ? '加入团队' : '创建个人账号'"
      title="开始使用 CrewScope"
      :description="invitation.kind === 'valid' ? '创建账号后直接加入邀请你的团队。' : '创建账号后设置你的第一个团队。'"
      :busy="submitting"
    >
      <div v-if="invitation.kind === 'valid'" class="register-page__invite" role="status">
        <UsersRound :size="16" aria-hidden="true" />
        已安全载入团队邀请
      </div>
      <AuthErrorSummary
        v-if="problem"
        :title="problem.title"
        :messages="[problem.message]"
        :tone="problem.tone"
        :focus-key="problemFocusKey"
      />
      <form class="register-page__form" :aria-busy="submitting" @submit.prevent="submitRegistration">
        <AuthField
          v-model="username"
          label="用户名"
          name="username"
          autocomplete="username"
          placeholder="alice"
          hint="用于登录和团队内识别"
          required
          :maxlength="64"
          :disabled="submitting"
          :error="fieldErrors.username"
          :focus-on-mount="!problem"
        />
        <AuthField
          v-model="email"
          label="邮箱"
          name="email"
          type="email"
          inputmode="email"
          autocomplete="email"
          placeholder="name@example.com"
          required
          :maxlength="254"
          :disabled="submitting"
          :error="fieldErrors.email"
        />
        <div class="register-page__span">
          <AuthField
            v-model="displayName"
            label="展示名"
            name="displayName"
            autocomplete="name"
            placeholder="你的姓名或团队称呼"
            required
            :maxlength="200"
            :disabled="submitting"
            :error="fieldErrors.displayName"
          />
        </div>
        <div class="register-page__span">
          <AuthPasswordField
            v-model="password"
            label="密码"
            name="password"
            autocomplete="new-password"
            placeholder="使用一段容易记住的长密码"
            required
            show-guidance
            :maxlength="512"
            :disabled="submitting"
            :error="fieldErrors.password"
          />
        </div>
        <BaseButton
          class="register-page__primary register-page__span"
          type="submit"
          :loading="submitting"
          :disabled="!online"
        >
          {{ submitting ? '正在创建账号…' : invitation.kind === 'valid' ? '创建账号并加入团队' : '创建账号' }}
          <template #icon><ArrowRight v-if="!submitting" :size="16" /></template>
        </BaseButton>
      </form>
      <template #footer>
        <RouterLink class="register-page__back" to="/login"><ArrowLeft :size="14" />已有账号，返回登录</RouterLink>
      </template>
    </AuthCard>
  </AuthLayout>
</template>

<style scoped>
.register-page__form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.register-page__span { grid-column: 1 / -1; }
.register-page__primary { width: 100%; min-height: var(--cs-auth-control-height); }
.register-page__status { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.register-page__invite {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 18px;
  border: 1px solid var(--cs-brand-200);
  border-radius: 10px;
  background: var(--cs-brand-50);
  color: var(--cs-brand-800);
  font-size: 10px;
  font-weight: 680;
}
.register-page__back {
  display: flex;
  width: fit-content;
  align-items: center;
  gap: 5px;
  margin: 20px auto 0;
  color: var(--cs-text-secondary);
  font-size: 10px;
  font-weight: 680;
}
.register-page__back:hover { color: var(--cs-brand-700); }

@media (max-width: 680px) {
  .register-page__form { grid-template-columns: 1fr; }
  .register-page__span { grid-column: auto; }
}
</style>
