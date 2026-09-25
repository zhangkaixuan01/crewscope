import { flushPromises, mount } from '@vue/test-utils'
import { CrewScopeApiError } from '../../api/client'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { activateF05Identity, clearF05UserData } from '../../app/f05Storage'
import type { CodingGateway } from '../../domains/coding/gateway'
import { CODING_STORE, createCodingStore } from '../../domains/coding/store'
import type { CodingScope } from '../../domains/coding/types'
import { fixtureIds } from '../../test/scopeFixtures'
import CodingTargetFormSection from './CodingTargetFormSection.vue'

const scope: CodingScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  projectId: fixtureIds.projectCrewScope,
}
const workItemId = '00000000-0000-4000-8000-00000000f301'
const bindingId = '00000000-0000-4000-8000-00000000f302'

describe('CodingTargetFormSection', () => {
  beforeEach(() => { sessionStorage.clear(); clearF05UserData(); localStorage.clear(); activateF05Identity('00000000-0000-0000-0000-000000000901') })

  it('applies server defaults, requires Ref Preflight and emits the exact Profile reference', async () => {
    const gateway = fixtureGateway()
    const wrapper = mountSection(gateway)
    await flushPromises()

    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe(bindingId)
    expect((wrapper.get('input[placeholder="main"]').element as HTMLInputElement).value).toBe('main')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('.')
    expect(latestChange(wrapper)?.[1]).toBe(false)

    await wrapper.findAll('button').find(button => button.text().includes('验证 Ref'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Preflight 通过')
    expect(latestChange(wrapper)).toEqual([{
      repositoryBindingId: bindingId,
      baselineRef: 'main',
      allowedPaths: ['.'],
      buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64) },
    }, true])
    expect(gateway.preflightCodingTarget).toHaveBeenCalledWith(
      scope, workItemId, bindingId, 'main', expect.any(AbortSignal),
    )

    await wrapper.get('input[type="checkbox"]').setValue(false)
    expect(latestChange(wrapper)).toEqual([null, true])
  })

  it('restores a Scope-partitioned draft and fails closed for an invalid Ref', async () => {
    const gateway = fixtureGateway()
    gateway.preflightCodingTarget = vi.fn(async () => {
      throw new CrewScopeApiError(422, {
        code: 'repository_ref_invalid', message: 'Ref 不存在或不可解析', correlationId: 'safe',
        retryable: false, currentVersion: null, details: {},
      })
    })
    const first = mountSection(gateway)
    await flushPromises()
    await first.get('textarea').setValue('src/main\npom.xml')
    await first.get('input[placeholder="main"]').setValue('missing')
    first.unmount()

    const restored = mountSection(gateway)
    await flushPromises()
    expect((restored.get('textarea').element as HTMLTextAreaElement).value).toBe('src/main\npom.xml')
    expect((restored.get('input[placeholder="main"]').element as HTMLInputElement).value).toBe('missing')
    await restored.findAll('button').find(button => button.text().includes('验证 Ref'))!.trigger('click')
    await flushPromises()

    expect(restored.get('[role="alert"]').text()).toContain('Ref 不存在')
    expect(latestChange(restored)?.[1]).toBe(false)
  })

  it('offers the Repository Settings recovery path when no ACTIVE Binding exists', async () => {
    const gateway = fixtureGateway()
    gateway.listRepositoryBindings = vi.fn(async () => [])
    const wrapper = mountSection(gateway)
    await flushPromises()

    expect(wrapper.text()).toContain('没有 ACTIVE RepositoryBinding')
    expect(wrapper.text()).toContain('前往仓库设置')
    expect(latestChange(wrapper)?.[1]).toBe(false)
  })

  it('starts from the project defaults and drops the provenance chip once changed', async () => {
    const gateway = fixtureGateway()
    gateway.listRepositoryBindings = vi.fn(async () => [
      ...defaultBindings(),
      { ...defaultBindings()[0]!, id: secondaryBindingId, repositoryKey: 'crewscope-web', defaultBranch: 'develop' },
    ])
    const wrapper = mountSection(gateway, {
      repositoryBindingId: secondaryBindingId,
      branch: 'release/2026-09',
      buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64) },
    })
    await flushPromises()

    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe(secondaryBindingId)
    expect((wrapper.get('input[placeholder="main"]').element as HTMLInputElement).value).toBe('release/2026-09')
    expect(provenanceChips(wrapper)).toHaveLength(3)

    await wrapper.get('select').setValue(bindingId)
    await flushPromises()
    // Changing the repository drops its two provenance chips and re-baselines the ref; the
    // BuildProfile chip stays because that value still equals the project default.
    expect(provenanceChips(wrapper)).toHaveLength(1)
    expect((wrapper.get('input[placeholder="main"]').element as HTMLInputElement).value).toBe('main')
  })
})

