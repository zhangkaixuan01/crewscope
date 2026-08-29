<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import '../design/auth-tokens.css'
import AuthLayout from './auth/AuthLayout.vue'
import InvitationWorkspace from './auth/InvitationWorkspace.vue'

const available = {
  state: 'AVAILABLE' as const, invitationId: 'invitation-story', teamName: 'Platform Engineering',
  targetRole: 'MEMBER' as const, expiresAt: '2026-09-05T10:00:00Z', targetRestricted: true,
}
const expired = { state: 'EXPIRED' as const, invitationId: null, teamName: null, targetRole: null, expiresAt: null, targetRestricted: false }
const unavailable = { ...expired, state: 'UNAVAILABLE' as const }
</script>

<template>
  <Story title="M7/Team invitations" :layout="{ type: 'single', iframe: true, width: 1280 }">
    <Variant title="Available · anonymous"><AuthLayout><InvitationWorkspace phase="available" :preview="available" :problem="null" :problem-focus-key="0" :authenticated="false" registration-allowed online /></AuthLayout></Variant>
    <Variant title="Available · authenticated"><AuthLayout><InvitationWorkspace phase="available" :preview="available" :problem="null" :problem-focus-key="0" authenticated registration-allowed online /></AuthLayout></Variant>
    <Variant title="Expired"><AuthLayout><InvitationWorkspace phase="expired" :preview="expired" :problem="null" :problem-focus-key="0" :authenticated="false" registration-allowed online /></AuthLayout></Variant>
    <Variant title="Unavailable"><AuthLayout><InvitationWorkspace phase="unavailable" :preview="unavailable" :problem="null" :problem-focus-key="0" :authenticated="false" registration-allowed online /></AuthLayout></Variant>
    <Variant title="Service unavailable"><AuthLayout><InvitationWorkspace phase="error" :preview="null" :problem="{ code: 'invitation_unavailable', title: '邀请服务暂时不可用', message: '请稍后重新读取邀请状态。', tone: 'error' }" :problem-focus-key="1" :authenticated="false" registration-allowed online /></AuthLayout></Variant>
  </Story>
</template>
