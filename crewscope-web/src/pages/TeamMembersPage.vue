<script setup lang="ts">
import { Check, Copy, Plus, ShieldCheck, UserRoundPlus, UsersRound, X } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import MemberActionMenu, { type MemberAction } from '../components/team/MemberActionMenu.vue'
import MemberLifecycleDialog from '../components/team/MemberLifecycleDialog.vue'
import ResponsibilityHandoverDialog from '../components/team/ResponsibilityHandoverDialog.vue'
import TeamInvitationManager from '../components/team/TeamInvitationManager.vue'
import { CrewScopeApiError } from '../api/client'
import { useNetworkStatus } from '../app/network'
import { useScopeStore } from '../domains/scope/store'
import type { TeamMemberSummary } from '../domains/scope/types'
import { useAuthStore } from '../domains/identity/store'
import { enumLabel } from '../domains/shared/labels'
import { teamJoinMethodLabels, teamMemberStatusLabels, teamRoleLabels } from '../domains/scope/labels'
import { useClipboard } from '../composables/useClipboard'
import { useRouteFocus } from '../composables/useRouteFocus'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const principal = inject(AUTH_PRINCIPAL)
const store = useScopeStore()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const online = useNetworkStatus()
const team = store.selectedTeam
// Configuration search deep-links a member into this list with `?member=`; the parameter is read-only.
const { locatedId, bindRow, clear: clearLocated } = useRouteFocus('member')
const locatedMember = computed(() => store.state.members.find(member => member.id === locatedId.value) ?? null)
const showAddMember = ref(false)
const targetPrincipalId = ref('')
const submitted = ref(false)
const canManageMembers = computed(() => Boolean(principal && can(principal, permissions.teamMembersManage)))
const canManageRoles = computed(() => Boolean(principal && can(principal, permissions.teamRolesManage)))
const clipboard = useClipboard()
const activeMembers = computed(() => store.state.members.filter(member => member.status === 'ACTIVE'))
const rejoinCandidates = computed(() => store.state.members.filter(member => member.status !== 'ACTIVE'))
const principalIdValid = computed(() => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(targetPrincipalId.value.trim()))
const viewerMember = computed(() => store.state.members.find(member => member.userPrincipalId === principal?.id) ?? null)
const viewerIsOwner = computed(() => Boolean(viewerMember.value && team.value && viewerMember.value.id === team.value.ownerMemberId))

/** `?tab=members|invitations` — anything else falls back to the member directory. */
const tab = computed<'members' | 'invitations'>(() => (route.query.tab === 'invitations' ? 'invitations' : 'members'))

const lifecycleTarget = ref<TeamMemberSummary | null>(null)
const lifecycleAction = ref<MemberAction | null>(null)
const lifecycleProblem = ref<{ title: string, message: string } | null>(null)

const handoverSource = ref<TeamMemberSummary | null>(null)
const handoverPreviewCount = ref<number | null>(null)
const handoverCandidates = computed(() =>
  store.state.members.filter(member => member.status === 'ACTIVE' && member.id !== handoverSource.value?.id))

watch(() => store.state.selectedTeamId, () => {
  showAddMember.value = false
  targetPrincipalId.value = ''
  submitted.value = false
  closeLifecycle()
  closeHandover()
  void store.loadMembers()
}, { immediate: true })

async function switchTab(next: 'members' | 'invitations'): Promise<void> {
  if (tab.value === next) return
  await router.replace({ query: { ...route.query, tab: next } })
}

async function addMember(): Promise<void> {
  const pageOwner = pageRequests.capture()
  submitted.value = true
  if (!principalIdValid.value) return
  try {
    await store.addMember(targetPrincipalId.value)
    if (!pageOwner.isCurrent()) return
    targetPrincipalId.value = ''
    submitted.value = false
    showAddMember.value = false
  } catch {
    // The store exposes a sanitized message while the global error boundary keeps technical detail private.
  }
}

function onMemberAction(member: TeamMemberSummary, action: MemberAction): void {
  if (action.type === 'reinvite') {
    void switchTab('invitations')
    return
  }
  if (action.type === 'handover') {
    openHandover(member)
    return
  }
  lifecycleTarget.value = member
  lifecycleAction.value = action
  lifecycleProblem.value = null
}

function closeLifecycle(): void {
  lifecycleTarget.value = null
  lifecycleAction.value = null
  lifecycleProblem.value = null
}

