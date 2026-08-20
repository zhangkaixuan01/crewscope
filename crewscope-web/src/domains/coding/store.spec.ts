import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import type { CodingGateway, RepositoryTransition } from './gateway'
import { createCodingStore } from './store'
import type {
  BuildProfileSummary,
  CodingAttemptSummary,
  CodingScope,
  CommandEvidenceSummary,
  CurrentCodingAttempt,
  EvidencePage,
  RepositoryBinding,
  RepositoryBindingInput,
  RepositoryCatalogItem,
  RepositoryCommandReceipt,
  RepositoryPreflight,
  TestEvidenceSummary,
} from './types'

const taskId = '00000000-0000-0000-0000-000000004201'
const executionId = '00000000-0000-0000-0000-000000004301'
const workspaceId = '00000000-0000-0000-0000-000000004401'
const workItemId = '00000000-0000-0000-0000-000000004501'
const bindingId = '00000000-0000-0000-0000-000000004101'
const platformScope: CodingScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  projectId: fixtureIds.projectCrewScope,
}
const securityScope: CodingScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamSecurity,
  projectId: fixtureIds.projectRuntime,
}

describe('CodingStore', () => {
  it('isolates a late Repository response after the full WorkProject Scope changes', async () => {
    const gateway = new FixtureCodingGateway()
    const first = deferred<RepositoryBinding[]>()
    const original = gateway.listRepositoryBindings.bind(gateway)
    gateway.listRepositoryBindings = vi.fn((scope, signal) => scope.teamId === fixtureIds.teamPlatform
      ? first.promise
      : original(scope, signal))
    const store = createCodingStore(gateway)

    const slow = store.loadRepositories(platformScope)
    await store.loadRepositories(securityScope)
    first.resolve([repository(platformScope, 'platform-repository')])
    await slow

    expect(store.state.repositories.value?.map(item => item.repositoryKey)).toEqual(['security-repository'])
    expect(store.state.repositoryDetails).toEqual({})
  })

  it('restores the Task/attempt/Workspace deep link and caches the selected attempt', async () => {
    const gateway = new FixtureCodingGateway()
    const attemptSpy = vi.spyOn(gateway, 'getAttempt')
    const store = createCodingStore(gateway)

    await store.synchronize(platformScope, { taskId, executionId, workspaceId })
    await store.loadAttempt(taskId, executionId)

    expect(store.state.routePhase).toBe('ready')
    expect(store.state.selectedExecutionId).toBe(executionId)
    expect(store.state.selectedWorkspaceId).toBe(workspaceId)
    expect(attemptSpy).toHaveBeenCalledTimes(1)

    await store.synchronize(platformScope, { taskId, executionId, workspaceId: crypto.randomUUID() })
    expect(store.state.routePhase).toBe('error')
    expect(store.state.routeErrorStatus).toBe(404)
    expect(store.state.selectedWorkspaceId).toBeNull()
  })

  it('forwards opaque evidence Cursors and de-duplicates page overlap', async () => {
    const gateway = new FixtureCodingGateway()
    const store = createCodingStore(gateway)
    store.activateScope(platformScope)

    await store.loadCommands(taskId, executionId)
    await store.loadCommands(taskId, executionId, true)
    await store.loadTestEvidence(taskId, executionId)
    await store.loadTestEvidence(taskId, executionId, true)
    const key = `${taskId}:${executionId}`

    expect(gateway.commandAfter).toEqual([undefined, 'command-cursor'])
    expect(gateway.testAfter).toEqual([undefined, 'test-cursor'])
    expect(store.state.commands[key]?.value?.items.map(item => item.id)).toEqual(['command-1', 'command-2'])
    expect(store.state.testEvidence[key]?.value?.items.map(item => item.id)).toEqual(['test-1', 'test-2'])
  })

  it('assembles the authorized Patch from contiguous byte pages and keeps only decoded content', async () => {
    const gateway = new FixtureCodingGateway()
    const source = new TextEncoder().encode(`${'x'.repeat(262_140)}中文`)
    gateway.readPatchPage = vi.fn(async (_scope, _taskId, _executionId, offset, limit) => {
      const bytes = source.slice(offset, Math.min(source.byteLength, offset + limit))
      return { bytes, offset, length: bytes.byteLength, totalSize: source.byteLength, etag: '"stable"', contentType: 'text/x-diff', filename: 'changes.patch' }
    })
    const store = createCodingStore(gateway)
    store.activateScope(platformScope)

    await store.loadPatch(taskId, executionId)

    const resource = store.state.patches[`${taskId}:${executionId}`]
    expect(resource?.phase).toBe('ready')
    expect(resource?.value?.content.endsWith('中文')).toBe(true)
    expect(resource?.value?.sizeBytes).toBe(source.byteLength)
    expect(gateway.readPatchPage).toHaveBeenCalledTimes(2)
  })

  it('fails the Patch closed when its bytes do not match the authoritative Artifact descriptor', async () => {
    const gateway = new FixtureCodingGateway()
    const source = new TextEncoder().encode('diff --git a/a.txt b/a.txt\n+changed\n')
    const selected = codingAttempt()
    selected.details!.diffManifest = {
      artifactId: 'diff-artifact', generation: 1, manifestHash: '1'.repeat(64), fileCount: 1,
      additions: 1, deletions: 0, baselineCommit: '2'.repeat(40), deliveryCommit: '3'.repeat(40),
      finalHash: '4'.repeat(64),
      patch: { artifactId: 'patch-artifact', kind: 'PATCH', contentType: 'text/x-diff;charset=utf-8', sizeBytes: source.byteLength, contentHash: '0'.repeat(64) },
      files: [], createdAt: '2026-08-20T01:00:00Z',
    }
    gateway.getAttempt = vi.fn(async () => selected)
    gateway.readPatchPage = vi.fn(async (_scope, _taskId, _executionId, offset) => ({
      bytes: source, offset, length: source.byteLength, totalSize: source.byteLength, etag: '"stable"', contentType: 'text/x-diff', filename: 'changes.patch',
    }))
    const store = createCodingStore(gateway)
    store.activateScope(platformScope)

    await store.loadAttempt(taskId, executionId)
    await store.loadPatch(taskId, executionId)

    expect(store.state.patches[`${taskId}:${executionId}`]).toMatchObject({
      phase: 'error', value: null, errorStatus: null,
    })
  })

  it('loads command logs and test reports through their evidence relationship and verifies the content', async () => {
    const gateway = new FixtureCodingGateway()
    const store = createCodingStore(gateway)
    store.activateScope(platformScope)
    await store.loadCommands(taskId, executionId)
    await store.loadTestEvidence(taskId, executionId)

    await store.loadCommandLog(taskId, executionId, 'command-1')
    await store.loadTestReport(taskId, executionId, 'test-1')

    expect(store.state.commandLogs[`${taskId}:${executionId}:command-1`]?.value).toMatchObject({
      content: 'build passed\n', complete: true, filename: 'command.log', loadedBytes: 13,
    })
    expect(store.state.testReports[`${taskId}:${executionId}:test-1`]?.value).toMatchObject({
      content: '{"tests":10}\n', complete: true, filename: 'report.json', loadedBytes: 13,
    })
  })

  it('retains a verified partial log when a later page is rate limited', async () => {
    const gateway = new FixtureCodingGateway()
    const source = new TextEncoder().encode('x'.repeat(70_000))
    const contentHash = await hash(source)
    gateway.listCommands = vi.fn(async () => ({ items: [{
      ...command('large-command'),
      commandLog: { artifactId: 'large-log', kind: 'COMMAND_LOG', contentType: 'text/plain', sizeBytes: source.byteLength, contentHash },
    }], nextCursor: null }))
    gateway.readCommandLogPage = vi.fn(async (_scope, _task, _execution, _evidence, offset, limit) => {
      if (offset > 0) throw apiError(429, 'coding_artifact_download_busy', '日志读取繁忙', true)
      const bytes = source.slice(offset, offset + limit)
      return { bytes, offset, length: bytes.byteLength, totalSize: source.byteLength, etag: '"stable"', contentType: 'text/plain', filename: 'large.log' }
    })
    const store = createCodingStore(gateway)
    store.activateScope(platformScope)
    await store.loadCommands(taskId, executionId)

    await store.loadCommandLog(taskId, executionId, 'large-command')
    await store.loadCommandLog(taskId, executionId, 'large-command', true)

    expect(store.state.commandLogs[`${taskId}:${executionId}:large-command`]).toMatchObject({
      phase: 'error', errorStatus: 429, errorMessage: '日志读取繁忙',
      value: { loadedBytes: 65_536, totalSize: 70_000, complete: false },
    })
  })

  it('invalidates Repository, CodingTarget and attempt caches at their ownership boundaries', async () => {
    const gateway = new FixtureCodingGateway()
    const store = createCodingStore(gateway)
    await store.loadRepositories(platformScope)
    await store.loadRepository(bindingId)
    await store.loadBuildProfiles(workItemId)
    await store.preflightTarget(workItemId, bindingId, 'main')
    await store.loadAttempt(taskId, executionId)
    await store.loadCommands(taskId, executionId)
    await store.loadTestEvidence(taskId, executionId)
    await store.loadPatch(taskId, executionId)

    store.invalidateRepositories()
    store.invalidateCodingTarget(workItemId)
    store.invalidateAttempt(taskId, executionId)

    expect(store.state.repositories.phase).toBe('idle')
    expect(store.state.repositoryDetails).toEqual({})
    expect(store.state.buildProfiles[workItemId]).toBeUndefined()
    expect(store.state.targetPreflights).toEqual({})
    expect(store.state.attempts).toEqual({})
    expect(store.state.commands).toEqual({})
    expect(store.state.testEvidence).toEqual({})
    expect(store.state.patches).toEqual({})
  })

  it('retains only the stable error envelope fields needed by the page boundary', async () => {
    const gateway = new FixtureCodingGateway()
    gateway.getCurrentAttempt = vi.fn(async () => {
      throw new CrewScopeApiError(403, {
        code: 'task_read_forbidden',
        message: '无权查看当前 Coding attempt',
        correlationId: 'safe-correlation',
        retryable: false,
        currentVersion: null,
        details: {},
      })
    })
    const store = createCodingStore(gateway)

    await store.synchronize(platformScope, { taskId })

    expect(store.state.routePhase).toBe('error')
    expect(store.state.routeErrorStatus).toBe(403)
    expect(store.state.routeErrorMessage).toBe('无权查看当前 Coding attempt')
    expect(JSON.stringify(store.state)).not.toContain('safe-correlation')
  })

  it('clears all Task facts and the active deep link when one Task is invalidated', async () => {
    const store = createCodingStore(new FixtureCodingGateway())
    await store.synchronize(platformScope, { taskId, executionId, workspaceId })
    await store.loadCommands(taskId, executionId)

    store.invalidateTask(taskId)

    expect(store.state.selectedTaskId).toBeNull()
    expect(store.state.currentAttempts[taskId]).toBeUndefined()
    expect(store.state.attemptHistories[taskId]).toBeUndefined()
    expect(store.state.attempts).toEqual({})
    expect(store.state.commands).toEqual({})
  })

  it('retries a failed Repository create with the original Idempotency-Key', async () => {
    const gateway = new FixtureCodingGateway()
    const keys: string[] = []
    gateway.createRepositoryBinding = vi.fn(async (_scope, _input, key) => {
      keys.push(key)
      if (keys.length === 1) throw apiError(503, 'repository_temporarily_unavailable', '仓库服务暂时不可用', true)
      return receipt()
    })
    const store = createCodingStore(gateway)
    await store.loadRepositories(platformScope)

    expect(await store.createRepository({ repositoryKey: 'crewscope-java', defaultBranch: 'main' })).toBe(false)
    expect(store.state.repositoryCommand.phase).toBe('error')
    expect(await store.retryRepositoryCommand()).toBe(true)

    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(store.state.repositoryCommand.phase).toBe('success')
  })

  it('drops a stale transition command and refreshes Repository facts after a version conflict', async () => {
    const gateway = new FixtureCodingGateway()
    const listSpy = vi.spyOn(gateway, 'listRepositoryBindings')
    gateway.transitionRepositoryBinding = vi.fn(async () => {
      throw apiError(409, 'optimistic_lock_conflict', 'RepositoryBinding version changed', false, 2)
    })
    const store = createCodingStore(gateway)
    await store.loadRepositories(platformScope)
    const binding = store.state.repositories.value![0]!

    expect(await store.transitionRepository(binding, 'disable')).toBe(false)

    expect(store.state.repositoryCommand.phase).toBe('conflict')
    expect(listSpy).toHaveBeenCalledTimes(2)
    expect(await store.retryRepositoryCommand()).toBe(false)
  })
})

