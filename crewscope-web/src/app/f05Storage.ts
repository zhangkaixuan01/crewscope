/**
 * Scoped browser-local user content (M9b-F05, contract S01 D06).
 *
 * Namespace: `cs.user.v1:<accountId>:<principalId>:<orgId>:<teamId|personal>:<projectId|none>:<kind>:<object-or-intentId>:<revision>`
 * Every segment is encodeURIComponent'd, so encoded `:` inside one segment never
 * collides with the `:` segment separator. Records carry schemaVersion, the
 * writing epoch, the full seven-dimension scope and expiresAt; a read validates
 * every dimension plus the epoch and deletes the key on any mismatch.
 *
 * Budgets: drafts 20 entries / 2 MiB / 7 days (no silent eviction — a full store
 * fails with `quota` so callers can keep the input in memory); recent 100 / 30d;
 * reading 200 / 30d (entry accounting in useScopedUserState); views and pins
 * 50 / 180d unused. Credentials never reach this layer: sensitive field names
 * are stripped defensively and secret forms must not call it at all.
 *
 * Epoch: the device-level `cs.user.epoch.v1` key guards cross-tab cleanup. While
 * the module epoch is null (anonymous or cleared) every read and write fails, so
 * a stale tab cannot resurrect cleared data. Sign-out clears everything before
 * the identity store notifies listeners ("clear first, then render").
 */
export const F05_USER_PREFIX = 'cs.user.v1:'
export const F05_EPOCH_KEY = 'cs.user.epoch.v1'
export type F05Kind = 'draft' | 'recent' | 'reading' | 'view' | 'pin'
export const F05_DRAFT_TTL_MS = 7 * 24 * 60 * 60 * 1000
export const F05_MAX_DRAFTS = 20
export const F05_MAX_DRAFT_BYTES = 2 * 1024 * 1024
export const F05_RECENT_TTL_MS = 30 * 24 * 60 * 60 * 1000
export const F05_MAX_RECENT = 100
export const F05_READING_TTL_MS = 30 * 24 * 60 * 60 * 1000
export const F05_MAX_VIEWS = 50
export const F05_VIEW_TTL_MS = 180 * 24 * 60 * 60 * 1000

export interface F05Scope { accountId: string, principalId: string, organizationId: string, teamId?: string | null, projectId?: string | null, objectId?: string | null, objectVersion?: number | null }
export interface F05Identity { principalId: string, accountId: string }

/** Builds the identity pair from an injected principal; null while signed out. */
export function principalIdentity(principal: { id: string, accountId: string } | null | undefined): F05Identity | null {
  return principal && principal.id && principal.accountId ? { principalId: principal.id, accountId: principal.accountId } : null
}
interface F05RecordScope { accountId: string, principalId: string, organizationId: string, teamId: string | null, projectId: string | null, objectId: string, revision: number | null }
export interface F05Record<T> { schemaVersion: 1, kind: F05Kind, epoch: number, scope: F05RecordScope, createdAt: number, updatedAt: number, expiresAt: number, value: T }
export type F05StorageResult = { ok: boolean, reason?: 'unavailable' | 'quota' | 'stale-epoch' | 'invalid' }

export interface F05RecentItem { kind: 'action' | 'object', objectType: string, id: string, label: string, subtitle?: string | null, route?: string, accessedAt: number }
export interface F05SavedView { id: string, name: string, routeName: string, filters: Record<string, string | number | boolean | null | string[]>, sort?: { field: string, direction: 'asc' | 'desc' } | null, pinned: boolean, updatedAt: number }

