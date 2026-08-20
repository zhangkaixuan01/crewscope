import { mount } from '@vue/test-utils'
import type { ArtifactTextDocument, CommandEvidenceSummary, TestEvidenceSummary } from '../../domains/coding/types'
import CodingEvidencePanel from './CodingEvidencePanel.vue'

describe('CodingEvidencePanel', () => {
  it('shows read-only command, test and ordered acceptance facts while masking common secrets', async () => {
    const loadLog = vi.fn()
    const wrapper = mount(CodingEvidencePanel, { props: fixtureProps({
      commandLog: () => ({ phase: 'ready', value: document('Bearer private-token\npassword=hunter2\n'), errorMessage: null, errorStatus: null }),
      onLoadCommandLog: loadLog,
    }) })

    expect(wrapper.text()).toContain('Exit Code')
    expect(wrapper.text()).toContain('Passed10')
    expect(wrapper.text()).toContain('Total12')
    expect(wrapper.text()).toContain('编译成功')
    expect(wrapper.text()).toContain('单元测试通过')
    expect(wrapper.text()).toContain('Bearer [REDACTED]')
    expect(wrapper.text()).toContain('password=[REDACTED]')
    expect(wrapper.text()).not.toContain('private-token')
    expect(wrapper.text()).not.toContain('hunter2')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('[contenteditable]').exists()).toBe(false)
  })

  it('loads bounded evidence content on demand and supports later byte pages', async () => {
    const loadLog = vi.fn()
    const loadReport = vi.fn()
    const partial = document('first page\n', false)
    const wrapper = mount(CodingEvidencePanel, { props: fixtureProps({
      commandLog: () => null,
      testReport: () => ({ phase: 'ready', value: partial, errorMessage: null, errorStatus: null }),
      onLoadCommandLog: loadLog,
      onLoadTestReport: loadReport,
    }) })

    await wrapper.get('.read-artifact').trigger('click')
    await wrapper.get('.report-block .artifact-view footer button').trigger('click')

    expect(loadLog).toHaveBeenCalledWith('command-1')
    expect(loadReport).toHaveBeenCalledWith('test-1', true)
  })
})

function fixtureProps(overrides: Record<string, unknown> = {}) {
  return {
    taskId: 'task-1', executionId: 'execution-1',
    commandsPhase: 'ready' as const,
    commands: { items: [command()], nextCursor: null }, commandsErrorMessage: null,
    testsPhase: 'ready' as const,
    tests: { items: [testEvidence()], nextCursor: null }, testsErrorMessage: null,
    commandLog: () => null,
    testReport: () => null,
    onLoadCommandsMore: vi.fn(), onLoadTestsMore: vi.fn(), onLoadCommandLog: vi.fn(), onLoadTestReport: vi.fn(),
    ...overrides,
  }
}

function command(): CommandEvidenceSummary {
  return {
    id: 'command-1', sequence: 1, commandKind: 'TEST', toolKey: 'coding.maven.test', timeoutSeconds: 60,
    startedAt: '2026-08-20T01:00:00Z', finishedAt: '2026-08-20T01:00:02Z', termination: 'EXITED',
    exitCode: 0, summary: '编译成功', failureClassification: null, evidenceHash: 'a'.repeat(64),
    commandLog: { artifactId: 'log-1', kind: 'COMMAND_LOG', contentType: 'text/plain', sizeBytes: 42, contentHash: 'b'.repeat(64) },
  }
}

function testEvidence(): TestEvidenceSummary {
  return {
    id: 'test-1', sequence: 1, diffGeneration: 1, diffManifestHash: 'c'.repeat(64), total: 12,
    passed: 10, failed: 1, errors: 0, skipped: 1, summary: '测试完成', failureClassification: 'TEST_FAILED',
    evidenceHash: 'd'.repeat(64), commandEvidenceIds: ['command-1'], createdAt: '2026-08-20T01:01:00Z',
    acceptance: [
      { criterionIndex: 1, criterion: '单元测试通过', status: 'FAILED', summary: '存在一个失败', commandEvidenceIds: ['command-1'] },
      { criterionIndex: 0, criterion: '编译成功', status: 'PASSED', summary: '构建完成', commandEvidenceIds: ['command-1'] },
    ],
    testReport: { artifactId: 'report-1', kind: 'TEST_REPORT', contentType: 'application/json', sizeBytes: 20, contentHash: 'e'.repeat(64) },
  }
}

function document(content: string, complete = true): ArtifactTextDocument {
  const bytes = new TextEncoder().encode(content)
  return { bytes, content, loadedBytes: bytes.byteLength, totalSize: complete ? bytes.byteLength : bytes.byteLength + 10,
    complete, etag: '"stable"', contentType: 'text/plain', filename: 'evidence.log' }
}