async function confirmLifecycle(roleKey: string): Promise<void> {
  const target = lifecycleTarget.value
  const action = lifecycleAction.value
  if (!target || !action) return
  const pageOwner = pageRequests.capture()
  lifecycleProblem.value = null
  try {
    switch (action.type) {
      case 'suspend': await store.suspendMember(target.id); break
      case 'activate': await store.activateMember(target.id); break
      case 'remove': await store.removeMember(target.id); break
      case 'leave': await store.leaveTeam(); break
      case 'transferOwnership': await store.transferOwnership(target.id); break
      case 'grantRole': await store.grantRole(target.id, roleKey); break
      case 'revokeRole': await store.revokeRole(target.id, action.grantId); break
      default: return
    }
    if (!pageOwner.isCurrent()) return
    if (action.type === 'leave') {
      closeLifecycle()
      await afterLeave(pageOwner)
      return
    }
    closeLifecycle()
  } catch (error) {
    if (!pageOwner.isCurrent()) return
    lifecycleProblem.value = lifecycleProblemText(error)
    if (error instanceof CrewScopeApiError && error.status === 409) void store.loadMembers(true)
  }
}

/** Leaving revokes the viewer's own membership: refresh the session, then leave the page. */
async function afterLeave(pageOwner: ReturnType<typeof pageRequests.capture>): Promise<void> {
  await authStore.refresh()
  if (!pageOwner.isCurrent()) return
  const teams = authStore.state.session?.teams ?? []
  store.reset()
  if (!teams.length) {
    await router.replace({ name: 'onboarding' })
    return
  }
  await store.synchronize(teams[0]!.teamId)
  if (!pageOwner.isCurrent()) return
  await router.replace({ name: 'conversation', query: { team: teams[0]!.teamId } })
}

function lifecycleProblemText(error: unknown): { title: string, message: string } {
  if (error instanceof CrewScopeApiError) {
    if (error.envelope.code === 'last_owner_protection') {
      return {
        title: '最后一个 Owner 保护',
        message: '这个 Team 只剩一个有效 Owner，服务端拒绝了该操作。请先把 Owner 转让给其他成员，再退出或降权。',
      }
    }
    if (error.status === 409 || error.envelope.code === 'optimistic_lock_conflict') {
      return {
        title: '成员状态已变化',
        message: '其他操作先一步改变了这位成员的状态。成员列表会重新加载，请基于最新状态重试。',
      }
    }
    return { title: '操作未完成', message: error.envelope.message }
  }
  return { title: '操作未完成', message: '暂时无法完成该操作，请稍后重试。' }
}

function openHandover(member: TeamMemberSummary): void {
  handoverSource.value = member
  handoverPreviewCount.value = null
  store.clearHandover()
  void previewResponsibilities('OWNER')
}

function closeHandover(): void {
  handoverSource.value = null
  handoverPreviewCount.value = null
  store.clearHandover()
}

async function previewResponsibilities(role: string): Promise<void> {
  const source = handoverSource.value
  if (!source) return
  handoverPreviewCount.value = null
  try {
    const items = await store.previewResponsibilities(source.id, role)
    if (handoverSource.value?.id === source.id) handoverPreviewCount.value = items.length
  } catch {
    if (handoverSource.value?.id === source.id) handoverPreviewCount.value = null
  }
}

async function createHandover(targetPrincipalId: string, role: string): Promise<void> {
  const source = handoverSource.value
  if (!source) return
  try {
    await store.createHandover({ sourceMemberId: source.id, targetPrincipalId, role })
  } catch {
    // The store exposes handoverErrorMessage; the dialog renders it inline.
  }
}

async function processHandover(): Promise<void> {
  const job = store.state.handoverJob
  if (!job) return
  try {
    await store.processHandover(job.id)
  } catch {
    // Rendered inline from the store state.
  }
}

async function cancelHandoverJob(): Promise<void> {
  const job = store.state.handoverJob
  if (!job) return
  try {
    await store.cancelHandover(job.id)
  } catch {
    // Rendered inline from the store state.
  }
}

function shortId(value: string): string {
  return `${value.slice(0, 8)}…${value.slice(-4)}`
}

function memberStatusLabel(value: string): string {
  return enumLabel(value, teamMemberStatusLabels)
}

function statusTone(value: string): 'success' | 'warning' | 'neutral' {
  if (value === 'ACTIVE') return 'success'
  if (value === 'SUSPENDED') return 'warning'
  return 'neutral'
}

function joinMethodLabel(value: string): string {
  return enumLabel(value, teamJoinMethodLabels)
}

function roleLabels(roles?: string[]): string {
  if (!roles?.length) return '成员'
  return roles.map(role => enumLabel(role, teamRoleLabels)).join('、')
}

