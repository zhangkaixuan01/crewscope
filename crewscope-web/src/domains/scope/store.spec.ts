import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureScopeGateway, fixtureIds, fixtureMembers } from '../../test/scopeFixtures'
import { createScopeStore } from './store'

describe('scope store', () => {
  it('restores a Team and WorkProject selected by URL identity', async () => {
    const store = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)

    const selection = await store.synchronize(fixtureIds.teamSecurity, fixtureIds.projectRuntime)

    expect(selection).toEqual({ teamId: fixtureIds.teamSecurity, projectId: fixtureIds.projectRuntime })
    expect(store.selectedTeam.value?.name).toBe('Security Engineering')
    expect(store.selectedProject.value?.key).toBe('SEC')
  })

  it('canonicalizes unknown URL scope to the first accessible Team and project', async () => {
    const store = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)

    const selection = await store.synchronize(crypto.randomUUID(), crypto.randomUUID())

    expect(selection).toEqual({ teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope })
    expect(store.state.phase).toBe('ready')
  })

  it('refreshes the active Team member list after a guarded add command', async () => {
    const gateway = new FixtureScopeGateway()
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
    await store.loadMembers()

    const newPrincipalId = '00000000-0000-4000-8000-000000000199'
    await store.addMember(newPrincipalId)

    expect(gateway.addedPrincipalIds).toEqual([newPrincipalId])
    expect(store.state.members.some(member => member.userPrincipalId === newPrincipalId)).toBe(true)
    expect(store.state.memberCommandPending).toBe(false)
  })

  it('represents an account without Team membership as an empty scope', async () => {
    const store = createScopeStore(new FixtureScopeGateway([], {}, {}), bootstrapPrincipal)

    expect(await store.synchronize()).toEqual({ teamId: null, projectId: null })
    expect(store.state.phase).toBe('empty')
  })

  it('does not let a stale member failure overwrite a newly selected Team', async () => {
    let rejectPlatform!: (reason: Error) => void
    const gateway = new FixtureScopeGateway()
    const originalListMembers = gateway.listMembers.bind(gateway)
    gateway.listMembers = (_organizationId, teamId) => {
      if (teamId === fixtureIds.teamPlatform) {
        return new Promise((_resolve, reject) => { rejectPlatform = reject })
      }
      return originalListMembers(_organizationId, teamId)
    }
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
    const staleRequest = store.loadMembers()

    await store.synchronize(fixtureIds.teamSecurity, fixtureIds.projectRuntime)
    await store.loadMembers()
    rejectPlatform(new Error('stale Team failed'))
    await staleRequest

    expect(store.state.membersTeamId).toBe(fixtureIds.teamSecurity)
    expect(store.state.membersErrorMessage).toBeNull()
  })

  it('clears cached scope and ignores a late Team response after identity removal', async () => {
    const pending = deferred<Awaited<ReturnType<FixtureScopeGateway['listTeams']>>>()
    const gateway = new FixtureScopeGateway()
    gateway.listTeams = vi.fn(async () => pending.promise)
    const store = createScopeStore(gateway, bootstrapPrincipal)

    const synchronization = store.synchronize()
    store.reset()
    pending.resolve([{
      id: fixtureIds.teamPlatform,
      organizationId: bootstrapPrincipal.organizationId,
      name: 'Late Team',
      status: 'ACTIVE',
      initializationStatus: 'READY',
      ownerMemberId: 'member-1',
      defaultWorkspaceId: 'workspace-1',
      version: 1,
    }])
    await synchronization

    expect(store.state.phase).toBe('idle')
    expect(store.state.teams).toEqual([])
    expect(store.state.selectedTeamId).toBeNull()
  })

  it('ignores a late member response after identity removal', async () => {
    const gateway = new FixtureScopeGateway()
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
    const pending = deferred<Awaited<ReturnType<FixtureScopeGateway['listMembers']>>>()
    gateway.listMembers = vi.fn(async () => pending.promise)

    const membersRequest = store.loadMembers()
    store.reset()
    pending.resolve(structuredClone(fixtureMembers[fixtureIds.teamPlatform]))
    await membersRequest

    expect(store.state.members).toEqual([])
    expect(store.state.membersTeamId).toBeNull()
    expect(store.state.membersLoading).toBe(false)
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}
