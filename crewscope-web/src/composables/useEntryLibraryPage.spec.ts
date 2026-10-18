import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, useRoute, type Router } from 'vue-router'
import type { Mock } from 'vitest'
import { oneOf, revisionQuery, uuidQuery } from '../utils/queryParams'
import { usePageRequestScope } from './usePageRequestScope'
import { useEntryLibraryPage, type LibraryCommandPhase } from './useEntryLibraryPage'

const SKILL_ID = '11111111-2222-4333-8444-555555555555'

/** vue-router's replace resolves on its own microtask chain; a macrotask drains it. */
const settleRoute = async (): Promise<void> => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

type Loads = {
  catalog: Mock<(force: boolean) => Promise<boolean>>
  detail: Mock<(id: string, force?: boolean) => Promise<boolean>>
  versions: Mock<(id: string, more?: boolean, force?: boolean) => Promise<boolean>>
  version: Mock<(id: string, revision: number) => Promise<boolean>>
  effective: Mock<(id: string, force?: boolean) => Promise<boolean>>
  clearCommand: Mock<() => void>
  activate: Mock<(organizationId: string, teamId: string | null) => void>
  reset: Mock<() => void>
}

interface Harness {
  router: Router
  library: ReturnType<typeof useEntryLibraryPage<string>>
  loads: Loads
  command: { state: LibraryCommandPhase }
  setOnline: (value: boolean) => void
  setReady: (value: boolean) => void
  setTeamId: (value: string | null) => void
}

/**
 * Mounts the composable against a real memory router. Skills-style wiring by default
 * (effectiveOnDraftTab: true); pass options overrides to flip to knowledge-style.
 */
async function mountLibrary(overrides: Record<string, unknown> = {}): Promise<Harness> {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/library/:name?', name: 'library', component: { template: '<div />' } }] })
  await router.push('/library')
  const loads: Loads = {
    catalog: vi.fn(() => Promise.resolve(true)),
    detail: vi.fn(() => Promise.resolve(true)),
    versions: vi.fn(() => Promise.resolve(true)),
    version: vi.fn(() => Promise.resolve(true)),
    effective: vi.fn(() => Promise.resolve(true)),
    clearCommand: vi.fn(),
    activate: vi.fn(),
    reset: vi.fn(),
  }
  const command = { state: reactive<LibraryCommandPhase>({ phase: 'idle', retryable: true }) }
  const online = ref(true)
  const ready = ref(false)
  const phaseRef = ref<'pending' | 'ready'>('pending')
  const teamId = ref<string | null>('team-1')
  let library!: ReturnType<typeof useEntryLibraryPage<string>>
  mount(defineComponent({
    setup() {
      const route = useRoute()
      library = useEntryLibraryPage<string>({
        pageRequests: usePageRequestScope(),
        catalogSources: () => [
          phaseRef.value,
          ready.value ? 'org-1' : null,
          teamId.value,
          oneOf(route.query.status, ['ALL', 'PUBLISHED'] as const, 'ALL'),
        ] as const,
        scopePresent: () => ready.value,
        activateScope: () => loads.activate('org-1', teamId.value),
        onCatalogReset: loads.reset,
        queryKeys: { selected: 'skill' },
        filters: [{ key: 'status', value: () => oneOf(route.query.status, ['ALL', 'PUBLISHED'] as const, 'ALL'), fallback: 'ALL' }],
        scopeReady: () => ready.value,
        teamId: () => teamId.value,
        online: () => online.value,
        selectedId: () => uuidQuery(route.query.skill),
        selectedRevision: () => revisionQuery(route.query.revision),
        tab: () => oneOf(route.query.tab, ['draft', 'versions'] as const, 'draft'),
        clearCommand: loads.clearCommand,
        commandPhase: () => ({ phase: command.state.phase, retryable: command.state.retryable }),
        loadCatalog: (force: boolean) => loads.catalog(force),
        loadDetail: (id: string, force?: boolean) => loads.detail(id, force),
        loadVersions: (id: string, more?: boolean, force?: boolean) => loads.versions(id, more, force),
        loadVersion: (id: string, revision: number) => loads.version(id, revision),
        loadEffective: (id: string, force?: boolean) => loads.effective(id, force),
        effectiveOnDraftTab: true,
        ...overrides,
      })
      return () => h('div')
    },
  }), { global: { plugins: [router] } })
  return {
    router,
    library,
    loads,
    command,
    setOnline: value => { online.value = value },
    setReady: value => {
      ready.value = value
      phaseRef.value = value ? 'ready' : 'pending'
    },
    setTeamId: value => { teamId.value = value },
  }
}

