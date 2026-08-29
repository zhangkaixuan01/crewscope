<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import M7AuthStatePreview, { authPreviewStates } from '../stories/M7AuthStatePreview.vue'

const labels = {
  loading: 'Session loading',
  'service-error': 'Session error',
  login: 'Login',
  'login-error': 'Invalid credentials',
  locked: 'Temporarily locked',
  register: 'Open registration',
  'registration-invite-only': 'Invite-only registration',
  'registration-closed': 'Registration disabled',
  onboarding: 'First team onboarding',
  invite: 'Valid team invitation',
  'invite-expired': 'Expired invitation',
} as const
</script>

<template>
  <Story title="M7/Auth foundation · Desktop" :layout="{ type: 'single', iframe: true, width: 1280 }">
    <Variant v-for="state in authPreviewStates" :key="state" :title="labels[state]">
      <M7AuthStatePreview :state="state" />
    </Variant>
  </Story>
</template>
