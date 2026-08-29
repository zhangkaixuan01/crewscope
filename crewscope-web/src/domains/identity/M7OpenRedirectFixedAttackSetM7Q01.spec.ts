import type { LocationQuery } from 'vue-router'
import { createMemoryHistory } from 'vue-router'
import { createCrewScopeRouter } from '../../app/router'
import { bootstrapPrincipal, fixtureAuthStore } from '../../test/authFixtures'
import { DEFAULT_LOGIN_DESTINATION, safeLoginDestination } from './route'

interface RedirectAttack {
  id: string
  returnTo: unknown
}

const attacks: RedirectAttack[] = [
  { id: 'RD-01', returnTo: 'https://attacker.example/work' },
  { id: 'RD-02', returnTo: 'http://attacker.example/work' },
  { id: 'RD-03', returnTo: '//attacker.example/work' },
  { id: 'RD-04', returnTo: '///attacker.example/work' },
  { id: 'RD-05', returnTo: '/conversation\\attacker' },
  { id: 'RD-06', returnTo: '/\\attacker.example/work' },
  { id: 'RD-07', returnTo: '/conversation#token=secret' },
  { id: 'RD-08', returnTo: '/invite#token=secret' },
  { id: 'RD-09', returnTo: '/login' },
  { id: 'RD-10', returnTo: '/login?returnTo=/work' },
  { id: 'RD-11', returnTo: '/register' },
  { id: 'RD-12', returnTo: '/unknown' },
  { id: 'RD-13', returnTo: 'javascript:alert(1)' },
  { id: 'RD-14', returnTo: 'data:text/html,attack' },
  { id: 'RD-15', returnTo: '' },
  { id: 'RD-16', returnTo: `/${'a'.repeat(2_048)}` },
  { id: 'RD-17', returnTo: '/conversation\u0000attack' },
  { id: 'RD-18', returnTo: '/conversation\nattack' },
  { id: 'RD-19', returnTo: '/conversation\tattack' },
  { id: 'RD-20', returnTo: '/conversation\u007fattack' },
  { id: 'RD-21', returnTo: ['/work', '/audit'] },
  { id: 'RD-22', returnTo: null },
  { id: 'RD-23', returnTo: undefined },
  { id: 'RD-24', returnTo: { path: '/work' } },
]

describe('M7-Q01 fixed open-redirect attack set', () => {
  it('freezes the RD-01..RD-24 denominator', () => {
    expect(attacks).toHaveLength(24)
    expect(new Set(attacks.map(attack => attack.id)).size).toBe(24)
    expect(attacks.map(attack => attack.id)).toEqual(
      Array.from({ length: 24 }, (_, index) => `RD-${String(index + 1).padStart(2, '0')}`),
    )
  })

  it.each(attacks)('$id rejects $returnTo', ({ returnTo }) => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(bootstrapPrincipal))

    expect(safeLoginDestination({ returnTo } as LocationQuery, router)).toBe(DEFAULT_LOGIN_DESTINATION)
  })
})