describe('useEntryLibraryPage selection lifecycle', () => {
  it('activates the catalog on readiness and reloads on filter or team changes', async () => {
    const harness = await mountLibrary()
    expect(harness.loads.catalog).not.toHaveBeenCalled()
    harness.setReady(true)
    await nextTick()
    expect(harness.loads.activate).toHaveBeenCalledWith('org-1', 'team-1')
    expect(harness.loads.catalog).toHaveBeenCalledWith(true)
    expect(harness.loads.reset).toHaveBeenCalled()
    expect(harness.loads.clearCommand).toHaveBeenCalled()
    await harness.router.push('/library?status=PUBLISHED')
    await nextTick()
    expect(harness.loads.catalog).toHaveBeenCalledTimes(2)
    harness.setTeamId('team-2')
    await nextTick()
    expect(harness.loads.activate).toHaveBeenLastCalledWith('org-1', 'team-2')
    expect(harness.loads.catalog).toHaveBeenCalledTimes(3)
  })

  it('loads the effective version on the draft tab for skills (editor diff baseline)', async () => {
    const harness = await mountLibrary()
    harness.setReady(true)
    await harness.router.push(`/library?skill=${SKILL_ID}`)
    await nextTick()
    expect(harness.loads.detail).toHaveBeenCalledWith(SKILL_ID, undefined)
    expect(harness.loads.effective).toHaveBeenCalledWith(SKILL_ID, undefined)
    expect(harness.loads.versions).not.toHaveBeenCalled()
    expect(harness.loads.clearCommand).toHaveBeenCalled()
  })

  it('knowledge wiring defers the effective load to the versions tab', async () => {
    const harness = await mountLibrary({ effectiveOnDraftTab: false })
    harness.setReady(true)
    await harness.router.push(`/library?skill=${SKILL_ID}`)
    await nextTick()
    expect(harness.loads.detail).toHaveBeenCalledTimes(1)
    expect(harness.loads.effective).not.toHaveBeenCalled()
    await harness.router.push(`/library?skill=${SKILL_ID}&tab=versions&revision=3`)
    await nextTick()
    expect(harness.loads.versions).toHaveBeenCalledWith(SKILL_ID, undefined, undefined)
    expect(harness.loads.effective).toHaveBeenCalledWith(SKILL_ID, undefined)
    expect(harness.loads.version).toHaveBeenCalledWith(SKILL_ID, 3)
  })

  it('clears command state before the readiness gate and reloads on a team switch', async () => {
    const harness = await mountLibrary()
    expect(harness.loads.clearCommand).toHaveBeenCalledTimes(1)
    harness.setReady(true)
    await harness.router.push(`/library?skill=${SKILL_ID}`)
    await nextTick()
    expect(harness.loads.detail).toHaveBeenCalledTimes(1)
    harness.setTeamId('team-2')
    await nextTick()
    expect(harness.loads.detail).toHaveBeenCalledTimes(2)
  })
})

