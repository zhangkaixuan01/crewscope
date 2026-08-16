import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { FixtureTaskGateway, fixtureTasks, taskIds } from '../../test/taskFixtures'
import { HttpTaskGateway } from './gateway'

describe('HttpTaskGateway', () => {
  const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }

  it('maps the Task collection through a public whitelist and preserves the opaque Cursor', async () => {
    const rawTask = {
      ...fixtureTasks[fixtureIds.teamPlatform]![0]!,
      taskToken: 'must-not-enter-web-state',
      claimTokenHash: 'secret-hash',
    }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ items: [rawTask], nextCursor: 'opaque+/cursor' }))
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const page = await gateway.listTasks({ ...scope, projectId: fixtureIds.projectCrewScope, status: 'ACTIVE', ownerPrincipalId: fixtureIds.principal, after: 'before+/cursor', limit: 25 })

    const url = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(url.pathname).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/tasks`)
    expect(Object.fromEntries(url.searchParams)).toEqual({
      limit: '25',
      after: 'before+/cursor',
      projectId: fixtureIds.projectCrewScope,
      status: 'ACTIVE',
      ownerPrincipalId: fixtureIds.principal,
    })
    expect(page.nextCursor).toBe('opaque+/cursor')
    expect(page.items[0]).not.toHaveProperty('taskToken')
    expect(page.items[0]).not.toHaveProperty('claimTokenHash')
  })

  it('creates a WorkItem Task with strong version and caller-owned idempotency metadata', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 0, correlationId: crypto.randomUUID(),
    }, 202))
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const input = {
      objective: '完成 M3-F02',
      acceptanceCriteria: ['列表可观测'],
      executorAgentProfileId: crypto.randomUUID(),
      conversationSource: null,
      providerBindingIds: [],
    }

    await gateway.createTask({
      scope,
      projectId: fixtureIds.projectCrewScope,
      workItemId: taskIds.workItem,
      expectedVersion: 7,
      input,
    }, 'task-create-1')

    const request = fetcher.mock.calls[0]?.[1]
    const headers = new Headers(request?.headers)
    expect(fetcher.mock.calls[0]?.[0]).toContain(`/work-items/${taskIds.workItem}/tasks`)
    expect(request?.method).toBe('POST')
    expect(headers.get('Idempotency-Key')).toBe('task-create-1')
    expect(headers.get('If-Match')).toBe('"7"')
    expect(request?.body).toBe(JSON.stringify(input))
  })

  it('sends member Task commands to the current attempt with strong version and exact body rules', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse({
      commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 3, correlationId: crypto.randomUUID(),
    }, 202))
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.commandTask({
      scope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 2, operation: 'PAUSE', reason: '等待审查',
    }, 'task-pause-1')
    await gateway.commandTask({
      scope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 3, operation: 'RESUME',
    }, 'task-resume-1')

    const pause = fetcher.mock.calls[0]!
    expect(pause[0]).toBe(`/api/v1/organizations/${scope.organizationId}/teams/${scope.teamId}/tasks/${taskIds.first}/attempts/${taskIds.execution}/pause`)
    expect(new Headers(pause[1]?.headers).get('Idempotency-Key')).toBe('task-pause-1')
    expect(new Headers(pause[1]?.headers).get('If-Match')).toBe('"2"')
    expect(pause[1]?.body).toBe(JSON.stringify({ reason: '等待审查' }))
    const resume = fetcher.mock.calls[1]!
    expect(resume[0]).toContain(`/attempts/${taskIds.execution}/resume`)
    expect(resume[1]?.body).toBeUndefined()
    expect(new Headers(resume[1]?.headers).get('If-Match')).toBe('"3"')
  })

  it('loads detail, attempts and Runtime facts from stable nested routes without retaining security fields', async () => {
    const fixture = new FixtureTaskGateway()
    const details = await fixture.getTask(scope, taskIds.first)
    const attempts = await fixture.listAttempts(scope, taskIds.first)
    const facts = await fixture.getRuntimeFacts(scope, taskIds.first, taskIds.execution)
    const responses = [
      jsonResponse({ ...details, rawAgentState: 'private' }),
      jsonResponse(attempts.map(value => ({ ...value, taskTokenJtiHash: 'private' }))),
      jsonResponse({ ...facts, claimToken: 'private', execution: { ...facts.execution, leaseTokenHash: 'private' } }),
    ]
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => responses.shift()!)
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const mappedDetails = await gateway.getTask(scope, taskIds.first)
    const mappedAttempts = await gateway.listAttempts(scope, taskIds.first)
    const mappedFacts = await gateway.getRuntimeFacts(scope, taskIds.first, taskIds.execution)

    expect(fetcher.mock.calls.map(call => call[0])).toEqual([
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/tasks/${taskIds.first}`,
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/tasks/${taskIds.first}/attempts`,
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/tasks/${taskIds.first}/attempts/${taskIds.execution}/runtime-facts`,
    ])
    expect(JSON.stringify([mappedDetails, mappedAttempts, mappedFacts])).not.toMatch(/rawAgentState|taskToken|claimToken|leaseTokenHash/)
  })

  it('loads the member-safe Runtime fleet summary without retaining operations-only Worker facts', async () => {
    const raw = {
      environment: 'production', observedAt: '2026-08-15T12:01:00Z', health: 'DEGRADED',
      runtimeCount: 2, workerCount: 3, activeWorkerCount: 2, staleWorkerCount: 1,
      drainingWorkerCount: 0, capacity: { maximum: 6, active: 4, available: 2, claimableWorkerIds: ['private'] },
      waitingRuntimeExecutions: 1, waitingCauses: [{ cause: 'CAPACITY', count: 1, executionIds: ['private'] }],
      workers: [{ stableKey: 'operations-only', lastHeartbeatAt: 'private' }],
      runtimeIds: ['private'],
    }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(raw))
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const summary = await gateway.getRuntimeHealth(scope)

    expect(fetcher.mock.calls[0]?.[0]).toBe(
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/runtime-health`,
    )
    expect(summary).toMatchObject({ health: 'DEGRADED', staleWorkerCount: 1, capacity: { available: 2 } })
    expect(JSON.stringify(summary)).not.toMatch(/workers|runtimeIds|stableKey|claimableWorkerIds|executionIds|lastHeartbeatAt/)
  })

  it('passes event and association Cursors as opaque values on each source-specific route', async () => {
    const emptyEventPage = { items: [], hasMore: false, taskTerminal: false, nextCursor: null }
    const emptyAssociationPage = { items: [], nextCursor: null }
    const associations = {
      task: { id: taskIds.first, projectId: fixtureIds.projectCrewScope, workItemId: taskIds.workItem, status: 'ACTIVE', objective: 'Task', href: '/work?task=1' },
      workItem: { id: taskIds.workItem, projectId: fixtureIds.projectCrewScope, key: 'CRW-18', title: 'Task', status: 'OPEN', href: '/work?workItem=1' },
      conversations: { items: [], nextCursor: null },
    }
    const responses = [jsonResponse(emptyEventPage), jsonResponse(emptyAssociationPage), jsonResponse(emptyAssociationPage), jsonResponse(associations)]
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => responses.shift()!)
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listEvents(scope, taskIds.first, 'event+/cursor', 20)
    await gateway.listByWorkItem(scope, fixtureIds.projectCrewScope, taskIds.workItem, 'work+/cursor', 20)
    await gateway.listByConversation(scope, taskIds.conversation, 'conversation+/cursor', 20)
    await gateway.getAssociations(scope, taskIds.first, 'task+/cursor', 20)

    const urls = fetcher.mock.calls.map(call => new URL(String(call[0]), 'http://crewscope.test'))
    expect(urls.map(url => url.searchParams.get('after'))).toEqual([
      'event+/cursor', 'work+/cursor', 'conversation+/cursor', 'task+/cursor',
    ])
    expect(urls[1]!.pathname).toContain(`/work-projects/${fixtureIds.projectCrewScope}/work-items/${taskIds.workItem}/tasks`)
    expect(urls[2]!.pathname).toContain(`/conversations/${taskIds.conversation}/tasks`)
    expect(urls[3]!.pathname).toContain(`/tasks/${taskIds.first}/associations`)
  })

  it('streams resumable Task events through the public whitelist and validates the Task boundary', async () => {
    const raw = {
      cursor: 'next+/cursor',
      context: { taskId: taskIds.first, taskExecutionId: taskIds.execution, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
      projectionGap: false,
      event: {
        eventId: 'event-live-1', domainEventId: 'domain-live-1', streamType: 'TASK', eventType: 'AGENT_RUN_EVENT_RECORDED',
        schemaVersion: '1', aggregateType: 'Task', aggregateId: taskIds.first, aggregateVersion: 2,
        correlationId: 'correlation', causationId: null, occurredAt: '2026-08-15T12:00:00Z',
        payload: {
          eventKind: 'PROGRESS', safeText: '正在验证', progressPercent: 60, name: { credential: 'nested-secret' },
          reasoning: 'private-reasoning', toolArguments: { credential: 'secret' }, contentHash: 'private-hash',
          usage: { totalTokens: 10, providerRequest: 'secret-request' },
          failure: { safeMessage: '安全错误', rawProviderError: 'secret-provider-error' },
        },
        claimToken: 'must-not-enter-web-state',
      },
      credential: 'must-not-enter-web-state',
    }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(sseResponse(`id: next+/cursor\nevent: AGENT_RUN_EVENT_RECORDED\ndata: ${JSON.stringify(raw)}\n\n`))
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const connection = await gateway.streamEvents(scope, taskIds.first, 'before+/cursor')
    const items = []
    for await (const item of connection.events) items.push(item)

    const url = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(url.searchParams.get('after')).toBe('before+/cursor')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Accept')).toBe('text/event-stream')
    expect(items).toHaveLength(1)
    expect(items[0]?.event.eventType).toBe('AGENT_RUN_EVENT_RECORDED')
    expect(items[0]?.event.payload).toEqual({
      eventKind: 'PROGRESS', safeText: '正在验证', progressPercent: 60,
      usage: { totalTokens: 10 }, failure: { safeMessage: '安全错误' },
    })
    expect(JSON.stringify(items)).not.toMatch(/claimToken|credential|nested-secret|reasoning|toolArguments|contentHash|providerRequest|rawProviderError/)
  })

  it('keeps the shared API error envelope intact for Store and permission boundaries', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      code: 'task_not_visible',
      message: 'Task 不可见',
      correlationId: 'correlation-403',
      retryable: false,
      currentVersion: null,
      details: { resource: 'task' },
    }, 403))
    const gateway = new HttpTaskGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await expect(gateway.getTask(scope, taskIds.first)).rejects.toMatchObject({
      status: 403,
      envelope: { code: 'task_not_visible', correlationId: 'correlation-403' },
    })
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function sseResponse(body: string): Response {
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
}
