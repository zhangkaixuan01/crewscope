import { fixtureMembers, fixtureIds } from '../../test/scopeFixtures'
import { principalDisplayName, principalNameDirectory } from './memberDirectory'

describe('member directory', () => {
  it('uses the authoritative member display name and a technical fallback only for unknown facts', () => {
    const directory = principalNameDirectory(fixtureMembers[fixtureIds.teamPlatform]!)

    expect(principalDisplayName(directory, fixtureIds.secondPrincipal)).toBe('Lin Chen')
    expect(principalDisplayName(directory, '00000000-0000-0000-0000-000000000999'))
      .toBe('成员 · 00000000…0999')
  })
})
