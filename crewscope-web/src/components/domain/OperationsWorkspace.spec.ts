import { flushPromises, mount } from '@vue/test-utils'
import OperationsWorkspace from './OperationsWorkspace.vue'
import type { AdministratorDiagnostics, OperationsHealthSummary, ProjectionDiagnostic, RecoveryCandidate } from '../../domains/teamops/types'

const health: OperationsHealthSummary = {
  observedAt: '2026-08-27T08:30:00Z', health: 'DEGRADED',
  components: ['PROJECTION', 'OUTBOX', 'DEAD_LETTER', 'CURSOR', 'NOTIFICATION'].map((component, index) => ({
    component: component as OperationsHealthSummary['components'][number]['component'], health: index ? 'HEALTHY' : 'DEGRADED',
    backlog: index ? 0 : 4, inFlight: 0, failures: 0, affected: index ? 0 : 2, oldestOutstandingAgeSeconds: index ? 0 : 12, stale: false,
  })),
}
const projection: ProjectionDiagnostic = {
  projectionName: 'team-activity', definitionVersion: 2, activeGeneration: 4, pointerVersion: 7, activeGenerationVersion: 9,
  shadowGeneration: 5, shadowStatus: 'VALIDATING', shadowGenerationVersion: 3, rebuildJobId: uuid(1), rebuildJobVersion: 2,
  lagSeconds: 4, gapCount: 0, deadLetterCount: 1, latestFailureCode: null,
  startConfirmation: 'START REBUILD team-activity', validateConfirmation: 'VALIDATE team-activity 5',
  switchConfirmation: 'SWITCH team-activity 5', cancelConfirmation: 'CANCEL team-activity 5', failConfirmation: 'FAIL team-activity 5',
}
const recovery: RecoveryCandidate = {
  type: 'OUTBOX_DEAD_LETTER', action: 'REPLAY_OUTBOX_DEAD_LETTER', outboxEventId: uuid(2), domainEventId: uuid(3),
  expectedVersion: 6, referenceHash: 'a'.repeat(64), confirmation: `REPLAY OUTBOX ${uuid(2)}:6`,
}
const diagnostics: AdministratorDiagnostics = { summary: health, projections: [projection], recoveryCandidates: [recovery] }

