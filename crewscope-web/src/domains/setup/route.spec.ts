import { configurationSearchTarget } from './route'

describe('Configuration search deep-link target', () => {
  it('merges the active Team into a server route that carries only field coordinates', () => {
    expect(configurationSearchTarget(`/settings/agents?agent=profile-1`, 'team-1')).toEqual({
      path: '/settings/agents',
      query: { team: 'team-1', agent: 'profile-1' },
    })
    expect(configurationSearchTarget('/settings/repositories?project=p-1&binding=b-1', 'team-1')).toEqual({
      path: '/settings/repositories',
      query: { team: 'team-1', project: 'p-1', binding: 'b-1' },
    })
  })

  it('keeps the active scope authoritative when the server route names another Team', () => {
    expect(configurationSearchTarget('/team/members?team=other&member=m-1', 'team-1')).toEqual({
      path: '/team/members',
      query: { team: 'team-1', member: 'm-1' },
    })
  })

  it('drops empty parameters and stays out of the way for routes the shell cannot render', () => {
    expect(configurationSearchTarget('/settings/models?provider=&connection=c-1', 'team-1')).toEqual({
      path: '/settings/models',
      query: { team: 'team-1', connection: 'c-1' },
    })
    expect(configurationSearchTarget('/settings/models', 'team-1')).toEqual({
      path: '/settings/models',
      query: { team: 'team-1' },
    })
    // A relative or root route would re-point the navigation at the shell's own page.
    expect(configurationSearchTarget('settings/models', 'team-1')).toBeNull()
    expect(configurationSearchTarget('/', 'team-1')).toBeNull()
    expect(configurationSearchTarget('', 'team-1')).toBeNull()
  })
})
