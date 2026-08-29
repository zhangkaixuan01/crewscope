<script setup lang="ts">
import { ArrowRight, RefreshCw } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AuthCard from '../components/auth/AuthCard.vue'
import AuthErrorSummary from '../components/auth/AuthErrorSummary.vue'
import AuthField from '../components/auth/AuthField.vue'
import AuthLayout from '../components/auth/AuthLayout.vue'
import AuthPasswordField from '../components/auth/AuthPasswordField.vue'
import BaseButton from '../components/base/BaseButton.vue'
import { useNetworkStatus } from '../app/network'
import { useIdentityGateway, registrationModeLabel } from '../domains/identity/gateway'
import { useAuthStore } from '../domains/identity/store'
import {
  IdentityRequestTimeoutError,
  offlineLoginProblem,
  presentLoginProblem,
  presentSessionProblem,
  type LoginProblem,
} from '../domains/identity/presentation'
import { safeLoginDestination } from '../domains/identity/route'
import type { AuthCsrfCoordinate, RegistrationMode } from '../domains/identity/types'

const props = withDefaults(defineProps<{
  sessionTimeoutMs?: number
  loginTimeoutMs?: number
}>(), {
  sessionTimeoutMs: 10_000,
  loginTimeoutMs: 15_000,
})

type LoginPhase = 'loading' | 'ready' | 'submitting' | 'session-error'

const gateway = useIdentityGateway()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const online = useNetworkStatus()
const phase = ref<LoginPhase>('loading')
const identifier = ref('')
const password = ref('')
const identifierError = ref<string | undefined>()
const problem = ref<LoginProblem | null>(null)
const problemFocusKey = ref(0)
const csrf = ref<AuthCsrfCoordinate | null>(null)
const registrationMode = ref<RegistrationMode | null>(null)
const formVisible = computed(() => phase.value === 'ready' || phase.value === 'submitting')
const submitting = computed(() => phase.value === 'submitting')
const registrationMessage = computed(() => registrationMode.value ? registrationModeLabel(registrationMode.value) : null)

let activeController: AbortController | null = null
let operationGeneration = 0
let disposed = false

onMounted(loadSession)
onBeforeUnmount(() => {
  disposed = true
  operationGeneration += 1
  activeController?.abort()
  password.value = ''
  csrf.value = null
})

watch(online, current => {
  if (!current && formVisible.value && !submitting.value) setProblem(offlineLoginProblem())
})

async function loadSession(): Promise<void> {
  phase.value = 'loading'
  problem.value = null
  identifierError.value = undefined
  if (!online.value) {
    phase.value = 'session-error'
    setProblem(offlineLoginProblem())
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
      await router.replace(safeLoginDestination(route.query, router))
      return
    }
    phase.value = 'ready'
  } catch (error) {
    if (disposed || isAbort(error)) return
    phase.value = 'session-error'
    setProblem(authStore.state.errorCode === 'network_unavailable' ? offlineLoginProblem() : presentSessionProblem(error))
  }
}

async function retrySession(): Promise<void> {
  await authStore.retry()
  await loadSession()
}

async function submitLogin(): Promise<void> {
  if (submitting.value || !csrf.value) return
  const normalizedIdentifier = identifier.value.trim()
  if (!normalizedIdentifier) {
    identifierError.value = '请输入用户名或邮箱'
    await nextTick()
    return
  }
  identifierError.value = undefined
  problem.value = null
  if (!online.value) {
    setProblem(offlineLoginProblem())
    return
  }

  phase.value = 'submitting'
  try {
    await timedRequest(signal => gateway.login({ identifier: normalizedIdentifier, password: password.value }, csrf.value!, signal), props.loginTimeoutMs)
    if (disposed) return
    if (!await authStore.refresh()) throw new Error('Authenticated Session could not be restored')
    if (disposed) return
    password.value = ''
    await router.replace(safeLoginDestination(route.query, router))
  } catch (error) {
    if (disposed || isAbort(error)) return
    const nextProblem = presentLoginProblem(error)
    if (nextProblem.code === 'invalid_credentials') password.value = ''
    phase.value = 'ready'
    setProblem(nextProblem)
  }
}

function setProblem(value: LoginProblem): void {
  problem.value = value
  problemFocusKey.value += 1
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
      kicker="正在恢复工作入口"
      title="正在确认你的会话"
      description="检查服务端 Session 和安全登录坐标后继续。"
      busy
      focus-on-mount
    >
      <p class="login-page__status" role="status">正在准备安全登录…</p>
    </AuthCard>

    <AuthCard
      v-else-if="phase === 'session-error'"
      kicker="暂时无法连接"
      title="没有完成会话检查"
      description="你的登录信息尚未提交。"
    >
      <AuthErrorSummary
        v-if="problem"
        :title="problem.title"
        :messages="[problem.message]"
        :tone="problem.tone"
        :focus-key="problemFocusKey"
      />
      <BaseButton class="login-page__primary" :disabled="!online" @click="retrySession">
        <template #icon><RefreshCw :size="16" /></template>
        重新检查
      </BaseButton>
    </AuthCard>

    <AuthCard
      v-else
      kicker="欢迎回来"
      title="继续你的团队工作"
      description="使用用户名或邮箱进入 CrewScope。"
      :busy="submitting"
    >
      <AuthErrorSummary
        v-if="problem"
        :title="problem.title"
        :messages="[problem.message]"
        :tone="problem.tone"
        :focus-key="problemFocusKey"
      />
      <form class="login-page__form" :aria-busy="submitting" @submit.prevent="submitLogin">
        <AuthField
          v-model="identifier"
          label="用户名或邮箱"
          name="identifier"
          autocomplete="username"
          inputmode="email"
          placeholder="name@example.com"
          required
          :maxlength="1024"
          :error="identifierError"
          :focus-on-mount="!problem"
          @input="identifierError = undefined"
        />
        <AuthPasswordField
          v-model="password"
          name="password"
          autocomplete="current-password"
          placeholder="输入你的密码"
          required
          :maxlength="512"
        />
        <BaseButton
          class="login-page__primary"
          type="submit"
          :loading="submitting"
          :disabled="!online"
        >
          {{ submitting ? '正在登录…' : '进入 CrewScope' }}
          <template #icon><ArrowRight v-if="!submitting" :size="16" /></template>
        </BaseButton>
      </form>
      <template #footer>
        <div v-if="registrationMessage" class="login-page__registration">
          <p>{{ registrationMessage }}</p>
          <RouterLink v-if="registrationMode === 'OPEN'" to="/register">创建账号</RouterLink>
        </div>
      </template>
    </AuthCard>
  </AuthLayout>
</template>

<style scoped>
.login-page__form { display: grid; gap: 16px; }
.login-page__primary { width: 100%; min-height: var(--cs-auth-control-height); }
.login-page__status,
.login-page__registration { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.login-page__registration { display: grid; justify-items: center; gap: 6px; margin-top: 20px; text-align: center; }
.login-page__registration p { margin: 0; }
.login-page__registration a { color: var(--cs-brand-700); font-weight: 720; }
</style>
