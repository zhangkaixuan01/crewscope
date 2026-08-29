<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import M7AuthStatePreview, { authPreviewStates } from '../stories/M7AuthStatePreview.vue'

const labels = {
  loading: 'Session loading · 390px',
  'service-error': 'Session error · 390px',
  login: 'Login · 390px',
  'login-error': 'Invalid credentials · 390px',
  locked: 'Temporarily locked · 390px',
  register: 'Open registration · 390px',
  'registration-invite-only': 'Invite-only registration · 390px',
  'registration-closed': 'Registration disabled · 390px',
  onboarding: 'First team onboarding · 390px',
  invite: 'Valid team invitation · 390px',
  'invite-expired': 'Expired invitation · 390px',
} as const
</script>

<template>
  <Story title="M7/Auth foundation · Narrow" :layout="{ type: 'single', iframe: true, width: 390 }">
    <Variant v-for="state in authPreviewStates" :key="state" :title="labels[state]">
      <M7AuthStatePreview :state="state" />
    </Variant>
  </Story>
</template>