function copyPrincipal(member: { userPrincipalId: string }): void { void clipboard.copy(member.userPrincipalId, member.userPrincipalId) }

</script>

<template>
  <SettingsShell eyebrow="团队 · 成员管理" :title="`${team?.name ?? 'Team'} 成员`">
    <template #actions>
      <BaseButton v-if="canManageMembers && tab === 'members'" size="small" @click="showAddMember = true"><Plus :size="14" />添加成员</BaseButton>
    </template>

    <StatePanel v-if="store.state.phase === 'loading' || store.state.phase === 'idle'" state="loading" />
    <StatePanel v-else-if="store.state.phase === 'error'" state="error" :description="store.state.errorMessage ?? undefined" @retry="store.reload" />
    <StatePanel v-else-if="store.state.phase === 'empty'" state="empty" title="还没有可访问的 Team"><template #action><RouterLink :to="{ name: 'onboarding' }"><BaseButton size="small">创建或加入 Team</BaseButton></RouterLink></template></StatePanel>

    <div v-else class="members-page page-shell">
      <nav class="members-tabs" :aria-label="`${team?.name ?? 'Team'} 成员管理视图`">
        <RouterLink
          :to="{ query: { ...route.query, tab: 'members' } }"
          class="members-tabs__tab"
          :class="{ 'members-tabs__tab--active': tab === 'members' }"
          :aria-current="tab === 'members' ? 'page' : undefined"
          replace
        >成员目录</RouterLink>
        <RouterLink
          :to="{ query: { ...route.query, tab: 'invitations' } }"
          class="members-tabs__tab"
          :class="{ 'members-tabs__tab--active': tab === 'invitations' }"
          :aria-current="tab === 'invitations' ? 'page' : undefined"
          replace
        >加入邀请</RouterLink>
      </nav>

      <template v-if="tab === 'members'">
        <section class="member-summary panel">
          <div class="summary-icon"><UsersRound :size="23" /></div>
          <div><p class="eyebrow">Active membership</p><h2>{{ activeMembers.length }} 位活跃成员</h2><p>Membership 决定 Team 可见性；TeamRole 决定管理动作。停用与移除会立即在全部通道收回访问，恢复不会复活旧的角色授权。</p></div>
          <StatusBadge tone="success" dot>{{ team?.initializationStatus === 'READY' ? 'Team Ready' : '等待初始化' }}</StatusBadge>
        </section>

        <form v-if="showAddMember && canManageMembers" class="add-member panel" @submit.prevent="addMember">
          <div class="add-member__heading"><i><UserRoundPlus :size="19" /></i><div><h2>添加已有用户</h2><p>输入同一 Organization 下的 ACTIVE USER Principal ID。服务端会重新验证身份、Scope 与 MEMBER_MANAGE 权限。</p></div><button type="button" aria-label="关闭添加成员" @click="showAddMember = false"><X :size="17" /></button></div>
          <label for="principal-id">选择身份</label>
          <div class="principal-input"><select id="principal-id" v-model="targetPrincipalId" :aria-invalid="submitted && !principalIdValid"><option value="">请选择待重新加入的身份</option><option v-for="member in rejoinCandidates" :key="member.userPrincipalId" :value="member.userPrincipalId">{{ member.displayName }} · {{ memberStatusLabel(member.status) }}</option></select><BaseButton type="submit" size="small" :loading="store.state.memberCommandPending">确认添加</BaseButton></div>
          <p v-if="!rejoinCandidates.length" class="field-hint">当前没有可重新加入的身份；新成员请切换到“加入邀请”创建一次性邀请链接。</p>
          <p v-if="submitted && !principalIdValid" class="field-error" role="alert">请选择一个身份。</p>
          <p v-if="store.state.membersErrorMessage" class="field-error" role="alert">{{ store.state.membersErrorMessage }}</p>
        </form>

        <section class="panel member-directory">
          <div class="panel-heading"><div><p class="eyebrow">Member directory</p><h2>团队成员</h2><p>展示身份目录中的显示名、成员状态与加入事实；行内菜单按状态与权限提供生命周期动作。</p></div><BaseButton v-if="canManageMembers && !showAddMember" variant="secondary" size="small" @click="showAddMember = true"><Plus :size="14" />添加成员</BaseButton></div>
          <p v-if="locatedId" class="locate-note" role="status">
            <span v-if="locatedMember">已定位到 {{ locatedMember.displayName }}。</span>
            <span v-else-if="store.state.membersLoading">正在加载成员名单，加载完成后显示定位结果。</span>
            <span v-else>当前 Team 的成员列表里没有这个成员：该成员可能已退出或被移除。</span>
            <BaseButton variant="ghost" size="small" @click="clearLocated()">清除定位</BaseButton>
          </p>
          <StatePanel v-if="store.state.membersLoading" state="loading" />
          <StatePanel v-else-if="store.state.membersErrorMessage" state="error" :description="store.state.membersErrorMessage" @retry="store.loadMembers(true)" />
          <StatePanel v-else-if="store.state.members.length === 0" state="empty" title="暂时没有成员事实"><template v-if="canManageMembers" #action><BaseButton size="small" @click="showAddMember = true"><Plus :size="14" />添加成员</BaseButton></template></StatePanel>
          <div v-else class="member-table" role="table" aria-label="团队成员列表">
            <div class="member-table__head" role="row"><span role="columnheader">成员</span><span role="columnheader">角色</span><span role="columnheader">状态</span><span role="columnheader">加入方式</span><span role="columnheader">加入时间</span><span role="columnheader"><span class="sr-only">操作</span></span></div>
            <div
              v-for="member in store.state.members"
              :key="member.id"
              :ref="element => bindRow(element, member.id)"
              class="member-row"
              :class="{ 'member-row--located': member.id === locatedId }"
              role="row"
            >
              <div class="member-identity" role="cell"><i>{{ member.displayName.slice(0, 1) }}</i><span><strong>{{ member.displayName }} <em v-if="member.userPrincipalId === principal?.id">你</em><em v-if="member.id === team?.ownerMemberId">Owner</em></strong><small class="mono" :title="member.userPrincipalId">{{ shortId(member.userPrincipalId) }} <button type="button" class="copy-principal" :aria-label="`复制 ${member.displayName} 的 Principal ID`" @click="copyPrincipal(member)"><Check v-if="clipboard.copied.value === member.userPrincipalId" :size="11" /><Copy v-else :size="11" /></button></small></span></div>
              <span class="member-roles" role="cell">{{ roleLabels(member.roles) }}</span>
              <span role="cell"><StatusBadge :tone="statusTone(member.status)" dot>{{ memberStatusLabel(member.status) }}</StatusBadge></span>
              <span class="join-method" role="cell">{{ joinMethodLabel(member.joinMethod) }}</span>
              <span class="joined-at" role="cell">{{ member.joinedAt ? new Date(member.joinedAt).toLocaleDateString('zh-CN') : '—' }}</span>
              <span class="member-actions" role="cell">
                <MemberActionMenu
                  :member="member"
                  :is-self="member.userPrincipalId === principal?.id"
                  :viewer-is-owner="viewerIsOwner"
                  :can-manage-members="canManageMembers"
                  :can-manage-roles="canManageRoles"
                  :pending="store.state.memberCommandPending"
                  @action="action => onMemberAction(member, action)"
                />
              </span>
            </div>
          </div>
        </section>
      </template>

      <template v-else>
        <TeamInvitationManager
          v-if="canManageMembers && team"
          :organization-id="team.organizationId"
          :team-id="team.id"
        />
        <StatePanel v-else state="empty" title="需要成员管理权限" description="创建与撤销加入邀请需要 team:members:manage 权限。" />
      </template>

      <section class="permission-note"><ShieldCheck :size="17" /><div><strong>权限守卫只改善界面体验</strong><span>导航和写按钮按当前会话权限显示；每次读取、生命周期命令与成员添加仍由服务端执行 ACTIVE Membership、Team Scope Role、If-Match 版本与目标 Principal 校验。</span></div></section>
    </div>

    <MemberLifecycleDialog
      :member="lifecycleTarget"
      :action="lifecycleAction"
      :pending="store.state.memberCommandPending"
      :problem="lifecycleProblem"
      :online="online"
      @confirm="confirmLifecycle"
      @cancel="closeLifecycle"
    />

    <ResponsibilityHandoverDialog
      :open="Boolean(handoverSource)"
      :source="handoverSource"
      :candidates="handoverCandidates"
      :pending="store.state.handoverPending"
      :problem="store.state.handoverErrorMessage"
      :job="store.state.handoverJob"
      :preview-count="handoverPreviewCount"
      @close="closeHandover"
      @preview="previewResponsibilities"
      @create="createHandover"
      @process="processHandover"
      @cancel-job="cancelHandoverJob"
    />
  </SettingsShell>
