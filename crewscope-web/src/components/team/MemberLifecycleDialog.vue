<script lang="ts">
import type { MemberAction } from './MemberActionMenu.vue'

export interface LifecycleDialogProblem {
  title: string
  message: string
}
</script>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { TeamMemberSummary } from '../../domains/scope/types'
import { teamRoleLabels } from '../../domains/scope/labels'
import { enumLabel } from '../../domains/shared/labels'
import BaseButton from '../base/BaseButton.vue'

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

const dialog = ref<HTMLElement | null>(null)
const selectedRole = ref('')

watch(() => props.action, async action => {
  selectedRole.value = action?.type === 'grantRole' ? 'MEMBER' : ''
  if (action) await nextTick(() => dialog.value?.querySelector<HTMLElement>('button:not(:disabled)')?.focus())
})

function cancel(): void {
  if (props.pending) return
  emit('cancel')
}

function trap(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    cancel()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const items = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), select')]
  if (!items.length) return
  const first = items[0]!
  const last = items.at(-1)!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault(); last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus()
  }
}

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
</script>

<template>
  <div v-if="member && action" class="dialog-backdrop" @mousedown.self="cancel()">
    <section ref="dialog" class="lifecycle-dialog" role="dialog" aria-modal="true" aria-labelledby="lifecycle-dialog-title" tabindex="-1" @keydown="trap">
      <p class="eyebrow">{{ copy(member, action, roleKey).eyebrow }}</p>
      <h3 id="lifecycle-dialog-title">{{ copy(member, action, roleKey).title }}</h3>
      <p>{{ copy(member, action, roleKey).body }}</p>
      <label v-if="copy(member, action, roleKey).selectable" for="lifecycle-role">角色</label>
      <select v-if="copy(member, action, roleKey).selectable" id="lifecycle-role" v-model="selectedRole">
        <option v-for="role in grantableRoles" :key="role" :value="role">{{ enumLabel(role, teamRoleLabels) }}</option>
      </select>
      <div v-if="problem" class="lifecycle-dialog__problem" role="alert">
        <strong>{{ problem.title }}</strong>
        <span>{{ problem.message }}</span>
      </div>
      <div class="lifecycle-dialog__actions">
        <BaseButton variant="ghost" :disabled="pending" @click="cancel()">取消</BaseButton>
        <BaseButton
          :variant="copy(member, action, roleKey).danger ? 'danger' : 'primary'"
          :loading="pending"
          :disabled="!online || (copy(member, action, roleKey).selectable && !selectedRole)"
          @click="emit('confirm', selectedRole)"
        >{{ copy(member, action, roleKey).confirmLabel }}</BaseButton>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; z-index: var(--cs-z-dialog); inset: 0; display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); }
.lifecycle-dialog { display: grid; width: min(460px, 100%); gap: var(--cs-space-12); padding: var(--cs-space-24); border-radius: 15px; background: var(--cs-surface); box-shadow: var(--cs-shadow-modal); }
.lifecycle-dialog h3 { font-size: var(--cs-text-lg); }
.lifecycle-dialog > p:not(.eyebrow) { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.lifecycle-dialog label { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.lifecycle-dialog select { min-height: 36px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); font-size: var(--cs-text-base); }
.lifecycle-dialog__problem { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-12); border: 1px solid var(--cs-warning-border); border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); }
.lifecycle-dialog__problem strong { font-size: var(--cs-text-sm); }
.lifecycle-dialog__problem span { font-size: var(--cs-text-xs); }
.lifecycle-dialog__actions { display: flex; justify-content: flex-end; gap: var(--cs-space-8); }
</style>
