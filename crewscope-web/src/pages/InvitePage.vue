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

const route = useRoute()
const router = useRouter()
const store = useInvitationStore()
const authStore = useAuthStore()
const scopeStore = useScopeStore()
const online = useNetworkStatus()
const localProblem = ref<InvitationProblem | null>(null)
const problemFocusKey = ref(0)

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
  const hash = route.hash
  if (hash) await router.replace({ name: 'invite' })
  await preview(hash || undefined)
}

async function preview(hash?: string): Promise<void> {
  localProblem.value = null
  if (!online.value) {
    setLocalProblem(offlineInvitationProblem())
    await store.previewProof(hash)
    return
  }
  await store.previewProof(hash)
}

async function accept(): Promise<void> {
  localProblem.value = null
  if (!online.value) return setLocalProblem(offlineInvitationProblem())
  const csrf = authStore.state.session?.csrf
  if (!csrf) return setLocalProblem({ code: 'csrf_rejected', title: '安全校验已失效', message: '请重新登录后接受邀请。', tone: 'warning' })
  const before = new Set(authStore.state.session?.teams.map(team => team.teamId) ?? [])
  if (!await store.acceptInvitation(csrf)) return
  if (!await authStore.refresh()) return
  const teams = authStore.state.session?.teams ?? []
  const selected = teams.find(team => !before.has(team.teamId))
    ?? teams.find(team => team.name === store.state.preview?.teamName)
    ?? teams[0]
  scopeStore.reset()
  await scopeStore.synchronize(selected?.teamId ?? null)
  await router.replace({ name: 'conversation', query: selected ? { team: selected.teamId } : {} })
}

async function login(): Promise<void> {
  if (store.hasProof() && store.state.preview?.state === 'AVAILABLE') {
    await router.push({ name: 'login', query: { returnTo: '/invite' } })
  } else {
    store.clearProof()
    await router.push({ name: 'login' })
  }
}

async function register(): Promise<void> {
  if (!store.hasProof()) return
  await router.push({ name: 'register' })
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
      @login="login"
      @register="register"
      @accept="accept"
      @retry="preview()"
    />
  </AuthLayout>
</template>
