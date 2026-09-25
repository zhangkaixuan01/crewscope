import { reactive } from 'vue'
import { apiClient, CrewScopeApiError, type CrewScopeApiClient } from './client'
import { commandFailure } from './commandIntent'
import type { CommandReceipt } from '../domains/scope/types'

export type CreationType = 'WORK_PROJECT' | 'WORK_ITEM' | 'CONVERSATION'
export interface CreationScope { organizationId: string; teamId: string; projectId?: string }
export interface CreationResult extends Omit<CreationScope, 'projectId'> {
  type: CreationType
  projectId: string | null
  resourceId: string
  committedVersion: number
  stage: 'COMMITTED'
}
export interface RecoveryEntry extends CreationScope {
  key: string
  type: CreationType
  createdAt: number
}
const STORAGE = 'crewscope.command-recovery.v1'
const ENTRY_PREFIX = `${STORAGE}.entry.`
const AGE = 7 * 24 * 60 * 60 * 1000
const LIMIT = 100
let identity: string | null = null
let identityInvalidated = false
let generation = 0
const controllers = new Set<AbortController>()
function entryId(entry: Pick<RecoveryEntry, 'organizationId' | 'key'>): string {
  return `${ENTRY_PREFIX}${encodeURIComponent(entry.organizationId)}.${encodeURIComponent(entry.key)}`
}
function cleanEntry({ key, type, createdAt, organizationId, teamId, projectId }: RecoveryEntry): RecoveryEntry {
  return { key, type, createdAt, organizationId, teamId, projectId }
}
function clearStorage(): void {
  for (const key of Object.keys(localStorage)) if (key.startsWith(ENTRY_PREFIX)) localStorage.removeItem(key)
  localStorage.removeItem(STORAGE)
}
function storedEntries(): RecoveryEntry[] {
  const entries: RecoveryEntry[] = []
  for (const key of Object.keys(localStorage)) {
    if (!key.startsWith(ENTRY_PREFIX)) continue
    const saved = JSON.parse(localStorage.getItem(key) ?? 'null') as { owner?: string; entry?: unknown } | null
    if (!saved || saved.owner !== identity || !isEntry(saved.entry)) continue
    if (Date.now() - saved.entry.createdAt >= AGE) {
      localStorage.removeItem(key)
      creationRecovery.warning = '部分本机恢复记录已过期；这不代表原操作未提交，请先核实已有结果，不要重复新建。'
    } else entries.push(cleanEntry(saved.entry))
  }
  return entries.sort((a, b) => a.createdAt - b.createdAt)
}
export const creationRecovery = reactive({
  entries: [] as RecoveryEntry[],
  warning: '',
})

/** Called only after the server has authenticated this account/principal/security version. */
export function setCreationIdentity(owner: string | null): void {
  if (owner === identity && owner !== null) return
  stopCreationQueries()
  const previous = identity
  identity = owner
  identityInvalidated = false
  creationRecovery.entries = []
  creationRecovery.warning = ''
  try {
    const raw = localStorage.getItem(STORAGE)
    const saved = raw ? JSON.parse(raw) as { owner?: unknown } : null
    if (!owner || (previous && previous !== owner) || (saved && saved.owner !== owner)) clearStorage()
    if (!owner) return
    localStorage.setItem(STORAGE, JSON.stringify({ owner }))
    creationRecovery.entries = storedEntries()
  } catch { creationRecovery.warning = '浏览器无法保存恢复记录；当前操作仅在内存保留，刷新后无法自动恢复。' }
}

function isEntry(value: unknown): value is RecoveryEntry {
  if (!value || typeof value !== 'object') return false
  const entry = value as RecoveryEntry
  return ['WORK_PROJECT', 'WORK_ITEM', 'CONVERSATION'].includes(entry.type)
    && typeof entry.key === 'string' && /^[A-Za-z0-9._:-]{1,200}$/.test(entry.key)
    && typeof entry.organizationId === 'string' && typeof entry.teamId === 'string'
    && (entry.projectId === undefined || typeof entry.projectId === 'string')
    && Number.isFinite(entry.createdAt) && entry.createdAt <= Date.now()
}

