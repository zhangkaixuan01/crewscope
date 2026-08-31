<script setup lang="ts">
import { Check, Plus, ShieldCheck, UserRoundPlus, UsersRound, X } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import TeamInvitationManager from '../components/team/TeamInvitationManager.vue'
import { useScopeStore } from '../domains/scope/store'

const principal = inject(AUTH_PRINCIPAL)
const store = useScopeStore()
const team = store.selectedTeam
const showAddMember = ref(false)
const targetPrincipalId = ref('')
const submitted = ref(false)
const canManageMembers = computed(() => Boolean(principal && can(principal, permissions.teamMembersManage)))
const activeMembers = computed(() => store.state.members.filter(member => member.status === 'ACTIVE'))
const principalIdValid = computed(() => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(targetPrincipalId.value.trim()))

watch(() => store.state.selectedTeamId, () => {
  showAddMember.value = false
  targetPrincipalId.value = ''
  submitted.value = false
  void store.loadMembers()
}, { immediate: true })

async function addMember(): Promise<void> {
  submitted.value = true
  if (!principalIdValid.value) return
  try {
    await store.addMember(targetPrincipalId.value)
    targetPrincipalId.value = ''
    submitted.value = false
    showAddMember.value = false
  } catch {
    // The store exposes a sanitized message while the global error boundary keeps technical detail private.
  }
}

function shortId(value: string): string {
  return `${value.slice(0, 8)}…${value.slice(-4)}`
}

</script>

<template>
  <AppShell eyebrow="Team · Member management" :title="`${team?.name ?? 'Team'} 成员`">
    <template #actions>
      <BaseButton v-if="canManageMembers" size="small" @click="showAddMember = true"><Plus :size="14" />添加成员</BaseButton>
    </template>

    <StatePanel v-if="store.state.phase === 'loading' || store.state.phase === 'idle'" state="loading" />
    <StatePanel v-else-if="store.state.phase === 'error'" state="error" :description="store.state.errorMessage ?? undefined" @retry="store.reload" />
    <StatePanel v-else-if="store.state.phase === 'empty'" state="empty" title="还没有可访问的 Team" />

    <div v-else class="members-page page-shell">
      <section class="member-summary panel">
        <div class="summary-icon"><UsersRound :size="23" /></div>
        <div><p class="eyebrow">Active membership</p><h2>{{ activeMembers.length }} 位活跃成员</h2><p>Membership 决定 Team 可见性；TeamRole 决定管理动作。Personal Agent 不替代成员的最终责任。</p></div>
        <StatusBadge tone="success" dot>{{ team?.initializationStatus === 'READY' ? 'Team Ready' : '等待初始化' }}</StatusBadge>
      </section>

      <form v-if="showAddMember && canManageMembers" class="add-member panel" @submit.prevent="addMember">
        <div class="add-member__heading"><i><UserRoundPlus :size="19" /></i><div><h2>添加已有用户</h2><p>输入同一 Organization 下的 ACTIVE USER Principal ID。服务端会重新验证身份、Scope 与 MEMBER_MANAGE 权限。</p></div><button type="button" aria-label="关闭添加成员" @click="showAddMember = false"><X :size="17" /></button></div>
        <label for="principal-id">User Principal ID</label>
        <div class="principal-input"><input id="principal-id" v-model="targetPrincipalId" class="mono" autocomplete="off" placeholder="00000000-0000-0000-0000-000000000000" :aria-invalid="submitted && !principalIdValid"><BaseButton type="submit" size="small" :loading="store.state.memberCommandPending">确认添加</BaseButton></div>
        <p v-if="submitted && !principalIdValid" class="field-error" role="alert">请输入有效的 UUID Principal ID。</p>
        <p v-if="store.state.membersErrorMessage" class="field-error" role="alert">{{ store.state.membersErrorMessage }}</p>
      </form>

      <section class="panel member-directory">
        <div class="panel-heading"><div><p class="eyebrow">Member directory</p><h2>团队成员</h2><p>展示身份目录中的显示名，以及成员状态和加入事实。</p></div><BaseButton v-if="canManageMembers && !showAddMember" variant="secondary" size="small" @click="showAddMember = true"><Plus :size="14" />添加成员</BaseButton></div>
        <StatePanel v-if="store.state.membersLoading" state="loading" />
        <StatePanel v-else-if="store.state.membersErrorMessage" state="error" :description="store.state.membersErrorMessage" @retry="store.loadMembers(true)" />
        <StatePanel v-else-if="store.state.members.length === 0" state="empty" title="暂时没有成员事实" />
        <div v-else class="member-table" role="table" aria-label="团队成员列表">
          <div class="member-table__head" role="row"><span role="columnheader">成员</span><span role="columnheader">状态</span><span role="columnheader">加入方式</span><span role="columnheader">加入时间</span><span role="columnheader">版本</span></div>
          <div v-for="member in store.state.members" :key="member.id" class="member-row" role="row">
            <div class="member-identity" role="cell"><i>{{ member.displayName.slice(0, 1) }}</i><span><strong>{{ member.displayName }} <em v-if="member.userPrincipalId === principal?.id">你</em><em v-if="member.id === team?.ownerMemberId">Owner</em></strong><small class="mono" :title="member.userPrincipalId">{{ shortId(member.userPrincipalId) }}</small></span></div>
            <span role="cell"><StatusBadge :tone="member.status === 'ACTIVE' ? 'success' : 'neutral'" dot>{{ member.status }}</StatusBadge></span>
            <span class="join-method" role="cell">{{ member.joinMethod }}</span>
            <span class="joined-at" role="cell">{{ member.joinedAt ? new Date(member.joinedAt).toLocaleDateString('zh-CN') : '—' }}</span>
            <span class="version mono" role="cell">v{{ member.version }} <Check v-if="member.id === team?.ownerMemberId" :size="12" aria-label="Team Owner" /></span>
          </div>
        </div>
      </section>

      <TeamInvitationManager
        v-if="canManageMembers && team"
        :organization-id="team.organizationId"
        :team-id="team.id"
      />

      <section class="permission-note"><ShieldCheck :size="17" /><div><strong>权限守卫只改善界面体验</strong><span>导航和写按钮按当前会话权限显示；每次读取和成员添加仍由服务端执行 ACTIVE Membership、Team Scope Role 与目标 Principal 校验。</span></div></section>
    </div>
  </AppShell>