const sensitive = /(?:password|secret|token|api[-_]?key|credential|authorization|private[-_]?key|access[-_]?key)/i
/** Legacy keys that can never prove identity ownership: purged once, never migrated (S01 D06). */
const LEGACY_LOCAL_PREFIXES = ['cs.f05.', 'crewscope:agent-configuration:', 'crewscope:stream:v1:']
const LEGACY_LOCAL_KEYS = ['cs.pref.device.command-recent.v1', 'cs.pref.conversation.scroll.v1', 'cs.pref.conversation.read-sequences.v1', 'cs.pref.diff.viewed-files.v1']
const LEGACY_SESSION_PREFIXES = ['crewscope:coding-target:v1:', 'crewscope:task-delegation:v1:']
/** Live session cursors: kept while signed in, cleared on sign-out / account switch. */
const SESSION_CURSOR_PREFIXES = ['crewscope:task-cursor:', 'crewscope:conversation:invocation:', 'crewscope:conversation:events:', 'crewscope:conversation:clarification:', 'crewscope:conversation:task-intent:']

const store = (): Storage | null => { try { return typeof window === 'undefined' ? null : window.localStorage } catch { return null } }
const session = (): Storage | null => { try { return typeof window === 'undefined' ? null : window.sessionStorage } catch { return null } }
const part = (value: string | null | undefined): string => encodeURIComponent(value ?? '_')

function f05Key(kind: F05Kind, scope: F05Scope): string {
  return [F05_USER_PREFIX.slice(0, -1), scope.accountId, scope.principalId, scope.organizationId, scope.teamId ?? 'personal', scope.projectId ?? 'none', kind, scope.objectId ?? 'none', String(scope.objectVersion ?? 0)].map(part).join(':')
}

// --- epoch -----------------------------------------------------------------

interface F05EpochRecord { version: 1, epoch: number, accountId: string | null, updatedAt: number }
let currentEpoch: number | null = null
const epochListeners = new Set<(epoch: number | null) => void>()

function readEpochRecord(): F05EpochRecord | null {
  const storage = store()
  if (!storage) return null
  try {
    const value: unknown = JSON.parse(storage.getItem(F05_EPOCH_KEY) ?? 'null')
    if (!value || typeof value !== 'object' || !Number.isFinite((value as F05EpochRecord).epoch)) return null
    const record = value as F05EpochRecord
    return { version: 1, epoch: record.epoch, accountId: typeof record.accountId === 'string' ? record.accountId : null, updatedAt: Number.isFinite(record.updatedAt) ? record.updatedAt : 0 }
  } catch { return null }
}

function writeEpochRecord(record: F05EpochRecord): void {
  try { store()?.setItem(F05_EPOCH_KEY, JSON.stringify(record)) } catch { /* storage availability never blocks the page */ }
}

function setModuleEpoch(epoch: number | null): void {
  currentEpoch = epoch
  for (const listener of epochListeners) listener(epoch)
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('crewscope:user-epoch', { detail: epoch }))
}

/** Activates the scoped namespace for the signed-in account; a different account bumps the epoch. */
export function activateF05Identity(accountId: string): void {
  const previous = readEpochRecord()
  const epoch = previous && previous.accountId === accountId ? previous.epoch : (previous?.epoch ?? 0) + 1
  writeEpochRecord({ version: 1, epoch, accountId, updatedAt: Date.now() })
  setModuleEpoch(epoch)
}

export function f05CurrentEpoch(): number | null { return currentEpoch }

export function subscribeF05Epoch(listener: (epoch: number | null) => void): () => void {
  epochListeners.add(listener)
  return () => epochListeners.delete(listener)
}

/**
 * Re-checks the persisted epoch before storage access. The storage event never
 * fires for same-page removals, so this keeps the module sealed immediately
 * after any external clear instead of writing into a wiped namespace.
 */
function syncEpochFromStorage(): boolean {
  const record = readEpochRecord()
  const epoch = record && record.accountId !== null ? record.epoch : null
  if (epoch !== currentEpoch) setModuleEpoch(epoch)
  return currentEpoch !== null
}

if (typeof window !== 'undefined') {
  // Second channel besides the `crewscope-auth` BroadcastChannel: another tab's
  // epoch write (sign-out / account switch) must seal this tab's storage access.
  window.addEventListener('storage', (event: StorageEvent) => {
    if (event.key !== F05_EPOCH_KEY) return
    const record = readEpochRecord()
    setModuleEpoch(record && record.accountId !== null ? record.epoch : null)
  })
}

