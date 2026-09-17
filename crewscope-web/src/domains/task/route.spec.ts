import type { LocationQuery } from 'vue-router'
import { fixtureIds } from '../../test/scopeFixtures'
import { taskIds } from '../../test/taskFixtures'
import {
  isRestorableTaskRoute,
  resolveTaskExecution,
  taskRouteMatchesScope,
  taskRouteSelection,
  withTaskRoute,
  withoutTaskRoute,
} from './route'

describe('Task route contract', () => {
  it('restores the server-authored WorkProject and Task deep link', () => {
    const selection = taskRouteSelection({
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
      workItem: taskIds.workItem,
      task: taskIds.first,
    })

    expect(isRestorableTaskRoute(selection)).toBe(true)
    expect(taskRouteMatchesScope(selection, {
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
    })).toBe(true)
  })

  it('fails closed for duplicate or incomplete query values', () => {
    const selection = taskRouteSelection({
      team: [fixtureIds.teamPlatform, fixtureIds.teamSecurity],
      project: fixtureIds.projectCrewScope,
      task: taskIds.first,
    } as LocationQuery)

    expect(selection.teamId).toBeNull()
    expect(isRestorableTaskRoute(selection)).toBe(false)
  })

  it('adds and removes only the Task focus while preserving the shared mode query', () => {
    const current = { view: 'board', status: 'ACTIVE' } as LocationQuery
    const linked = withTaskRoute(current, {
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
      workItemId: taskIds.workItem,
      taskId: taskIds.first,
    })

    expect(linked).toMatchObject({ view: 'board', status: 'ACTIVE', task: taskIds.first })
    expect(withoutTaskRoute(linked as LocationQuery)).toMatchObject({ view: 'board', task: undefined })
  })

  it('reads the WorkDesk execution deep link separately from the Task identity', () => {
    const selection = taskRouteSelection({
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
      task: taskIds.first,
      taskExecution: taskIds.execution,
    })

    expect(selection.executionId).toBe(taskIds.execution)
    expect(taskRouteSelection({ task: taskIds.first }).executionId).toBeNull()
    expect(taskRouteSelection({ taskExecution: ['a', 'b'] } as LocationQuery).executionId).toBeNull()
  })
})

describe('Task execution resolution', () => {
  const attempts = [{ id: taskIds.execution }, { id: taskIds.previousExecution }]

  it('honours the deep link only while it names an attempt the Task has', () => {
    expect(resolveTaskExecution({ executionId: taskIds.previousExecution }, attempts, {
      selectedId: taskIds.execution,
      currentExecutionId: taskIds.execution,
    })).toBe(taskIds.previousExecution)

    // A stale or foreign identity must not blank the runtime facts: the member's own choice wins.
    expect(resolveTaskExecution({ executionId: 'unknown' }, attempts, {
      selectedId: taskIds.previousExecution,
      currentExecutionId: taskIds.execution,
    })).toBe(taskIds.previousExecution)
  })

  it('falls back to the current execution and then to the newest attempt', () => {
    expect(resolveTaskExecution({ executionId: null }, attempts, {
      selectedId: null,
      currentExecutionId: taskIds.previousExecution,
    })).toBe(taskIds.previousExecution)

    expect(resolveTaskExecution({ executionId: 'unknown' }, attempts, {
      selectedId: null,
      currentExecutionId: null,
    })).toBe(taskIds.execution)

    expect(resolveTaskExecution({ executionId: null }, [], {
      selectedId: null,
      currentExecutionId: null,
    })).toBeNull()
  })
})
