import { createCommandGateway } from '../../api/commandGateway'
import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { Etagged, SettingsScope } from '../settings/types'
import type { SkillGateway } from './gateway'
import type {
  CreateSkillInput,
  DistillSkillInput,
  SkillCommandReceipt,
  SkillDistillationReceipt,
  SkillFilter,
  SkillSummary,
  SkillVersion,
  UpdateSkillDraftInput,
} from './types'

export type SkillResourcePhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface SkillResource<T> {
  phase: SkillResourcePhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

export interface SkillPageResource extends SkillResource<SkillSummary[]> {
  /** Skill-key keyset cursor; null means the listing reached its end. */
  nextAfter: string | null
  loadingMore: boolean
}

export interface SkillVersionPageResource extends SkillResource<SkillVersion[]> {
  /** Revision keyset cursor; null means the history reached its end. */
  nextAfter: number | null
  loadingMore: boolean
}

/**
 * Aggregated PUBLISHED skill-key view for the agent configuration picker (F02 D4): the API has
 * no single endpoint, so this folds keyset pages client-side. `keys` is `[]` on error — a
 * degraded picker must not block configuration saving; the server 422 remains the backstop.
 */
export interface SkillKeyCatalog {
  phase: 'idle' | 'loading' | 'ready' | 'error'
  keys: string[]
  errorMessage: string | null
}

export interface SkillCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: 'create' | 'save-draft' | 'publish' | 'disable' | 'rollback' | 'distill' | null
  resourceId: string | null
  receipt: SkillCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  /** Server head version echoed by a 409 envelope — the conflict panel's only trustworthy source. */
  currentVersion: number | null
  retryable: boolean
}

export interface SkillStoreState {
  skills: SkillPageResource
  skillDetails: Record<string, SkillResource<Etagged<SkillSummary>>>
  versionHistory: Record<string, SkillVersionPageResource>
  versionDetails: Record<string, SkillResource<Etagged<SkillVersion>>>
  effectiveVersions: Record<string, SkillResource<Etagged<SkillVersion>>>
  publishedKeys: SkillKeyCatalog
  command: SkillCommandState
}

export interface SkillStore {
  state: Readonly<SkillStoreState>
  activateScope(scope: SettingsScope): void
  loadSkills(filter: SkillFilter, more?: boolean, force?: boolean): Promise<void>
  loadSkill(skillId: string, force?: boolean): Promise<void>
  loadVersions(skillId: string, more?: boolean, force?: boolean): Promise<void>
  loadVersion(skillId: string, revision: number, force?: boolean): Promise<void>
  loadEffectiveVersion(skillId: string, force?: boolean): Promise<void>
  /** Aggregates PUBLISHED keys across keyset pages for the configuration picker. */
  loadPublishedKeys(force?: boolean): Promise<void>
  createSkill(input: CreateSkillInput, idempotencyKey: string): Promise<boolean>
  saveDraft(skillId: string, input: UpdateSkillDraftInput, idempotencyKey: string): Promise<boolean>
  publishSkill(skillId: string, idempotencyKey: string): Promise<boolean>
  disableSkill(skillId: string, reason: string | null, idempotencyKey: string): Promise<boolean>
  rollbackSkill(skillId: string, toRevision: number, idempotencyKey: string): Promise<boolean>
  distill(input: DistillSkillInput, idempotencyKey: string): Promise<boolean>
  /** Distillation receipts ride the shared command slot; panels down-cast by `operation === 'distill'`. */
  distillationReceipt(): SkillDistillationReceipt | null
  clearCommand(): void
  reset(): void
}

export const SKILL_STORE: InjectionKey<SkillStore> = Symbol('crewscope-skill-store')

const PAGE_SIZE = 50
/** Aggregation caps for the PUBLISHED-key fold (D4): 10 pages × 50 keys, hard-stopped at 500. */
const PUBLISHED_KEYS_MAX_PAGES = 10
const PUBLISHED_KEYS_MAX_COUNT = 500

interface SkillRequest {
  key: string
  version: number
  controller: AbortController
  scopeKey: string | null
}

