<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import AuthLayout from '../components/auth/AuthLayout.vue'
import InvitationWorkspace from '../components/auth/InvitationWorkspace.vue'
import { useNetworkStatus } from '../app/network'
import { offlineInvitationProblem, type InvitationProblem } from '../domains/invitation/presentation'
import { useInvitationStore } from '../domains/invitation/store'
import { useAuthStore } from '../domains/identity/store'
import { useScopeStore } from '../domains/scope/store'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const store = useInvitationStore()
const authStore = useAuthStore()
const scopeStore = useScopeStore()
const online = useNetworkStatus()
const localProblem = ref<InvitationProblem | null>(null)
const problemFocusKey = ref(0)
/** The accept was committed but the session has not caught up yet; resync never re-accepts. */
const sessionPending = ref(false)
const resyncing = ref(false)

const authenticated = computed(() => authStore.state.phase === 'authenticated')
const registrationAllowed = computed(() => authStore.state.session?.registrationMode !== 'DISABLED')
const problem = computed(() => localProblem.value ?? store.state.publicProblem)

onMounted(initialize)
onBeforeUnmount(() => store.pausePublic())
onBeforeRouteLeave(to => {
  store.pausePublic()
  if (to.name !== 'login' && to.name !== 'register') store.clearProof()
})

async function initialize(): Promise<void> {
  const pageOwner = pageRequests.capture()
  const hash = route.hash
  if (hash) await router.replace({ name: 'invite' })
  await preview(hash || undefined)
  if (!pageOwner.isCurrent()) return
}

async function preview(hash?: string): Promise<void> {
  const pageOwner = pageRequests.capture()
  localProblem.value = null
  if (!online.value) {
    setLocalProblem(offlineInvitationProblem())
    await store.previewProof(hash)
    if (!pageOwner.isCurrent()) return
    return
  }
  await store.previewProof(hash)
  if (!pageOwner.isCurrent()) return
}

async function accept(): Promise<void> {
  const pageOwner = pageRequests.captureIdentity()
  localProblem.value = null
  sessionPending.value = false
  if (!online.value) return setLocalProblem(offlineInvitationProblem())
  const csrf = authStore.state.session?.csrf
  if (!csrf) return setLocalProblem({ code: 'csrf_rejected', title: '安全校验已失效', message: '请重新登录后接受邀请。', tone: 'warning' })
  const result = await store.acceptInvitation(csrf)
  if (!pageOwner.isCurrent()) return
  if (!result) return
  if (!await authStore.refresh()) {
    sessionPending.value = true
    return
  }
  if (!pageOwner.isCurrent()) return
  await locate(result.acceptance?.teamId ?? null)
}

/** Resync re-reads the session and command results; it never submits a second accept. */
async function resync(): Promise<void> {
  const pageOwner = pageRequests.captureIdentity()
  resyncing.value = true
  try {
    if (!await authStore.refresh()) return
    if (!pageOwner.isCurrent()) return
    await locate(store.state.acceptance?.teamId ?? null)
  } finally {
    if (pageOwner.isCurrent()) resyncing.value = false
  }
}

async function locate(committedTeamId: string | null): Promise<void> {
  const pageOwner = pageRequests.captureIdentity()
  const teams = authStore.state.session?.teams ?? []
  const selected = committedTeamId
    ? teams.find(team => team.teamId === committedTeamId)
    : undefined
  if (committedTeamId && !selected) {
    // The committed coordinate is authoritative; without it in the session the accept stays pending.
    sessionPending.value = true
    return
  }
  const fallback = selected
    ?? teams.find(team => team.name === store.state.preview?.teamName)
    ?? teams[0]
  sessionPending.value = false
  scopeStore.reset()
  await scopeStore.synchronize(fallback?.teamId ?? null)
  if (!pageOwner.isCurrent()) return
  await router.replace({ name: 'conversation', query: fallback ? { team: fallback.teamId } : {} })
  if (!pageOwner.isCurrent()) return
}

async function login(): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (store.hasProof() && store.state.preview?.state === 'AVAILABLE') {
    await router.push({ name: 'login', query: { returnTo: '/invite' } })
    if (!pageOwner.isCurrent()) return
  } else {
    store.clearProof()
    await router.push({ name: 'login' })
    if (!pageOwner.isCurrent()) return
  }
}

async function register(): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!store.hasProof()) return
  await router.push({ name: 'register' })
  if (!pageOwner.isCurrent()) return
}

function setLocalProblem(value: InvitationProblem): void {
  localProblem.value = value
  problemFocusKey.value += 1
}
</script>

<template>
  <AuthLayout>
    <InvitationWorkspace
      :phase="store.state.publicPhase"
      :preview="store.state.preview"
      :problem="problem"
      :problem-focus-key="problemFocusKey + store.state.publicErrorGeneration"
      :authenticated="authenticated"
      :registration-allowed="registrationAllowed"
      :online="online"
      :session-pending="sessionPending"
      :resyncing="resyncing"
      @login="login"
      @register="register"
      @accept="accept"
      @retry="preview()"
      @resync="resync"
    />
  </AuthLayout>
</template>
