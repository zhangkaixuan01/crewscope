import { fixtureIds } from '../../test/scopeFixtures'
import { FixtureWorkItemGateway, workItemIds } from '../../test/workItemFixtures'
import { createWorkItemStore, WORK_ITEM_UNDO_WINDOW_MS } from './store'
import type { WorkItemAvailableTransition } from './types'
import { CrewScopeApiError } from '../../api/client'
import { responsibilityIds } from '../../test/workItemFixtures'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

describe('WorkItem store', () => {
  it('stops remaining rows when the owning page unmounts without a project change', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    let ownsPage = true
    let resolve!: (value: Awaited<ReturnType<typeof gateway.assignExecutor>>) => void
    const write = vi.spyOn(gateway, 'assignExecutor').mockImplementation(() => new Promise(yes => { resolve = yes }))
    const batch = store.assignRows(scope, [bulkRow('a', workItemIds.first, []), bulkRow('b', workItemIds.second, [])], 'EXECUTOR', fixtureIds.principal, () => ownsPage)
    await vi.waitFor(() => expect(write).toHaveBeenCalledTimes(1))
    ownsPage = false
    resolve({ commandId: 'command', domainEventId: 'event', committedVersion: 1, correlationId: 'correlation' })
    await batch
    expect(write).toHaveBeenCalledTimes(1)
    expect(store.state.bulkPending).toBeNull()
  })
  it('stops a batch after prefetch when the project changes away and back', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const details = await gateway.getWorkItem(scope, workItemIds.first)
    let resolve!: (value: typeof details) => void
    gateway.getWorkItem = vi.fn(() => new Promise<typeof details>(yes => { resolve = yes }))
    const command = vi.spyOn(gateway, 'transitionWorkItem')
    const old = store.transitionRows(scope, [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')]), bulkRow('b', workItemIds.second, [offered('IN_REVIEW')])], 'IN_REVIEW')
    await store.load({ ...scope, projectId: fixtureIds.projectRuntime })
    await store.load(scope)
    resolve(details)
    await old
    expect(command).not.toHaveBeenCalled()
    expect(store.state.bulkPending).toBeNull()
  })

  it('retains original version/key and skips confirmed rows on partial-batch retry', async () => {
    const gateway = new FixtureWorkItemGateway()
    const original = gateway.transitionWorkItem.bind(gateway)
    const calls: Array<Parameters<typeof gateway.transitionWorkItem>> = []
    gateway.transitionWorkItem = async (...args) => {
      calls.push(args)
      const result = await original(...args)
      if (args[1] === workItemIds.second && calls.length === 2) throw new Error('response lost')
      return result
    }
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const rows = [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')]), bulkRow('b', workItemIds.second, [offered('IN_REVIEW')])]
    await store.transitionRows(scope, rows, 'IN_REVIEW')
    await store.transitionRows(scope, rows, 'IN_REVIEW')
    expect(calls).toHaveLength(3)
    expect(calls[2]).toEqual(calls[1])
  })

  it('does not publish an old detail command after selecting A → B → A', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)
    let reject!: (reason: Error) => void
    gateway.addComment = vi.fn(() => new Promise<never>((_yes, no) => { reject = no }))
    const old = store.addComment({ content: 'old comment' }).catch(() => {})
    await store.loadDetails(scope, workItemIds.second)
    await store.loadDetails(scope, workItemIds.first)
    reject(new Error('late'))
    await old
    expect(store.state.detailCommandErrorMessage).toBeNull()
  })
  it('loads a server status filter and continues from an opaque Cursor without duplicates', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)

    await store.load(scope)
    await store.loadMore()

    expect(gateway.queries[1]?.after).toBe('next-page')
    expect(store.state.items.map(item => item.id)).toEqual([workItemIds.first, workItemIds.second, workItemIds.third])
    expect(store.state.nextCursor).toBeNull()
  })

  it('continues a filtered page with the whole filter and restarts when any dimension changes', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)

    await store.load(scope, { type: ['FEATURE', 'BUG'], sort: 'priority' })
    await store.loadMore()
    expect(gateway.queries[1]).toMatchObject({ type: ['FEATURE', 'BUG'], sort: 'priority', after: 'next-page' })

    await store.load(scope, { type: ['BUG', 'FEATURE'], sort: 'priority' })
    expect(gateway.queries).toHaveLength(2)

    await store.load(scope, { status: 'READY' })
    expect(gateway.queries.at(-1)?.after).toBeUndefined()
    expect(gateway.queries.at(-1)?.status).toBe('READY')
    expect(store.state.items.map(item => item.id)).toEqual([workItemIds.second])
    expect(store.state.nextCursor).toBeNull()
  })

  it('reads a bare status argument as the legacy single-dimension filter', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)

    await store.load(scope, 'IN_PROGRESS')

    expect(gateway.queries[0]).toMatchObject({ status: 'IN_PROGRESS' })
    expect(store.state.items.map(item => item.id)).toEqual([workItemIds.first])
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

  it('loads the server action verdict alongside the details instead of widening the state machine', async () => {
    const gateway = new FixtureWorkItemGateway()
    gateway.availabilityOverride = [{
      actionId: 'complete', targetStatus: 'DONE', label: '标记完成', strength: 'PRIMARY',
      reversible: false, enabled: false, reason: 'REVIEWER_REQUIRED',
      reasonMessage: '需要先指派 Reviewer', remedyLabel: '指派 Reviewer', remedyRoute: '/work',
    }]
    const store = createWorkItemStore(gateway)

    await store.loadDetails(scope, workItemIds.first)

    expect(gateway.availabilityQueries).toEqual([workItemIds.first])
    expect(store.state.availabilityPhase).toBe('ready')
    expect(store.state.availableTransitions.map(transition => transition.targetStatus)).toEqual(['DONE'])
    expect(store.state.availableTransitions[0]?.reasonMessage).toBe('需要先指派 Reviewer')
  })

  it('still renders the details when the action verdict fails, and says so', async () => {
    const gateway = new FixtureWorkItemGateway()
    gateway.listAvailableTransitions = async () => { throw new Error('availability unavailable') }
    const store = createWorkItemStore(gateway)

    await store.loadDetails(scope, workItemIds.first)

    expect(store.state.detailPhase).toBe('ready')
    expect(store.state.availabilityPhase).toBe('error')
    expect(store.state.availableTransitions).toEqual([])
    expect(store.state.undoOffer).toBeNull()
  })

  it('undoes a reversible transition by executing the reverse edge as an ordinary command', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)

    await store.transition('IN_REVIEW')

    expect(store.state.undoOffer).toMatchObject({
      workItemId: workItemIds.first,
      fromStatus: 'IN_PROGRESS',
      toStatus: 'IN_REVIEW',
      expectedVersion: 1,
    })

    await store.undoTransition()

    // The reverse edge goes through transitionWorkItem like any other action: same contract, same
    // expectedVersion check, same Receipt. Nothing compensating, nothing bypassed.
    expect(gateway.transitions).toEqual([
      { workItemId: workItemIds.first, targetStatus: 'IN_REVIEW', expectedVersion: 0 },
      { workItemId: workItemIds.first, targetStatus: 'IN_PROGRESS', expectedVersion: 1 },
    ])
    expect(store.state.detail?.workItem.status).toBe('IN_PROGRESS')
    expect(store.state.undoOffer).toBeNull()
    expect(store.state.detailCommandPending).toBeNull()
  })

  it('does not offer an undo for an edge the state machine cannot reverse', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)

    await store.transition('CANCELLED')

    // CANCELLED only leads to ARCHIVED, so an undo offer here would promise an execution the
    // domain would reject.
    expect(store.state.undoOffer).toBeNull()
  })

  it('runs an action a row offered after reading the authoritative version', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope)

    // 列表行的替身不携带结论，正是「服务端什么都没说」的形状：此时不能当成「不行」，
    // 必须去读一次权威副本再决定。
    const row = store.state.items.find(item => item.id === workItemIds.first)!
    expect(row.availableActions).toEqual([])

    const result = await store.transitionFromRow(scope, row, 'IN_REVIEW')

    expect(result).toEqual({ status: 'executed' })
    expect(gateway.transitions).toEqual([{ workItemId: workItemIds.first, targetStatus: 'IN_REVIEW', expectedVersion: 0 }])
  })

  it('refuses a row the server already said no to, without reading the detail', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const row = {
      ...store.state.items.find(item => item.id === workItemIds.first)!,
      availableActions: [{
        actionId: 'submit-review', targetStatus: 'IN_REVIEW' as const, label: '提交评审', strength: 'PRIMARY' as const,
        reversible: true, enabled: false, reason: 'REVIEWER_REQUIRED' as const,
        reasonMessage: '需要先指派 Reviewer', remedyLabel: null, remedyRoute: null,
      }],
    }

    const result = await store.transitionFromRow(scope, row, 'IN_REVIEW')

    expect(result).toEqual({ status: 'refused', message: '需要先指派 Reviewer' })
    expect(gateway.transitions).toEqual([])
    expect(gateway.availabilityQueries).toEqual([])
  })

  it('lets the authoritative copy overrule a row that has fallen behind', async () => {
    const gateway = new FixtureWorkItemGateway()
    gateway.availabilityOverride = [{
      actionId: 'submit-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY',
      reversible: true, enabled: false, reason: 'GATE_NOT_PASSED',
      reasonMessage: '前置 Gate 未通过', remedyLabel: null, remedyRoute: null,
    }]
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const row = store.state.items.find(item => item.id === workItemIds.first)!

    const result = await store.transitionFromRow(scope, row, 'IN_REVIEW')

    expect(result).toEqual({ status: 'refused', message: '前置 Gate 未通过' })
    expect(gateway.transitions).toEqual([])
  })

  it('reports an unreadable row as a failure rather than throwing at the click site', async () => {
    const gateway = new FixtureWorkItemGateway()
    gateway.getWorkItem = async () => { throw new Error('offline') }
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const row = store.state.items.find(item => item.id === workItemIds.first)!

    const result = await store.transitionFromRow(scope, row, 'IN_REVIEW')

    expect(result.status).toBe('failed')
    expect(gateway.transitions).toEqual([])
  })

  it('reports a lost command as a failure the card can show', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const row = store.state.items.find(item => item.id === workItemIds.first)!
    gateway.transitionWorkItem = async () => {
      gateway.items[0]!.version = 2
      throw new CrewScopeApiError(409, { code: 'optimistic_lock_conflict', message: 'Version conflict', correlationId: 'conflict', retryable: true, currentVersion: 2, details: {} })
    }

    const result = await store.transitionFromRow(scope, row, 'IN_REVIEW')

    expect(result.status).toBe('failed')
    expect(result.status === 'failed' && result.message).toContain('详情已刷新')
    expect(store.state.versionConflict).toEqual({ attemptedVersion: 0, currentVersion: 2 })
  })

  it('names the row whose command is in flight so only that card waits', async () => {
    const gateway = new FixtureWorkItemGateway()
    let duringCommand: string | null = 'not observed'
    gateway.transitionWorkItem = async (...args) => {
      duringCommand = store.state.rowActionItemId
      return FixtureWorkItemGateway.prototype.transitionWorkItem.apply(gateway, args)
    }
    const store = createWorkItemStore(gateway)
    await store.load(scope)
    const row = store.state.items.find(item => item.id === workItemIds.first)!

    await store.transitionFromRow(scope, row, 'IN_REVIEW')

    expect(duringCommand).toBe(workItemIds.first)
    expect(store.state.rowActionItemId).toBeNull()
  })

  it('refuses an expired undo instead of submitting a stale reverse command', async () => {
    const gateway = new FixtureWorkItemGateway()
    const store = createWorkItemStore(gateway)
    await store.loadDetails(scope, workItemIds.first)
    await store.transition('IN_REVIEW')
    const commandCount = gateway.transitions.length
    const realNow = Date.now

    try {
      Date.now = () => realNow() + WORK_ITEM_UNDO_WINDOW_MS + 1
      await store.undoTransition()
    } finally {
      Date.now = realNow
    }

    expect(gateway.transitions.length).toBe(commandCount)
    expect(store.state.undoOffer).toBeNull()
    expect(store.state.detailCommandErrorMessage).toContain('撤销时限已过')
    expect(store.state.detail?.workItem.status).toBe('IN_REVIEW')
  })

  describe('batch transitions', () => {
    it('reports every settled row, asking the server only for the rows that may still move', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)

      const results = await store.transitionRows(scope, [
        bulkRow('a', workItemIds.first, [offered('IN_REVIEW')]),
        // 未表态：既未提供也未拒绝，按「静默不是拒绝」照发命令。
        bulkRow('b', workItemIds.second, []),
        bulkRow('c', workItemIds.third, [refused('IN_REVIEW', '评审尚未通过')]),
      ], 'IN_REVIEW')

      expect(results.map(result => [result.key, result.outcome])).toEqual([
        ['CRW-18', 'executed'],
        ['CRW-19', 'executed'],
        ['CRW-20', 'excluded'],
      ])
      expect(results[2]!.message).toBe('评审尚未通过')
      // 被排除的行从未被提交：预告与执行共用同一条裁决，所以「跳过的行确实没发命令」。
      expect(gateway.transitions.map(transition => transition.workItemId)).toEqual([workItemIds.first, workItemIds.second])
    })

    it('keeps every row’s command independent, with its own idempotency key and current version', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)

      await store.transitionRows(scope, [
        bulkRow('a', workItemIds.first, [offered('IN_REVIEW')]),
        bulkRow('b', workItemIds.second, [offered('IN_REVIEW')]),
      ], 'IN_REVIEW')

      // 每行独立幂等键：批量重试时不会把「已经执行过」的行再执行一次。
      expect(gateway.transitionIdempotencyKeys).toHaveLength(2)
      expect(new Set(gateway.transitionIdempotencyKeys).size).toBe(2)
    })

    it('advances and then clears the batch progress', async () => {
      const gateway = new FixtureWorkItemGateway()
      const progress: Array<{ completed: number, total: number } | null> = []
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      gateway.transitionWorkItem = async (...args) => {
        progress.push(store.state.bulkPending && { ...store.state.bulkPending })
        return FixtureWorkItemGateway.prototype.transitionWorkItem.apply(gateway, args)
      }

      await store.transitionRows(scope, [
        bulkRow('a', workItemIds.first, [offered('IN_REVIEW')]),
        bulkRow('b', workItemIds.second, [offered('IN_REVIEW')]),
      ], 'IN_REVIEW')
      progress.push(store.state.bulkPending)

      // 逐行推进而不是一个转圈：成员要能看见批量走到了第几项。
      expect(progress).toEqual([{ completed: 0, total: 2 }, { completed: 1, total: 2 }, null])
    })

    it('classifies a lost command as unconfirmed rather than as a refusal', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      gateway.transitionWorkItem = async () => { throw new TypeError('Failed to fetch') }

      const [result] = await store.transitionRows(scope, [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')])], 'IN_REVIEW')

      // 结果未知 ≠ 失败：把丢失的命令读成「可以做点什么的工作项」会诱发重复提交。
      expect(result!.outcome).toBe('failed')
      expect(result!.message).toContain('提交结果尚未确认')
    })

    it('re-reads a conflicting row and names its state instead of guessing', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      const reads: string[] = []
      const detailRead = gateway.getWorkItem.bind(gateway)
      gateway.getWorkItem = (queryScope, workItemId) => {
        reads.push(workItemId)
        return detailRead(queryScope, workItemId)
      }
      gateway.transitionWorkItem = async () => {
        gateway.items.find(item => item.id === workItemIds.first)!.version = 2
        throw new CrewScopeApiError(409, { code: 'optimistic_lock_conflict', message: 'Version conflict', correlationId: 'conflict', retryable: true, currentVersion: 2, details: {} })
      }

      const [result] = await store.transitionRows(scope, [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')])], 'IN_REVIEW')

      expect(result!.outcome).toBe('failed')
      expect(result!.message).toContain('已重新读取当前状态')
      // 汇总说的是「当前事实」，那就必须真的去问过：一次取 version，冲突后一次回读。
      expect(reads.filter(id => id === workItemIds.first)).toHaveLength(2)
    })

    it('carries the server’s own reason for a 4xx verdict the row had not published', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      gateway.transitionWorkItem = async () => {
        throw new CrewScopeApiError(403, { code: 'policy_denied', message: 'Only the owner may move this WorkItem', correlationId: 'denied', retryable: false, currentVersion: null, details: {} })
      }

      const [result] = await store.transitionRows(scope, [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')])], 'IN_REVIEW')

      expect(result!.outcome).toBe('refused')
      expect(result!.message).toBe('Only the owner may move this WorkItem')
    })

    it('reloads the collection only when something actually changed', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      const afterLoad = gateway.queries.length

      // 全部被排除的批量什么都没改；让成员等一页不可能不同的列表是纯粹的等待。
      const excluded = await store.transitionRows(scope, [bulkRow('c', workItemIds.third, [refused('IN_REVIEW', '评审尚未通过')])], 'IN_REVIEW')
      expect(excluded[0]!.outcome).toBe('excluded')
      expect(gateway.queries.length).toBe(afterLoad)

      await store.transitionRows(scope, [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')])], 'IN_REVIEW')
      expect(gateway.queries.length).toBeGreaterThan(afterLoad)
    })

    it('refreshes the open drawer so a batch cannot hand the member their own conflict', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      await store.loadDetails(scope, workItemIds.first)
      const versionBefore = store.state.detail!.workItem.version

      await store.transitionRows(scope, [bulkRow('a', workItemIds.first, [offered('IN_REVIEW')])], 'IN_REVIEW')

      // 抽屉里的版本与裁决仍是批量之前的；不刷新，下一次点击就会撞上成员自己的批量造成的冲突。
      expect(store.state.detail!.workItem.status).toBe('IN_REVIEW')
      expect(store.state.detail!.workItem.version).toBeGreaterThan(versionBefore)
    })
  })

  describe('batch assignments', () => {
    it('reads the owner chain before replacing it, so the conflict check has something to check', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)

      await store.assignRows(scope, [bulkRow('a', workItemIds.first, [])], 'OWNER', fixtureIds.principal)

      // 服务端会校验这两项；行本身不带责任链，所以链必须在这里读出来而不是编一个。
      expect(gateway.ownerReplacements[0]).toEqual({
        actorPrincipalId: fixtureIds.principal,
        expectedAssignmentId: responsibilityIds.owner,
        expectedVersion: 0,
      })
    })

    it('assigns an executor without reading a chain it does not need', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)

      const [result] = await store.assignRows(scope, [bulkRow('a', workItemIds.first, [])], 'EXECUTOR', fixtureIds.principal)

      expect(result!.outcome).toBe('executed')
      expect(gateway.executorAssignments).toEqual([{ actorPrincipalId: fixtureIds.principal }])
    })

    it('reloads the collection after an assignment even though the rows look unchanged', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      const afterLoad = gateway.queries.length

      await store.assignRows(scope, [bulkRow('a', workItemIds.first, [])], 'EXECUTOR', fixtureIds.principal)

      // 列表行不携带责任事实：不重读，界面就会在服务端已经改动后显得毫无变化。
      expect(gateway.queries.length).toBeGreaterThan(afterLoad)
    })

    it('names a moved responsibility chain in the same words a single-row assignment uses', async () => {
      const gateway = new FixtureWorkItemGateway()
      const store = createWorkItemStore(gateway)
      await store.load(scope)
      gateway.replaceOwner = async () => {
        throw new CrewScopeApiError(409, { code: 'responsibility_conflict', message: 'stale chain', correlationId: 'conflict', retryable: true, currentVersion: null, details: {} })
      }

      const [result] = await store.assignRows(scope, [bulkRow('a', workItemIds.first, [])], 'OWNER', fixtureIds.principal)

      expect(result!.outcome).toBe('refused')
      expect(result!.message).toBe('责任链已发生变化，最新责任已刷新，请确认后重试')
    })
  })
})

function offered(targetStatus: 'IN_REVIEW' | 'DONE'): WorkItemAvailableTransition {
  return {
    actionId: `to-${targetStatus}`.toLowerCase().replaceAll('_', '-'), targetStatus, label: '提交评审',
    strength: 'PRIMARY', reversible: true, enabled: true, reason: null, reasonMessage: null,
    remedyLabel: null, remedyRoute: null,
  }
}

function refused(targetStatus: 'IN_REVIEW' | 'DONE', reasonMessage: string): WorkItemAvailableTransition {
  return { ...offered(targetStatus), enabled: false, reason: 'REVIEWER_REQUIRED', reasonMessage }
}

function bulkRow(id: string, workItemId: string, availableActions: readonly WorkItemAvailableTransition[]) {
  return { id: workItemId, key: id === 'a' ? 'CRW-18' : id === 'b' ? 'CRW-19' : 'CRW-20', availableActions }
}
