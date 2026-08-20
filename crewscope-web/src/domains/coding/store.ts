import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { CodingGateway } from './gateway'
import type {
  BuildProfileSummary,
  ArtifactBytePage,
  ArtifactSummary,
  ArtifactTextDocument,
  CodingAttemptSummary,
  CodingPatchDocument,
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

export type CodingPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface CodingResource<T> {
  phase: CodingPhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

export type RepositoryCommandPhase = 'idle' | 'pending' | 'success' | 'error' | 'conflict'

export interface RepositoryCommandState {
  phase: RepositoryCommandPhase
  operation: 'create' | 'activate' | 'disable' | null
  bindingId: string | null
  errorMessage: string | null
  errorStatus: number | null
  receipt: RepositoryCommandReceipt | null
  retryable: boolean
}

interface CodingState {
  repositoryCatalog: CodingResource<RepositoryCatalogItem[]>
  repositories: CodingResource<RepositoryBinding[]>
  repositoryDetails: Record<string, CodingResource<RepositoryBinding>>
  repositoryPreflights: Record<string, CodingResource<RepositoryPreflight>>
  repositoryCommand: RepositoryCommandState
  buildProfiles: Record<string, CodingResource<BuildProfileSummary[]>>
  targetPreflights: Record<string, CodingResource<RepositoryPreflight>>
  currentAttempts: Record<string, CodingResource<CurrentCodingAttempt>>
  attemptHistories: Record<string, CodingResource<CodingAttemptSummary[]>>
  attempts: Record<string, CodingResource<CodingAttemptSummary>>
  commands: Record<string, CodingResource<EvidencePage<CommandEvidenceSummary>>>
  testEvidence: Record<string, CodingResource<EvidencePage<TestEvidenceSummary>>>
  commandLogs: Record<string, CodingResource<ArtifactTextDocument>>
  testReports: Record<string, CodingResource<ArtifactTextDocument>>
  patches: Record<string, CodingResource<CodingPatchDocument>>
  selectedTaskId: string | null
  selectedExecutionId: string | null
  selectedWorkspaceId: string | null
  routePhase: CodingPhase
  routeErrorMessage: string | null
  routeErrorStatus: number | null
}

export interface CodingStore {
  state: Readonly<CodingState>
  activateScope(scope: CodingScope): void
  synchronize(
    scope: CodingScope,
    route: { taskId?: string | null, executionId?: string | null, workspaceId?: string | null },
  ): Promise<void>
  loadRepositories(scope: CodingScope, force?: boolean): Promise<void>
  loadRepositoryCatalog(scope: CodingScope, force?: boolean): Promise<void>
  loadRepository(bindingId: string, force?: boolean): Promise<void>
  preflightRepositoryDraft(input: RepositoryBindingInput): Promise<RepositoryPreflight | null>
  preflightRepository(bindingId: string): Promise<RepositoryPreflight | null>
  createRepository(input: RepositoryBindingInput): Promise<boolean>
  transitionRepository(binding: RepositoryBinding, transition: 'activate' | 'disable'): Promise<boolean>
  retryRepositoryCommand(): Promise<boolean>
  clearRepositoryCommand(): void
  loadBuildProfiles(workItemId: string, force?: boolean): Promise<void>
  preflightTarget(workItemId: string, bindingId: string, baselineRef: string): Promise<RepositoryPreflight | null>
  loadCurrentAttempt(taskId: string, force?: boolean): Promise<void>
  loadAttemptHistory(taskId: string, force?: boolean): Promise<void>
  loadAttempt(taskId: string, executionId: string, force?: boolean): Promise<void>
  loadCommands(taskId: string, executionId: string, more?: boolean): Promise<void>
  loadTestEvidence(taskId: string, executionId: string, more?: boolean): Promise<void>
  loadCommandLog(taskId: string, executionId: string, commandEvidenceId: string, more?: boolean): Promise<void>
  loadTestReport(taskId: string, executionId: string, testEvidenceId: string, more?: boolean): Promise<void>
  loadPatch(taskId: string, executionId: string, force?: boolean): Promise<void>
  invalidateRepositories(): void
  invalidateCodingTarget(workItemId: string): void
  invalidateTask(taskId: string): void
  invalidateAttempt(taskId: string, executionId: string): void
  clearSelection(): void
  reset(): void
}

export const CODING_STORE: InjectionKey<CodingStore> = Symbol('crewscope-coding-store')

export function createCodingStore(gateway: CodingGateway): CodingStore {
  const state = reactive<CodingState>({
    repositoryCatalog: idleResource<RepositoryCatalogItem[]>(),
    repositories: idleResource<RepositoryBinding[]>(),
    repositoryDetails: {},
    repositoryPreflights: {},
    repositoryCommand: idleRepositoryCommand(),
    buildProfiles: {},
    targetPreflights: {},
    currentAttempts: {},
    attemptHistories: {},
    attempts: {},
    commands: {},
    testEvidence: {},
    commandLogs: {},
    testReports: {},
    patches: {},
    selectedTaskId: null,
    selectedExecutionId: null,
    selectedWorkspaceId: null,
    routePhase: 'idle',
    routeErrorMessage: null,
    routeErrorStatus: null,
  })

  let activeScope: CodingScope | null = null
  let activeScopeKey: string | null = null
  let synchronizationVersion = 0
  const requestVersions = new Map<string, number>()
  const requestAborts = new Map<string, AbortController>()
  let lastRepositoryCommand: {
    scopeKey: string
    key: string
    operation: 'create' | 'activate' | 'disable'
    bindingId: string | null
    run: (key: string) => Promise<RepositoryCommandReceipt>
  } | null = null
  let repositoryCommandVersion = 0

  function activateScope(scope: CodingScope): void {
    const nextKey = scopeKey(scope)
    if (activeScopeKey === nextKey) return
    cancelAll()
    activeScope = { ...scope }
    activeScopeKey = nextKey
    clearState()
  }

  async function synchronize(
    scope: CodingScope,
    route: { taskId?: string | null, executionId?: string | null, workspaceId?: string | null },
  ): Promise<void> {
    activateScope(scope)
    synchronizationVersion += 1
    const version = synchronizationVersion
    const expectedScope = scopeKey(scope)
    if (!route.taskId) {
      clearSelection()
      return
    }
    state.selectedTaskId = route.taskId
    state.selectedExecutionId = route.executionId ?? null
    state.selectedWorkspaceId = route.workspaceId ?? null
    state.routePhase = 'loading'
    state.routeErrorMessage = null
    state.routeErrorStatus = null

    await Promise.all([
      loadCurrentAttempt(route.taskId),
      loadAttemptHistory(route.taskId),
    ])
    if (!synchronizationCurrent(version, expectedScope, route.taskId)) return

    const current = state.currentAttempts[route.taskId]
    const history = state.attemptHistories[route.taskId]
    if (current?.phase === 'error' || history?.phase === 'error') {
      state.routePhase = 'error'
      state.routeErrorMessage = current?.errorMessage ?? history?.errorMessage ?? 'Coding attempt 暂时不可用'
      state.routeErrorStatus = current?.errorStatus ?? history?.errorStatus ?? null
      return
    }

    const executionId = route.executionId ?? current?.value?.currentAttempt?.executionId ?? null
    state.selectedExecutionId = executionId
    if (!executionId) {
      state.routePhase = 'empty'
      return
    }
    await loadAttempt(route.taskId, executionId)
    if (!synchronizationCurrent(version, expectedScope, route.taskId)) return
    const attempt = state.attempts[attemptKey(route.taskId, executionId)]
    if (attempt?.phase === 'error') {
      state.routePhase = 'error'
      state.routeErrorMessage = attempt.errorMessage
      state.routeErrorStatus = attempt.errorStatus
      return
    }
    const workspaceId = attempt?.value?.details?.workspace.id ?? null
    if (route.workspaceId && route.workspaceId !== workspaceId) {
      state.routePhase = 'error'
      state.routeErrorMessage = 'Workspace 不属于当前 Task attempt'
      state.routeErrorStatus = 404
      state.selectedWorkspaceId = null
      return
    }
    state.selectedWorkspaceId = workspaceId
    state.routePhase = attempt?.value?.coding ? 'ready' : 'empty'
  }

  async function loadRepositories(scope: CodingScope, force = false): Promise<void> {
    activateScope(scope)
    await loadSingle(
      'repositories',
      () => state.repositories,
      value => { state.repositories = value },
      force,
      signal => gateway.listRepositoryBindings(scope, signal),
      '暂时无法加载 RepositoryBinding',
    )
  }

  async function loadRepositoryCatalog(scope: CodingScope, force = false): Promise<void> {
    activateScope(scope)
    await loadSingle(
      'repository-catalog',
      () => state.repositoryCatalog,
      value => { state.repositoryCatalog = value },
      force,
      signal => gateway.listRepositoryCatalog(scope, signal),
      '暂时无法加载受管 Repository Catalog',
    )
  }

  async function loadRepository(bindingId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadCached(
      `repository:${bindingId}`,
      state.repositoryDetails,
      bindingId,
      force,
      signal => gateway.getRepositoryBinding(scope, bindingId, signal),
      '暂时无法加载 RepositoryBinding 详情',
    )
  }

  async function preflightRepositoryDraft(input: RepositoryBindingInput): Promise<RepositoryPreflight | null> {
    const scope = requireScope()
    const key = `draft:${input.repositoryKey}:${input.defaultBranch}`
    await loadCached(
      `repository-preflight:${key}`,
      state.repositoryPreflights,
      key,
      true,
      signal => gateway.preflightRepositoryDraft(scope, input, signal),
      'Repository Preflight 未通过',
    )
    return state.repositoryPreflights[key]?.value ?? null
  }

  async function preflightRepository(bindingId: string): Promise<RepositoryPreflight | null> {
    const scope = requireScope()
    await loadCached(
      `repository-preflight:${bindingId}`,
      state.repositoryPreflights,
      bindingId,
      true,
      signal => gateway.preflightRepositoryBinding(scope, bindingId, signal),
      'Repository Preflight 未通过',
    )
    return state.repositoryPreflights[bindingId]?.value ?? null
  }

  async function createRepository(input: RepositoryBindingInput): Promise<boolean> {
    const scope = requireScope()
    return runRepositoryCommand({
      scopeKey: requireScopeKey(),
      key: crypto.randomUUID(),
      operation: 'create',
      bindingId: null,
      run: key => gateway.createRepositoryBinding(scope, input, key),
    })
  }

  async function transitionRepository(
    binding: RepositoryBinding,
    transition: 'activate' | 'disable',
  ): Promise<boolean> {
    const scope = requireScope()
    return runRepositoryCommand({
      scopeKey: requireScopeKey(),
      key: crypto.randomUUID(),
      operation: transition,
      bindingId: binding.id,
      run: key => gateway.transitionRepositoryBinding(scope, binding.id, transition, binding.version, key),
    })
  }

  async function retryRepositoryCommand(): Promise<boolean> {
    if (!lastRepositoryCommand || lastRepositoryCommand.scopeKey !== activeScopeKey) return false
    return runRepositoryCommand(lastRepositoryCommand)
  }

  async function runRepositoryCommand(command: NonNullable<typeof lastRepositoryCommand>): Promise<boolean> {
    const commandVersion = ++repositoryCommandVersion
    lastRepositoryCommand = command
    state.repositoryCommand = {
      phase: 'pending', operation: command.operation, bindingId: command.bindingId,
      errorMessage: null, errorStatus: null, receipt: null, retryable: false,
    }
    try {
      const receipt = await command.run(command.key)
      if (command.scopeKey !== activeScopeKey || commandVersion !== repositoryCommandVersion) return false
      state.repositoryCommand = {
        phase: 'success', operation: command.operation, bindingId: command.bindingId,
        errorMessage: null, errorStatus: null, receipt, retryable: false,
      }
      lastRepositoryCommand = null
      await refreshRepositoriesAfterCommand(command.bindingId)
      return true
    } catch (error) {
      if (command.scopeKey !== activeScopeKey || commandVersion !== repositoryCommandVersion) return false
      const status = statusOf(error)
      const conflict = status === 409 || status === 412
      const retryable = error instanceof CrewScopeApiError && error.envelope.retryable
      state.repositoryCommand = {
        phase: conflict ? 'conflict' : 'error', operation: command.operation,
        bindingId: command.bindingId,
        errorMessage: conflict ? 'RepositoryBinding 已更新，页面已刷新为最新版本' : presentError(error, 'Repository 操作失败'),
        errorStatus: status,
        receipt: null,
        retryable,
      }
      if (conflict) {
        // A stale If-Match command cannot be retried; load the authoritative version first.
        lastRepositoryCommand = null
        await refreshRepositoriesAfterCommand(command.bindingId)
      } else if (!retryable) {
        lastRepositoryCommand = null
      }
      return false
    }
  }

  async function refreshRepositoriesAfterCommand(bindingId: string | null): Promise<void> {
    const scope = requireScope()
    invalidateRequest('repositories')
    state.repositories = idleResource()
    if (bindingId) {
      invalidateRequest(`repository:${bindingId}`)
      delete state.repositoryDetails[bindingId]
    }
    await Promise.all([
      loadRepositories(scope, true),
      bindingId ? loadRepository(bindingId, true) : Promise.resolve(),
    ])
  }

  function clearRepositoryCommand(): void {
    repositoryCommandVersion += 1
    state.repositoryCommand = idleRepositoryCommand()
    lastRepositoryCommand = null
  }

  async function loadBuildProfiles(workItemId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadCached(
      `build-profiles:${workItemId}`,
      state.buildProfiles,
      workItemId,
      force,
      signal => gateway.listBuildProfiles(scope, workItemId, signal),
      '暂时无法加载 BuildProfile',
    )
  }

  async function preflightTarget(
    workItemId: string,
    bindingId: string,
    baselineRef: string,
  ): Promise<RepositoryPreflight | null> {
    const scope = requireScope()
    const cacheKey = `${workItemId}:${bindingId}:${baselineRef}`
    await loadCached(
      `target-preflight:${cacheKey}`,
      state.targetPreflights,
      cacheKey,
      true,
      signal => gateway.preflightCodingTarget(scope, workItemId, bindingId, baselineRef, signal),
      '暂时无法验证 Repository Ref',
    )
    return state.targetPreflights[cacheKey]?.value ?? null
  }

  async function loadCurrentAttempt(taskId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadCached(
      `current-attempt:${taskId}`,
      state.currentAttempts,
      taskId,
      force,
      signal => gateway.getCurrentAttempt(scope, taskId, signal),
      '暂时无法加载当前 Coding attempt',
    )
  }

  async function loadAttemptHistory(taskId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadCached(
      `attempt-history:${taskId}`,
      state.attemptHistories,
      taskId,
      force,
      signal => gateway.listAttempts(scope, taskId, signal),
      '暂时无法加载 Coding attempt 历史',
    )
  }

  async function loadAttempt(taskId: string, executionId: string, force = false): Promise<void> {
    const scope = requireScope()
    const key = attemptKey(taskId, executionId)
    await loadCached(
      `attempt:${key}`,
      state.attempts,
      key,
      force,
      signal => gateway.getAttempt(scope, taskId, executionId, signal),
      '暂时无法加载 Coding attempt 详情',
    )
  }

  async function loadCommands(taskId: string, executionId: string, more = false): Promise<void> {
    const scope = requireScope()
    const key = attemptKey(taskId, executionId)
    const after = more ? state.commands[key]?.value?.nextCursor ?? undefined : undefined
    if (more && !after) return
    await loadCached(
      `commands:${key}`,
      state.commands,
      key,
      false,
      signal => gateway.listCommands(scope, taskId, executionId, after, 50, signal),
      '暂时无法加载 CommandEvidence',
      more ? mergeEvidencePages : undefined,
    )
  }

  async function loadTestEvidence(taskId: string, executionId: string, more = false): Promise<void> {
    const scope = requireScope()
    const key = attemptKey(taskId, executionId)
    const after = more ? state.testEvidence[key]?.value?.nextCursor ?? undefined : undefined
    if (more && !after) return
    await loadCached(
      `test-evidence:${key}`,
      state.testEvidence,
      key,
      false,
      signal => gateway.listTestEvidence(scope, taskId, executionId, after, 50, signal),
      '暂时无法加载 TestEvidence',
      more ? mergeEvidencePages : undefined,
    )
  }

  async function loadCommandLog(
    taskId: string,
    executionId: string,
    commandEvidenceId: string,
    more = false,
  ): Promise<void> {
    const evidence = state.commands[attemptKey(taskId, executionId)]?.value?.items
      .find(item => item.id === commandEvidenceId)
    await loadArtifactText(
      'command-log',
      state.commandLogs,
      artifactKey(taskId, executionId, commandEvidenceId),
      evidence?.commandLog ?? null,
      more,
      (scope, offset, limit, signal) => gateway.readCommandLogPage(
        scope, taskId, executionId, commandEvidenceId, offset, limit, signal,
      ),
      '暂时无法读取 CommandEvidence 日志',
    )
  }

  async function loadTestReport(
    taskId: string,
    executionId: string,
    testEvidenceId: string,
    more = false,
  ): Promise<void> {
    const evidence = state.testEvidence[attemptKey(taskId, executionId)]?.value?.items
      .find(item => item.id === testEvidenceId)
    await loadArtifactText(
      'test-report',
      state.testReports,
      artifactKey(taskId, executionId, testEvidenceId),
      evidence?.testReport ?? null,
      more,
      (scope, offset, limit, signal) => gateway.readTestReportPage(
        scope, taskId, executionId, testEvidenceId, offset, limit, signal,
      ),
      '暂时无法读取 TestEvidence 报告',
    )
  }

  async function loadArtifactText(
    kind: string,
    cache: Record<string, CodingResource<ArtifactTextDocument>>,
    key: string,
    descriptor: ArtifactSummary | null,
    more: boolean,
    request: (scope: CodingScope, offset: number, limit: number, signal: AbortSignal) => Promise<ArtifactBytePage>,
    fallback: string,
  ): Promise<void> {
    const scope = requireScope()
    const existing = cache[key]
    if (!descriptor) {
      cache[key] = { phase: 'error', value: null, errorMessage: 'Evidence 没有可读取的 Artifact', errorStatus: null }
      return
    }
    if (!more && existing && ['ready', 'empty'].includes(existing.phase)) return
    if (more && existing?.value?.complete) return
    const current = more ? existing?.value ?? null : null
    const offset = current?.loadedBytes ?? 0
    const requestKey = `${kind}:${key}`
    const version = nextRequest(requestKey)
    const controller = replaceController(requestKey)
    const expectedScope = activeScopeKey
    cache[key] = { phase: 'loading', value: current, errorMessage: null, errorStatus: null }
    try {
      const page = await request(scope, offset, 64 * 1024, controller.signal)
      if (!requestCurrent(requestKey, version, expectedScope, controller)) return
      const document = await appendArtifactPage(current, page, descriptor)
      if (!requestCurrent(requestKey, version, expectedScope, controller)) return
      cache[key] = { phase: document.loadedBytes === 0 ? 'empty' : 'ready', value: document, errorMessage: null, errorStatus: null }
    } catch (error) {
      if (isAbort(error) || !requestCurrent(requestKey, version, expectedScope, controller)) return
      cache[key] = {
        phase: 'error', value: current, errorMessage: presentError(error, fallback), errorStatus: statusOf(error),
      }
    } finally {
      releaseController(requestKey, controller)
    }
  }

  async function appendArtifactPage(
    current: ArtifactTextDocument | null,
    page: ArtifactBytePage,
    descriptor: ArtifactSummary,
  ): Promise<ArtifactTextDocument> {
    const offset = current?.loadedBytes ?? 0
    if (page.offset !== offset || page.length !== page.bytes.byteLength || page.totalSize < offset + page.length) {
      throw new TypeError('Coding Artifact pages are not contiguous')
    }
    if (page.totalSize !== descriptor.sizeBytes || page.totalSize > 8 * 1024 * 1024) {
      throw new TypeError('Coding Artifact size is outside the browser reading budget')
    }
    if (current && (page.totalSize !== current.totalSize || page.etag !== current.etag
      || page.contentType !== current.contentType || page.filename !== current.filename)) {
      throw new TypeError('Coding Artifact metadata changed while reading')
    }
    if (!page.contentType.startsWith(descriptor.contentType.split(';')[0]!.toLowerCase())) {
      throw new TypeError('Coding Artifact content type does not match its descriptor')
    }
    if (page.length === 0 && offset < page.totalSize) throw new TypeError('Coding Artifact page made no progress')
    const bytes = new Uint8Array(offset + page.length)
    if (current) bytes.set(current.bytes)
    bytes.set(page.bytes, offset)
    const complete = bytes.byteLength === page.totalSize
    if (complete && await sha256(bytes) !== descriptor.contentHash.toLowerCase()) {
      throw new TypeError('Coding Artifact hash does not match its descriptor')
    }
    // Streaming mode retains an incomplete trailing code point without displaying a replacement glyph.
    const content = new TextDecoder('utf-8', { fatal: true }).decode(bytes, { stream: !complete })
    return {
      bytes, content, loadedBytes: bytes.byteLength, totalSize: page.totalSize, complete,
      etag: page.etag, contentType: page.contentType, filename: page.filename,
    }
  }

  async function loadPatch(taskId: string, executionId: string, force = false): Promise<void> {
    const scope = requireScope()
    const key = attemptKey(taskId, executionId)
    const descriptor = state.attempts[key]?.value?.details?.diffManifest?.patch ?? null
    await loadCached(
      `patch:${key}`,
      state.patches,
      key,
      force,
      signal => readCompletePatch(scope, taskId, executionId, signal, descriptor?.sizeBytes, descriptor?.contentHash),
      '暂时无法读取最终 Patch',
    )
  }

  async function readCompletePatch(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    signal: AbortSignal,
    expectedSize?: number,
    expectedHash?: string,
  ): Promise<CodingPatchDocument> {
    const pageSize = 256 * 1024
    const clientLimit = 16 * 1024 * 1024
    const chunks: Uint8Array[] = []
    let offset = 0
    let totalSize: number | null = null
    let etag: string | null = null
    let etagInitialized = false
    while (totalSize === null || offset < totalSize) {
      const page = await gateway.readPatchPage(scope, taskId, executionId, offset, pageSize, signal)
      if (page.offset !== offset || page.length !== page.bytes.byteLength || page.totalSize < offset + page.length) {
        throw new TypeError('Coding Patch pages are not contiguous')
      }
      if (totalSize !== null && page.totalSize !== totalSize) throw new TypeError('Coding Patch size changed while reading')
      if (etagInitialized && page.etag !== etag) throw new TypeError('Coding Patch ETag changed while reading')
      totalSize = page.totalSize
      etag = page.etag
      etagInitialized = true
      if (totalSize > clientLimit) throw new TypeError('Coding Patch exceeds the browser reading budget')
      if (expectedSize !== undefined && totalSize !== expectedSize) throw new TypeError('Coding Patch size does not match its Artifact descriptor')
      if (page.length === 0 && offset < totalSize) throw new TypeError('Coding Patch page made no progress')
      chunks.push(page.bytes)
      offset += page.length
    }
    const bytes = new Uint8Array(totalSize ?? 0)
    let position = 0
    for (const chunk of chunks) {
      bytes.set(chunk, position)
      position += chunk.byteLength
    }
    if (expectedHash !== undefined && await sha256(bytes) !== expectedHash.toLowerCase()) {
      throw new TypeError('Coding Patch hash does not match its Artifact descriptor')
    }
    return { content: new TextDecoder('utf-8', { fatal: true }).decode(bytes), sizeBytes: bytes.byteLength, etag }
  }

  async function loadSingle<T>(
    requestKey: string,
    read: () => CodingResource<T>,
    write: (resource: CodingResource<T>) => void,
    force: boolean,
    request: (signal: AbortSignal) => Promise<T>,
    fallback: string,
  ): Promise<void> {
    const existing = read()
    if (!force && ['ready', 'empty'].includes(existing.phase)) return
    const version = nextRequest(requestKey)
    const controller = replaceController(requestKey)
    const expectedScope = activeScopeKey
    write({ phase: 'loading', value: existing.value, errorMessage: null, errorStatus: null })
    try {
      const value = await request(controller.signal)
      if (!requestCurrent(requestKey, version, expectedScope, controller)) return
      write({ phase: isEmpty(value) ? 'empty' : 'ready', value, errorMessage: null, errorStatus: null })
    } catch (error) {
      if (isAbort(error) || !requestCurrent(requestKey, version, expectedScope, controller)) return
      write({
        phase: 'error',
        value: existing.value,
        errorMessage: presentError(error, fallback),
        errorStatus: statusOf(error),
      })
    } finally {
      releaseController(requestKey, controller)
    }
  }

  async function loadCached<T>(
    requestKey: string,
    cache: Record<string, CodingResource<T>>,
    cacheKey: string,
    force: boolean,
    request: (signal: AbortSignal) => Promise<T>,
    fallback: string,
    merge?: (current: T, incoming: T) => T,
  ): Promise<void> {
    const existing = cache[cacheKey]
    if (!force && !merge && existing && ['ready', 'empty'].includes(existing.phase)) return
    const version = nextRequest(requestKey)
    const controller = replaceController(requestKey)
    const expectedScope = activeScopeKey
    cache[cacheKey] = {
      phase: 'loading',
      value: existing?.value ?? null,
      errorMessage: null,
      errorStatus: null,
    }
    try {
      const incoming = await request(controller.signal)
      if (!requestCurrent(requestKey, version, expectedScope, controller)) return
      const value = merge && existing?.value ? merge(existing.value, incoming) : incoming
      cache[cacheKey] = {
        phase: isEmpty(value) ? 'empty' : 'ready',
        value,
        errorMessage: null,
        errorStatus: null,
      }
    } catch (error) {
      if (isAbort(error) || !requestCurrent(requestKey, version, expectedScope, controller)) return
      cache[cacheKey] = {
        phase: 'error',
        value: existing?.value ?? null,
        errorMessage: presentError(error, fallback),
        errorStatus: statusOf(error),
      }
    } finally {
      releaseController(requestKey, controller)
    }
  }

  function invalidateRepositories(): void {
    invalidateRequest('repositories')
    invalidateRequestPrefix('repository:')
    state.repositories = idleResource()
    state.repositoryDetails = {}
    state.repositoryPreflights = {}
  }

  function invalidateCodingTarget(workItemId: string): void {
    invalidateRequest(`build-profiles:${workItemId}`)
    invalidateRequestPrefix(`target-preflight:${workItemId}:`)
    delete state.buildProfiles[workItemId]
    for (const key of Object.keys(state.targetPreflights)) {
      if (key.startsWith(`${workItemId}:`)) delete state.targetPreflights[key]
    }
  }

  function invalidateTask(taskId: string): void {
    invalidateRequest(`current-attempt:${taskId}`)
    invalidateRequest(`attempt-history:${taskId}`)
    invalidateRequestPrefix(`attempt:${taskId}:`)
    invalidateRequestPrefix(`commands:${taskId}:`)
    invalidateRequestPrefix(`test-evidence:${taskId}:`)
    invalidateRequestPrefix(`command-log:${taskId}:`)
    invalidateRequestPrefix(`test-report:${taskId}:`)
    invalidateRequestPrefix(`patch:${taskId}:`)
    delete state.currentAttempts[taskId]
    delete state.attemptHistories[taskId]
    deleteByPrefix(state.attempts, `${taskId}:`)
    deleteByPrefix(state.commands, `${taskId}:`)
    deleteByPrefix(state.testEvidence, `${taskId}:`)
    deleteByPrefix(state.commandLogs, `${taskId}:`)
    deleteByPrefix(state.testReports, `${taskId}:`)
    deleteByPrefix(state.patches, `${taskId}:`)
    if (state.selectedTaskId === taskId) clearSelection()
  }

  function invalidateAttempt(taskId: string, executionId: string): void {
    const key = attemptKey(taskId, executionId)
    for (const prefix of ['attempt:', 'commands:', 'test-evidence:', 'patch:']) invalidateRequest(`${prefix}${key}`)
    invalidateRequestPrefix(`command-log:${key}:`)
    invalidateRequestPrefix(`test-report:${key}:`)
    delete state.attempts[key]
    delete state.commands[key]
    delete state.testEvidence[key]
    deleteByPrefix(state.commandLogs, `${key}:`)
    deleteByPrefix(state.testReports, `${key}:`)
    delete state.patches[key]
  }

  function clearSelection(): void {
    synchronizationVersion += 1
    state.selectedTaskId = null
    state.selectedExecutionId = null
    state.selectedWorkspaceId = null
    state.routePhase = 'idle'
    state.routeErrorMessage = null
    state.routeErrorStatus = null
  }

  function reset(): void {
    cancelAll()
    activeScope = null
    activeScopeKey = null
    clearState()
  }

  function clearState(): void {
    state.repositoryCatalog = idleResource()
    state.repositories = idleResource()
    state.repositoryDetails = {}
    state.repositoryPreflights = {}
    state.repositoryCommand = idleRepositoryCommand()
    lastRepositoryCommand = null
    state.buildProfiles = {}
    state.targetPreflights = {}
    state.currentAttempts = {}
    state.attemptHistories = {}
    state.attempts = {}
    state.commands = {}
    state.testEvidence = {}
    state.commandLogs = {}
    state.testReports = {}
    state.patches = {}
    state.selectedTaskId = null
    state.selectedExecutionId = null
    state.selectedWorkspaceId = null
    state.routePhase = 'idle'
    state.routeErrorMessage = null
    state.routeErrorStatus = null
  }

  function cancelAll(): void {
    synchronizationVersion += 1
    for (const controller of requestAborts.values()) controller.abort()
    requestAborts.clear()
    requestVersions.clear()
  }

  function nextRequest(key: string): number {
    const version = (requestVersions.get(key) ?? 0) + 1
    requestVersions.set(key, version)
    return version
  }

  function replaceController(key: string): AbortController {
    requestAborts.get(key)?.abort()
    const controller = new AbortController()
    requestAborts.set(key, controller)
    return controller
  }

  function releaseController(key: string, controller: AbortController): void {
    if (requestAborts.get(key) === controller) requestAborts.delete(key)
  }

  function requestCurrent(
    key: string,
    version: number,
    expectedScope: string | null,
    controller: AbortController,
  ): boolean {
    return requestVersions.get(key) === version
      && activeScopeKey === expectedScope
      && requestAborts.get(key) === controller
  }

  function invalidateRequest(key: string): void {
    requestVersions.set(key, (requestVersions.get(key) ?? 0) + 1)
    requestAborts.get(key)?.abort()
    requestAborts.delete(key)
  }

  function invalidateRequestPrefix(prefix: string): void {
    for (const key of new Set([...requestVersions.keys(), ...requestAborts.keys()])) {
      if (key.startsWith(prefix)) invalidateRequest(key)
    }
  }

  function requireScope(): CodingScope {
    if (!activeScope) throw new Error('Coding Scope is not selected')
    return { ...activeScope }
  }

  function requireScopeKey(): string {
    if (!activeScopeKey) throw new Error('Coding Scope is not selected')
    return activeScopeKey
  }

  function synchronizationCurrent(version: number, expectedScope: string, taskId: string): boolean {
    return version === synchronizationVersion
      && activeScopeKey === expectedScope
      && state.selectedTaskId === taskId
  }

  return {
    state: readonly(state) as Readonly<CodingState>,
    activateScope,
    synchronize,
    loadRepositories,
    loadRepositoryCatalog,
    loadRepository,
    preflightRepositoryDraft,
    preflightRepository,
    createRepository,
    transitionRepository,
    retryRepositoryCommand,
    clearRepositoryCommand,
    loadBuildProfiles,
    preflightTarget,
    loadCurrentAttempt,
    loadAttemptHistory,
    loadAttempt,
    loadCommands,
    loadTestEvidence,
    loadCommandLog,
    loadTestReport,
    loadPatch,
    invalidateRepositories,
    invalidateCodingTarget,
    invalidateTask,
    invalidateAttempt,
    clearSelection,
    reset,
  }
}

export function installCodingStore(app: App, gateway: CodingGateway): CodingStore {
  const store = createCodingStore(gateway)
  app.provide(CODING_STORE, store)
  return store
}

export function useCodingStore(): CodingStore {
  const store = inject(CODING_STORE)
  if (!store) throw new Error('CrewScope Coding Store is not installed')
  return store
}

function idleResource<T>(): CodingResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null }
}

