<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import OnboardingWorkspace from '../components/auth/OnboardingWorkspace.vue'
import { useNetworkStatus } from '../app/network'
import { useAgentStore } from '../domains/agent/store'
import { useAuthStore } from '../domains/identity/store'
import {
  offlineOnboardingProblem,
  onboardingProjectionProblem,
  type OnboardingProblem,
} from '../domains/onboarding/presentation'
import { useOnboardingStore, type OnboardingPhase } from '../domains/onboarding/store'
import { useScopeStore } from '../domains/scope/store'

type HydrationPhase = 'idle' | 'workspace' | 'agent' | 'ready' | 'error'

const router = useRouter()
const authStore = useAuthStore()
const onboardingStore = useOnboardingStore()
const scopeStore = useScopeStore()
const agentStore = useAgentStore()
const online = useNetworkStatus()
const teamName = ref('')
const teamError = ref<string>()
const hydrationPhase = ref<HydrationPhase>('idle')
const localProblem = ref<OnboardingProblem | null>(null)
const localErrorGeneration = ref(0)
const personalAgentName = ref('Personal Agent')
const createdTeamId = ref<string | null>(null)
let disposed = false

const problem = computed(() => localProblem.value ?? onboardingStore.state.problem)
const errorGeneration = computed(() => localErrorGeneration.value + onboardingStore.state.errorGeneration)
const canEdit = computed(() => onboardingStore.state.status?.state === 'TEAM_REQUIRED' && !onboardingStore.state.receipt)
const phase = computed<OnboardingPhase>(() => {
  if (hydrationPhase.value === 'ready') return 'complete'
  if (hydrationPhase.value === 'error') return 'error'
  if (hydrationPhase.value === 'workspace' || hydrationPhase.value === 'agent') return 'verifying'
  return onboardingStore.state.phase
})
const currentStage = computed(() => {
  if (hydrationPhase.value === 'workspace') return 'workspace' as const
  if (hydrationPhase.value === 'agent') return 'agent' as const
  if (hydrationPhase.value === 'ready') return 'ready' as const
  return onboardingStore.state.phase === 'verifying' ? 'workspace' as const : 'team' as const
})

onMounted(initialize)
onBeforeUnmount(() => {
  disposed = true
  // Cancel pending status/creation checks and discard route-local retry intent.
  onboardingStore.reset()
})

async function initialize(): Promise<void> {
  localProblem.value = null
  const complete = await onboardingStore.load()
  if (disposed) return
  if (complete) await router.replace('/conversation')
}

async function submit(): Promise<void> {
  teamError.value = undefined
  localProblem.value = null
  const normalizedName = teamName.value.trim()
  if (!normalizedName || normalizedName.length > 200) {
    teamError.value = '团队名称需要包含 1 至 200 个字符'
    return
  }
  if (!online.value) {
    setLocalProblem(offlineOnboardingProblem())
    return
  }
  const csrf = authStore.state.session?.csrf
  if (!csrf) {
    setLocalProblem(onboardingProjectionProblem())
    return
  }
  const complete = await onboardingStore.createFirstTeam(normalizedName, csrf)
  if (!disposed && complete) await hydrateWorkspace()
}

async function retry(): Promise<void> {
  localProblem.value = null
  if (!online.value) {
    setLocalProblem(offlineOnboardingProblem())
    return
  }
  if (hydrationPhase.value === 'error') {
    await hydrateWorkspace()
    return
  }
  await authStore.refresh()
  if (disposed) return
  const csrf = authStore.state.session?.csrf
  if (!csrf) {
    setLocalProblem(onboardingProjectionProblem())
    return
  }
  if (canEdit.value) {
    await submit()
    return
  }
  const complete = await onboardingStore.retry(csrf)
  if (!disposed && complete) await hydrateWorkspace()
}

async function hydrateWorkspace(): Promise<void> {
  hydrationPhase.value = 'workspace'
  localProblem.value = null
  try {
    if (!await authStore.refresh()) throw new Error('Authenticated Session was not restored')
    if (disposed) return
    const session = authStore.state.session
    const team = session?.teams[0]
    if (!session?.authenticated || !team) throw new Error('Onboarding Team is absent from Session')

    scopeStore.reset()
    const selection = await scopeStore.synchronize(team.teamId)
    if (disposed) return
    if (scopeStore.state.phase !== 'ready'
      || selection.teamId !== team.teamId
      || scopeStore.selectedTeam.value?.initializationStatus !== 'READY') {
      throw new Error('Onboarding Team projection is not ready')
    }

    hydrationPhase.value = 'agent'
    agentStore.activateScope({ organizationId: session.principal!.organizationId, teamId: team.teamId })
    await agentStore.loadAgents(false, true)
    if (disposed) return
    const personalAgent = (agentStore.state.agents.value ?? []).find(agent =>
      agent.defaultProfile
      && agent.ownershipType === 'USER'
      && agent.ownerMemberId === team.memberId
      && agent.status === 'ACTIVE',
    )
    if (!personalAgent) throw new Error('Default Personal Agent projection is not ready')

    createdTeamId.value = team.teamId
    personalAgentName.value = personalAgent.displayName
    hydrationPhase.value = 'ready'
  } catch {
    if (disposed) return
    hydrationPhase.value = 'error'
    setLocalProblem(onboardingProjectionProblem())
  }
}

async function enterConversation(): Promise<void> {
  const query = createdTeamId.value ? { team: createdTeamId.value } : undefined
  await router.replace({ name: 'conversation', query })
}

function setLocalProblem(value: OnboardingProblem): void {
  localProblem.value = value
  localErrorGeneration.value += 1
}
</script>

<template>
  <OnboardingWorkspace
    v-model:team-name="teamName"
    :phase="phase"
    :problem="problem"
    :error-generation="errorGeneration"
    :team-error="teamError"
    :current-stage="currentStage"
    :can-edit="canEdit"
    :online="online"
    :personal-agent-name="personalAgentName"
    @submit="submit"
    @retry="retry"
    @enter="enterConversation"
  />
</template>
