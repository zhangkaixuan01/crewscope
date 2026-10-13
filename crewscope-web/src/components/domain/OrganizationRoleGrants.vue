<script setup lang="ts">
import { X } from '@lucide/vue'
import { computed, inject, nextTick, onMounted, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import { AUTH_PRINCIPAL, can, permissions } from '../../app/auth'
import { AUTH_STORE } from '../../domains/identity/store'
import { createFormCommandKeys } from '../../api/formCommandKeys'
import BaseBadge from '../base/BaseBadge.vue'
import BaseButton from '../base/BaseButton.vue'
import BaseTable from '../base/BaseTable.vue'
import StatePanel from '../feedback/StatePanel.vue'
import { organizationRoleLabels, roleGrantStatusLabels } from '../../domains/organization/labels'
import { organizationProblem } from '../../domains/organization/presentation'
import type { OrganizationRoleGrant } from '../../domains/organization/types'
import { useOrganizationStore } from '../../domains/organization/store'
import { enumLabel } from '../../domains/shared/labels'

/**
 * M12-F01 roles tab: the role-grant history is the authorization audit face (the org-grain
 * audit listing does not exist), so revoked rows stay visible behind the status filter.
 */
const props = defineProps<{ organizationId: string }>()

const principal = inject(AUTH_PRINCIPAL)
const authStore = inject(AUTH_STORE)
const store = useOrganizationStore()
const canWrite = computed(() => Boolean(principal && can(principal, permissions.orgManageSettings)))
// Granting ORG_OWNER is the owner's move alone; the session role is the only client-side
// signal (the permission vocabulary cannot distinguish ADMIN from OWNER).
const sessionOwner = computed(() => authStore?.state.session?.organization?.role === 'ORG_OWNER')
const grants = computed(() => store.state.roleGrants)
const roleCommand = computed(() => store.state.roleCommand)
const problem = computed(() => roleCommand.value.phase === 'error'
  ? organizationProblem(roleCommand.value.errorStatus, roleCommand.value.errorCode, 'grant') : null)

const statusFilter = ref<'all' | 'ACTIVE' | 'REVOKED'>('all')
const filteredGrants = computed(() => {
  const value = grants.value.value ?? []
  return statusFilter.value === 'all' ? value : value.filter(grant => grant.status === statusFilter.value)
})

const intentKeys = createFormCommandKeys()
const grantPrincipal = ref('')
const grantRole = ref<'ORG_AUDITOR' | 'ORG_ADMIN' | 'ORG_OWNER'>('ORG_AUDITOR')
const grantReason = ref('')
const grantSubmitted = ref(false)
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const principalValid = computed(() => uuidPattern.test(grantPrincipal.value.trim()))
const reasonTooLong = computed(() => grantReason.value.trim().length > 500)
const grantValid = computed(() => principalValid.value && !reasonTooLong.value)
const grantDisabledReason = computed(() => {
  if (!principalValid.value) return '请输入有效的 Principal ID（UUID 格式）。'
  if (reasonTooLong.value) return '原因最长 500 字。'
  if (roleCommand.value.phase === 'pending') return '正在提交。'
  return ''
})

const revoking = ref<OrganizationRoleGrant | null>(null)
const revokeReason = ref('')
const revokeSubmitted = ref(false)
const dialog = useTemplateRef<HTMLElement>('dialog')
const revokeReasonInput = ref<HTMLInputElement | null>(null)

onMounted(() => { void store.loadRoleGrants(props.organizationId) })

// A revoked grant may be the operator's own role — refresh the session so the surrounding
// page re-renders its permission-gated surfaces from the new vocabulary immediately (D2).
watch(() => roleCommand.value.phase, phase => {
  if (phase !== 'success') return
  void authStore?.refresh()
  grantPrincipal.value = ''
  grantReason.value = ''
  grantSubmitted.value = false
  if (revoking.value) closeRevoke()
})

watch(revoking, grant => {
  if (grant) void nextTick(() => (revokeReasonInput.value ?? dialog.value)?.focus())
})

async function submitGrant(): Promise<void> {
  grantSubmitted.value = true
  if (!grantValid.value) return
  store.clearRoleCommand()
  const ok = await store.grantRole(props.organizationId, {
    principalId: grantPrincipal.value.trim(),
    role: grantRole.value,
    reason: grantReason.value.trim() || undefined,
  }, intentKeys.forInput([grantPrincipal.value.trim(), grantRole.value]))
  if (!ok) grantSubmitted.value = false
}

function openRevoke(grant: OrganizationRoleGrant): void {
  store.clearRoleCommand()
  revoking.value = grant
  revokeReason.value = ''
  revokeSubmitted.value = false
}

function closeRevoke(): void {
  if (roleCommand.value.phase === 'pending') return
  revoking.value = null
}

async function submitRevoke(): Promise<void> {
  const grant = revoking.value
  if (!grant) return
  revokeSubmitted.value = true
  if (revokeReason.value.trim().length > 500) return
  await store.revokeRoleGrant(props.organizationId, grant.id, {
    reason: revokeReason.value.trim() || undefined,
  }, grant.version, intentKeys.forInput([grant.id]))
}

function handleKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(dialog.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    closeRevoke()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)')]
  const first = controls[0]
  const last = controls.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function dateText(value: string | null): string {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '—'
}
</script>

<template>
  <div class="org-roles">
    <StatePanel v-if="grants.phase === 'loading' || grants.phase === 'idle'" state="loading" />
    <StatePanel
      v-else-if="grants.phase === 'error'"
      state="error"
      description="角色授予记录读取失败。"
      @retry="store.loadRoleGrants(organizationId, true)"
    />
    <StatePanel v-else-if="grants.phase === 'forbidden'" state="forbidden" title="需要组织审计角色" description="组织 Owner、组织管理员或组织审计员可以查看角色授予记录。">
      <template #action>
        <BaseButton variant="secondary" size="small" @click="authStore?.refresh()">刷新会话</BaseButton>
      </template>
    </StatePanel>
    <template v-else>
      <fieldset class="org-filter">
        <legend class="sr-only">状态筛选</legend>
        <label><input v-model="statusFilter" type="radio" name="grant-status" value="all" />全部</label>
        <label><input v-model="statusFilter" type="radio" name="grant-status" value="ACTIVE" />有效</label>
        <label><input v-model="statusFilter" type="radio" name="grant-status" value="REVOKED" />已撤销</label>
      </fieldset>
      <p v-if="filteredGrants.length === 0" class="org-roles-empty">没有匹配的角色授予记录。</p>
      <BaseTable v-else caption="组织角色授予记录">
        <thead>
          <tr>
            <th scope="col">Principal</th>
            <th scope="col">角色</th>
            <th scope="col">状态</th>
            <th scope="col">授予人</th>
            <th scope="col">授予时间</th>
            <th scope="col">撤销时间</th>
            <th scope="col">原因</th>
            <th v-if="canWrite" scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="grant in filteredGrants" :key="grant.id">
            <td class="org-mono" :title="grant.principalId">{{ grant.principalId }}</td>
            <td><BaseBadge>{{ enumLabel(grant.role, organizationRoleLabels) }}</BaseBadge></td>
            <td><BaseBadge>{{ enumLabel(grant.status, roleGrantStatusLabels) }}</BaseBadge></td>
            <td class="org-mono" :title="grant.grantedByPrincipalId">{{ grant.grantedByPrincipalId }}</td>
            <td>{{ dateText(grant.grantedAt) }}</td>
            <td>{{ dateText(grant.revokedAt) }}</td>
            <td>{{ grant.reason ?? '—' }}</td>
            <td v-if="canWrite">
              <BaseButton
                v-if="grant.status === 'ACTIVE'"
                variant="ghost"
                size="small"
                @click="openRevoke(grant)"
              >撤销</BaseButton>
              <span v-else>—</span>
            </td>
          </tr>
        </tbody>
      </BaseTable>
      <p class="org-note">已撤销的记录保留为授权审计历史；操作明细见审计中心。</p>
    </template>

    <form v-if="canWrite" class="org-grant-form" @submit.prevent="submitGrant">
      <h3>授予组织角色</h3>
      <div class="org-grant-fields">
        <label for="org-grant-principal">Principal ID（本组织成员）</label>
        <input
          id="org-grant-principal"
          v-model="grantPrincipal"
          type="text"
          inputmode="text"
          autocomplete="off"
          :aria-invalid="grantSubmitted && !principalValid"
          aria-describedby="org-grant-principal-reason"
          placeholder="00000000-0000-0000-0000-000000000000"
        />
        <p id="org-grant-principal-reason" class="org-reason" aria-live="polite">
          {{ grantSubmitted && !principalValid ? '请输入有效的 Principal ID（UUID 格式）。' : '仅可授予本组织的 Principal；授予权限由服务端校验。' }}
        </p>
        <label for="org-grant-role">角色</label>
        <select id="org-grant-role" v-model="grantRole">
          <option value="ORG_AUDITOR">组织审计员</option>
          <option value="ORG_ADMIN">组织管理员</option>
          <option v-if="sessionOwner" value="ORG_OWNER">组织 Owner</option>
        </select>
        <!-- The OWNER option is not merely hidden: a non-owner admin must see the move exists
          but is theirs not to make, so the rule stays auditable in the UI. -->
        <p v-if="!sessionOwner" class="org-reason">组织 Owner 角色只能由现任 Owner 授予。</p>
        <label for="org-grant-reason">原因（可选，最长 500 字）</label>
        <input id="org-grant-reason" v-model="grantReason" type="text" :aria-invalid="reasonTooLong" />
        <p v-if="roleCommand.phase === 'error'" role="alert" class="org-error">
          {{ problem?.title ? `${problem.title}：${problem.message}` : roleCommand.errorMessage }}
        </p>
        <p v-else-if="roleCommand.phase === 'success'" class="org-ok" role="status">角色命令已受理，列表已刷新。</p>
        <div class="org-actions">
          <BaseButton type="submit" :disabled="Boolean(grantDisabledReason) || roleCommand.phase === 'pending'">
            {{ roleCommand.phase === 'pending' ? '提交中…' : '授予角色' }}
          </BaseButton>
        </div>
        <p v-if="grantDisabledReason" class="org-reason" aria-live="polite">{{ grantDisabledReason }}</p>
      </div>
    </form>

    <div v-if="revoking" class="org-revoke-backdrop" @click.self="closeRevoke">
      <form
        ref="dialog"
        class="org-revoke-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="org-revoke-title"
        tabindex="-1"
        @submit.prevent="submitRevoke"
        @keydown="handleKeydown"
      >
        <header>
          <h2 id="org-revoke-title">撤销组织角色</h2>
          <BaseButton variant="ghost" size="small" :disabled="roleCommand.phase === 'pending'" @click="closeRevoke"><X :size="14" aria-hidden="true" />关闭</BaseButton>
        </header>
        <p>
          将撤销 <span class="org-mono">{{ revoking.principalId }}</span> 的
          {{ enumLabel(revoking.role, organizationRoleLabels) }}（版本 {{ revoking.version }}）。
        </p>
        <label for="org-revoke-reason">撤销原因（可选，最长 500 字）</label>
        <input
          id="org-revoke-reason"
          ref="revokeReasonInput"
          v-model="revokeReason"
          type="text"
          :aria-invalid="revokeSubmitted && revokeReason.trim().length > 500"
        />
        <p v-if="roleCommand.phase === 'error'" role="alert" class="org-error">
          {{ problem?.title ? `${problem.title}：${problem.message}` : roleCommand.errorMessage }}
        </p>
        <div class="org-actions">
          <BaseButton type="submit" :disabled="roleCommand.phase === 'pending'">
            {{ roleCommand.phase === 'pending' ? '撤销中…' : '确认撤销' }}
          </BaseButton>
          <BaseButton variant="ghost" :disabled="roleCommand.phase === 'pending'" @click="closeRevoke">取消</BaseButton>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.org-roles { display: grid; gap: var(--cs-space-16); }
.org-filter { display: flex; gap: var(--cs-space-16); margin: 0; padding: 0; border: 0; }
.org-filter label { display: flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-roles-empty { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-mono { font-family: var(--cs-font-mono, monospace); font-size: var(--cs-text-sm); max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.org-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-grant-form { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.org-grant-form h3 { margin: 0; font-size: var(--cs-text-md); color: var(--cs-text); }
.org-grant-fields { display: grid; gap: var(--cs-space-4); max-width: 480px; }
.org-grant-fields label { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-grant-fields input, .org-grant-fields select { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); font-size: var(--cs-text-base); }
.org-reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-error { margin: 0; color: var(--cs-text-danger, #b3261e); font-size: var(--cs-text-sm); }
.org-ok { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-actions { display: flex; gap: var(--cs-space-8); }
.org-revoke-backdrop { position: fixed; inset: 0; display: grid; place-items: center; padding: var(--cs-space-16); background: var(--cs-scrim, rgba(0, 0, 0, 0.4)); z-index: 60; }
.org-revoke-dialog { display: grid; gap: var(--cs-space-12); width: min(480px, 100%); padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.org-revoke-dialog header { display: flex; align-items: center; justify-content: space-between; }
.org-revoke-dialog h2 { margin: 0; font-size: var(--cs-text-md); }
.org-revoke-dialog p { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-revoke-dialog label { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-revoke-dialog input { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }
</style>
