<script lang="ts">
export type MemberAction =
  | { type: 'suspend' } | { type: 'activate' }
  | { type: 'remove' } | { type: 'leave' }
  | { type: 'transferOwnership' }
  | { type: 'grantRole' } | { type: 'revokeRole', grantId: string, roleKey: string }
  | { type: 'handover' } | { type: 'reinvite' }
</script>

<script setup lang="ts">
import { ArrowLeftRight, MoreHorizontal, ShieldOff, UserMinus, UserPlus, UserRoundCheck, UserRoundX, LogOut } from '@lucide/vue'
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { Component } from 'vue'
import type { TeamMemberSummary } from '../../domains/scope/types'
import { teamRoleLabels } from '../../domains/scope/labels'
import { enumLabel } from '../../domains/shared/labels'

/**
 * Row-level "more" menu. Visibility mirrors the server's own guards (membership status ×
 * viewer permissions); every entry still re-validates server side — this only shapes the UI.
 */
const props = defineProps<{
  member: TeamMemberSummary
  isSelf: boolean
  viewerIsOwner: boolean
  canManageMembers: boolean
  canManageRoles: boolean
  pending: boolean
}>()

const emit = defineEmits<{ action: [action: MemberAction] }>()

const open = ref(false)
const trigger = ref<HTMLButtonElement | null>(null)
const menu = ref<HTMLDivElement | null>(null)

interface Entry {
  key: string
  label: string
  description?: string
  icon: Component
  danger?: boolean
  action: MemberAction
}

function revokeEntries(): Entry[] {
  return (props.member.grants ?? [])
    .filter(grant => !grant.status || grant.status === 'ACTIVE')
    .map(grant => ({
      key: `revoke-${grant.id}`,
      label: `撤销角色 · ${enumLabel(grant.roleKey, teamRoleLabels)}`,
      icon: ShieldOff,
      danger: true,
      action: { type: 'revokeRole', grantId: grant.id, roleKey: grant.roleKey },
    }))
}

const entries = (): Entry[] => {
  const list: Entry[] = []
  const status = props.member.status
  if (status === 'ACTIVE' || status === 'SUSPENDED') {
    if (props.canManageMembers && !props.isSelf) {
      if (status === 'ACTIVE') {
        list.push({ key: 'suspend', label: '停用成员', description: '立即失去此 Team 的全部访问', icon: UserRoundX, danger: true, action: { type: 'suspend' } })
      } else {
        list.push({ key: 'activate', label: '恢复成员', description: '恢复基础访问；旧角色授权不复活', icon: UserRoundCheck, action: { type: 'activate' } })
      }
      list.push({ key: 'remove', label: '移除成员', description: '撤销成员资格与角色授权', icon: UserMinus, danger: true, action: { type: 'remove' } })
    }
    if (props.isSelf && status === 'ACTIVE') {
      list.push({ key: 'leave', label: '离开这个 Team', description: '撤销你的成员资格与角色授权', icon: LogOut, danger: true, action: { type: 'leave' } })
    }
    if (status === 'ACTIVE' && props.canManageRoles) {
      list.push({ key: 'grant', label: '授予角色', icon: UserPlus, action: { type: 'grantRole' } })
      list.push(...revokeEntries())
    }
    if (props.viewerIsOwner && status === 'ACTIVE' && !props.isSelf) {
      list.push({ key: 'transfer', label: '转让 Owner', description: '把 Team Owner 转给此成员', icon: ArrowLeftRight, danger: true, action: { type: 'transferOwnership' } })
    }
    if (props.canManageMembers) {
      list.push({ key: 'handover', label: '交接责任', description: '把此成员的工作项责任转给他人', icon: ArrowLeftRight, action: { type: 'handover' } })
    }
  }
  if ((status === 'REMOVED' || status === 'LEFT' || status === 'INVITED') && props.canManageMembers) {
    list.push({ key: 'reinvite', label: '发送加入邀请', description: '通过一次性邀请链接重新加入', icon: UserPlus, action: { type: 'reinvite' } })
  }
  return list
}

async function toggle(): Promise<void> {
  open.value = !open.value
  if (open.value) {
    await nextTick()
    menu.value?.querySelector<HTMLButtonElement>('button')?.focus()
  }
}

function choose(entry: Entry): void {
  open.value = false
  emit('action', entry.action)
  void nextTick(() => trigger.value?.focus())
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || !open.value) return
  event.preventDefault()
  open.value = false
  trigger.value?.focus()
}

function onPointerDown(event: PointerEvent): void {
  if (!open.value) return
  const target = event.target as Node
  if (menu.value?.contains(target) || trigger.value?.contains(target)) return
  open.value = false
}

watch(() => props.member.id, () => { open.value = false })
document.addEventListener('pointerdown', onPointerDown, true)
onBeforeUnmount(() => document.removeEventListener('pointerdown', onPointerDown, true))
</script>

<template>
  <div class="member-menu" @keydown="onKeydown">
    <button
      ref="trigger"
      type="button"
      class="member-menu__trigger"
      :aria-label="`更多操作：${member.displayName}`"
      :aria-expanded="open"
      aria-haspopup="menu"
      :disabled="pending"
      @click="toggle"
    ><MoreHorizontal :size="15" /></button>
    <div v-if="open" ref="menu" class="member-menu__list" role="menu" :aria-label="`${member.displayName} 的成员操作`">
      <button
        v-for="entry in entries()"
        :key="entry.key"
        type="button"
        role="menuitem"
        class="member-menu__item"
        :class="{ 'member-menu__item--danger': entry.danger }"
        @click="choose(entry)"
      >
        <i><component :is="entry.icon" :size="14" /></i>
        <span><strong>{{ entry.label }}</strong><small v-if="entry.description">{{ entry.description }}</small></span>
      </button>
      <p v-if="!entries().length" class="member-menu__empty">没有可用操作</p>
    </div>
  </div>
</template>

<style scoped>
.member-menu { position: relative; justify-self: end; }
.member-menu__trigger { display: grid; width: 30px; height: 30px; place-items: center; border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.member-menu__trigger:hover, .member-menu__trigger:focus-visible, .member-menu__trigger[aria-expanded="true"] { border-color: var(--cs-border-accent-strong); color: var(--cs-text-brand); }
.member-menu__trigger:disabled { opacity: .5; cursor: default; }
.member-menu__list { position: absolute; top: calc(100% + 6px); right: 0; z-index: var(--cs-z-popover); display: grid; min-width: 250px; max-height: 340px; overflow-y: auto; padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 11px; background: var(--cs-surface); box-shadow: var(--cs-shadow-modal); }
.member-menu__item { display: grid; grid-template-columns: 28px 1fr; align-items: start; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 0; border-radius: 8px; background: transparent; text-align: left; cursor: pointer; }
.member-menu__item:hover, .member-menu__item:focus-visible { background: var(--cs-surface-accent); }
.member-menu__item > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-style: normal; }
.member-menu__item--danger > i { background: var(--cs-danger-soft, var(--cs-surface-subtle)); color: var(--cs-danger); }
.member-menu__item strong { display: block; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.member-menu__item--danger strong { color: var(--cs-danger); }
.member-menu__item small { display: block; margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.member-menu__empty { margin: 0; padding: var(--cs-space-8) var(--cs-space-12); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
