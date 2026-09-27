import { activateF05Identity, clearF05UserData, type F05SavedView } from '../../app/f05Storage'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  buildSavedView,
  captureFilters,
  deleteView,
  filterKeysFor,
  listViews,
  pinView,
  readPinnedProjects,
  resolveEntryView,
  saveView,
  togglePinnedProject,
  todayFilterKeys,
  viewFilters,
  viewQuery,
  workFilterKeys,
} from './savedViews'

const owner = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' }

beforeEach(() => {
  activateF05Identity('account-1')
})

afterEach(() => {
  clearF05UserData()
  localStorage.clear()
  sessionStorage.clear()
})

describe('savedViews serialization', () => {
  it('captures only the committed string filter keys, nothing else from the URL', () => {
    const filters = captureFilters({
      team: 'team-1', project: 'project-9', status: 'IN_PROGRESS', type: ['all'], priority: '',
      workItem: 'item-1', deskRole: 3, sort: 'priority',
    }, workFilterKeys)

    expect(filters).toEqual({ status: 'IN_PROGRESS', sort: 'priority' })
  })

  it('round-trips a view through the frozen record and back to URL filters', () => {
    const view = buildSavedView({
      id: 'view-1', name: '进行中的缺陷', routeName: 'work',
      filters: { status: 'IN_PROGRESS', type: 'DEFECT', sort: 'priority', direction: 'asc' },
    })

    expect(view.sort).toEqual({ field: 'priority', direction: 'asc' })
    // Work no longer owns a direction (the server sort contract fixes it), so a stored one drops
    // on the sanitized read even though the frozen record itself still carries it.
    expect(viewFilters(view)).toEqual({ status: 'IN_PROGRESS', type: 'DEFECT', sort: 'priority' })
    // A record a previous version wrote with non-primitive junk loses only the junk.
    expect(viewFilters({ ...view, filters: { ...view.filters, status: ['IN_PROGRESS'], priority: null, view: true } }))
      .toEqual({ type: 'DEFECT', sort: 'priority', view: 'true' })
  })
})

describe('savedViews entry priority', () => {
  const views: F05SavedView[] = [
    buildSavedView({ id: 'v-plain', name: '普通视图', routeName: 'work', filters: { status: 'OPEN' } }),
    { ...buildSavedView({ id: 'v-pin', name: '置顶视图', routeName: 'work', filters: { status: 'IN_PROGRESS' } }), pinned: true },
  ]

  it('never lets a stored preference override an explicit URL', () => {
    expect(resolveEntryView({ team: 'team-1', status: 'OPEN' }, workFilterKeys, views)).toBeNull()
    expect(resolveEntryView({ team: 'team-1', view: 'board' }, workFilterKeys, views)).toBeNull()
  })

  it('applies the first pinned view only when the URL carries no filter key of its own', () => {
    expect(resolveEntryView({ team: 'team-1' }, workFilterKeys, views)?.id).toBe('v-pin')
    expect(resolveEntryView({ team: 'team-1' }, workFilterKeys, [views[0]!])).toBeNull()
  })

  it('treats canonical default values as no filter at all, but a non-default value stays explicit', () => {
    // Work canonicalizes its whole filter set into the URL, so a reload re-enters with the page
    // defaults written out — the stored default view must still apply behind them.
    const defaults = { view: 'list', status: 'all', type: 'all', priority: 'all', sort: 'updatedAt' }
    expect(resolveEntryView({ view: 'list', status: 'all', sort: 'updatedAt' }, workFilterKeys, views, defaults)?.id).toBe('v-pin')
    expect(resolveEntryView({ status: 'OPEN', type: 'all' }, workFilterKeys, views, defaults)).toBeNull()
    // Without defaults (today writes no defaulted keys), any value stays an explicit link.
    expect(resolveEntryView({ deskProject: 'all' }, todayFilterKeys, views)).toBeNull()
  })

  it('replaces the filter keys wholesale and keeps every other coordinate', () => {
    const applied = viewQuery({ team: 'team-1', project: 'project-9', workItem: 'item-1', status: 'OPEN', sort: 'title' }, views[1], workFilterKeys)
    expect(applied).toEqual({ team: 'team-1', project: 'project-9', workItem: 'item-1', status: 'IN_PROGRESS' })

    // 「默认」 resets the page to its own defaults by dropping every filter key.
    expect(viewQuery({ team: 'team-1', status: 'OPEN', type: 'DEFECT' }, null, workFilterKeys)).toEqual({ team: 'team-1' })
  })
})

describe('savedViews storage', () => {
  it('lists, pins and deletes views per page under one identity', () => {
    saveView(owner, buildSavedView({ id: 'w-1', name: '进行中', routeName: 'work', filters: { status: 'IN_PROGRESS' } }))
    saveView(owner, buildSavedView({ id: 't-1', name: '今日按角色', routeName: 'today', filters: { deskGroup: 'role' } }))

    expect(listViews(owner, 'work').map(view => view.id)).toEqual(['w-1'])
    expect(listViews(owner, 'today').map(view => view.id)).toEqual(['t-1'])

    expect(pinView(owner, 'w-1', true).ok).toBe(true)
    const pinned = listViews(owner, 'work')
    expect(pinned[0]!.pinned).toBe(true)
    // The today view never rode along into the work switcher's pinning.
    expect(listViews(owner, 'today')[0]!.pinned).toBe(false)

    // Pinning is exclusive per page: a second pinned view demotes the first, so the entry default
    // never drifts to whichever view was updated most recently.
    saveView(owner, buildSavedView({ id: 'w-2', name: '缺陷', routeName: 'work', filters: { type: 'DEFECT' } }))
    expect(pinView(owner, 'w-2', true).ok).toBe(true)
    const switched = listViews(owner, 'work')
    expect(switched.find(view => view.id === 'w-1')!.pinned).toBe(false)
    expect(switched.find(view => view.id === 'w-2')!.pinned).toBe(true)
    expect(deleteView(owner, 'w-2').ok).toBe(true)

    expect(deleteView(owner, 'w-1').ok).toBe(true)
    expect(listViews(owner, 'work')).toEqual([])
    expect(listViews(owner, 'today')).toHaveLength(1)
  })

  it('seals the namespace on sign-out: another account never sees the views', () => {
    saveView(owner, buildSavedView({ id: 'w-1', name: '进行中', routeName: 'work', filters: { status: 'IN_PROGRESS' } }))
    clearF05UserData()

    expect(listViews(owner, 'work')).toEqual([])
    expect(readPinnedProjects(owner)).toEqual([])
  })
})

describe('pinned projects', () => {
  it('pins, deduplicates and unpins through local storage only', () => {
    expect(togglePinnedProject(owner, 'project-1', true).result.ok).toBe(true)
    expect(togglePinnedProject(owner, 'project-2', true).result.ok).toBe(true)
    // Pinning the same project twice is one entry, refreshed to the front.
    togglePinnedProject(owner, 'project-1', true)
    expect(readPinnedProjects(owner).map(pin => pin.projectId)).toEqual(['project-1', 'project-2'])

    const removed = togglePinnedProject(owner, 'project-1', false)
    expect(removed.pins.map(pin => pin.projectId)).toEqual(['project-2'])
    expect(readPinnedProjects(owner).map(pin => pin.projectId)).toEqual(['project-2'])
  })

  it('keeps today and work filters under distinct key sets', () => {
    expect(filterKeysFor('today')).not.toContain('status')
    expect(filterKeysFor('work')).toContain('view')
  })
})