const secondaryBindingId = '00000000-0000-4000-8000-00000000f309'

function mountSection(gateway: CodingGateway, initial?: { repositoryBindingId: string | null, branch: string | null, buildProfile: { key: string, version: number, profileHash: string } | null }) {
  return mount(CodingTargetFormSection, {
    props: { scope, workItemId, initial: initial ?? null },
    global: {
      provide: {
        [CODING_STORE as symbol]: createCodingStore(gateway),
        [AUTH_PRINCIPAL as symbol]: { ...fixturePrincipal },
      },
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

const fixturePrincipal = {
  id: fixtureIds.principal,
  accountId: '00000000-0000-0000-0000-000000000901',
  displayName: '张凯旋',
  organizationId: fixtureIds.organization,
  role: 'Team Member',
  permissions: new Set<string>(),
}

function latestChange(wrapper: ReturnType<typeof mountSection>) {
  return wrapper.emitted('change')?.at(-1)
}

function provenanceChips(wrapper: ReturnType<typeof mountSection>) {
  return wrapper.findAll('small')
    .filter(node => node.text().includes('项目默认'))
}

function defaultBindings() {
  return [{
    id: bindingId,
    organizationId: scope.organizationId,
    teamId: scope.teamId,
    workspaceId: fixtureIds.workspacePlatform,
    projectId: scope.projectId,
    kind: 'LOCAL_MANAGED',
    repositoryKey: 'crewscope-java',
    defaultBranch: 'main',
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-08-20T01:00:00Z',
    createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-08-20T01:00:00Z',
    updatedByPrincipalId: fixtureIds.principal,
  }]
}

function fixtureGateway(): CodingGateway {
  return {
    listRepositoryCatalog: vi.fn(async () => []),
    listRepositoryBindings: vi.fn(async () => defaultBindings()),
    getRepositoryBinding: vi.fn(async () => { throw new Error('unused') }),
    createRepositoryBinding: vi.fn(async () => { throw new Error('unused') }),
    preflightRepositoryDraft: vi.fn(async () => { throw new Error('unused') }),
    preflightRepositoryBinding: vi.fn(async () => { throw new Error('unused') }),
    transitionRepositoryBinding: vi.fn(async () => { throw new Error('unused') }),
    listBuildProfiles: vi.fn(async () => [{
      key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64),
      buildTool: 'MAVEN', javaRelease: 17, commandKinds: ['COMPILE', 'TEST', 'VERIFY'],
    }]),
    preflightCodingTarget: vi.fn(async (_scope, _workItemId, _bindingId, baselineRef) => ({
      ready: true, repositoryKey: 'crewscope-java', baselineRef, baselineCommit: 'b'.repeat(40),
    })),
    getCurrentAttempt: vi.fn(async () => { throw new Error('unused') }),
    listAttempts: vi.fn(async () => { throw new Error('unused') }),
    getAttempt: vi.fn(async () => { throw new Error('unused') }),
    listCommands: vi.fn(async () => { throw new Error('unused') }),
    listTestEvidence: vi.fn(async () => { throw new Error('unused') }),
    readPatchPage: vi.fn(async () => { throw new Error('unused') }),
    readCommandLogPage: vi.fn(async () => { throw new Error('unused') }),
    readTestReportPage: vi.fn(async () => { throw new Error('unused') }),
  }
}
