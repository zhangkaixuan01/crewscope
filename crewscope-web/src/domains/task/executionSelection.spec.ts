import type { LocationQuery } from 'vue-router'
import { taskIds } from '../../test/taskFixtures'
import { parseExecutionSelection, resolveExecutionSelection } from './executionSelection'

describe('parseExecutionSelection', () => {
  it('reads the unified parameter and the legacy alias without inventing identities', () => {
    expect(parseExecutionSelection({ taskExecution: taskIds.execution })).toMatchObject({
      unified: taskIds.execution, alias: null, conflict: false, aliasOnly: false,
    })
    expect(parseExecutionSelection({ attempt: taskIds.execution })).toMatchObject({
      unified: null, alias: taskIds.execution, conflict: false, aliasOnly: true,
    })
    expect(parseExecutionSelection({})).toMatchObject({
      unified: null, alias: null, conflict: false, aliasOnly: false,
    })
  })

  it('marks both-parameters-different as a conflict, and equal values as one choice', () => {
    expect(parseExecutionSelection({
      taskExecution: taskIds.execution,
      attempt: taskIds.previousExecution,
    })).toMatchObject({ conflict: true, aliasOnly: false })

    expect(parseExecutionSelection({
      taskExecution: taskIds.execution,
      attempt: taskIds.execution,
    })).toMatchObject({ conflict: false, aliasOnly: false })

    expect(parseExecutionSelection({ taskExecution: ['a', 'b'], attempt: taskIds.execution } as LocationQuery))
      .toMatchObject({ unified: null, alias: taskIds.execution, conflict: false, aliasOnly: true })
  })
})

describe('resolveExecutionSelection', () => {
  const attempts = [{ id: taskIds.execution }, { id: taskIds.previousExecution }]

  it('CONFLICT outranks every other input: nothing is chosen, nothing is guessed', () => {
    expect(resolveExecutionSelection(
      parseExecutionSelection({ taskExecution: taskIds.execution, attempt: taskIds.previousExecution }),
      attempts,
      { selectedId: taskIds.execution, currentExecutionId: taskIds.execution },
    )).toEqual({ state: 'CONFLICT', unified: taskIds.execution, alias: taskIds.previousExecution })
  })

  it('an explicit URL choice naming a live attempt is SELECTED, from either parameter', () => {
    expect(resolveExecutionSelection(
      parseExecutionSelection({ taskExecution: taskIds.previousExecution }),
      attempts,
      { selectedId: taskIds.execution, currentExecutionId: taskIds.execution },
    )).toEqual({ state: 'SELECTED', executionId: taskIds.previousExecution })

    expect(resolveExecutionSelection(
      parseExecutionSelection({ attempt: taskIds.previousExecution }),
      attempts,
      { selectedId: taskIds.execution, currentExecutionId: taskIds.execution },
    )).toEqual({ state: 'SELECTED', executionId: taskIds.previousExecution })
  })

  it('an explicit URL choice the Task no longer has stays as HISTORY_VIEW instead of falling back', () => {
    // A stale or foreign identity must not silently swap the evidence the member is reading.
    expect(resolveExecutionSelection(
      parseExecutionSelection({ taskExecution: 'unknown-execution' }),
      attempts,
      { selectedId: taskIds.previousExecution, currentExecutionId: taskIds.execution },
    )).toEqual({ state: 'HISTORY_VIEW', executionId: 'unknown-execution' })
  })

  it('without an explicit choice the live selection wins, then the server current execution — never a guess', () => {
    expect(resolveExecutionSelection(parseExecutionSelection({}), attempts, {
      selectedId: taskIds.previousExecution,
      currentExecutionId: taskIds.execution,
    })).toEqual({ state: 'SELECTED', executionId: taskIds.previousExecution })

    // A member's stale selection is dropped, not trusted over the server's current fact.
    expect(resolveExecutionSelection(parseExecutionSelection({}), attempts, {
      selectedId: 'unknown-execution',
      currentExecutionId: taskIds.execution,
    })).toEqual({ state: 'SELECTED', executionId: taskIds.execution })

    expect(resolveExecutionSelection(parseExecutionSelection({}), attempts, {
      selectedId: null,
      currentExecutionId: null,
    })).toEqual({ state: 'NO_EXECUTION' })

    expect(resolveExecutionSelection(parseExecutionSelection({}), [], {
      selectedId: null,
      currentExecutionId: null,
    })).toEqual({ state: 'NO_EXECUTION' })
  })
})
