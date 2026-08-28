import { createApp } from 'vue'
import '../../design/tokens.css'
import '../../design/base.css'
import IdentityExperienceFixture, { identityFixtureStates, type IdentityFixtureState } from './IdentityExperienceFixture.vue'

const requestedState = new URLSearchParams(window.location.search).get('state')
const state: IdentityFixtureState = identityFixtureStates.includes(requestedState as IdentityFixtureState)
  ? requestedState as IdentityFixtureState
  : 'login'

createApp(IdentityExperienceFixture, { state }).mount('#app')
