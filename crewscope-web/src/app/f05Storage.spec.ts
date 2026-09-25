import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  activateF05Identity, clearF05TeamScope, clearF05UserData, F05_EPOCH_KEY, F05_MAX_DRAFTS, f05CurrentEpoch,
  F05_USER_PREFIX, purgeF05LegacyKeys, readF05, readF05Recent, readF05Views, removeF05DraftIfRevision,
  stripSensitiveFields, subscribeF05Epoch, upsertF05View, writeF05, writeF05Recent,
} from './f05Storage'

const scope = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1', projectId: 'project-1', objectId: 'object-1' }

describe('f05Storage namespace', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('builds keys from every scope dimension with personal and none placeholders', () => {
    activateF05Identity('account-1')
    writeF05('draft', scope, { note: 'x' })
    const key = Object.keys(localStorage).find(item => item.startsWith(F05_USER_PREFIX)) ?? ''
    const segments = key.split(':')
    expect(segments).toHaveLength(9)
    expect(segments[0]).toBe('cs.user.v1')
    expect(decodeURIComponent(segments[1])).toBe('account-1')
    expect(decodeURIComponent(segments[6])).toBe('draft')
    expect(decodeURIComponent(segments[7])).toBe('object-1')

    writeF05('draft', { ...scope, teamId: null, projectId: null, objectId: null }, { note: 'y' })
    const bare = Object.keys(localStorage).map(item => item.split(':')).find(parts => parts.length === 9 && decodeURIComponent(parts[7]) === 'none')
    expect(bare && decodeURIComponent(bare[4]) === 'personal').toBe(true)
  })

  it('round-trips a record and restores nothing after clearF05UserData', () => {
    activateF05Identity('account-1')
    expect(writeF05('draft', scope, { note: '本地草稿' }).ok).toBe(true)
    expect(readF05('draft', scope)?.value).toEqual({ note: '本地草稿' })
    const epochBefore = f05CurrentEpoch()
    clearF05UserData()
    expect(readF05('draft', scope)).toBeNull()
    expect(f05CurrentEpoch()).toBeNull()
    expect(JSON.parse(localStorage.getItem(F05_EPOCH_KEY) ?? '{}').epoch).toBeGreaterThan(epochBefore ?? 0)
  })

  it('seals the namespace while signed out and isolates accounts through epoch bumps', () => {
    expect(writeF05('draft', scope, { note: 'no' }).reason).toBe('stale-epoch')
    activateF05Identity('account-1')
    writeF05('draft', scope, { note: 'yes' })
    // Same account re-activation keeps the epoch; an account switch bumps it,
    // so every record written under the previous epoch becomes unreadable.
    activateF05Identity('account-1')
    expect(readF05('draft', scope)?.value).toEqual({ note: 'yes' })
    activateF05Identity('account-2')
    expect(readF05('draft', scope)).toBeNull()
    writeF05('draft', scope, { note: 'from-account-2' })
    activateF05Identity('account-1')
    expect(readF05('draft', scope)).toBeNull()
  })

  it('bumps the epoch through the storage event from another tab', () => {
    activateF05Identity('account-1')
    writeF05('draft', scope, { note: 'kept' })
    const heard: (number | null)[] = []
    const unsubscribe = subscribeF05Epoch(epoch => heard.push(epoch))
    // The other tab really clears the epoch before its storage event arrives here.
    localStorage.setItem(F05_EPOCH_KEY, JSON.stringify({ version: 1, epoch: 42, accountId: null, updatedAt: Date.now() }))
    window.dispatchEvent(new StorageEvent('storage', { key: F05_EPOCH_KEY }))
    expect(f05CurrentEpoch()).toBeNull()
    expect(readF05('draft', scope)).toBeNull()
    unsubscribe()
    expect(heard).toContain(null)
  })

  it('resyncs with the persisted epoch even after a same-page wipe', () => {
    activateF05Identity('account-1')
    localStorage.removeItem(F05_EPOCH_KEY)
    expect(writeF05('draft', scope, { note: 'wiped' }).reason).toBe('stale-epoch')
  })
})