function persist(entry: RecoveryEntry): void {
  if (!identity) return
  try {
    // Individual keys prevent concurrent tabs from replacing each other's recovery collection.
    // Late responses only delete their own key: they can never rewrite resurrected records.
    const saved = JSON.parse(localStorage.getItem(STORAGE) ?? 'null') as { owner?: string } | null
    if (saved?.owner !== identity) { invalidateIdentity(); throw new Error('identity changed') }
    const encoded = JSON.stringify({ owner: identity, entry: cleanEntry(entry) })
    localStorage.setItem(entryId(entry), encoded)
    if (localStorage.getItem(entryId(entry)) !== encoded) throw new Error('storage verification failed')
    if (storedEntries().length > LIMIT) {
      localStorage.removeItem(entryId(entry))
      creationRecovery.entries = creationRecovery.entries.filter(item => entryId(item) !== entryId(entry))
      throw capacityError()
    }
  } catch (error) {
    if (error instanceof CrewScopeApiError) throw error
    creationRecovery.warning = '浏览器无法保存恢复记录；当前操作仅在内存保留，刷新后无法自动恢复。'
  }
}

export function stopCreationQueries(): void {
  generation += 1
  for (const controller of controllers) controller.abort()
  controllers.clear()
}

/** A logout/account switch in another tab must not be undone by this tab's late response. */
export function observeCreationStorage(event: StorageEvent): void {
  if (event.key !== null && event.key !== STORAGE && !event.key.startsWith(ENTRY_PREFIX)) return
  try {
    const raw = event.key === STORAGE ? event.newValue : localStorage.getItem(STORAGE)
    const saved = raw ? JSON.parse(raw) as { owner: string } : null
    if (!saved || saved.owner !== identity) invalidateIdentity()
    else creationRecovery.entries = storedEntries()
  } catch { invalidateIdentity() }
}

function invalidateIdentity(): void {
  stopCreationQueries()
  identity = null
  identityInvalidated = true
  creationRecovery.entries = []
  creationRecovery.warning = '登录状态已在其他页面变化，请刷新并重新确认身份。'
}

export function acknowledgeCreation(key: string | undefined, organizationId?: string): void {
  if (!key) return
  const removed = creationRecovery.entries.filter(entry => entry.key === key && (!organizationId || entry.organizationId === organizationId))
  creationRecovery.entries = creationRecovery.entries.filter(entry => !removed.includes(entry))
  for (const entry of removed) {
    try { localStorage.removeItem(entryId(entry)) } catch { creationRecovery.warning = '无法清理本机记录；再次确认只会读取原操作，不会重复创建。' }
  }
}

function pendingError(committed = false): CrewScopeApiError {
  return new CrewScopeApiError(0, { code: committed ? 'creation_projection_pending' : 'creation_result_pending',
    message: committed ? '已创建，详情尚未同步。请再次确认并打开原结果，不要重复新建。'
      : '结果仍待确认。请使用“再次确认”查询原操作；不要重复新建。旧命令可能暂无自动定位结果。',
    correlationId: 'unavailable', retryable: true, currentVersion: null, details: {} })
}
function capacityError(): CrewScopeApiError {
  return new CrewScopeApiError(429, {
    code: 'command_recovery_capacity', message: '待确认操作已达上限，请先确认已有结果。', correlationId: 'unavailable',
    retryable: false, currentVersion: null, details: {},
  })
}

export async function createAndLocate(
  client: CrewScopeApiClient, scope: CreationScope, type: CreationType, key: string,
  send: () => Promise<CommandReceipt>,
): Promise<CommandReceipt> {
  if (identityInvalidated) throw new CrewScopeApiError(401, {
    code: 'authentication_required', message: '请刷新页面并重新确认身份。', correlationId: 'unavailable',
    retryable: false, currentVersion: null, details: {},
  })
  const started = generation
  const owner = identity
  creationRecovery.entries = creationRecovery.entries.filter(item => Date.now() - item.createdAt < AGE)
  if (owner) {
    try {
      const merged = new Map(storedEntries().map(item => [entryId(item), item]))
      for (const item of creationRecovery.entries) if (!merged.has(entryId(item))) merged.set(entryId(item), item)
      creationRecovery.entries = [...merged.values()]
    } catch { /* Keep memory records when storage is unavailable. */ }
  }
  let entry = creationRecovery.entries.find(item => item.key === key && item.organizationId === scope.organizationId)
  if (entry && (entry.type !== type || entry.teamId !== scope.teamId || entry.projectId !== scope.projectId))
    throw new Error('The original creation identity belongs to another target')
  if (!entry) {
    if (creationRecovery.entries.length >= LIMIT) throw capacityError()
    entry = { ...scope, key, type, createdAt: Date.now() }
    creationRecovery.entries.push(entry)
    persist(entry)
    if (identityInvalidated) throw new DOMException('Creation identity changed', 'AbortError')
    try { await send() } catch (error) {
      if (commandFailure(error) !== 'unknown') {
        if (identity === owner) acknowledgeCreation(key, scope.organizationId)
        throw error
      }
      // A lost response is resolved by reads, never by creating another intent.
    }
  }
  if (started !== generation) throw new DOMException('Creation scope changed', 'AbortError')
  try { return await resolveCreation(entry, client) }
  catch (error) {
    // A read rejection after dispatch is NOT a rejected write. Keep the original intent/key
    // even if permission was lost while locating an already committed resource.
    if (error instanceof CrewScopeApiError && commandFailure(error) !== 'unknown') throw pendingError()
    throw error
  }
}