// --- record validation -----------------------------------------------------

const KINDS: readonly F05Kind[] = ['draft', 'recent', 'reading', 'view', 'pin']
const validRecord = <T>(value: unknown): value is F05Record<T> => {
  if (!value || typeof value !== 'object') return false
  const record = value as F05Record<T>
  return record.schemaVersion === 1 && KINDS.includes(record.kind) && Number.isFinite(record.epoch) && Boolean(record.scope && typeof record.scope === 'object') && Number.isFinite(record.expiresAt) && Number.isFinite(record.updatedAt) && 'value' in record
}
function sameScope(scope: F05Scope, record: F05RecordScope): boolean { return scope.accountId === record.accountId && scope.principalId === record.principalId && scope.organizationId === record.organizationId && (scope.teamId ?? null) === record.teamId && (scope.projectId ?? null) === record.projectId && (scope.objectId ?? 'none') === record.objectId && (scope.objectVersion ?? 0) === (record.revision ?? 0) }

function clean<T>(value: T): T { if (Array.isArray(value)) return value.map(clean) as T; if (!value || typeof value !== 'object') return value; const output: Record<string, unknown> = {}; for (const [name, item] of Object.entries(value as Record<string, unknown>)) if (!sensitive.test(name)) output[name] = clean(item); return output as T }
export function stripSensitiveFields<T>(value: T): T { return clean(value) }

// --- CRUD ------------------------------------------------------------------

export function readF05<T>(kind: F05Kind, scope: F05Scope): F05Record<T> | null {
  const storage = store()
  if (!storage || !syncEpochFromStorage()) return null
  const key = f05Key(kind, scope)
  try {
    const raw = storage.getItem(key)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (!validRecord<T>(value) || value.kind !== kind || value.epoch !== currentEpoch || !sameScope(scope, value.scope) || value.expiresAt <= Date.now()) { storage.removeItem(key); return null }
    return value
  } catch { try { storage.removeItem(key) } catch { /* best effort */ } return null }
}

/** Enumerates stored keys of one kind for an account+principal (used by budgets and cleanups). */
function keysForPrefix(prefix: string): string[] { const storage = store(); if (!storage) return []; const keys: string[] = []; for (let index = storage.length - 1; index >= 0; index -= 1) { const key = storage.key(index); if (key && key.startsWith(prefix)) keys.push(key) } return keys }

function purgeExpiredF05(): void {
  const storage = store()
  if (!storage) return
  for (const key of keysForPrefix(F05_USER_PREFIX)) {
    try {
      const value: unknown = JSON.parse(storage.getItem(key) ?? 'null')
      if (!value || typeof value !== 'object' || !Number.isFinite((value as F05Record<unknown>).expiresAt) || (value as F05Record<unknown>).expiresAt <= Date.now()) storage.removeItem(key)
    } catch { storage.removeItem(key) }
  }
}

