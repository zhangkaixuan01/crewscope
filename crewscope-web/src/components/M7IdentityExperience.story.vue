<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import IdentityExperienceFixture, { identityFixtureStates } from '../spikes/m7/IdentityExperienceFixture.vue'

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
  account: 'Account and security',
} as const
</script>

<template>
  <Story title="M7/Identity experience" :layout="{ type: 'grid', width: 1280 }">
    <Variant v-for="state in identityFixtureStates" :key="state" :title="labels[state]">
      <IdentityExperienceFixture :state="state" />
    </Variant>
  </Story>
</template>