describe('f05Storage record hygiene', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('deletes expired, corrupt and dimension-mismatched records on read', () => {
    activateF05Identity('account-1')
    writeF05('draft', scope, { note: 'x' })
    const key = Object.keys(localStorage).find(item => item.startsWith(F05_USER_PREFIX))
    expect(key).toBeTruthy()
    if (!key) return
    const record = JSON.parse(localStorage.getItem(key) ?? '{}') as { expiresAt: number, scope: Record<string, string> }
    localStorage.setItem(key, JSON.stringify({ ...record, expiresAt: Date.now() - 1 }))
    expect(readF05('draft', scope)).toBeNull()
    expect(localStorage.getItem(key)).toBeNull()

    writeF05('draft', scope, { note: 'x' })
    localStorage.setItem(key, '{not json')
    expect(readF05('draft', scope)).toBeNull()

    writeF05('draft', scope, { note: 'x' })
    localStorage.setItem(key, JSON.stringify({ ...record, scope: { ...record.scope, objectId: 'other' } }))
    expect(readF05('draft', scope)).toBeNull()
  })

  it('strips sensitive-looking fields defensively', () => {
    expect(stripSensitiveFields({ title: 'ok', apiKey: 'secret', nested: { api_key: 'x', safe: 1 } })).toEqual({ title: 'ok', nested: { safe: 1 } })
  })

  it('deletes a submitted draft only when its revision still matches', () => {
    activateF05Identity('account-1')
    writeF05('draft', scope, { note: 'first', draftRevision: 1 })
    expect(removeF05DraftIfRevision(scope, 2)).toBe(false)
    expect(readF05('draft', scope)?.value).toEqual({ note: 'first', draftRevision: 1 })
    expect(removeF05DraftIfRevision(scope, 1)).toBe(true)
    expect(readF05('draft', scope)).toBeNull()
  })
})

describe('f05Storage draft budget', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('rejects the 21st draft without evicting any existing one', () => {
    activateF05Identity('account-1')
    for (let index = 0; index < F05_MAX_DRAFTS; index += 1) {
      expect(writeF05('draft', { ...scope, objectId: `item-${index}` }, { note: 'x' }).ok).toBe(true)
    }
    const overflow = writeF05('draft', { ...scope, objectId: 'item-overflow' }, { note: 'x' })
    expect(overflow.ok).toBe(false)
    expect(overflow.reason).toBe('quota')
    expect(readF05('draft', { ...scope, objectId: 'item-0' })?.value).toEqual({ note: 'x' })

    // Overwriting an existing draft never consumes a new slot.
    expect(writeF05('draft', { ...scope, objectId: 'item-0' }, { note: 'updated' }).ok).toBe(true)
    expect(readF05('draft', { ...scope, objectId: 'item-0' })?.value).toEqual({ note: 'updated' })
  })
})

