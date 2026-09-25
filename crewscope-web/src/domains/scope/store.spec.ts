import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureScopeGateway, fixtureIds, fixtureMembers } from '../../test/scopeFixtures'
import { createScopeStore } from './store'

describe('scope store', () => {
  it('only accepts the newest forced member read in the same Team', async () => {
    const gateway = new FixtureScopeGateway()
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
    let resolve!: (members: typeof fixtureMembers[typeof fixtureIds.teamPlatform]) => void
    gateway.listMembers = vi.fn().mockImplementationOnce(() => new Promise(yes => { resolve = yes })).mockResolvedValue([])
    const old = store.loadMembers(true)
    await store.loadMembers(true)
    resolve(fixtureMembers[fixtureIds.teamPlatform]!)
    await old
    expect(store.state.members).toEqual([])
    expect(store.state.membersLoading).toBe(false)
  })
  it('restores a Team and WorkProject selected by URL identity', async () => {
    const store = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)

    const selection = await store.synchronize(fixtureIds.teamSecurity, fixtureIds.projectRuntime)

    expect(selection).toEqual({ teamId: fixtureIds.teamSecurity, projectId: fixtureIds.projectRuntime })
    expect(store.selectedTeam.value?.name).toBe('Security Engineering')
    expect(store.selectedProject.value?.key).toBe('SEC')
  })

  it('does not substitute another project for an explicit inaccessible project', async () => {
    const store = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)

    const selection = await store.synchronize(crypto.randomUUID(), crypto.randomUUID())

    expect(selection.projectId).toBeNull()
    expect(store.state.phase).toBe('error')
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

  it('suspends a member through the current version and re-reads the list', async () => {
    const gateway = new FixtureScopeGateway()
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    await store.loadMembers()

    await store.suspendMember(fixtureIds.memberSecond)

    const suspended = store.state.members.find(member => member.id === fixtureIds.memberSecond)
    expect(suspended?.status).toBe('SUSPENDED')
    expect(suspended?.version).toBe(1)
    expect(suspended?.authorizationVersion).toBe(2)
    expect(gateway.lifecycleCommands).toEqual([{
      action: 'suspend', memberId: fixtureIds.memberSecond, memberVersion: 0,
      idempotencyKey: expect.any(String),
    }])
  })

  it('reactivation restores access without reviving revoked role grants', async () => {
    const gateway = new FixtureScopeGateway()
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    await store.loadMembers()

    await store.grantRole(fixtureIds.memberSecond, 'TEAM_LEAD')
    await store.suspendMember(fixtureIds.memberSecond)
    await store.activateMember(fixtureIds.memberSecond)

    const reactivated = store.state.members.find(member => member.id === fixtureIds.memberSecond)
    expect(reactivated?.status).toBe('ACTIVE')
    expect(reactivated?.roles).toEqual([])
    expect(reactivated?.grants).toEqual([])
  })

  it('leaves the Team for the viewer own membership only', async () => {
    const gateway = new FixtureScopeGateway()
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    await store.loadMembers()

    await store.leaveTeam()

    const self = store.state.members.find(member => member.id === fixtureIds.memberOwner)
    expect(self?.status).toBe('LEFT')
    expect(gateway.lifecycleCommands.map(command => command.action)).toEqual(['leave'])
  })

  it('runs a handover from preview through processing while keeping the job in state', async () => {
    const gateway = new FixtureScopeGateway()
    const assignment = { assignmentId: 'assignment-1', workItemId: '00000000-0000-0000-0000-000000000901', role: 'OWNER', version: 3 }
    gateway.responsibilities[fixtureIds.memberSecond] = [assignment]
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    await store.loadMembers()

    const preview = await store.previewResponsibilities(fixtureIds.memberSecond, 'OWNER')
    expect(preview).toEqual([assignment])

    const created = await store.createHandover({
      sourceMemberId: fixtureIds.memberSecond,
      targetPrincipalId: fixtureIds.principal,
      role: 'OWNER',
    })
    expect(created.status).toBe('PENDING')
    expect(store.state.handoverJob?.id).toBe(created.id)
    expect(store.state.handoverPending).toBe(false)

    const processed = await store.processHandover(created.id)
    expect(processed.status).toBe('COMPLETED')
    expect(processed.items.map(item => item.state)).toEqual(['DONE'])
    expect(store.state.handoverJob?.status).toBe('COMPLETED')
  })

  it('creates, refreshes and selects a WorkProject under the active Team', async () => {
    const gateway = new FixtureScopeGateway()
    gateway.projects[fixtureIds.teamPlatform] = []
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)

    expect(await store.checkWorkProjectKey('crew')).toBe(true)
    const created = await store.createWorkProject(
      { key: ' crew ', name: ' CrewScope Platform ' },
      'project-command-1',
    )

    expect(gateway.createdProjects).toEqual([{
      input: { key: 'CREW', name: 'CrewScope Platform' },
      idempotencyKey: 'project-command-1',
    }])
    expect(store.state.selectedProjectId).toBe(created.id)
    expect(store.selectedProject.value?.key).toBe('CREW')
    expect(store.state.projectCommandPending).toBe(false)
    expect(store.state.projectCommandErrorMessage).toBeNull()
  })

  it('keeps a retryable WorkProject command visible when its projection is delayed', async () => {
    const gateway = new FixtureScopeGateway()
    gateway.projects[fixtureIds.teamPlatform] = []
    gateway.createWorkProject = vi.fn(async () => ({
      commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
      committedVersion: 0, correlationId: crypto.randomUUID(),
    }))
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)

    await expect(store.createWorkProject(
      { key: 'CREW', name: 'CrewScope Platform' },
      'project-command-retry',
    )).rejects.toThrow('result is not available')

    expect(store.state.projectCommandRetryable).toBe(true)
    expect(store.state.projectCommandErrorMessage).toContain('结果仍待确认')
    expect(store.state.projectCommandPending).toBe(false)
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