describe('OperationsWorkspace', () => {
  it('keeps member health low-cardinality and hides all administrator diagnostics', () => {
    const wrapper = mountWorkspace({ canManage: false, diagnostics })

    expect(wrapper.text()).toContain('Projection')
    expect(wrapper.text()).toContain('Notification')
    expect(wrapper.text()).toContain('一键演示证据入口')
    expect(wrapper.text()).not.toContain('Projection 与恢复管理')
    expect(wrapper.text()).not.toContain(recovery.referenceHash.slice(0, 12))
    expect(wrapper.text()).not.toContain(projection.startConfirmation)
  })

  it('requires the exact server phrase, traps focus and emits an explicit projection coordinate', async () => {
    const wrapper = mountWorkspace({}, true)
    const validate = wrapper.findAll('button').find(button => button.text() === '验证')!
    ;(validate.element as HTMLButtonElement).focus()
    await validate.trigger('click')

    const dialog = wrapper.get('[role="dialog"]')
    expect(document.activeElement).toBe(dialog.get('h2').element)
    const confirm = dialog.get('input')
    await confirm.setValue('wrong')
    expect(dialog.findAll('button').at(-1)!.attributes('disabled')).toBeDefined()
    await confirm.setValue(projection.validateConfirmation!)
    await dialog.findAll('button').at(-1)!.trigger('click')

    expect(wrapper.emitted('projectionCommand')?.[0]?.[0]).toEqual({
      operation: 'validate', projectionName: 'team-activity', generation: 5,
      body: { expectedDefinitionVersion: 2, rebuildJobId: uuid(1), expectedGenerationVersion: 3, expectedJobVersion: 2, confirmation: projection.validateConfirmation },
    })
    expect(wrapper.emitted('projectionCommand')?.[0]?.[1]).toMatch(/^[0-9a-f-]{36}$/)

    await wrapper.setProps({ command: {
      phase: 'error', operation: 'projection-validate', targetId: 'team-activity', receipt: null,
      error: { kind: 'offline', message: '网络中断', status: 0, retryable: true, currentVersion: null },
    } })
    await dialog.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.emitted('projectionCommand')?.[1]?.[1]).toBe(wrapper.emitted('projectionCommand')?.[0]?.[1])

    await dialog.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(document.activeElement).toBe(validate.element)
    wrapper.unmount()
  })

  it('sends the diagnostics candidate only through the recovery event after strong confirmation', async () => {
    const wrapper = mountWorkspace()
    await wrapper.findAll('button').find(button => button.text().includes('执行恢复'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    await dialog.get('input').setValue(recovery.confirmation)
    await dialog.findAll('button').at(-1)!.trigger('click')

    expect(wrapper.emitted('recover')?.[0]?.slice(0, 2)).toEqual([recovery, recovery.confirmation])
  })

  it('disables mutations offline and preserves a stale summary after refresh failure', () => {
    const wrapper = mountWorkspace({ online: false, phase: 'error', error: { kind: 'server', message: '刷新失败', status: 503, retryable: true, currentVersion: null } })
    expect(wrapper.text()).toContain('暂停自动刷新')
    expect(wrapper.text()).toContain('保留上次健康事实')
    expect(wrapper.findAll('button').find(button => button.text().includes('影子重建'))!.attributes('disabled')).toBeDefined()
  })

  it.each([
    ['切换', 'switch', projection.switchConfirmation, {
      expectedDefinitionVersion: 2, previousActiveGeneration: 4, rebuildJobId: uuid(1), expectedPointerVersion: 7,
      expectedPreviousGenerationVersion: 9, expectedTargetGenerationVersion: 3, expectedJobVersion: 2,
      confirmation: projection.switchConfirmation,
    }],
    ['取消', 'cancel', projection.cancelConfirmation, {
      expectedGenerationVersion: 3, expectedJobVersion: 2, confirmation: projection.cancelConfirmation,
    }],
  ] as const)('emits the exact %s generation command body', async (label, operation, phrase, body) => {
    const wrapper = mountWorkspace()
    await wrapper.findAll('button').find(button => button.text() === label)!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    await dialog.get('input').setValue(phrase!)
    await dialog.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.emitted('projectionCommand')?.[0]?.[0]).toEqual({
      operation, projectionName: 'team-activity', generation: 5, rebuildJobId: operation === 'cancel' ? uuid(1) : undefined, body,
    })
  })

  it('starts a new shadow generation only from the active pointer coordinate', async () => {
    const startable: ProjectionDiagnostic = {
      ...projection, shadowGeneration: null, shadowStatus: null, shadowGenerationVersion: null,
      rebuildJobId: null, rebuildJobVersion: null, validateConfirmation: null, switchConfirmation: null,
      cancelConfirmation: null, failConfirmation: null,
    }
    const wrapper = mountWorkspace({ diagnostics: { ...diagnostics, projections: [startable] } })
    await wrapper.findAll('button').find(button => button.text().includes('影子重建'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    await dialog.get('input').setValue(startable.startConfirmation)
    await dialog.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.emitted('projectionCommand')?.[0]?.[0]).toEqual({
      operation: 'start', projectionName: 'team-activity',
      body: { expectedDefinitionVersion: 2, expectedPointerVersion: 7, confirmation: startable.startConfirmation },
    })
  })

  it('validates the failure code before emitting a terminal projection command', async () => {
    const wrapper = mountWorkspace()
    await wrapper.findAll('button').find(button => button.text().includes('标记失败'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    const inputs = dialog.findAll('input')
    await inputs[0]!.setValue(projection.failConfirmation)
    await inputs[1]!.setValue('invalid code')
    expect(dialog.findAll('button').at(-1)!.attributes('disabled')).toBeDefined()
    await inputs[1]!.setValue('OPERATOR_VERIFIED_FAILURE')
    await dialog.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.emitted('projectionCommand')?.[0]?.[0]).toEqual({
      operation: 'fail', projectionName: 'team-activity', generation: 5, rebuildJobId: uuid(1),
      body: { expectedGenerationVersion: 3, expectedJobVersion: 2, failureCode: 'OPERATOR_VERIFIED_FAILURE', confirmation: projection.failConfirmation },
    })
  })

  it('renders initial and stale diagnostics failures, empty projections and terminal receipts', async () => {
    const initial = mountWorkspace({ phase: 'error', error: apiError('server', 'health unavailable'), health: null, diagnostics: null })
    expect(initial.text()).toContain('运行健康暂时不可用')
    await initial.findAll('button').find(button => button.text().includes('刷新事实'))!.trigger('click')
    expect(initial.emitted('refresh')).toHaveLength(1)

    const admin = mountWorkspace({
      diagnosticsPhase: 'error', diagnosticsError: apiError('server', 'diagnostics unavailable'),
      diagnostics: { ...diagnostics, projections: [], recoveryCandidates: [] },
      command: { phase: 'success', operation: 'projection-switch', targetId: 'team-activity', error: null,
        receipt: { commandId: uuid(11), projectionName: 'team-activity', generation: 5, rebuildJobId: uuid(1), generationStatus: 'ACTIVE', rebuildStatus: 'COMPLETED', generationVersion: 4, rebuildJobVersion: 3, pointerVersion: 8 } },
    })
    expect(admin.text()).toContain('诊断刷新失败')
    expect(admin.text()).toContain('暂无 Projection 定义')
    expect(admin.text()).toContain('当前没有可恢复的失败项')
    expect(admin.text()).toContain('命令已接受')
    await admin.findAll('button').find(button => button.text() === '关闭')!.trigger('click')
    expect(admin.emitted('clearCommand')).toHaveLength(1)
  })
})

function mountWorkspace(overrides: Record<string, unknown> = {}, attach = false) {
  return mount(OperationsWorkspace, {
    props: {
      phase: 'ready', error: null, health, diagnosticsPhase: 'ready', diagnosticsError: null,
      diagnostics, command: { phase: 'idle', operation: null, targetId: null, receipt: null, error: null },
      canManage: true, online: true, ...overrides,
    } as never,
    global: {
      stubs: { RouterLink: { props: ['to'], template: '<a href="#"><slot /></a>' } },
      mocks: { $route: { query: { team: uuid(9) } } },
    },
    attachTo: attach ? document.body : undefined,
  })
}
function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
function apiError(kind: 'server', message: string) { return { kind, message, status: 503, retryable: true, currentVersion: null } }