class FixtureCodingGateway implements CodingGateway {
  commandAfter: Array<string | undefined> = []
  testAfter: Array<string | undefined> = []

  async listRepositoryCatalog(_scope: CodingScope, signal?: AbortSignal): Promise<RepositoryCatalogItem[]> {
    throwIfAborted(signal)
    return [{ repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' }]
  }

  async listRepositoryBindings(scope: CodingScope, signal?: AbortSignal): Promise<RepositoryBinding[]> {
    throwIfAborted(signal)
    return [repository(scope, scope.teamId === fixtureIds.teamSecurity ? 'security-repository' : 'crewscope-java')]
  }

  async getRepositoryBinding(scope: CodingScope, id: string, signal?: AbortSignal): Promise<RepositoryBinding> {
    throwIfAborted(signal)
    const value = repository(scope, 'crewscope-java')
    value.id = id
    return value
  }

  async createRepositoryBinding(
    _scope: CodingScope,
    _input: RepositoryBindingInput,
    _idempotencyKey: string,
  ): Promise<RepositoryCommandReceipt> {
    return receipt()
  }

  async preflightRepositoryDraft(
    _scope: CodingScope,
    input: RepositoryBindingInput,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight> {
    throwIfAborted(signal)
    return preflight(input.repositoryKey, input.defaultBranch)
  }

  async preflightRepositoryBinding(
    _scope: CodingScope,
    _bindingId: string,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight> {
    throwIfAborted(signal)
    return preflight('crewscope-java', 'main')
  }

  async transitionRepositoryBinding(
    _scope: CodingScope,
    _bindingId: string,
    _transition: RepositoryTransition,
    _expectedVersion: number,
    _idempotencyKey: string,
  ): Promise<RepositoryCommandReceipt> {
    return receipt()
  }

  async listBuildProfiles(
    _scope: CodingScope,
    _workItemId: string,
    signal?: AbortSignal,
  ): Promise<BuildProfileSummary[]> {
    throwIfAborted(signal)
    return [{
      key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64), buildTool: 'MAVEN',
      javaRelease: 17, commandKinds: ['TEST'],
    }]
  }

  async preflightCodingTarget(
    _scope: CodingScope,
    _workItemId: string,
    _bindingId: string,
    baselineRef: string,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight> {
    throwIfAborted(signal)
    return preflight('crewscope-java', baselineRef)
  }

  async getCurrentAttempt(
    _scope: CodingScope,
    requestedTaskId: string,
    signal?: AbortSignal,
  ): Promise<CurrentCodingAttempt> {
    throwIfAborted(signal)
    return { taskId: requestedTaskId, currentAttempt: codingAttempt() }
  }

  async listAttempts(
    _scope: CodingScope,
    _taskId: string,
    signal?: AbortSignal,
  ): Promise<CodingAttemptSummary[]> {
    throwIfAborted(signal)
    return [codingAttempt()]
  }

  async getAttempt(
    _scope: CodingScope,
    _taskId: string,
    _executionId: string,
    signal?: AbortSignal,
  ): Promise<CodingAttemptSummary> {
    throwIfAborted(signal)
    return codingAttempt()
  }

  async listCommands(
    _scope: CodingScope,
    _taskId: string,
    _executionId: string,
    after?: string,
    _limit?: number,
    signal?: AbortSignal,
  ): Promise<EvidencePage<CommandEvidenceSummary>> {
    throwIfAborted(signal)
    this.commandAfter.push(after)
    return after
      ? { items: [command('command-1'), command('command-2')], nextCursor: null }
      : { items: [command('command-1')], nextCursor: 'command-cursor' }
  }

  async listTestEvidence(
    _scope: CodingScope,
    _taskId: string,
    _executionId: string,
    after?: string,
    _limit?: number,
    signal?: AbortSignal,
  ): Promise<EvidencePage<TestEvidenceSummary>> {
    throwIfAborted(signal)
    this.testAfter.push(after)
    return after
      ? { items: [testEvidence('test-1'), testEvidence('test-2')], nextCursor: null }
      : { items: [testEvidence('test-1')], nextCursor: 'test-cursor' }
  }

  async readPatchPage(
    _scope: CodingScope,
    _taskId: string,
    _executionId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ) {
    throwIfAborted(signal)
    const source = new TextEncoder().encode('diff --git a/src/Main.java b/src/Main.java\n+changed\n')
    const bytes = source.slice(offset, Math.min(source.byteLength, offset + limit))
    return { bytes, offset, length: bytes.byteLength, totalSize: source.byteLength, etag: '"patch"', contentType: 'text/x-diff', filename: 'changes.patch' }
  }

  async readCommandLogPage(
    _scope: CodingScope, _taskId: string, _executionId: string, _evidenceId: string,
    offset: number, limit: number, signal?: AbortSignal,
  ) {
    throwIfAborted(signal)
    return textPage('build passed\n', offset, limit, 'text/plain;charset=utf-8', 'command.log')
  }

  async readTestReportPage(
    _scope: CodingScope, _taskId: string, _executionId: string, _evidenceId: string,
    offset: number, limit: number, signal?: AbortSignal,
  ) {
    throwIfAborted(signal)
    return textPage('{"tests":10}\n', offset, limit, 'application/json', 'report.json')
  }
}

function repository(scope: CodingScope, repositoryKey: string): RepositoryBinding {
  return {
    id: bindingId,
    organizationId: scope.organizationId,
    teamId: scope.teamId,
    workspaceId: scope.teamId === fixtureIds.teamSecurity ? fixtureIds.workspaceSecurity : fixtureIds.workspacePlatform,
    projectId: scope.projectId,
    kind: 'LOCAL_MANAGED',
    repositoryKey,
    defaultBranch: 'main',
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-08-20T01:00:00Z',
    createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-08-20T02:00:00Z',
    updatedByPrincipalId: fixtureIds.principal,
  }
}

function codingAttempt(): CodingAttemptSummary {
  return {
    executionId,
    attempt: 1,
    executionStatus: 'RUNNING',
    current: true,
    coding: true,
    details: {
      executionId,
      attempt: 1,
      workspace: {
        id: workspaceId,
        repositoryKey: 'crewscope-java',
        baselineCommit: '1'.repeat(40),
        managedBranch: 'crewscope/tasks/task/attempt-1',
        status: 'ACTIVE',
        recoveryGeneration: 0,
        completionReason: null,
        failureCode: null,
        fingerprint: '2'.repeat(64),
        version: 2,
        retainUntil: '2026-09-20T01:00:00Z',
        createdAt: '2026-08-20T01:00:00Z',
        updatedAt: '2026-08-20T01:01:00Z',
      },
      sandbox: null,
      diffManifest: null,
      codingResult: null,
      commandEvidenceCount: 2,
      testEvidenceCount: 2,
    },
  }
}

function command(id: string): CommandEvidenceSummary {
  return {
    id, sequence: id.endsWith('1') ? 1 : 2, commandKind: 'TEST', toolKey: 'coding.maven.test',
    timeoutSeconds: 60, startedAt: '2026-08-20T01:00:00Z', finishedAt: '2026-08-20T01:01:00Z',
    termination: 'EXITED', exitCode: 0, summary: 'passed', failureClassification: null,
    evidenceHash: '3'.repeat(64), commandLog: artifact('COMMAND_LOG'),
  }
}

function testEvidence(id: string): TestEvidenceSummary {
  return {
    id, sequence: id.endsWith('1') ? 1 : 2, diffGeneration: 1, diffManifestHash: '4'.repeat(64),
    total: 10, passed: 10, failed: 0, errors: 0, skipped: 0, summary: 'passed',
    failureClassification: null, evidenceHash: '5'.repeat(64), commandEvidenceIds: [],
    acceptance: [], testReport: artifact('TEST_REPORT'), createdAt: '2026-08-20T01:01:00Z',
  }
}

function artifact(kind: string) {
  const command = kind === 'COMMAND_LOG'
  return {
    artifactId: crypto.randomUUID(), kind, contentType: command ? 'text/plain' : 'application/json',
    sizeBytes: command ? 13 : 13,
    contentHash: command
      ? '1914ad82ebaddb8883a24c04b269b1c4ef9961f15c6148be8e1f728856fa788d'
      : 'eba1e22cf3c8d4b9571351db09dad19bbc724a3154f0cff51c0e1cf56847e434',
  }
}

function textPage(content: string, offset: number, limit: number, contentType: string, filename: string) {
  const source = new TextEncoder().encode(content)
  const bytes = source.slice(offset, Math.min(source.byteLength, offset + limit))
  return { bytes, offset, length: bytes.byteLength, totalSize: source.byteLength, etag: '"stable"', contentType, filename }
}

function preflight(repositoryKey: string, baselineRef: string): RepositoryPreflight {
  return { ready: true, repositoryKey, baselineRef, baselineCommit: '7'.repeat(40) }
}

function receipt(): RepositoryCommandReceipt {
  return {
    commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
    committedVersion: 1, correlationId: crypto.randomUUID(),
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
}

function apiError(status: number, code: string, message: string, retryable: boolean, currentVersion: number | null = null) {
  return new CrewScopeApiError(status, {
    code, message, retryable, currentVersion, correlationId: 'safe-correlation', details: {},
  })
}

async function hash(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', copy.buffer))]
    .map(value => value.toString(16).padStart(2, '0')).join('')
}
