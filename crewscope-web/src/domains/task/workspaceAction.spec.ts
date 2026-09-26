import { resolveWorkspaceAction, taskControlOperations, type WorkspaceActionInput } from './workspaceAction'

function input(overrides: Partial<WorkspaceActionInput> = {}): WorkspaceActionInput {
  return {
    canControl: true,
    historyView: false,
    commandPending: false,
    versionConflict: false,
    selectionRequired: false,
    configurationMissing: false,
    executionStatus: 'RUNNING',
    waitingReason: null,
    reviewAwaited: false,
    deliveryAwaited: false,
    taskStatus: 'ACTIVE',
    ...overrides,
  }
}

describe('resolveWorkspaceAction', () => {
  it.each([
    ['FORBIDDEN', { canControl: false }, {
      historyView: true, commandPending: true, versionConflict: true, selectionRequired: true,
      configurationMissing: true, executionStatus: 'RUNNING', reviewAwaited: true, deliveryAwaited: true,
    }],
    ['HISTORY_VIEW', { historyView: true }, {
      commandPending: true, versionConflict: true, selectionRequired: true,
      configurationMissing: true, executionStatus: 'RUNNING', reviewAwaited: true, deliveryAwaited: true,
    }],
    ['COMMAND_PENDING', { commandPending: true }, {
      versionConflict: true, selectionRequired: true,
      configurationMissing: true, executionStatus: 'RUNNING', reviewAwaited: true, deliveryAwaited: true,
    }],
    ['VERSION_CONFLICT', { versionConflict: true }, {
      selectionRequired: true, configurationMissing: true,
      executionStatus: 'RUNNING', reviewAwaited: true, deliveryAwaited: true,
    }],
    ['SELECTION_REQUIRED', { selectionRequired: true }, {
      configurationMissing: true, executionStatus: 'RUNNING', reviewAwaited: true, deliveryAwaited: true,
    }],
    ['CONFIGURATION_MISSING', { configurationMissing: true }, {
      executionStatus: 'RUNNING', reviewAwaited: true, deliveryAwaited: true,
    }],
  ] as const)('level %s wins over every later level', (state, overrides, laterFlags) => {
    // Every later level is triggered at once; the earliest contract level must be the verdict.
    expect(resolveWorkspaceAction(input({ ...overrides, ...laterFlags })).state).toBe(state)
  })

  it('reports EXECUTING for live statuses and for waiting-on-execution reasons', () => {
    for (const executionStatus of ['RUNNING', 'CLAIMED', 'PREPARING', 'RECOVERING', 'CANCEL_REQUESTED'] as const) {
      expect(resolveWorkspaceAction(input({ executionStatus })).state).toBe('EXECUTING')
    }
    expect(resolveWorkspaceAction(input({ executionStatus: 'WAITING', waitingReason: 'RUNTIME' })).state).toBe('EXECUTING')
    expect(resolveWorkspaceAction(input({ executionStatus: 'WAITING', waitingReason: null })).state).toBe('EXECUTING')
  })

  it('reports AWAITING_DECISION for human gates even while the wait reason is people', () => {
    expect(resolveWorkspaceAction(input({
      executionStatus: 'WAITING', waitingReason: 'REVIEW',
    }))).toMatchObject({ state: 'AWAITING_DECISION', anchor: 'ws-review' })
    expect(resolveWorkspaceAction(input({
      executionStatus: 'COMPLETED', reviewAwaited: true,
    }))).toMatchObject({ state: 'AWAITING_DECISION', anchor: 'ws-review' })
    expect(resolveWorkspaceAction(input({
      executionStatus: 'COMPLETED', deliveryAwaited: true,
    })).state).toBe('AWAITING_DECISION')
  })

  it('keeps a live execution reported as EXECUTING when a stale review also exists', () => {
    // Contract §4.7 order: 正在执行 (7) is reported before 等待审查 (8).
    expect(resolveWorkspaceAction(input({
      executionStatus: 'RUNNING', reviewAwaited: true,
    })).state).toBe('EXECUTING')
  })

  it('offers RESUME only for the paused idle state and no operation for terminals', () => {
    expect(resolveWorkspaceAction(input({ executionStatus: 'PAUSED' }))).toMatchObject({
      state: 'IDLE', operation: 'RESUME',
    })
    expect(resolveWorkspaceAction(input({ executionStatus: 'COMPLETED' }))).toMatchObject({
      state: 'IDLE', headline: '执行已完成', operation: null,
    })
    expect(resolveWorkspaceAction(input({ executionStatus: 'FAILED' }))).toMatchObject({
      state: 'IDLE', headline: '执行失败',
    })
    expect(resolveWorkspaceAction(input({ executionStatus: null, taskStatus: 'CREATED' }))).toMatchObject({
      state: 'IDLE', headline: '尚未启动',
    })
  })

  it('carries at most one operation and one anchor on every level', () => {
    const states = [
      input({ canControl: false }),
      input({ historyView: true }),
      input({ commandPending: true }),
      input({ versionConflict: true }),
      input({ selectionRequired: true }),
      input({ configurationMissing: true }),
      input({ executionStatus: 'RUNNING' }),
      input({ executionStatus: 'WAITING', waitingReason: 'REVIEW' }),
      input({ executionStatus: 'PAUSED' }),
      input({ executionStatus: 'COMPLETED' }),
    ]
    for (const value of states) {
      const action = resolveWorkspaceAction(value)
      expect(typeof action.headline).toBe('string')
      expect(action.headline.length).toBeGreaterThan(0)
      expect([null, 'ws-execution', 'ws-review']).toContain(action.anchor)
    }
  })
})

describe('taskControlOperations', () => {
  const base = { attempt: 1, maxAttempts: 3, terminal: null }

  it('mirrors the TaskControlPanel rule for pause, resume, cancel and retry', () => {
    expect(taskControlOperations({ ...base, status: 'RUNNING' })).toEqual(['PAUSE', 'CANCEL'])
    expect(taskControlOperations({ ...base, status: 'PAUSED' })).toEqual(['RESUME', 'CANCEL'])
    expect(taskControlOperations({ ...base, status: 'WAITING' })).toEqual(['CANCEL'])
    expect(taskControlOperations({ ...base, status: 'COMPLETED' })).toEqual([])
    expect(taskControlOperations({ ...base, status: 'FAILED', attempt: 1, maxAttempts: 1 })).toEqual([])
  })

  it('offers RETRY only for retryable failure classes with attempts left', () => {
    expect(taskControlOperations({
      status: 'FAILED', attempt: 1, maxAttempts: 3, terminal: { failureClass: 'TRANSIENT' },
    })).toEqual(['RETRY'])
    expect(taskControlOperations({
      status: 'FAILED', attempt: 1, maxAttempts: 3, terminal: { failureClass: 'POLICY_VIOLATION' },
    })).toEqual([])
    expect(taskControlOperations({
      status: 'FAILED', attempt: 3, maxAttempts: 3, terminal: { failureClass: 'TRANSIENT' },
    })).toEqual([])
  })
})
