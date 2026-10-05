import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import { knowledgeIndexJobStatusLabels } from './labels'
import type { KnowledgeGateway } from './gateway'
import type {
  KnowledgeIndexJob,
  KnowledgeIndexJobFilter,
  RepositoryBuildInput,
} from './types'

export type KnowledgeIndexPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface KnowledgeIndexJobPageResource {
  phase: KnowledgeIndexPhase
  value: KnowledgeIndexJob[] | null
  errorMessage: string | null
  errorStatus: number | null
  /** Job-id keyset cursor; null means the listing reached its end. */
  nextAfter: string | null
  loadingMore: boolean
}

/**
 * The I01c commands are structurally idempotent (contract §2: no Idempotency-Key, no If-Match),
 * so this slot carries a plain success message instead of a receipt envelope — `enqueued: 0`
 * is a skip the wording must never render as a failure.
 */
export interface KnowledgeIndexCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error'
  operation: 'rebuild' | 'repository-build' | 'cancel' | null
  jobId: string | null
  message: string | null
  errorMessage: string | null
  errorCode: string | null
  errorStatus: number | null
}

export interface KnowledgeIndexStoreState {
  jobs: KnowledgeIndexJobPageResource
  command: KnowledgeIndexCommandState
}

export interface KnowledgeIndexStore {
  state: Readonly<KnowledgeIndexStoreState>
  activateScope(scope: SettingsScope): void
  loadJobs(filter: KnowledgeIndexJobFilter, more?: boolean, force?: boolean): Promise<void>
  rebuild(): Promise<boolean>
  enqueueRepositoryBuild(input: RepositoryBuildInput): Promise<boolean>
  cancelJob(jobId: string): Promise<boolean>
  clearCommand(): void
  reset(): void
}

export const KNOWLEDGE_INDEX_STORE: InjectionKey<KnowledgeIndexStore> = Symbol('crewscope-knowledge-index-store')

/** The store consumes only the I01c control plane; the entry surface stays with `store.ts`. */
export type KnowledgeIndexGateway = Pick<
  KnowledgeGateway,
  'listJobs' | 'cancelJob' | 'rebuild' | 'enqueueRepositoryBuild'
>

const PAGE_SIZE = 50

interface IndexRequest {
  key: string
  version: number
  controller: AbortController
  scopeKey: string | null
}