/** One shared budget for result lookup and exact resource visibility, including hung requests. */
export async function resolveCreation(entry: RecoveryEntry, client = apiClient): Promise<CommandReceipt> {
  const controller = new AbortController()
  controllers.add(controller)
  const deadline = Date.now() + 30_000
  const timeout = setTimeout(() => controller.abort(), 30_000)
  let result: { receipt: CommandReceipt; result: CreationResult } | null = null
  let requests = 0
  try {
    for (let attempt = 0; requests < 8 && Date.now() < deadline; attempt += 1) {
      let delay = [1000, 2000, 4000, 8000][Math.min(attempt, 3)]!
      try {
        if (!result) {
          requests += 1
          result = await client.get<{ receipt: CommandReceipt; result: CreationResult }>(
          `/organizations/${encodeURIComponent(entry.organizationId)}/command-results`,
          { idempotencyKey: entry.key, signal: controller.signal, cache: 'no-store' })
        }
        const coordinate = result.result
        if (coordinate.organizationId !== entry.organizationId || coordinate.teamId !== entry.teamId
          || coordinate.type !== entry.type || coordinate.stage !== 'COMMITTED'
          || (entry.projectId && coordinate.projectId !== entry.projectId)
          || !coordinate.resourceId) throw new Error('Invalid creation coordinate')
        if (requests >= 8) break
        requests += 1
        const resource = await client.get<Record<string, unknown>>(resourcePath(coordinate), { signal: controller.signal, cache: 'no-store' })
        if (controller.signal.aborted) throw new DOMException('Creation query stopped', 'AbortError')
        const detail = coordinate.type === 'WORK_ITEM' ? resource.workItem : coordinate.type === 'CONVERSATION' ? resource.conversation : resource
        if (!detail || typeof detail !== 'object' || (detail as { id?: unknown }).id !== coordinate.resourceId)
          throw new Error('The resource detail does not match the committed creation coordinate')
        return { ...result.receipt, creation: coordinate, createdResource: resource, recoveryKey: entry.key }
      } catch (error) {
        if (controller.signal.aborted) throw error
        if (error instanceof CrewScopeApiError) {
          if ([401, 403].includes(error.status)) throw error
          if (error.status !== 404 && error.status !== 429 && commandFailure(error) !== 'unknown') throw error
          if (error.status === 429) delay = Math.max(delay, error.retryAfterMs ?? 0)
        } else if (!(error instanceof TypeError)) throw error
      }
      if (Date.now() + delay >= deadline || attempt === 7) break
      await new Promise<void>((resolve, reject) => {
        const abort = () => { clearTimeout(timer); reject(new DOMException('Stopped', 'AbortError')) }
        const timer = setTimeout(() => { controller.signal.removeEventListener('abort', abort); resolve() }, delay)
        controller.signal.addEventListener('abort', abort, { once: true })
      })
    }
    throw pendingError(Boolean(result))
  } catch (error) {
    if (Date.now() >= deadline) throw pendingError(Boolean(result))
    throw error
  } finally { clearTimeout(timeout); controllers.delete(controller) }
}

function resourcePath(result: CreationResult): string {
  const root = `/organizations/${encodeURIComponent(result.organizationId)}/teams/${encodeURIComponent(result.teamId)}`
  if (result.type === 'CONVERSATION') return `${root}/conversations/${encodeURIComponent(result.resourceId)}`
  if (result.type === 'WORK_PROJECT') return `${root}/work-projects/${encodeURIComponent(result.resourceId)}`
  return `${root}/work-projects/${encodeURIComponent(result.projectId ?? '')}/work-items/${encodeURIComponent(result.resourceId)}`
}
