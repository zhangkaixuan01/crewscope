// @vitest-environment node
import { createMemoryHistory } from 'vue-router'
import { bootstrapPrincipal, fixtureAuthStore } from '../../test/authFixtures'
import { createCrewScopeRouter } from '../../app/router'
import { defaultLoginDestination, safeLoginDestination } from './route'

describe('safeLoginDestination', () => {
  it.each([
    '/today?team=team-1',
    '/conversation?focus=CRW-18&team=team-1',
    '/settings/agents?team=team-1',
    '/settings/organization?tab=governance',
    '/settings/quota?month=2026-09',
    '/account',
    '/invite',
  ])('accepts a registered protected route: %s', candidate => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(bootstrapPrincipal))

    expect(safeLoginDestination({ returnTo: candidate }, router, bootstrapPrincipal)).toBe(candidate)
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

    expect(safeLoginDestination({ returnTo: candidate }, router, bootstrapPrincipal)).toBe('/conversation')
  })

  it('rejects duplicate return targets', () => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(bootstrapPrincipal))

    expect(safeLoginDestination({ returnTo: ['/work', '/audit'] }, router, bootstrapPrincipal)).toBe('/conversation')
  })
})

describe('defaultLoginDestination', () => {
  // M13b-F01（R01/R49）：落点裁决顺序——对话成员不变，纯组织角色直达组织设置。
  it('keeps the conversation workspace for members', () => {
    expect(defaultLoginDestination(bootstrapPrincipal)).toBe('/conversation')
  })

  it('lands a pure organization role on the organization settings', () => {
    const orgOnly = { permissions: new Set(['org:audit-read']) }
    expect(defaultLoginDestination(orgOnly)).toBe('/settings/organization')
  })

  it('falls back to Today for scope-only members and to the account page otherwise', () => {
    expect(defaultLoginDestination({ permissions: new Set(['scope:read']) })).toBe('/today')
    expect(defaultLoginDestination({ permissions: new Set<string>() })).toBe('/account')
    expect(defaultLoginDestination(null)).toBe('/account')
  })
})