function idleRepositoryCommand(): RepositoryCommandState {
  return {
    phase: 'idle', operation: null, bindingId: null,
    errorMessage: null, errorStatus: null, receipt: null, retryable: false,
  }
}

function scopeKey(scope: CodingScope): string {
  return `${scope.organizationId}:${scope.teamId}:${scope.projectId}`
}

function attemptKey(taskId: string, executionId: string): string {
  return `${taskId}:${executionId}`
}

function artifactKey(taskId: string, executionId: string, evidenceId: string): string {
  return `${attemptKey(taskId, executionId)}:${evidenceId}`
}

function mergeEvidencePages<T extends { id: string }>(
  current: EvidencePage<T>,
  incoming: EvidencePage<T>,
): EvidencePage<T> {
  const known = new Set(current.items.map(item => item.id))
  return {
    items: [...current.items, ...incoming.items.filter(item => !known.has(item.id))],
    nextCursor: incoming.nextCursor,
  }
}

function deleteByPrefix<T>(cache: Record<string, T>, prefix: string): void {
  for (const key of Object.keys(cache)) if (key.startsWith(prefix)) delete cache[key]
}

function isEmpty(value: unknown): boolean {
  if (Array.isArray(value)) return value.length === 0
  if (value && typeof value === 'object' && 'items' in value) {
    return Array.isArray((value as { items: unknown }).items)
      && (value as { items: unknown[] }).items.length === 0
  }
  return false
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

async function sha256(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', copy.buffer))
  return [...digest].map(value => value.toString(16).padStart(2, '0')).join('')
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}
