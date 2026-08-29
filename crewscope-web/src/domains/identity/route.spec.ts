import { createMemoryHistory } from 'vue-router'
import { bootstrapPrincipal, fixtureAuthStore } from '../../test/authFixtures'
import { createCrewScopeRouter } from '../../app/router'
import { DEFAULT_LOGIN_DESTINATION, safeLoginDestination } from './route'

describe('safeLoginDestination', () => {
  it.each([
    '/today?team=team-1',
    '/conversation?focus=CRW-18&team=team-1',
    '/settings/agents?team=team-1&agent=agent-1',
    '/account',
    '/invite',
  ])('accepts a registered protected route: %s', candidate => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(bootstrapPrincipal))

    expect(safeLoginDestination({ returnTo: candidate }, router)).toBe(candidate)
  })

  it.each([
    'https://attacker.example/work',
    '//attacker.example/work',
    '/login?returnTo=/work',
    '/register',
    '/invite#token=secret',
    '/unknown',
    '/conversation#secret',
    '/conversation\\attacker',
  ])('rejects an external, public or unknown target: %s', candidate => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(bootstrapPrincipal))

    expect(safeLoginDestination({ returnTo: candidate }, router)).toBe(DEFAULT_LOGIN_DESTINATION)
  })

  it('rejects duplicate return targets', () => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(bootstrapPrincipal))

    expect(safeLoginDestination({ returnTo: ['/work', '/audit'] }, router)).toBe(DEFAULT_LOGIN_DESTINATION)
  })
})