describe('f05Storage targeted cleanup', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('clears one team on revocation without touching another Team', () => {
    activateF05Identity('account-1')
    writeF05('draft', { ...scope, teamId: 'team-a', objectId: 'o1' }, { note: 'a' })
    writeF05('draft', { ...scope, teamId: 'team-b', objectId: 'o2' }, { note: 'b' })
    clearF05TeamScope({ accountId: 'account-1', organizationId: 'org-1', teamId: 'team-a' })
    expect(readF05('draft', { ...scope, teamId: 'team-a', objectId: 'o1' })).toBeNull()
    expect(readF05('draft', { ...scope, teamId: 'team-b', objectId: 'o2' })?.value).toEqual({ note: 'b' })
  })

  it('purges legacy keys that cannot prove identity ownership', () => {
    activateF05Identity('account-1')
    localStorage.setItem('cs.f05.draft.v1.leftover', 'stale')
    localStorage.setItem('cs.pref.device.command-recent.v1', '[]')
    localStorage.setItem('crewscope:agent-configuration:agent-9', '{}')
    localStorage.setItem('crewscope:stream:v1:org:team:durable:TEAM', '1')
    localStorage.setItem('cs.pref.device.theme.v1', '{"version":1,"value":"dark"}')
    sessionStorage.setItem('crewscope:coding-target:v1:org:team:p:1', '{}')
    sessionStorage.setItem('crewscope:task-delegation:v1:org:team:p:1', '{}')
    purgeF05LegacyKeys()
    expect(localStorage.getItem('cs.f05.draft.v1.leftover')).toBeNull()
    expect(localStorage.getItem('cs.pref.device.command-recent.v1')).toBeNull()
    expect(localStorage.getItem('crewscope:agent-configuration:agent-9')).toBeNull()
    expect(localStorage.getItem('crewscope:stream:v1:org:team:durable:TEAM')).toBeNull()
    expect(sessionStorage.getItem('crewscope:coding-target:v1:org:team:p:1')).toBeNull()
    expect(sessionStorage.getItem('crewscope:task-delegation:v1:org:team:p:1')).toBeNull()
    // Device preferences survive the purge.
    expect(localStorage.getItem('cs.pref.device.theme.v1')).toBe('{"version":1,"value":"dark"}')
  })

  it('clears live session cursors on sign-out only', () => {
    activateF05Identity('account-1')
    sessionStorage.setItem('crewscope:task-cursor:org:team:task-1', '1')
    sessionStorage.setItem('crewscope:conversation:invocation:org:team:c-1', '1')
    purgeF05LegacyKeys()
    expect(sessionStorage.getItem('crewscope:task-cursor:org:team:task-1')).toBe('1')
    clearF05UserData()
    expect(sessionStorage.getItem('crewscope:task-cursor:org:team:task-1')).toBeNull()
    expect(sessionStorage.getItem('crewscope:conversation:invocation:org:team:c-1')).toBeNull()
  })
})

describe('f05Storage recent and views', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('keeps at most 100 recents and drops entries older than 30 days', () => {
    activateF05Identity('account-1')
    const now = Date.now()
    const items = Array.from({ length: 130 }, (_, index) => ({ kind: 'object' as const, objectType: 'WORK_ITEM', id: `w-${index}`, label: `条目 ${index}`, subtitle: null, route: `/work?workItem=w-${index}`, accessedAt: now }))
    writeF05Recent(scope, items)
    expect(readF05Recent(scope)).toHaveLength(100)
    expect(readF05Recent(scope)[0]?.id).toBe('w-0')

    const stale = [{ kind: 'object' as const, objectType: 'WORK_ITEM', id: 'w-old', label: '旧条目', subtitle: null, route: '/work', accessedAt: now - 31 * 24 * 60 * 60 * 1000 }]
    writeF05Recent(scope, stale)
    expect(readF05Recent(scope)).toHaveLength(0)
  })

  it('expires saved views unused for 180 days', () => {
    activateF05Identity('account-1')
    vi.useFakeTimers()
    vi.setSystemTime(Date.now() - 181 * 24 * 60 * 60 * 1000)
    upsertF05View(scope, { id: 'v-1', name: '我的看板', routeName: 'work', filters: { status: 'IN_PROGRESS' }, sort: null, pinned: false, updatedAt: Date.now() })
    vi.setSystemTime(Date.now() + 181 * 24 * 60 * 60 * 1000)
    vi.useRealTimers()
    expect(readF05Views(scope)).toHaveLength(0)
    upsertF05View(scope, { id: 'v-2', name: '新建', routeName: 'work', filters: {}, sort: null, pinned: true, updatedAt: Date.now() })
    expect(readF05Views(scope).map(view => view.id)).toEqual(['v-2'])
  })
})