</template>

<style scoped>
.member-summary { display: grid; grid-template-columns: 50px 1fr auto; align-items: center; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-24); background: linear-gradient(135deg, var(--cs-surface), var(--cs-info-soft)); }.summary-icon { display: grid; width: 50px; height: 50px; place-items: center; border-radius: 15px; background: var(--cs-info-soft); color: var(--cs-info); }.member-summary h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-lg); }.member-summary p:last-child { max-width: 720px; margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.members-tabs { display: inline-flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 11px; background: var(--cs-surface); }.members-tabs__tab { display: inline-flex; align-items: center; min-height: var(--cs-density-control-height); padding: var(--cs-space-8) var(--cs-space-16); border-radius: 8px; color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); text-decoration: none; }.members-tabs__tab:hover, .members-tabs__tab:focus-visible { color: var(--cs-text-brand); }.members-tabs__tab--active { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.add-member { padding: var(--cs-space-20); }.add-member__heading { display: grid; grid-template-columns: 40px 1fr 30px; gap: var(--cs-space-12); margin-bottom: var(--cs-space-16); }.add-member__heading > i { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 11px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.add-member__heading h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-base); }.add-member__heading p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.add-member__heading > button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.add-member > label { display: block; margin-bottom: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.principal-input { display: grid; max-width: 700px; grid-template-columns: 1fr auto; gap: var(--cs-space-8); }.principal-input input { min-width: 0; min-height: 36px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); font-size: var(--cs-text-base); }.principal-input input[aria-invalid="true"] { border-color: var(--cs-danger); }.field-error { margin: var(--cs-space-8) 0 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.member-directory { overflow: visible; }.member-table__head, .member-row { display: grid; grid-template-columns: minmax(220px, 1.5fr) 110px 110px 110px 70px 44px; align-items: center; gap: var(--cs-space-12); padding-inline: var(--cs-space-20); }.member-table__head { min-height: 38px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .06em; text-transform: uppercase; }.member-row { min-height: 72px; border-bottom: 1px solid var(--cs-border); }.member-row:last-child { border-bottom: 0; }.member-identity { display: flex; align-items: center; gap: var(--cs-space-12); min-width: 0; }.member-identity > i { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-style: normal; font-weight: var(--cs-weight-semibold); }.member-identity strong, .member-identity small { display: flex; align-items: center; gap: var(--cs-space-4); }.member-identity strong { font-size: var(--cs-text-sm); }.member-identity strong em { padding: var(--cs-space-2) var(--cs-space-4); border-radius: var(--cs-radius-pill); background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-style: normal; }.member-identity small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.join-method, .joined-at, .version { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.version { display: flex; align-items: center; gap: var(--cs-space-4); }.version svg { color: var(--cs-success); }.member-actions { display: grid; justify-items: end; }
.permission-note { display: flex; align-items: flex-start; gap: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.permission-note > svg { flex: 0 0 auto; color: var(--cs-text-brand); }.permission-note strong, .permission-note span { display: block; }.permission-note strong { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.permission-note span { margin-top: var(--cs-space-2); font-size: var(--cs-text-xs); }
@media (max-width: 850px) { .member-table__head { display: none; }.member-row { grid-template-columns: 1fr auto; gap: var(--cs-space-8); padding-block: var(--cs-space-12); }.member-row > .join-method, .member-row > .joined-at { display: none; }.member-row > .member-roles { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.version { grid-column: 2; }.member-actions { grid-row: 1; grid-column: 2; }.member-summary { grid-template-columns: 44px 1fr; }.member-summary > :last-child { grid-column: 1 / -1; justify-self: start; } }
@media (max-width: 767px) { .member-summary { padding: var(--cs-space-16); }.summary-icon { width: 44px; height: 44px; }.add-member { padding: var(--cs-space-16); }.principal-input { grid-template-columns: 1fr; }.member-table__head, .member-row { padding-inline: var(--cs-space-16); }.locate-note { margin-inline: var(--cs-space-16); }.member-directory .panel-heading { align-items: flex-start; flex-direction: column; } }
.copy-principal { display: inline-grid; width: 20px; height: 20px; place-items: center; border-radius: 5px; color: var(--cs-text-muted); vertical-align: middle; cursor: pointer; }
.copy-principal:hover, .copy-principal:focus-visible { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
/* 配置搜索的 `?member=` 深链：只在参数存在时渲染，参数不存在时不产生任何节点。 */
.locate-note { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--cs-space-4) var(--cs-space-12); min-height: var(--cs-density-control-height); margin: var(--cs-space-12) var(--cs-space-20) 0; padding: var(--cs-space-4) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent); color: var(--cs-text-brand); font-size: var(--cs-text-sm); }
.locate-note > span { min-width: 0; }
.member-row--located { background: var(--cs-surface-accent); box-shadow: inset 3px 0 0 var(--cs-text-brand); }
</style>