/** Team-scoped I01c job listing with generation checks in addition to AbortSignal cancellation. */
export function createKnowledgeIndexStore(gateway: KnowledgeIndexGateway): KnowledgeIndexStore {
  const state = reactive<KnowledgeIndexStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, IndexRequest>()

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  async function loadJobs(filter: KnowledgeIndexJobFilter, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (more && (state.jobs.nextAfter === null || state.jobs.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(state.jobs.phase)) return
    const after = more ? state.jobs.nextAfter : null
    const request = beginRequest('jobs')
    if (more) state.jobs.loadingMore = true
    else {
      state.jobs.phase = 'loading'
      state.jobs.errorMessage = null
      state.jobs.errorStatus = null
      if (force) state.jobs.value = null
    }
    try {
      const page = await gateway.listJobs(scope, filter, after, PAGE_SIZE, request.controller.signal)
      if (!isCurrent(request)) return
      // The listing is (createdAt, id) ascending — append-only merging keeps that order intact.
      state.jobs.value = more ? mergeJobs(state.jobs.value ?? [], page.items) : page.items
      state.jobs.nextAfter = page.nextAfter
      state.jobs.phase = state.jobs.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(state.jobs, error, '暂时无法加载索引作业')
    } finally {
      if (isCurrent(request)) state.jobs.loadingMore = false
      finishRequest(request)
    }
  }

  async function rebuild(): Promise<boolean> {
    const scope = requireScope()
    return runCommand('rebuild', null, async () => {
      const accepted = await gateway.rebuild(scope)
      return accepted.enqueued > 0
        ? `已入队 ${accepted.enqueued} 个知识重建作业`
        : '未新建作业：索引开关未开启，或没有可重建的生效版本'
    }, () => { state.jobs = jobPageResource() })
  }

  async function enqueueRepositoryBuild(input: RepositoryBuildInput): Promise<boolean> {
    const scope = requireScope()
    return runCommand('repository-build', null, async () => {
      const accepted = await gateway.enqueueRepositoryBuild(scope, input)
      if (accepted.enqueued === 0 || !accepted.job) {
        return '未创建作业：索引开关未开启（跳过，不是错误）'
      }
      return `已入队仓库索引作业（${accepted.job.id.slice(0, 8)}，状态：${knowledgeIndexJobStatusLabels[accepted.job.status]}）`
    }, () => { state.jobs = jobPageResource() })
  }

  async function cancelJob(jobId: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand('cancel', jobId, async () => {
      const snapshot = await gateway.cancelJob(scope, jobId)
      replaceJob(snapshot)
      return '已取消排队中的作业'
    }, undefined)
  }

  async function runCommand(
    operation: NonNullable<KnowledgeIndexCommandState['operation']>,
    jobId: string | null,
    action: () => Promise<string>,
    onSuccess?: () => void,
  ): Promise<boolean> {
    const commandGeneration = generation
    if (state.command.phase === 'pending') return false
    state.command = {
      phase: 'pending', operation, jobId, message: null, errorMessage: null, errorCode: null, errorStatus: null,
    }
    const pending = state.command
    try {
      const message = await action()
      if (commandGeneration !== generation || state.command !== pending) return false
      onSuccess?.()
      state.command.phase = 'success'
      state.command.message = message
      return true
    } catch (error) {
      if (commandGeneration !== generation || state.command !== pending) return false
      state.command.phase = 'error'
      state.command.errorMessage = presentError(error, '索引作业命令执行失败')
      state.command.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
      state.command.errorStatus = statusOf(error)
      if (operation === 'cancel' && error instanceof CrewScopeApiError) {
        // Contract §6: a 409 carries details.status — the row's live state, worth the banner.
        const live = error.envelope.details && typeof error.envelope.details.status === 'string'
          ? knowledgeIndexJobStatusLabels[error.envelope.details.status as KnowledgeIndexJob['status']] ?? error.envelope.details.status
          : null
        if (live) state.command.errorMessage = `${state.command.errorMessage}（当前状态：${live}）`
      }
      return false
    }
  }

  /** Swaps one row with the cancel response snapshot; the listing stays otherwise untouched. */
  function replaceJob(snapshot: KnowledgeIndexJob): void {
    const rows = state.jobs.value
    if (!rows) return
    const index = rows.findIndex(job => job.id === snapshot.id)
    if (index < 0) return
    state.jobs.value = [...rows.slice(0, index), snapshot, ...rows.slice(index + 1)]
  }

  function clearCommand(): void {
    state.command = commandState()
  }

  function reset(): void {
    activeScope = null
    activeScopeKey = null
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Knowledge Index Store Scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): IndexRequest {
    requests.get(key)?.controller.abort()
    const request: IndexRequest = {
      key,
      version: generation,
      controller: new AbortController(),
      scopeKey: activeScopeKey,
    }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: IndexRequest): boolean {
    return generation === request.version
      && activeScopeKey === request.scopeKey
      && requests.get(request.key) === request
  }

  function finishRequest(request: IndexRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    for (const request of requests.values()) request.controller.abort()
    requests.clear()
  }

  function replaceState(next: KnowledgeIndexStoreState): void {
    state.jobs = next.jobs
    state.command = next.command
  }

  return {
    state: readonly(state) as Readonly<KnowledgeIndexStoreState>,
    activateScope,
    loadJobs,
    rebuild,
    enqueueRepositoryBuild,
    cancelJob,
    clearCommand,
    reset,
  }
}

export function installKnowledgeIndexStore(app: App, gateway: KnowledgeIndexGateway): KnowledgeIndexStore {
  const store = createKnowledgeIndexStore(gateway)
  app.provide(KNOWLEDGE_INDEX_STORE, store)
  return store
}

export function useKnowledgeIndexStore(): KnowledgeIndexStore {
  const store = inject(KNOWLEDGE_INDEX_STORE)
  if (!store) throw new Error('CrewScope Knowledge Index Store is not installed')
  return store
}

function initialState(): KnowledgeIndexStoreState {
  return { jobs: jobPageResource(), command: commandState() }
}

function jobPageResource(): KnowledgeIndexJobPageResource {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null, nextAfter: null, loadingMore: false }
}

function commandState(): KnowledgeIndexCommandState {
  return { phase: 'idle', operation: null, jobId: null, message: null, errorMessage: null, errorCode: null, errorStatus: null }
}

function mergeJobs(existing: KnowledgeIndexJob[], incoming: KnowledgeIndexJob[]): KnowledgeIndexJob[] {
  const known = new Set(existing.map(job => job.id))
  return [...existing, ...incoming.filter(job => !known.has(job.id))]
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function setError(resource: KnowledgeIndexJobPageResource, error: unknown, fallback: string): void {
  resource.phase = 'error'
  resource.errorMessage = presentError(error, fallback)
  resource.errorStatus = statusOf(error)
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