export function writeF05<T>(kind: F05Kind, scope: F05Scope, value: T, options: { ttlMs?: number } = {}): F05StorageResult {
  const storage = store()
  if (!storage) return { ok: false, reason: 'unavailable' }
  if (!syncEpochFromStorage()) return { ok: false, reason: 'stale-epoch' }
  const epoch = currentEpoch
  if (epoch === null) return { ok: false, reason: 'stale-epoch' }
  const now = Date.now()
  const key = f05Key(kind, scope)
  const recordScope: F05RecordScope = { accountId: scope.accountId, principalId: scope.principalId, organizationId: scope.organizationId, teamId: scope.teamId ?? null, projectId: scope.projectId ?? null, objectId: scope.objectId ?? 'none', revision: scope.objectVersion ?? null }
  const existing = readF05<T>(kind, scope)
  const record: F05Record<T> = { schemaVersion: 1, kind, epoch, scope: recordScope, createdAt: existing?.createdAt ?? now, updatedAt: now, expiresAt: now + (options.ttlMs ?? F05_DRAFT_TTL_MS), value: clean(value) }
  let serialized: string
  try { serialized = JSON.stringify(record) } catch { return { ok: false, reason: 'invalid' } }
  if (kind === 'draft') {
    purgeExpiredF05()
    const identityPrefix = `${F05_USER_PREFIX}${part(scope.accountId)}:${part(scope.principalId)}:`
    const draftKeys = keysForPrefix(`${identityPrefix}`).filter(key => key.split(':')[6] === 'draft')
    const existingIndex = draftKeys.indexOf(key)
    const count = existingIndex >= 0 ? draftKeys.length : draftKeys.length + 1
    if (count > F05_MAX_DRAFTS) return { ok: false, reason: 'quota' }
    const previousBytes = existingIndex >= 0 ? storage.getItem(key)?.length ?? 0 : 0
    const totalBytes = draftKeys.reduce((sum, item) => sum + (storage.getItem(item)?.length ?? 0), 0) - previousBytes + serialized.length
    if (totalBytes > F05_MAX_DRAFT_BYTES) return { ok: false, reason: 'quota' }
  }
  try { storage.setItem(key, serialized) } catch { return { ok: false, reason: 'quota' } }
  // Read-back verification: only report success when the record really landed.
  const readBack = readF05<T>(kind, scope)
  return readBack && readBack.updatedAt === record.updatedAt ? { ok: true } : { ok: false, reason: 'quota' }
}

export function removeF05(kind: F05Kind, scope: F05Scope): void { try { store()?.removeItem(f05Key(kind, scope)) } catch { /* best effort */ } }

/** Deletes the draft only when its stored revision still matches the submitted snapshot. */
export function removeF05DraftIfRevision(scope: F05Scope, revision: number): boolean {
  const record = readF05<unknown>('draft', scope)
  if (!record) return false
  const draftRevision = (record.value as { draftRevision?: unknown } | null)?.draftRevision
  if (draftRevision !== revision) return false
  removeF05('draft', scope)
  return true
}

// --- targeted cleanup ------------------------------------------------------

/** Revocation / 403 cleanup: clears one team dimension without touching another Team. */
export function clearF05TeamScope(scope: { accountId: string, organizationId: string, teamId: string }): string[] {
  const storage = store()
  if (!storage) return []
  const removed: string[] = []
  for (const key of keysForPrefix(F05_USER_PREFIX)) {
    try {
      const value = JSON.parse(storage.getItem(key) ?? 'null') as F05Record<unknown> | null
      if (!value?.scope || value.scope.accountId !== scope.accountId || value.scope.organizationId !== scope.organizationId || value.scope.teamId !== scope.teamId) continue
      storage.removeItem(key)
      removed.push(key)
    } catch { /* unreadable keys self-heal on read */ }
  }
  return removed
}

export function clearF05Object(kind: F05Kind, scope: Pick<F05Scope, 'accountId' | 'principalId' | 'organizationId' | 'teamId' | 'projectId' | 'objectId'>): void {
  const storage = store()
  if (!storage) return
  for (const key of keysForPrefix(`${F05_USER_PREFIX}${part(scope.accountId)}:${part(scope.principalId)}:`)) {
    try {
      const value = JSON.parse(storage.getItem(key) ?? 'null') as F05Record<unknown> | null
      if (!value || value.kind !== kind || !value.scope) continue
      if (value.scope.organizationId === scope.organizationId && (value.scope.teamId ?? null) === (scope.teamId ?? null) && (value.scope.projectId ?? null) === (scope.projectId ?? null) && value.scope.objectId === (scope.objectId ?? 'none')) storage.removeItem(key)
    } catch { /* best effort */ }
  }
}

// --- legacy purge & full cleanup -------------------------------------------