</template>

<style scoped>
.member-summary { display: grid; grid-template-columns: 50px 1fr auto; align-items: center; gap: 15px; padding: 21px 23px; background: linear-gradient(135deg, var(--cs-surface), var(--cs-info-soft)); }.summary-icon { display: grid; width: 50px; height: 50px; place-items: center; border-radius: 15px; background: var(--cs-info-soft); color: var(--cs-info); }.member-summary h2 { margin-bottom: 5px; font-size: 18px; }.member-summary p:last-child { max-width: 720px; margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.add-member { padding: 20px; }.add-member__heading { display: grid; grid-template-columns: 40px 1fr 30px; gap: 12px; margin-bottom: 17px; }.add-member__heading > i { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 11px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.add-member__heading h2 { margin-bottom: 3px; font-size: 14px; }.add-member__heading p { margin: 0; color: var(--cs-text-muted); font-size: 10px; }.add-member__heading > button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.add-member > label { display: block; margin-bottom: 5px; color: var(--cs-text-secondary); font-size: 10px; font-weight: 750; }.principal-input { display: grid; max-width: 700px; grid-template-columns: 1fr auto; gap: 8px; }.principal-input input { min-width: 0; min-height: 36px; padding: 0 11px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); font-size: 11px; }.principal-input input[aria-invalid="true"] { border-color: var(--cs-danger); }.field-error { margin: 7px 0 0; color: var(--cs-danger); font-size: 10px; }
.member-directory { overflow: hidden; }.member-table__head, .member-row { display: grid; grid-template-columns: minmax(250px, 1.5fr) 110px 120px 120px 70px; align-items: center; gap: 10px; padding-inline: 20px; }.member-table__head { min-height: 38px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .06em; text-transform: uppercase; }.member-row { min-height: 72px; border-bottom: 1px solid var(--cs-border); }.member-row:last-child { border-bottom: 0; }.member-identity { display: flex; align-items: center; gap: 10px; min-width: 0; }.member-identity > i { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border-radius: 50%; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 11px; font-style: normal; font-weight: 800; }.member-identity strong, .member-identity small { display: flex; align-items: center; gap: 5px; }.member-identity strong { font-size: 11px; }.member-identity strong em { padding: 1px 5px; border-radius: var(--cs-radius-pill); background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 8px; font-style: normal; }.member-identity small { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.join-method, .joined-at, .version { color: var(--cs-text-secondary); font-size: 10px; }.version { display: flex; align-items: center; gap: 5px; }.version svg { color: var(--cs-success); }
.permission-note { display: flex; align-items: flex-start; gap: 10px; padding: 13px 15px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.permission-note > svg { flex: 0 0 auto; color: var(--cs-brand-600); }.permission-note strong, .permission-note span { display: block; }.permission-note strong { color: var(--cs-text-secondary); font-size: 10px; }.permission-note span { margin-top: 2px; font-size: 9px; }
@media (max-width: 850px) { .member-table__head { display: none; }.member-row { grid-template-columns: 1fr auto; gap: 8px; padding-block: 12px; }.member-row > .join-method, .member-row > .joined-at { display: none; }.version { grid-column: 2; }.member-summary { grid-template-columns: 44px 1fr; }.member-summary > :last-child { grid-column: 1 / -1; justify-self: start; } }
@media (max-width: 767px) { .member-summary { padding: 17px; }.summary-icon { width: 44px; height: 44px; }.add-member { padding: 16px; }.principal-input { grid-template-columns: 1fr; }.member-table__head, .member-row { padding-inline: 15px; }.member-row { grid-template-columns: 1fr auto; }.member-directory .panel-heading { align-items: flex-start; flex-direction: column; } }
</style>
