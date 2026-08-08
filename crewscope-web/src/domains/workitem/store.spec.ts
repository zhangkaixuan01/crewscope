import { fixtureIds } from '../../test/scopeFixtures'
import { FixtureWorkItemGateway, workItemIds } from '../../test/workItemFixtures'
import { createWorkItemStore } from './store'
import { CrewScopeApiError } from '../../api/client'
import { responsibilityIds } from '../../test/workItemFixtures'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

describe('WorkItem store', () => {
  it('loads a server status filter and continues from an opaque Cursor without duplicates', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)

    await store.load(scope)
    await store.loadMore()

    expect(gateway.queries[1]?.after).toBe('next-page')
    expect(store.state.items.map(item => item.id)).toEqual([workItemIds.first, workItemIds.second, workItemIds.third])
    expect(store.state.nextCursor).toBeNull()
  })

  it('reloads the active query after an idempotent create command', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope, 'BACKLOG')

    await store.create({ key: 'CRW-21', type: 'TASK', title: '准备发布', description: null, priority: 'MEDIUM', labels: [], dueAt: null })

    expect(gateway.creations[0]?.key).toBe('CRW-21')
    expect(gateway.queries.at(-1)?.status).toBe('BACKLOG')
    expect(store.state.items[0]?.title).toBe('准备发布')
    expect(store.state.commandPending).toBe(false)
  })

  it('discards an older project response after scope changes', async () => {
    let resolveFirst!: (value: { items: []; nextCursor: null }) => void
    const gateway = new FixtureWorkItemGateway()
    const original = gateway.listWorkItems.bind(gateway)
    gateway.listWorkItems = query => query.projectId === fixtureIds.projectCrewScope
      ? new Promise(resolve => { resolveFirst = resolve })
      : original(query)
    const store = createWorkItemStore(gateway)
    const stale = store.load(scope)

    await store.load({ ...scope, projectId: fixtureIds.projectRuntime })
    resolveFirst({ items: [], nextCursor: null })
    await stale

    expect(store.state.items.length).toBeGreaterThan(0)
    expect(store.state.phase).toBe('ready')
  })

  it('does not let a slow create Receipt restore the previous project query', async () => {
    let resolveCreate!: (value: { commandId: string; domainEventId: string; committedVersion: number; correlationId: string }) => void
    const gateway = new FixtureWorkItemGateway()
    gateway.createWorkItem = () => new Promise(resolve => { resolveCreate = resolve })
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const creation = store.create({ key: 'CRW-21', type: 'TASK', title: '旧项目命令', description: null, priority: 'MEDIUM', labels: [], dueAt: null })

    await store.load({ ...scope, projectId: fixtureIds.projectRuntime })
    resolveCreate({ commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' })
    await creation

    expect(gateway.queries.at(-1)?.projectId).toBe(fixtureIds.projectRuntime)
    expect(store.state.commandErrorMessage).toBeNull()
  })

  it('loads a detail snapshot, transitions with its version and refreshes collection facts', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    await store.loadDetails(scope, workItemIds.first)

    await store.transition('IN_REVIEW')

    expect(gateway.transitions).toEqual([{ workItemId: workItemIds.first, targetStatus: 'IN_REVIEW', expectedVersion: 0 }])
    expect(store.state.detail?.workItem.status).toBe('IN_REVIEW')
    expect(store.state.detail?.workItem.version).toBe(1)
    expect(store.state.items.find(item => item.id === workItemIds.first)?.status).toBe('IN_REVIEW')
  })

  it('loads the active responsibility chain and first timeline page with the detail snapshot', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)

    await store.loadDetails(scope, workItemIds.first)

    expect(store.state.responsibilityPhase).toBe('ready')
    expect(store.state.responsibilities.map(item => item.role)).toEqual(['OWNER', 'EXECUTOR', 'REVIEWER'])
    expect(store.state.timelinePhase).toBe('ready')
    expect(store.state.timeline).toHaveLength(2)
    expect(store.state.timelineNextCursor).toBe('timeline-page-2')
  })

  it('preserves the current Owner identity/version and assignment version on responsibility commands', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)

    await store.replaceOwner('00000000-0000-0000-0000-000000000105')
    const executor = store.state.responsibilities.find(item => item.id === responsibilityIds.executor)!
    await store.releaseResponsibility(executor)

    expect(gateway.ownerReplacements[0]).toEqual({
      actorPrincipalId: '00000000-0000-0000-0000-000000000105',
      expectedAssignmentId: responsibilityIds.owner,
      expectedVersion: 0,
    })
    expect(gateway.releases).toEqual([{ assignmentId: responsibilityIds.executor, expectedVersion: 0 }])
    expect(store.state.responsibilities.some(item => item.id === responsibilityIds.executor)).toBe(false)
  })

  it('refreshes the responsibility chain before exposing a safe concurrency error', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)
    const replacement = structuredClone(gateway.responsibilities[0]!)
    replacement.id = '00000000-0000-0000-0000-000000000999'
    replacement.actorDisplayName = '服务端新 Owner'
    gateway.replaceOwner = async () => {
      gateway.responsibilities[0] = replacement
      throw new CrewScopeApiError(409, { code: 'responsibility_conflict', message: 'stale chain', correlationId: 'conflict', retryable: true, currentVersion: null, details: {} })
    }

    await expect(store.replaceOwner(fixtureIds.principal)).rejects.toMatchObject({ status: 409 })

    expect(store.state.responsibilities[0]?.id).toBe(replacement.id)
    expect(store.state.responsibilityCommandErrorMessage).toContain('最新责任已刷新')
    expect(store.state.responsibilityCommandPending).toBeNull()
  })

  it('continues the timeline from its Cursor and removes duplicate event IDs', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)

    await store.loadTimelineMore()

    expect(gateway.timelineQueries.at(-1)?.after).toBe('timeline-page-2')
    expect(store.state.timeline.map(event => event.eventId)).toEqual([
      '00000000-0000-0000-0000-000000001001',
      '00000000-0000-0000-0000-000000001002',
    ])
    expect(store.state.timelineNextCursor).toBeNull()
  })

  it('refreshes immutable comments and ResourceLinks after collaboration commands', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)

    await store.addComment({ content: '补充验收证据' })
    await store.linkResource({ resourceType: 'EXTERNAL_URL', resourceReference: 'https://example.com/evidence', label: '验收证据' })

    expect(store.state.detail?.comments.at(-1)?.content).toBe('补充验收证据')
    expect(store.state.detail?.resourceLinks.at(-1)?.label).toBe('验收证据')
    expect(store.state.detailCommandPending).toBeNull()
  })

  it('surfaces an optimistic conflict and refreshes to the server version', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)
    gateway.transitionWorkItem = async () => {
      gateway.items[0]!.version = 2
      throw new CrewScopeApiError(409, { code: 'optimistic_lock_conflict', message: 'Version conflict', correlationId: 'conflict', retryable: true, currentVersion: 2, details: {} })
    }

    await expect(store.transition('IN_REVIEW')).rejects.toMatchObject({ status: 409 })

    expect(store.state.versionConflict).toEqual({ attemptedVersion: 0, currentVersion: 2 })
    expect(store.state.detail?.workItem.version).toBe(2)
    expect(store.state.detailCommandErrorMessage).toContain('详情已刷新')
  })

  it('does not let an older detail response replace a newly selected WorkItem', async () => {
    let resolveFirst!: (value: Awaited<ReturnType<FixtureWorkItemGateway['getWorkItem']>>) => void
    const gateway = new FixtureWorkItemGateway()
    const original = gateway.getWorkItem.bind(gateway)
    gateway.getWorkItem = (_scope, workItemId) => workItemId === workItemIds.first
      ? new Promise(resolve => { resolveFirst = resolve })
      : original(_scope, workItemId)
    const store = createWorkItemStore(gateway)
    const stale = store.loadDetails(scope, workItemIds.first)

    await store.loadDetails(scope, workItemIds.second)
    resolveFirst(await new FixtureWorkItemGateway().getWorkItem(scope, workItemIds.first))
    await stale

    expect(store.state.selectedWorkItemId).toBe(workItemIds.second)
    expect(store.state.detail?.workItem.id).toBe(workItemIds.second)
  })
})
