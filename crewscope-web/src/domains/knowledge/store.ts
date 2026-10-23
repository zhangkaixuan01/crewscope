import { createCommandGateway } from '../../api/commandGateway'
import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { Etagged, SettingsScope } from '../settings/types'
import type { KnowledgeGateway } from './gateway'
import type {
  CreateKnowledgeEntryInput,
  DistillKnowledgeInput,
  KnowledgeCommandReceipt,
  KnowledgeDistillationReceipt,
  KnowledgeEntryFilter,
  KnowledgeEntrySummary,
  KnowledgeSwitchConfig,
  KnowledgeVersion,
  UpdateKnowledgeDraftInput,
} from './types'
import { isAbort, statusOf, presentError } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

export type KnowledgeResourcePhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface KnowledgeResource<T> {
  phase: KnowledgeResourcePhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

export interface KnowledgeEntryPageResource extends KnowledgeResource<KnowledgeEntrySummary[]> {
  /** Entry-key keyset cursor; null means the listing reached its end. */
  nextAfter: string | null
  loadingMore: boolean
}

export interface KnowledgeVersionPageResource extends KnowledgeResource<KnowledgeVersion[]> {
  /** Revision keyset cursor; null means the history reached its end. */
  nextAfter: number | null
  loadingMore: boolean
}

export interface KnowledgeCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: 'create' | 'save-draft' | 'publish' | 'retire' | 'delete' | 'rollback' | 'distill' | null
  resourceId: string | null
  receipt: KnowledgeCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  /** Server head version echoed by a 409 envelope — the conflict panel's only trustworthy source. */
  currentVersion: number | null
  retryable: boolean
}

export interface KnowledgeStoreState {
  entries: KnowledgeEntryPageResource
  entryDetails: Record<string, KnowledgeResource<Etagged<KnowledgeEntrySummary>>>
  versionHistory: Record<string, KnowledgeVersionPageResource>
  versionDetails: Record<string, KnowledgeResource<Etagged<KnowledgeVersion>>>
  effectiveVersions: Record<string, KnowledgeResource<Etagged<KnowledgeVersion>>>
  /** M13b-A03 deployment switches; null value = unknown (load pending or failed) — never "off". */
  config: KnowledgeResource<KnowledgeSwitchConfig>
  command: KnowledgeCommandState
}

export interface KnowledgeStore {
  state: Readonly<KnowledgeStoreState>
  activateScope(scope: SettingsScope): void
  loadEntries(filter: KnowledgeEntryFilter, more?: boolean, force?: boolean): Promise<void>
  loadEntry(entryId: string, force?: boolean): Promise<void>
  loadVersions(entryId: string, more?: boolean, force?: boolean): Promise<void>
  loadVersion(entryId: string, revision: number, force?: boolean): Promise<void>
  loadEffectiveVersion(entryId: string, force?: boolean): Promise<void>
  /** M13b-A03 switch read (R04 banner + PENDING hint); idempotent within a scope. */
  loadConfig(force?: boolean): Promise<void>
  createEntry(input: CreateKnowledgeEntryInput, idempotencyKey: string): Promise<boolean>
  saveDraft(entryId: string, input: UpdateKnowledgeDraftInput, idempotencyKey: string): Promise<boolean>
  publishEntry(entryId: string, idempotencyKey: string): Promise<boolean>
  retireEntry(entryId: string, idempotencyKey: string): Promise<boolean>
  /** M13b-A05: rolls the effective pointer back to `toRevision` by appending a new revision. */
  rollbackEntry(entryId: string, toRevision: number, idempotencyKey: string): Promise<boolean>
  deleteEntry(entryId: string, idempotencyKey: string): Promise<boolean>
  distill(input: DistillKnowledgeInput, idempotencyKey: string): Promise<boolean>
  /** Distillation receipts ride the shared command slot; panels down-cast by `operation === 'distill'`. */
  distillationReceipt(): KnowledgeDistillationReceipt | null
  clearCommand(): void
  reset(): void
}

export const KNOWLEDGE_STORE: InjectionKey<KnowledgeStore> = Symbol('crewscope-knowledge-store')

const PAGE_SIZE = 50