/** Team-scoped skill cache with generation checks in addition to AbortSignal cancellation. */
export function createSkillStore(gateway: SkillGateway): SkillStore {
  const commandIntents = createCommandGateway(gateway, {
    createSkill: 2, saveDraft: 4, publishSkill: 3, disableSkill: 4, rollbackSkill: 4, distill: 2,
  })
  gateway = commandIntents.gateway
  const state = reactive<SkillStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, SkillRequest>()

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  async function loadSkills(filter: SkillFilter, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (more && (state.skills.nextAfter === null || state.skills.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(state.skills.phase)) return
    const after = more ? state.skills.nextAfter : null
    const request = beginRequest('skills')
    if (more) state.skills.loadingMore = true
    else {
      state.skills.phase = 'loading'
      state.skills.errorMessage = null
      state.skills.errorStatus = null
      if (force) state.skills.value = null
    }
    try {
      const page = await gateway.listSkills(scope, filter, after, PAGE_SIZE, request.controller.signal)
      if (!isCurrent(request)) return
      state.skills.value = more
        ? mergeSkills(state.skills.value ?? [], page.items)
        : page.items
      state.skills.nextAfter = page.nextAfter
      state.skills.phase = state.skills.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(state.skills, error, '暂时无法加载 Skill 目录')
    } finally {
      if (isCurrent(request)) state.skills.loadingMore = false
      finishRequest(request)
    }
  }

  async function loadSkill(skillId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadResource(
      `skill:${skillId}`,
      state.skillDetails,
      skillId,
      force,
      signal => gateway.getSkill(scope, skillId, signal),
      value => assertHeadVersion(value.value, value.etag),
      '暂时无法加载 Skill 详情',
    )
  }

  async function loadVersions(skillId: string, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (!state.versionHistory[skillId]) state.versionHistory[skillId] = versionPageResource()
    const resource = state.versionHistory[skillId]!
    if (more && (resource.nextAfter === null || resource.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(resource.phase)) return
    const after = more ? resource.nextAfter : null
    const request = beginRequest(`versions:${skillId}`)
    if (more) resource.loadingMore = true
    else {
      resource.phase = 'loading'
      resource.errorMessage = null
      resource.errorStatus = null
      if (force) resource.value = null
    }
    try {
      const page = await gateway.listVersions(scope, skillId, after, PAGE_SIZE, request.controller.signal)
      if (!isCurrent(request)) return
      for (const version of page.items) {
        if (version.skillId !== skillId || version.revision < 1) {
          throw new Error('Skill version history is outside the requested skill')
        }
      }
      resource.value = more ? mergeVersions(resource.value ?? [], page.items) : page.items
      resource.nextAfter = page.nextAfter
      resource.phase = resource.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载版本历史')
    } finally {
      if (isCurrent(request)) resource.loadingMore = false
      finishRequest(request)
    }
  }

  async function loadVersion(skillId: string, revision: number, force = false): Promise<void> {
    const scope = requireScope()
    const key = `${skillId}:${revision}`
    await loadResource(
      `version:${key}`,
      state.versionDetails,
      key,
      force,
      signal => gateway.getVersion(scope, skillId, revision, signal),
      value => {
        if (value.value.skillId !== skillId || value.value.revision !== revision) {
          throw new Error('Skill version does not match its request')
        }
      },
      '暂时无法加载版本内容',
    )
  }

  async function loadEffectiveVersion(skillId: string, force = false): Promise<void> {
    const scope = requireScope()
    const existing = state.effectiveVersions[skillId]
    if (!force && existing?.phase === 'ready') return
    if (!existing) state.effectiveVersions[skillId] = resourceState()
    const resource = state.effectiveVersions[skillId]!
    const request = beginRequest(`effective:${skillId}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    try {
      const value = await gateway.getEffectiveVersion(scope, skillId, request.controller.signal)
      if (!isCurrent(request)) return
      // Null is a resolved answer — "no effective revision" (DRAFT/DISABLED head) — not an error.
      resource.value = value
      resource.phase = value === null ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载生效版本')
    } finally {
      finishRequest(request)
    }
  }

  async function loadPublishedKeys(force = false): Promise<void> {
    const scope = requireScope()
    if (!force && state.publishedKeys.phase === 'ready') return
    const request = beginRequest('published-keys')
    state.publishedKeys.phase = 'loading'
    state.publishedKeys.errorMessage = null
    try {
      const keys: string[] = []
      let after: string | null = null
      for (let page = 0; page < PUBLISHED_KEYS_MAX_PAGES && keys.length < PUBLISHED_KEYS_MAX_COUNT; page += 1) {
        const result = await gateway.listSkills(scope, { status: 'PUBLISHED' }, after, PAGE_SIZE, request.controller.signal)
        if (!isCurrent(request)) return
        keys.push(...result.items.map(skill => skill.skillKey))
        if (result.nextAfter === null) break
        after = result.nextAfter
      }
      state.publishedKeys.keys = keys.slice(0, PUBLISHED_KEYS_MAX_COUNT)
      state.publishedKeys.phase = 'ready'
    } catch (error) {
      // Degraded, not blocking: the picker hides its team group and the server 422 stays the
      // final authority on out-of-ceiling keys when saving.
      if (!isAbort(error) && isCurrent(request)) {
        state.publishedKeys.phase = 'error'
        state.publishedKeys.keys = []
        state.publishedKeys.errorMessage = presentError(error, '暂时无法加载团队 Skill')
      }
    } finally {
      finishRequest(request)
    }
  }

  async function createSkill(input: CreateSkillInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand(
      'create',
      null,
      () => gateway.createSkill(scope, input, idempotencyKey),
      () => {
        state.skills = skillPageResource()
        state.publishedKeys = keyCatalog()
      },
    )
  }

  async function saveDraft(skillId: string, input: UpdateSkillDraftInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(skillId)
    if (etag === null) return false
    return runCommand(
      'save-draft',
      skillId,
      () => gateway.saveDraft(scope, skillId, input, etag, idempotencyKey),
      () => {
        delete state.skillDetails[skillId]
        state.skills = skillPageResource()
      },
    )
  }

  async function publishSkill(skillId: string, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(skillId)
    if (etag === null) return false
    return runCommand(
      'publish',
      skillId,
      () => gateway.publishSkill(scope, skillId, etag, idempotencyKey),
      () => invalidatePointerFacts(skillId),
    )
  }

  async function disableSkill(skillId: string, reason: string | null, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(skillId)
    if (etag === null) return false
    return runCommand(
      'disable',
      skillId,
      () => gateway.disableSkill(scope, skillId, reason, etag, idempotencyKey),
      () => invalidatePointerFacts(skillId),
    )
  }

  async function rollbackSkill(skillId: string, toRevision: number, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    const etag = await requireHeadEtag(skillId)
    if (etag === null) return false
    return runCommand(
      'rollback',
      skillId,
      () => gateway.rollbackSkill(scope, skillId, toRevision, etag, idempotencyKey),
      () => invalidatePointerFacts(skillId),
    )
  }

  async function distill(input: DistillSkillInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand(
      'distill',
      null,
      () => gateway.distill(scope, input, idempotencyKey),
      () => {
        state.skills = skillPageResource()
      },
    )
  }

  function distillationReceipt(): SkillDistillationReceipt | null {
    return state.command.operation === 'distill' && state.command.receipt
      ? state.command.receipt as SkillDistillationReceipt
      : null
  }

  /** Ensures the head detail is ready and returns its ETag, or null when the continuation went stale. */
  async function requireHeadEtag(skillId: string): Promise<string | null> {
    const started = generation
    const previous = state.command
    if (state.skillDetails[skillId]?.phase !== 'ready') await loadSkill(skillId)
    if (started !== generation || state.command !== previous) return null
    return state.skillDetails[skillId]?.value?.etag ?? null
  }

  async function runCommand(
    operation: NonNullable<SkillCommandState['operation']>,
    resourceId: string | null,
    action: () => Promise<SkillCommandReceipt>,
    onSuccess?: () => void,
  ): Promise<boolean> {
    const commandGeneration = generation
    if (state.command.phase === 'pending') return false
    state.command = {
      phase: 'pending', operation, resourceId, receipt: null,
      errorMessage: null, errorStatus: null, currentVersion: null, retryable: false,
    }
    const pending = state.command
    try {
      const receipt = await action()
      if (commandGeneration !== generation || state.command !== pending) return false
      onSuccess?.()
      state.command.phase = 'success'
      state.command.receipt = receipt
      return true
    } catch (error) {
      if (commandGeneration !== generation || state.command !== pending) return false
      state.command.phase = conflict(error) ? 'conflict' : 'error'
      state.command.errorMessage = presentError(error, 'Skill 命令执行失败')
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
    target: Record<string, SkillResource<T>>,
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
    const request = beginRequest(requestKey)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    try {
      const value = await load(request.controller.signal)
      if (!isCurrent(request)) return
      validate?.(value)
      resource.value = value
      resource.phase = 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, fallback)
    } finally {
      finishRequest(request)
    }
  }

  function invalidatePointerFacts(skillId: string): void {
    delete state.skillDetails[skillId]
    delete state.versionHistory[skillId]
    delete state.effectiveVersions[skillId]
    state.skills = skillPageResource()
    // Publish/disable/rollback all move the PUBLISHED key set — the picker fold must re-run.
    state.publishedKeys = keyCatalog()
  }

  function clearCommand(): void {
    state.command = commandState()
  }

  function reset(): void {
    commandIntents.clear()
    activeScope = null
    activeScopeKey = null
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Skill Store Scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): SkillRequest {
    requests.get(key)?.controller.abort()
    const request: SkillRequest = {
      key,
      version: generation,
      controller: new AbortController(),
      scopeKey: activeScopeKey,
    }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: SkillRequest): boolean {
    return generation === request.version
      && activeScopeKey === request.scopeKey
      && requests.get(request.key) === request
  }

  function finishRequest(request: SkillRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    for (const request of requests.values()) request.controller.abort()
    requests.clear()
  }

  function replaceState(next: SkillStoreState): void {
    state.skills = next.skills
    state.skillDetails = next.skillDetails
    state.versionHistory = next.versionHistory
    state.versionDetails = next.versionDetails
    state.effectiveVersions = next.effectiveVersions
    state.publishedKeys = next.publishedKeys
    state.command = next.command
  }

  return {
    state: readonly(state) as Readonly<SkillStoreState>,
    activateScope,
    loadSkills,
    loadSkill,
    loadVersions,
    loadVersion,
    loadEffectiveVersion,
    loadPublishedKeys,
    createSkill,
    saveDraft,
    publishSkill,
    disableSkill,
    rollbackSkill,
    distill,
    distillationReceipt,
    clearCommand,
    reset,
  }
}

export function installSkillStore(app: App, gateway: SkillGateway): SkillStore {
  const store = createSkillStore(gateway)
  app.provide(SKILL_STORE, store)
  return store
}

export function useSkillStore(): SkillStore {
  const store = inject(SKILL_STORE)
  if (!store) throw new Error('CrewScope Skill Store is not installed')
  return store
}

function initialState(): SkillStoreState {
  return {
    skills: skillPageResource(),
    skillDetails: {},
    versionHistory: {},
    versionDetails: {},
    effectiveVersions: {},
    publishedKeys: keyCatalog(),
    command: commandState(),
  }
}

function resourceState<T>(): SkillResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null }
}

function skillPageResource(): SkillPageResource {
  return { ...resourceState<SkillSummary[]>(), nextAfter: null, loadingMore: false }
}

function versionPageResource(): SkillVersionPageResource {
  return { ...resourceState<SkillVersion[]>(), nextAfter: null, loadingMore: false }
}

function keyCatalog(): SkillKeyCatalog {
  return { phase: 'idle', keys: [], errorMessage: null }
}

function commandState(): SkillCommandState {
  return {
    phase: 'idle', operation: null, resourceId: null, receipt: null,
    errorMessage: null, errorStatus: null, currentVersion: null, retryable: false,
  }
}

function assertHeadVersion(value: SkillSummary, etag: string): void {
  if (etag !== `"${value.version}"`) throw new Error('Skill ETag does not match its head version')
}

function mergeSkills(existing: SkillSummary[], incoming: SkillSummary[]): SkillSummary[] {
  const known = new Set(existing.map(item => item.skillKey))
  return [...existing, ...incoming.filter(item => !known.has(item.skillKey))]
}

function mergeVersions(existing: SkillVersion[], incoming: SkillVersion[]): SkillVersion[] {
  const known = new Set(existing.map(item => item.revision))
  return [...existing, ...incoming.filter(item => !known.has(item.revision))]
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function setError(resource: SkillResource<unknown>, error: unknown, fallback: string): void {
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

function conflict(error: unknown): boolean {
  return error instanceof CrewScopeApiError && (error.status === 409 || error.status === 412)
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