function removePrefixed(storage: Storage | null, prefixes: string[], exactKeys: string[] = []): string[] {
  if (!storage) return []
  const removed: string[] = []
  for (let index = storage.length - 1; index >= 0; index -= 1) {
    const key = storage.key(index)
    if (!key) continue
    if (prefixes.some(prefix => key.startsWith(prefix)) || exactKeys.includes(key)) { storage.removeItem(key); removed.push(key) }
  }
  return removed
}

/** One-time removal of pre-F05 keys that cannot prove identity ownership. Idempotent. */
export function purgeF05LegacyKeys(): string[] {
  return [...removePrefixed(store(), LEGACY_LOCAL_PREFIXES, LEGACY_LOCAL_KEYS), ...removePrefixed(session(), LEGACY_SESSION_PREFIXES)]
}

/** Sign-out / account switch: clears scoped data, live cursors and legacy keys, then seals the epoch. Runs before the identity store notifies listeners. */
export function clearF05UserData(): string[] {
  const removed = [...removePrefixed(store(), [F05_USER_PREFIX, ...LEGACY_LOCAL_PREFIXES], LEGACY_LOCAL_KEYS), ...removePrefixed(session(), [...LEGACY_SESSION_PREFIXES, ...SESSION_CURSOR_PREFIXES])]
  const previous = readEpochRecord()
  writeEpochRecord({ version: 1, epoch: (previous?.epoch ?? 0) + 1, accountId: null, updatedAt: Date.now() })
  setModuleEpoch(null)
  return removed
}

// --- recent / reading / views ----------------------------------------------

export function readF05Recent(scope: F05Scope): F05RecentItem[] {
  const value = readF05<F05RecentItem[]>('recent', { ...scope, objectId: null, objectVersion: null })?.value
  if (!Array.isArray(value)) return []
  const cutoff = Date.now() - F05_RECENT_TTL_MS
  return value.filter(item => item && typeof item === 'object' && typeof item.id === 'string' && typeof item.accessedAt === 'number' && item.accessedAt > cutoff).slice(0, F05_MAX_RECENT)
}

export function writeF05Recent(scope: F05Scope, items: F05RecentItem[]): F05StorageResult {
  return writeF05('recent', { ...scope, objectId: null, objectVersion: null }, items.slice(0, F05_MAX_RECENT), { ttlMs: F05_RECENT_TTL_MS })
}

/** Reading state lives under a per-object segment such as `execution-1:viewed-files`. */
export function readF05Reading<T>(scope: F05Scope, name: string): T | null {
  return readF05<T>('reading', { ...scope, objectId: scope.objectId ? `${scope.objectId}:${name}` : name })?.value ?? null
}

export function writeF05Reading<T>(scope: F05Scope, name: string, value: T): F05StorageResult {
  return writeF05('reading', { ...scope, objectId: scope.objectId ? `${scope.objectId}:${name}` : name }, value, { ttlMs: F05_READING_TTL_MS })
}

export function readF05Views(scope: F05Scope): F05SavedView[] {
  const value = readF05<F05SavedView[]>('view', { ...scope, objectId: null, objectVersion: null })?.value
  if (!Array.isArray(value)) return []
  const cutoff = Date.now() - F05_VIEW_TTL_MS
  return value.filter(item => item && typeof item === 'object' && typeof item.id === 'string' && typeof item.updatedAt === 'number' && item.updatedAt > cutoff).slice(0, F05_MAX_VIEWS)
}

export function writeF05Views(scope: F05Scope, views: F05SavedView[]): F05StorageResult {
  return writeF05('view', { ...scope, objectId: null, objectVersion: null }, views.slice(0, F05_MAX_VIEWS), { ttlMs: F05_VIEW_TTL_MS })
}

export function upsertF05View(scope: F05Scope, view: F05SavedView): F05StorageResult { return writeF05Views(scope, [view, ...readF05Views(scope).filter(item => item.id !== view.id)]) }
export function removeF05View(scope: F05Scope, viewId: string): F05StorageResult { return writeF05Views(scope, readF05Views(scope).filter(item => item.id !== viewId)) }