describe('useEntryLibraryPage idempotent command runner', () => {
  async function runScript(harness: Harness, script: (key: string) => Promise<boolean>): Promise<string> {
    let key = ''
    await harness.library.runCommand('publish', SKILL_ID, async k => { key = k; return script(k) })
    return key
  }

  it('keeps the key for a retryable failure, drops it after success and refreshes every fact surface', async () => {
    const harness = await mountLibrary()
    harness.setReady(true)
    await harness.router.push(`/library?skill=${SKILL_ID}`)
    await nextTick()
    harness.command.state.phase = 'error'
    harness.command.state.retryable = true
    const failed = await runScript(harness, () => Promise.resolve(false))
    const retried = await runScript(harness, () => Promise.resolve(false))
    // An unchanged resubmit after a retryable failure is the same logical request.
    expect(retried).toBe(failed)
    harness.command.state.retryable = false
    const confirmed = await runScript(harness, () => Promise.resolve(true))
    expect(harness.loads.catalog).toHaveBeenCalledWith(true)
    expect(harness.loads.detail).toHaveBeenCalledWith(SKILL_ID, true)
    expect(harness.loads.versions).toHaveBeenCalledWith(SKILL_ID, false, true)
    expect(harness.loads.effective).toHaveBeenCalledWith(SKILL_ID, true)
    const after = await runScript(harness, () => Promise.resolve(false))
    // Success consumed the attempt; the next submit mints a fresh key.
    expect(after).not.toBe(confirmed)
  })

  it('drops the key and reloads catalog+detail on a conflict', async () => {
    const harness = await mountLibrary()
    harness.command.state.phase = 'conflict'
    const conflictKey = await runScript(harness, () => Promise.resolve(false))
    expect(harness.loads.catalog).toHaveBeenCalledWith(true)
    expect(harness.loads.detail).toHaveBeenCalledWith(SKILL_ID, true)
    harness.command.state.phase = 'error'
    const retried = await runScript(harness, () => Promise.resolve(false))
    // The conflict recovery dropped the key after the failed send, so the next
    // submit is a new logical request.
    expect(retried).not.toBe(conflictKey)
  })

  it('stays local-only while offline: no key minted, nothing sent', async () => {
    const harness = await mountLibrary()
    harness.setOnline(false)
    const run = vi.fn(() => Promise.resolve(true))
    const result = await harness.library.runCommand('publish', SKILL_ID, run)
    expect(result).toBe(false)
    expect(run).not.toHaveBeenCalled()
    expect(harness.loads.catalog).not.toHaveBeenCalled()
  })
})

describe('useEntryLibraryPage query coordination', () => {
  it('collapses invalid filters and orphaned coordinates out of the URL', async () => {
    const harness = await mountLibrary()
    harness.setReady(true)
    await harness.router.push('/library?status=GARBAGE&skill=not-a-uuid&revision=0&tab=versions')
    await settleRoute()
    // status GARBAGE normalizes to the ALL fallback (deleted); the unparseable skill id and
    // revision=0 are orphans (deleted); tab=versions is legitimate and stays.
    expect(harness.router.currentRoute.value.query).toEqual({ tab: 'versions' })
    expect(harness.loads.detail).not.toHaveBeenCalled()
  })

  it('select, close, filter and revision helpers rewrite only the coordinates they own', async () => {
    const harness = await mountLibrary()
    harness.setReady(true)
    await harness.router.push('/library?status=PUBLISHED')
    await settleRoute()
    harness.library.select(SKILL_ID)
    await settleRoute()
    expect(harness.router.currentRoute.value.query).toEqual({ status: 'PUBLISHED', skill: SKILL_ID })
    harness.library.selectRevision(2)
    await settleRoute()
    expect(harness.router.currentRoute.value.query).toEqual({ status: 'PUBLISHED', skill: SKILL_ID, revision: '2' })
    harness.library.changeTab('versions')
    await settleRoute()
    expect(harness.router.currentRoute.value.query).toEqual({ status: 'PUBLISHED', skill: SKILL_ID, revision: '2', tab: 'versions' })
    harness.library.replaceFilter('status', 'ALL', 'ALL')
    await settleRoute()
    // Changing a filter abandons the selection (both pages delete selected+revision with it).
    expect(harness.router.currentRoute.value.query).toEqual({ tab: 'versions' })
    harness.library.closeDetail()
    await settleRoute()
    expect(harness.router.currentRoute.value.query).toEqual({ tab: 'versions' })
  })

  it('pre-loads the versions panel optimistically when entering the versions tab', async () => {
    const onEnterVersionsTab = vi.fn()
    const harness = await mountLibrary({ onEnterVersionsTab })
    harness.setReady(true)
    await harness.router.push(`/library?skill=${SKILL_ID}`)
    await nextTick()
    harness.library.changeTab('versions')
    await nextTick()
    expect(onEnterVersionsTab).toHaveBeenCalledWith(SKILL_ID)
  })

  it('short-circuits reload while offline', async () => {
    const harness = await mountLibrary()
    harness.setReady(true)
    await nextTick()
    const readinessLoads = harness.loads.catalog.mock.calls.length
    harness.setOnline(false)
    await harness.library.reload()
    expect(harness.loads.catalog.mock.calls.length).toBe(readinessLoads)
    harness.setOnline(true)
    const current = await harness.library.reload()
    expect(current).toBe(true)
    expect(harness.loads.catalog.mock.calls.length).toBe(readinessLoads + 1)
    expect(harness.loads.catalog).toHaveBeenLastCalledWith(true)
  })
})
