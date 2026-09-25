<script setup lang="ts">
import { BellRing, ShieldCheck } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { secureId } from '../../api/secureId'
import { HttpTeamOpsGateway, type NotificationPreferenceInput } from '../../domains/teamops/gateway'
import { inboxItemTypes, type InboxItemType } from '../../domains/teamops/types'
import { inboxItemTypeLabels } from '../../domains/teamops/labels'
import type { AuthSessionTeam } from '../../domains/identity/types'
import type { Etagged, NotificationPreference } from '../../domains/teamops/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'

/**
 * A07 self preference: reachable from /account without any administrator grant — the
 * server re-derives the caller's own membership, so removed members see 403 here too.
 */
const props = defineProps<{
  organizationId: string
  teams: AuthSessionTeam[]
}>()

const gateway = new HttpTeamOpsGateway()
const selectedTeamId = ref(props.teams[0]?.teamId ?? '')
const selectedTeam = computed(() => props.teams.find(team => team.teamId === selectedTeamId.value) ?? null)
type Phase = 'loading' | 'ready' | 'forbidden' | 'error'
const phase = ref<Phase>('loading')
const problem = ref('')
const pending = ref(false)
const saved = ref(false)
const loaded = ref<Etagged<NotificationPreference> | null>(null)
const draftEnabled = ref(true)
const draftItemTypes = ref<Set<InboxItemType>>(new Set(inboxItemTypes))

watch(() => props.teams, teams => {
  if (!teams.some(team => team.teamId === selectedTeamId.value)) selectedTeamId.value = teams[0]?.teamId ?? ''
}, { immediate: true })
watch(selectedTeamId, () => { void load() }, { immediate: true })

const dirty = computed(() => {
  const value = loaded.value?.value
  if (!value) return false
  const same = draftEnabled.value === value.enabled
    && draftItemTypes.value.size === value.enabledItemTypes.length
    && value.enabledItemTypes.every(item => draftItemTypes.value.has(item))
  return !same
})

async function load(): Promise<void> {
  const team = selectedTeam.value
  if (!team) return
  phase.value = 'loading'
  problem.value = ''
  saved.value = false
  try {
    const result = await gateway.selfNotificationPreference({ organizationId: props.organizationId, teamId: team.teamId })
    // A slow GET for a previously selected Team must not overwrite the one now on screen.
    if (selectedTeam.value?.teamId !== team.teamId) return
    loaded.value = result
    draftEnabled.value = result.value.enabled
    draftItemTypes.value = new Set(result.value.enabledItemTypes)
    phase.value = 'ready'
  } catch (error) {
    if (selectedTeam.value?.teamId !== team.teamId) return
    loaded.value = null
    if (error instanceof CrewScopeApiError && error.status === 403) {
      phase.value = 'forbidden'
      problem.value = '当前账号不是这个 Team 的有效成员，不能读写这里的偏好。'
    } else {
      phase.value = 'error'
      problem.value = error instanceof CrewScopeApiError ? error.envelope.message : '暂时无法加载通知偏好，请稍后重试。'
    }
  }
}

function toggle(item: InboxItemType): void {
  saved.value = false
  if (draftItemTypes.value.has(item)) draftItemTypes.value.delete(item)
  else draftItemTypes.value.add(item)
}

async function save(): Promise<void> {
  const team = selectedTeam.value
  const current = loaded.value
  if (!team || !current) return
  pending.value = true
  problem.value = ''
  saved.value = false
  try {
    const input: NotificationPreferenceInput = {
      enabled: draftEnabled.value,
      enabledItemTypes: inboxItemTypes.filter(item => draftItemTypes.value.has(item)),
      mutedUntil: current.value.mutedUntil,
    }
    const result = await gateway.updateSelfNotificationPreference(
      { organizationId: props.organizationId, teamId: team.teamId }, current.etag, input, secureId())
    if (selectedTeam.value?.teamId !== team.teamId) return
    loaded.value = result
    draftEnabled.value = result.value.enabled
    draftItemTypes.value = new Set(result.value.enabledItemTypes)
    saved.value = true
  } catch (error) {
    if (error instanceof CrewScopeApiError && error.status === 409) {
      problem.value = '偏好刚被其他会话修改。已重新加载最新版本，请核对后再次保存。'
      await load()
    } else {
      problem.value = error instanceof CrewScopeApiError ? error.envelope.message : '暂时无法保存，请稍后重试。'
    }
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <section class="team-preference panel">
    <div class="team-preference__heading">
      <i><BellRing :size="18" /></i>
      <div>
        <p class="eyebrow">Notification preference</p>
        <h2>当前团队通知偏好</h2>
        <p>偏好由你自己的成员资格保护：不要求管理员权限，移除成员后即不可读写。</p>
      </div>
      <StatusBadge v-if="saved" tone="success" dot>已保存</StatusBadge>
    </div>

    <div v-if="teams.length > 1" class="team-preference__team">
      <label for="team-preference-team">Team</label>
      <select id="team-preference-team" v-model="selectedTeamId">
        <option v-for="team in teams" :key="team.teamId" :value="team.teamId">{{ team.name }}</option>
      </select>
    </div>

    <p v-if="phase === 'loading'" class="team-preference__status" role="status">正在读取你在{{ selectedTeam?.name ?? '这个 Team' }}的通知偏好…</p>
    <p v-else-if="phase === 'forbidden'" class="team-preference__problem" role="alert">{{ problem }}</p>
    <div v-else-if="phase === 'error'" class="team-preference__problem" role="alert">
      {{ problem }}
      <BaseButton variant="secondary" size="small" @click="load()">重试</BaseButton>
    </div>

    <template v-if="phase === 'ready' && loaded">
      <label class="team-preference__switch">
        <input type="checkbox" :checked="draftEnabled" @change="draftEnabled = ($event.target as HTMLInputElement).checked; saved = false">
        <span>接收这个 Team 的通知</span>
      </label>
      <fieldset class="team-preference__types" :disabled="!draftEnabled">
        <legend>通知类别</legend>
        <label v-for="item in inboxItemTypes" :key="item" class="team-preference__type">
          <input
            type="checkbox"
            :checked="draftItemTypes.has(item)"
            @change="toggle(item)"
          >
          <span>{{ inboxItemTypeLabels[item] }}</span>
        </label>
      </fieldset>
      <p v-if="problem" class="team-preference__problem" role="alert">{{ problem }}</p>
      <div class="team-preference__actions">
        <BaseButton size="small" :loading="pending" :disabled="!dirty" @click="save()">保存偏好</BaseButton>
      </div>
      <p class="team-preference__note"><ShieldCheck :size="13" />保存只影响你自己，不会改变其他成员的通知。</p>
    </template>
  </section>
</template>

<style scoped>
.team-preference { display: grid; gap: var(--cs-space-16); padding: var(--cs-space-20); }
.team-preference__heading { display: grid; grid-template-columns: 40px 1fr auto; gap: var(--cs-space-12); align-items: start; }
.team-preference__heading > i { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 11px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.team-preference__heading h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-base); }
.team-preference__heading p:last-child { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.team-preference__team { display: grid; gap: var(--cs-space-4); max-width: 360px; }
.team-preference__team label { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.team-preference__team select { min-height: 36px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); font-size: var(--cs-text-base); }
.team-preference__status { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.team-preference__problem { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-sm); }
.team-preference__switch { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.team-preference__types { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 10px; }
.team-preference__types:disabled { opacity: .55; }
.team-preference__types legend { padding-inline: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.team-preference__type { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.team-preference__actions { display: flex; justify-content: flex-end; }
.team-preference__note { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
