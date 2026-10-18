<script lang="ts">
import type { MemberAction } from './MemberActionMenu.vue'

export interface LifecycleDialogProblem {
  title: string
  message: string
}
</script>

<script setup lang="ts">
import BaseSelect from '../base/BaseSelect.vue'
import { computed, ref, watch } from 'vue'
import type { TeamMemberSummary } from '../../domains/scope/types'
import { teamRoleLabels } from '../../domains/scope/labels'
import { enumLabel } from '../../domains/shared/labels'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'

/**
 * ADR-038 lifecycle confirmation. Dangerous actions state their blast radius before the
 * If-Match command is sent; the 409 `last_owner_protection` contract is surfaced verbatim.
 */
const props = defineProps<{
  member: TeamMemberSummary | null
  action: MemberAction | null
  roleKey?: string
  pending: boolean
  problem: LifecycleDialogProblem | null
  online: boolean
}>()

const emit = defineEmits<{
  confirm: [roleKey: string]
  cancel: []
}>()

const selectedRole = ref('')

watch(() => props.action, action => {
  selectedRole.value = action?.type === 'grantRole' ? 'MEMBER' : ''
})

interface Copy {
  eyebrow: string
  title: string
  body: string
  confirmLabel: string
  danger: boolean
  selectable: boolean
}

const grantableRoles = ['TEAM_ADMIN', 'TEAM_LEAD', 'MEMBER', 'AUDITOR'] as const

function copy(member: TeamMemberSummary, action: MemberAction, roleKey?: string): Copy {
  const name = member.displayName
  switch (action.type) {
    case 'suspend':
      return {
        eyebrow: 'Member lifecycle', title: `停用 ${name}？`,
        body: '停用后该成员立即失去此 Team 的全部访问（页面、实时流、执行与通知通道）。之后可以恢复，但恢复只重建基础访问，不会复活此前的角色授权。',
        confirmLabel: '确认停用', danger: true, selectable: false,
      }
    case 'activate':
      return {
        eyebrow: 'Member lifecycle', title: `恢复 ${name}？`,
        body: '该成员将重新获得此 Team 的基础访问。此前撤销的角色授权不会自动复活，需要重新授予。',
        confirmLabel: '确认恢复', danger: false, selectable: false,
      }
    case 'remove':
      return {
        eyebrow: 'Member lifecycle', title: `移除 ${name}？`,
        body: '成员资格与全部角色授权将被撤销，撤销即刻在所有通道生效。移除后仍可以通过新的邀请重新加入。',
        confirmLabel: '确认移除', danger: true, selectable: false,
      }
    case 'leave':
      return {
        eyebrow: 'Member lifecycle', title: '离开这个 Team？',
        body: '你的成员资格与角色授权将被撤销，离开即刻生效。若是最后一个 Owner，服务端会拒绝并提示保护。',
        confirmLabel: '确认离开', danger: true, selectable: false,
      }
    case 'transferOwnership':
      return {
        eyebrow: 'Ownership', title: `把 Owner 转让给 ${name}？`,
        body: '转让后你不再是 Team Owner（保留成员资格），该成员获得 Owner 授权。这是立即生效的撤权动作，请确认对方可以承接。',
        confirmLabel: '确认转让', danger: true, selectable: false,
      }
    case 'grantRole':
      return {
        eyebrow: 'Team roles', title: `给 ${name} 授予角色`,
        body: '角色授权即刻生效；撤销角色或移除成员时会同步收回。',
        confirmLabel: '确认授予', danger: false, selectable: true,
      }
    case 'revokeRole':
      return {
        eyebrow: 'Team roles', title: `撤销 ${name} 的${enumLabel(action.roleKey, teamRoleLabels)}角色？`,
        body: `将撤销角色授权（grant ${action.grantId.slice(0, 8)}…）。撤销即刻生效；授予与撤销都会推进该成员的授权版本。`,
        confirmLabel: '确认撤销角色', danger: true, selectable: false,
      }
    default:
      return { eyebrow: '', title: '', body: '', confirmLabel: '', danger: false, selectable: false }
  }
}

const current = computed(() => props.member && props.action ? copy(props.member, props.action, props.roleKey) : null)
</script>

<template>
  <BaseDialog
    :open="current !== null"
    :title="current?.title ?? ''"
    :eyebrow="current?.eyebrow ?? ''"
    width="min(460px, 100%)"
    :close-disabled="pending"
    close-label="关闭成员操作确认"
    @close="emit('cancel')"
  >
    <div class="lifecycle-body">
      <p class="lifecycle-copy">{{ current?.body }}</p>
      <template v-if="current?.selectable">
        <label for="lifecycle-role">角色</label>
        <BaseSelect id="lifecycle-role" v-model="selectedRole">
          <option v-for="role in grantableRoles" :key="role" :value="role">{{ enumLabel(role, teamRoleLabels) }}</option>
        </BaseSelect>
      </template>
      <div v-if="problem" class="lifecycle-problem" role="alert">
        <strong>{{ problem.title }}</strong>
        <span>{{ problem.message }}</span>
      </div>
    </div>
    <template #footer>
      <!-- 危险确认的初始焦点必须落在安全侧（取消），而不是 header 的关闭钮或破坏性主钮。 -->
      <BaseButton variant="ghost" :disabled="pending" autofocus @click="emit('cancel')">取消</BaseButton>
      <BaseButton
        :variant="current?.danger ? 'danger' : 'primary'"
        :loading="pending"
        :disabled="!online || (current?.selectable && !selectedRole)"
        @click="emit('confirm', selectedRole)"
      >{{ current?.confirmLabel }}</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.lifecycle-body { display: grid; gap: var(--cs-space-12); }
.lifecycle-copy { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.lifecycle-body label { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.lifecycle-body :deep(select) { min-height: 36px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); font-size: var(--cs-text-base); }
.lifecycle-problem { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-12); border: 1px solid var(--cs-warning-border); border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); }
.lifecycle-problem strong { font-size: var(--cs-text-sm); }
.lifecycle-problem span { font-size: var(--cs-text-xs); }
</style>
