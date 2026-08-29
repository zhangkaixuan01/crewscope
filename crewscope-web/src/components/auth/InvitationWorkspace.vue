<script setup lang="ts">
import { ArrowLeft, ArrowRight, Clock3, RefreshCw, ShieldCheck, UsersRound } from '@lucide/vue'
import { computed } from 'vue'
import type { InvitationProblem } from '../../domains/invitation/presentation'
import type { InvitationPreview, InvitationRole } from '../../domains/invitation/types'
import BaseButton from '../base/BaseButton.vue'
import AuthCard from './AuthCard.vue'
import AuthErrorSummary from './AuthErrorSummary.vue'

const props = defineProps<{
  phase: 'idle' | 'previewing' | 'available' | 'expired' | 'unavailable' | 'accepting' | 'accepted' | 'error'
  preview: InvitationPreview | null
  problem: InvitationProblem | null
  problemFocusKey: number
  authenticated: boolean
  registrationAllowed: boolean
  online: boolean
}>()

defineEmits<{ login: [], register: [], accept: [], retry: [] }>()

const available = computed(() => props.preview?.state === 'AVAILABLE')

function roleLabel(role: InvitationRole | null): string {
  if (!role) return '—'
  return { TEAM_ADMIN: 'Team Admin', TEAM_LEAD: 'Team Lead', MEMBER: 'Member', AUDITOR: 'Auditor' }[role]
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <AuthCard
    v-if="phase === 'idle' || phase === 'previewing'"
    kicker="团队邀请"
    title="正在检查邀请"
    description="只读取公开的 Team、角色和有效期信息。"
    busy
    focus-on-mount
  >
    <p class="invitation-workspace__status" role="status">正在验证一次性邀请…</p>
  </AuthCard>

  <AuthCard
    v-else-if="available"
    size="wide"
    :kicker="`${preview?.teamName} 邀请你加入`"
    title="加入团队，一起推进工作"
    description="接受后会建立你的 Team Membership 和角色；你的账号仍保留独立 Personal Agent。"
    :busy="phase === 'accepting'"
    focus-on-mount
  >
    <template #before><p class="invitation-workspace__mark"><UsersRound :size="17" />团队邀请</p></template>
    <AuthErrorSummary
      v-if="problem"
      :title="problem.title"
      :messages="[problem.message]"
      :tone="problem.tone"
      :focus-key="problemFocusKey"
    />
    <dl class="invitation-workspace__facts">
      <div><dt>团队</dt><dd>{{ preview?.teamName }}</dd></div>
      <div><dt>加入角色</dt><dd>{{ roleLabel(preview?.targetRole ?? null) }}</dd></div>
      <div><dt>有效至</dt><dd><Clock3 :size="13" />{{ formatDate(preview?.expiresAt ?? null) }}</dd></div>
    </dl>
    <p v-if="preview?.targetRestricted" class="invitation-workspace__restricted"><ShieldCheck :size="14" />这是定向邀请，登录账号的邮箱需要与邀请目标匹配。</p>
    <div v-if="authenticated" class="invitation-workspace__actions">
      <BaseButton :loading="phase === 'accepting'" :disabled="!online" @click="$emit('accept')">{{ phase === 'accepting' ? '正在加入…' : '接受邀请并加入团队' }}<template #icon><ArrowRight v-if="phase !== 'accepting'" :size="16" /></template></BaseButton>
      <BaseButton variant="ghost" :disabled="phase === 'accepting'" @click="$emit('retry')"><RefreshCw :size="14" />重新检查邀请</BaseButton>
    </div>
    <div v-else class="invitation-workspace__actions invitation-workspace__actions--anonymous">
      <BaseButton :disabled="!online" @click="$emit('login')">使用已有账号登录并加入<template #icon><ArrowRight :size="16" /></template></BaseButton>
      <BaseButton v-if="registrationAllowed" variant="secondary" :disabled="!online" @click="$emit('register')">创建账号并加入团队</BaseButton>
    </div>
    <p class="invitation-workspace__privacy"><ShieldCheck :size="14" />接受前不会创建成员关系；邀请只能成功使用一次。</p>
  </AuthCard>

  <AuthCard
    v-else-if="phase === 'error'"
    kicker="团队邀请"
    title="暂时无法检查邀请"
    description="邀请内容没有被提交或消费。"
    focus-on-mount
  >
    <AuthErrorSummary
      v-if="problem"
      :title="problem.title"
      :messages="[problem.message]"
      :tone="problem.tone"
      :focus-key="problemFocusKey"
    />
    <BaseButton class="invitation-workspace__full" :disabled="!online" @click="$emit('retry')"><RefreshCw :size="15" />重新检查</BaseButton>
  </AuthCard>

  <AuthCard
    v-else
    kicker="团队邀请"
    :title="phase === 'expired' ? '这个邀请已经过期' : '这个邀请无法使用'"
    description="邀请可能已经使用、被撤销，或链接不完整。请联系团队管理员获取新链接。"
    focus-on-mount
  >
    <div class="invitation-workspace__unavailable"><ShieldCheck :size="18" /><p>不会披露邀请目标、邀请人或内部状态。</p></div>
    <BaseButton class="invitation-workspace__full" variant="secondary" @click="$emit('login')"><ArrowLeft :size="14" />返回登录</BaseButton>
  </AuthCard>
</template>

<style scoped>
.invitation-workspace__status { margin: 0; color: var(--cs-text-muted); font-size: 10px; }.invitation-workspace__mark { display: inline-flex; align-items: center; gap: 7px; padding: 6px 9px; margin: 0 0 20px; border-radius: 9px; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 10px; font-weight: 750; }.invitation-workspace__facts { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0 0 18px; border: 1px solid var(--cs-border); border-radius: 12px; }.invitation-workspace__facts div { min-width: 0; padding: 12px; }.invitation-workspace__facts div + div { border-left: 1px solid var(--cs-border); }.invitation-workspace__facts dt { color: var(--cs-text-muted); font-size: 8px; }.invitation-workspace__facts dd { display: flex; align-items: center; gap: 5px; margin: 4px 0 0; overflow-wrap: anywhere; font-size: 10px; font-weight: 720; }.invitation-workspace__restricted, .invitation-workspace__privacy { display: flex; align-items: flex-start; gap: 6px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.invitation-workspace__restricted { padding: 9px 10px; border-radius: 8px; background: var(--cs-warning-soft); color: #79551d; }.invitation-workspace__restricted svg, .invitation-workspace__privacy svg { flex: 0 0 auto; }.invitation-workspace__actions { display: grid; grid-template-columns: 1.4fr 1fr; gap: 9px; margin-top: 18px; }.invitation-workspace__actions--anonymous { grid-template-columns: 1.3fr 1fr; }.invitation-workspace__privacy { margin: 13px 0 0; }.invitation-workspace__full { width: 100%; }.invitation-workspace__unavailable { display: flex; align-items: center; gap: 8px; padding: 11px; margin-bottom: 16px; border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.invitation-workspace__unavailable p { margin: 0; font-size: 9px; }
@media (max-width: 680px) { .invitation-workspace__facts { grid-template-columns: 1fr; }.invitation-workspace__facts div + div { border-top: 1px solid var(--cs-border); border-left: 0; }.invitation-workspace__actions, .invitation-workspace__actions--anonymous { grid-template-columns: 1fr; } }
</style>
