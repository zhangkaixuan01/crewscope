<script setup lang="ts">
import { computed, inject } from 'vue'
import { RouterLink } from 'vue-router'
import { MessageSquare } from '@lucide/vue'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import TeamObserverWorkspace from '../components/domain/TeamObserverWorkspace.vue'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useScopeStore } from '../domains/scope/store'
import type { TeamObserverScope } from '../domains/teamobserver/types'

const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const online = useNetworkStatus()
const scope = computed<TeamObserverScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
</script>

<template>
  <AppShell title="Team Observer" eyebrow="协作 · 只读团队摘要">
    <template #actions>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation', query: { team: scopeStore.state.selectedTeamId, assistant: 'team-observer' } }">
        <BaseButton size="small" variant="secondary" @click="navigate"><MessageSquare :size="14" />对话式提问</BaseButton>
      </RouterLink>
    </template>
    <StatePanel v-if="scopeStore.state.phase === 'loading' || scopeStore.state.phase === 'idle'" state="loading" title="正在加载团队观测"><template #action><RouterLink :to="{ name: 'today' }"><BaseButton size="small" variant="secondary">前往 Today</BaseButton></RouterLink></template></StatePanel>
    <StatePanel v-else-if="scopeStore.state.phase === 'error'" state="error" title="团队观测暂时不可用" :description="scopeStore.state.errorMessage ?? undefined" @retry="scopeStore.reload"><template #action><BaseButton size="small" variant="secondary" @click="scopeStore.reload">重新加载</BaseButton></template></StatePanel>
    <StatePanel v-else-if="!scope" state="empty" title="请选择 Team" description="Team Observer 需要明确的团队范围。">
      <template #action><RouterLink :to="{ name: 'today' }"><BaseButton size="small">前往 Today</BaseButton></RouterLink></template>
    </StatePanel>
    <template v-else>
      <section class="observer-note panel"><strong>只读团队观测</strong><span>这里展示团队运行摘要；需要修改配置或推进工作项时，请前往对应工作台。</span><RouterLink class="touch-target" :to="{ name: 'work', query: { team: scope.teamId } }">前往工作项</RouterLink></section>
      <TeamObserverWorkspace :scope="scope" :team-name="scopeStore.selectedTeam.value?.name ?? '当前团队'" :online="online" variant="summary" />
    </template>
  </AppShell>
</template>

<style scoped>
.observer-note { display: flex; align-items: center; gap: var(--cs-space-12); margin-bottom: var(--cs-space-16); padding: var(--cs-space-12) var(--cs-space-16); }.observer-note strong { font-size: var(--cs-text-base); }.observer-note span { flex: 1; color: var(--cs-text-muted); font-size: var(--cs-text-base); }.observer-note a { color: var(--cs-text-brand); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
@media (max-width: 640px) { .observer-note { align-items: flex-start; flex-wrap: wrap; }.observer-note span { flex-basis: 100%; }.observer-note a { margin-left: auto; } }
</style>
