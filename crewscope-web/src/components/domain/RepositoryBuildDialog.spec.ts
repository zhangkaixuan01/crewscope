import { mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import RepositoryBuildDialog from './RepositoryBuildDialog.vue'
import type { CodingStore } from '../../domains/coding/store'
import type { CodingResource } from '../../domains/coding/store'
import type { RepositoryBinding } from '../../domains/coding/types'
import { CODING_STORE } from '../../domains/coding/store'
import type { RepositoryBuildInput } from '../../domains/knowledge/types'
import { SCOPE_STORE } from '../../domains/scope/store'
import type { ScopeStore } from '../../domains/scope/store'
import type { WorkProjectSummary } from '../../domains/scope/types'

describe('RepositoryBuildDialog', () => {
  it('cascades from the selected project to its ACTIVE bindings', async () => {
    const harness = mounted()
    const selects = harness.wrapper.findAll('select')
    expect(selects[1]!.text()).toContain('请先选择项目')

    await selects[0]!.setValue('project-1')
    expect(harness.codingCalls).toEqual([{ organizationId: 'org-1', teamId: 'team-1', projectId: 'project-1' }])

    const options = harness.wrapper.findAll('select')[1]!.text()
    expect(options).toContain('crewscope/backend · main')
    // 非 ACTIVE 的绑定不进入选项。
    expect(options).not.toContain('crewscope/legacy')
  })

  it('rejects commits that are neither 40 nor 64 hex characters before emitting', async () => {
    const harness = mounted()
    await harness.wrapper.findAll('select')[0]!.setValue('project-1')
    await harness.wrapper.findAll('select')[1]!.setValue('binding-1')
    const commit = harness.wrapper.get('input')

    await commit.setValue('abc123')
    await harness.wrapper.get('form').trigger('submit')
    expect(harness.wrapper.text()).toContain('提交需为 40 或 64 位十六进制字符。')
    expect(harness.wrapper.emitted('submit')).toBeUndefined()

    await commit.setValue('b'.repeat(64))
    await harness.wrapper.get('form').trigger('submit')
    expect(harness.wrapper.emitted('submit')?.[0]).toEqual([{
      projectId: 'project-1',
      bindingId: 'binding-1',
      commit: 'b'.repeat(64),
    } satisfies RepositoryBuildInput])
  })

  it('words the binding miss (404) and the retired binding (422) instead of the raw envelope', async () => {
    const miss = mounted({ errorMessage: 'binding not found', errorCode: 'aggregate_not_found', errorStatus: 404 })
    expect(miss.wrapper.text()).toContain('仓库绑定不存在或不属于该项目。')

    const retired = mounted({ errorMessage: 'invalid value', errorCode: 'invalid_value', errorStatus: 422 })
    expect(retired.wrapper.text()).toContain('所选仓库绑定已停用。')

    const other = mounted({ errorMessage: '索引服务暂时不可用', errorCode: null, errorStatus: 503 })
    expect(other.wrapper.text()).toContain('索引服务暂时不可用')
  })

  it('falls back to an empty-project notice when the Team has no projects', async () => {
    const harness = mounted()
    harness.scopeState.projects = []
    await nextTick()
    expect(harness.wrapper.get('select').text()).toContain('当前 Team 暂无项目')
  })

  it('closes through the footer cancel control', async () => {
    const harness = mounted()
    await harness.wrapper.get('footer button').trigger('click')
    expect(harness.wrapper.emitted('close')).toHaveLength(1)
  })
})

function mounted(commandOverrides: { errorMessage?: string, errorCode?: string | null, errorStatus?: number | null } = {}) {
  const scopeState = reactive({ projects: [project()] })
  const scope = { state: scopeState } as unknown as ScopeStore
  const codingCalls: Array<{ organizationId: string, teamId: string, projectId: string }> = []
  const coding = {
    state: reactive({
      repositories: { phase: 'ready', value: [binding({ id: 'binding-1' }), binding({ id: 'binding-2', repositoryKey: 'crewscope/legacy', status: 'RETIRED' })], errorMessage: null, errorStatus: null } as CodingResource<RepositoryBinding[]>,
    }),
    async loadRepositories(codingScope: { organizationId: string, teamId: string, projectId: string }) {
      codingCalls.push({ organizationId: codingScope.organizationId, teamId: codingScope.teamId, projectId: codingScope.projectId })
    },
  } as unknown as CodingStore
  const wrapper = mount(RepositoryBuildDialog, {
    props: {
      scope: { organizationId: 'org-1', teamId: 'team-1' },
      submitting: false,
      errorMessage: commandOverrides.errorMessage ?? null,
      errorCode: commandOverrides.errorCode ?? null,
      errorStatus: commandOverrides.errorStatus ?? null,
    },
    global: { provide: { [SCOPE_STORE as symbol]: scope, [CODING_STORE as symbol]: coding } },
  })
  return { wrapper, scopeState, codingCalls }
}

function project(overrides: Partial<WorkProjectSummary> = {}): WorkProjectSummary {
  return {
    id: 'project-1', organizationId: 'org-1', teamId: 'team-1', workspaceId: 'workspace-1',
    key: 'crewscope', name: 'CrewScope 平台', status: 'ACTIVE', version: 1,
    createdAt: '2026-10-01T01:00:00Z', createdByPrincipalId: 'principal-1',
    updatedAt: '2026-10-02T01:00:00Z', updatedByPrincipalId: 'principal-1',
    ...overrides,
  }
}

function binding(overrides: Partial<RepositoryBinding> = {}): RepositoryBinding {
  return {
    id: 'binding-1', organizationId: 'org-1', teamId: 'team-1', workspaceId: 'workspace-1', projectId: 'project-1',
    kind: 'GITHUB', repositoryKey: 'crewscope/backend', defaultBranch: 'main', status: 'ACTIVE', version: 3,
    createdAt: '2026-10-01T01:00:00Z', createdByPrincipalId: 'principal-1',
    updatedAt: '2026-10-02T01:00:00Z', updatedByPrincipalId: 'principal-1',
    ...overrides,
  }
}