/** Team-scoped knowledge cache with generation checks in addition to AbortSignal cancellation. */
export function createKnowledgeStore(gateway: KnowledgeGateway): KnowledgeStore {
  const commandIntents = createCommandGateway(gateway, {
    createEntry: 2, saveDraft: 4, publishEntry: 3, retireEntry: 3, deleteEntry: 3, distill: 2,
  })
  gateway = commandIntents.gateway
  const state = reactive<KnowledgeStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  // Scope switches advance the epoch (voiding captures) and change the coordinate (voiding
  // stragglers that miss the abort) — the generation + scopeKey pair this replaces.
  const requests = createRequestScope(() => activeScopeKey ?? '')

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    requests.invalidate()
    replaceState(initialState())
    // R04: the switch facts are scope-level reads — fire them with activation so the banner
    // and the PENDING hint never depend on a separate page-side watch ordering.
    void loadConfig()
  }

  async function loadEntries(filter: KnowledgeEntryFilter, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (more && (state.entries.nextAfter === null || state.entries.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(state.entries.phase)) return
    const after = more ? state.entries.nextAfter : null
    const request = requests.begin('entries')
    if (more) state.entries.loadingMore = true
    else {
      state.entries.phase = 'loading'
      state.entries.errorMessage = null
      state.entries.errorStatus = null
      if (force) state.entries.value = null
    }
    try {
      const page = await gateway.listEntries(scope, filter, after, PAGE_SIZE, request.signal)
      if (!request.isCurrent()) return
      state.entries.value = more
        ? mergeEntries(state.entries.value ?? [], page.items)
        : page.items
      state.entries.nextAfter = page.nextAfter
      state.entries.phase = state.entries.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(state.entries, error, '暂时无法加载知识条目')
    } finally {
      if (request.isCurrent()) state.entries.loadingMore = false
      request.finish()
    }
  }

  async function loadEntry(entryId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadResource(
      `entry:${entryId}`,
      state.entryDetails,
      entryId,
      force,
      signal => gateway.getEntry(scope, entryId, signal),
      value => assertHeadVersion(value.value, value.etag),
      '暂时无法加载条目详情',
    )
  }

  async function loadVersions(entryId: string, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (!state.versionHistory[entryId]) state.versionHistory[entryId] = versionPageResource()
    const resource = state.versionHistory[entryId]!
    if (more && (resource.nextAfter === null || resource.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(resource.phase)) return
    const after = more ? resource.nextAfter : null
    const request = requests.begin(`versions:${entryId}`)
    if (more) resource.loadingMore = true
    else {
      resource.phase = 'loading'
      resource.errorMessage = null
      resource.errorStatus = null
      if (force) resource.value = null
    }
    try {
      const page = await gateway.listVersions(scope, entryId, after, PAGE_SIZE, request.signal)
      if (!request.isCurrent()) return
      for (const version of page.items) {
        if (version.entryId !== entryId || version.revision < 1) {
          throw new Error('Knowledge version history is outside the requested entry')
        }
      }
      resource.value = more ? mergeVersions(resource.value ?? [], page.items) : page.items
      resource.nextAfter = page.nextAfter
      resource.phase = resource.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(resource, error, '暂时无法加载版本历史')
    } finally {
      if (request.isCurrent()) resource.loadingMore = false
      request.finish()
    }
  }

  async function loadVersion(entryId: string, revision: number, force = false): Promise<void> {
    const scope = requireScope()
    const key = `${entryId}:${revision}`
    await loadResource(
      `version:${key}`,
      state.versionDetails,
      key,
      force,
      signal => gateway.getVersion(scope, entryId, revision, signal),
      value => {
        if (value.value.entryId !== entryId || value.value.revision !== revision) {
          throw new Error('Knowledge version does not match its request')
        }
      },
      '暂时无法加载版本内容',
    )
  }

  async function loadConfig(force = false): Promise<void> {
    const scope = requireScope()
    if (!force && state.config.phase === 'ready') return
    const request = requests.begin('config')
    state.config.phase = 'loading'
    state.config.errorMessage = null
    state.config.errorStatus = null
    try {
      const value = await gateway.switchConfig(scope, request.signal)
      if (!request.isCurrent()) return
      state.config.value = value
      state.config.phase = 'ready'
    } catch (error) {
      // Unknown switches read as "enabled": the banner stays hidden rather than crying wolf.
      if (!isAbort(error) && request.isCurrent()) setError(state.config, error, '暂时无法读取知识能力开关')
    } finally {
      request.finish()
    }
  }

  async function loadEffectiveVersion(entryId: string, force = false): Promise<void> {
    const scope = requireScope()
    const existing = state.effectiveVersions[entryId]
    if (!force && existing?.phase === 'ready') return
    if (!existing) state.effectiveVersions[entryId] = resourceState()
    const resource = state.effectiveVersions[entryId]!
    const request = requests.begin(`effective:${entryId}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    try {
      const value = await gateway.getEffectiveVersion(scope, entryId, request.signal)
      if (!request.isCurrent()) return
      // Null is a resolved answer — "no effective version" (DRAFT/RETIRED/DELETED) — not an error.
      resource.value = value
      resource.phase = value === null ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(resource, error, '暂时无法加载生效版本')
    } finally {
      request.finish()
    }
  }

  async function createEntry(input: CreateKnowledgeEntryInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand(
      'create',
      null,
      () => gateway.createEntry(scope, input, idempotencyKey),
      () => { state.entries = entryPageResource() },
    )
  }

  async function saveDraft(entryId: string, input: UpdateKnowledgeDraftInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(entryId)
    if (etag === null) return false
    return runCommand(
      'save-draft',
      entryId,
      () => gateway.saveDraft(scope, entryId, input, etag, idempotencyKey),
      () => {
        delete state.entryDetails[entryId]
        state.entries = entryPageResource()
      },
    )
  }

  async function publishEntry(entryId: string, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(entryId)
    if (etag === null) return false
    return runCommand(
      'publish',
      entryId,
      () => gateway.publishEntry(scope, entryId, etag, idempotencyKey),
      () => invalidatePointerFacts(entryId),
    )
  }

  async function retireEntry(entryId: string, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(entryId)
    if (etag === null) return false
    return runCommand(
      'retire',
      entryId,
      () => gateway.retireEntry(scope, entryId, etag, idempotencyKey),
      () => invalidatePointerFacts(entryId),
    )
  }

  /** M13b-F04（R28/A05）：回滚=追加内容与目标修订相同的新不可变修订并移动生效指针。
   * 目标即当前生效修订时命令必然 422 knowledge_version_unchanged——已加载的 head 上直接
   * 拒并说清楚；未加载或竞态窗口交给服务端兜底（通用错误链呈现服务端事实）。 */
  async function rollbackEntry(entryId: string, toRevision: number, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const head = state.entryDetails[entryId]?.value?.value ?? null
    if (head && head.effectiveRevision === toRevision) {
      if (state.command.phase === 'pending') return false
      state.command = {
        phase: 'error', operation: 'rollback', resourceId: entryId, receipt: null,
        errorMessage: `r${toRevision} 已是当前生效版本，无需回滚。`,
        errorStatus: 422, currentVersion: null, retryable: false,
      }
      return false
    }
    const etag = await requireHeadEtag(entryId)
    if (etag === null) return false
    return runCommand(
      'rollback',
      entryId,
      () => gateway.rollbackEntry(scope, entryId, toRevision, etag, idempotencyKey),
      () => {
        invalidatePointerFacts(entryId)
        deleteVersionDetails(entryId)
      },
    )
  }

  async function deleteEntry(entryId: string, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(entryId)
    if (etag === null) return false
    return runCommand(
      'delete',
      entryId,
      () => gateway.deleteEntry(scope, entryId, etag, idempotencyKey),
      () => {
        invalidatePointerFacts(entryId)
        deleteVersionDetails(entryId)
      },
    )
  }

  async function distill(input: DistillKnowledgeInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand(
      'distill',
      null,
      () => gateway.distill(scope, input, idempotencyKey),
      () => { state.entries = entryPageResource() },
    )
  }

  function distillationReceipt(): KnowledgeDistillationReceipt | null {
    return state.command.operation === 'distill' && state.command.receipt
      ? state.command.receipt as KnowledgeDistillationReceipt
      : null
  }

  /** Ensures the head detail is ready and returns its ETag, or null when the continuation went stale. */
  async function requireHeadEtag(entryId: string): Promise<string | null> {
    const owner = requests.capture()
    const previous = state.command
    if (state.entryDetails[entryId]?.phase !== 'ready') await loadEntry(entryId)
    if (!owner.isCurrent() || state.command !== previous) return null
    return state.entryDetails[entryId]?.value?.etag ?? null
  }

  async function runCommand(
    operation: NonNullable<KnowledgeCommandState['operation']>,
    resourceId: string | null,
    action: () => Promise<KnowledgeCommandReceipt>,
    onSuccess?: () => void,
  ): Promise<boolean> {
    const commandOwner = requests.capture()
    if (state.command.phase === 'pending') return false
    state.command = {
      phase: 'pending', operation, resourceId, receipt: null,
      errorMessage: null, errorStatus: null, currentVersion: null, retryable: false,
    }
    const pending = state.command
    try {
      const receipt = await action()
      if (!commandOwner.isCurrent() || state.command !== pending) return false
      onSuccess?.()
      state.command.phase = 'success'
      state.command.receipt = receipt
      return true
    } catch (error) {
      if (!commandOwner.isCurrent() || state.command !== pending) return false
      state.command.phase = conflict(error) ? 'conflict' : 'error'
      state.command.errorMessage = presentError(error, '知识库命令执行失败')
      state.command.errorStatus = statusOf(error)
      state.command.currentVersion = error instanceof CrewScopeApiError
        ? error.envelope.currentVersion ?? null
        : null
      state.command.retryable = error instanceof CrewScopeApiError && error.envelope.retryable
      return false
    }
  }

  async function loadResource<T>(
    requestKey: string,
    target: Record<string, KnowledgeResource<T>>,
    resourceKey: string,
    force: boolean,
    load: (signal: AbortSignal) => Promise<T>,
    validate: ((value: T) => void) | undefined,
    fallback: string,
  ): Promise<void> {
    const existing = target[resourceKey]
    if (!force && existing?.phase === 'ready') return
    if (!existing) target[resourceKey] = resourceState<T>()
    // Vue wraps Record entries on read; mutating the original raw object after await would not trigger rendering.
    const resource = target[resourceKey]!
    const request = requests.begin(requestKey)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    try {
      const value = await load(request.signal)
      if (!request.isCurrent()) return
      validate?.(value)
      resource.value = value
      resource.phase = 'ready'
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(resource, error, fallback)
    } finally {
      request.finish()
    }
  }

  function invalidatePointerFacts(entryId: string): void {
    delete state.entryDetails[entryId]
    delete state.versionHistory[entryId]
    delete state.effectiveVersions[entryId]
    state.entries = entryPageResource()
  }

  function deleteVersionDetails(entryId: string): void {
    for (const key of Object.keys(state.versionDetails)) {
      if (key.startsWith(`${entryId}:`)) delete state.versionDetails[key]
    }
  }

  function clearCommand(): void {
    state.command = commandState()
  }

  function reset(): void {
    commandIntents.clear()
    activeScope = null
    activeScopeKey = null
    requests.invalidate()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Knowledge Store Scope is not active')
    return { ...activeScope }
  }

  function replaceState(next: KnowledgeStoreState): void {
    state.entries = next.entries
    state.entryDetails = next.entryDetails
    state.versionHistory = next.versionHistory
    state.versionDetails = next.versionDetails
    state.effectiveVersions = next.effectiveVersions
    state.config = next.config
    state.command = next.command
  }

  return {
    state: readonly(state) as Readonly<KnowledgeStoreState>,
    activateScope,
    loadEntries,
    loadEntry,
    loadVersions,
    loadVersion,
    loadEffectiveVersion,
    loadConfig,
    createEntry,
    saveDraft,
    publishEntry,
    retireEntry,
    rollbackEntry,
    deleteEntry,
    distill,
    distillationReceipt,
    clearCommand,
    reset,
  }
}

export function installKnowledgeStore(app: App, gateway: KnowledgeGateway): KnowledgeStore {
  const store = createKnowledgeStore(gateway)
  app.provide(KNOWLEDGE_STORE, store)
  return store
}

export function useKnowledgeStore(): KnowledgeStore {
  const store = inject(KNOWLEDGE_STORE)
  if (!store) throw new Error('CrewScope Knowledge Store is not installed')
  return store
}

function initialState(): KnowledgeStoreState {
  return {
    entries: entryPageResource(),
    entryDetails: {},
    versionHistory: {},
    versionDetails: {},
    effectiveVersions: {},
    config: resourceState<KnowledgeSwitchConfig>(),
    command: commandState(),
  }
}

function resourceState<T>(): KnowledgeResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null }
}

function entryPageResource(): KnowledgeEntryPageResource {
  return { ...resourceState<KnowledgeEntrySummary[]>(), nextAfter: null, loadingMore: false }
}

function versionPageResource(): KnowledgeVersionPageResource {
  return { ...resourceState<KnowledgeVersion[]>(), nextAfter: null, loadingMore: false }
}

function commandState(): KnowledgeCommandState {
  return {
    phase: 'idle', operation: null, resourceId: null, receipt: null,
    errorMessage: null, errorStatus: null, currentVersion: null, retryable: false,
  }
}

function assertHeadVersion(value: KnowledgeEntrySummary, etag: string): void {
  if (etag !== `"${value.version}"`) throw new Error('Knowledge Entry ETag does not match its head version')
}

function mergeEntries(existing: KnowledgeEntrySummary[], incoming: KnowledgeEntrySummary[]): KnowledgeEntrySummary[] {
  const known = new Set(existing.map(item => item.entryKey))
  return [...existing, ...incoming.filter(item => !known.has(item.entryKey))]
}

function mergeVersions(existing: KnowledgeVersion[], incoming: KnowledgeVersion[]): KnowledgeVersion[] {
  const known = new Set(existing.map(item => item.revision))
  return [...existing, ...incoming.filter(item => !known.has(item.revision))]
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function setError(resource: KnowledgeResource<unknown>, error: unknown, fallback: string): void {
  resource.phase = 'error'
  resource.errorMessage = presentError(error, fallback)
  resource.errorStatus = statusOf(error)
}



function conflict(error: unknown): boolean {
  return error instanceof CrewScopeApiError && (error.status === 409 || error.status === 412)
}
