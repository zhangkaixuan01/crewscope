import type { LocationQuery } from 'vue-router'
import { fixtureIds } from '../../test/scopeFixtures'
import {
  codingRouteMatchesScope,
  codingRouteSelection,
  isRestorableCodingRoute,
  withCodingRoute,
  withoutCodingRoute,
} from './route'

describe('Coding route contract', () => {
  it('restores a Task/execution/Workspace only inside the selected WorkProject Scope', () => {
    const selection = codingRouteSelection({
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
      workItem: 'work-item-1',
      task: 'task-1',
      taskExecution: 'attempt-1',
      workspace: 'workspace-1',
    })

    expect(isRestorableCodingRoute(selection)).toBe(true)
    expect(codingRouteMatchesScope(selection, {
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
    })).toBe(true)
  })

  it('reads the execution from either spelling, and reports a conflict instead of choosing', () => {
    expect(codingRouteSelection({ task: 'task-1', attempt: 'attempt-1' }).executionId).toBe('attempt-1')
    expect(codingRouteSelection({ task: 'task-1', taskExecution: 'attempt-1' }).executionId).toBe('attempt-1')
    expect(codingRouteSelection({ task: 'task-1', taskExecution: 'attempt-1', attempt: 'attempt-1' }).executionConflict)
      .toBe(false)

    const conflict = codingRouteSelection({ task: 'task-1', taskExecution: 'attempt-1', attempt: 'attempt-2' })
    expect(conflict.executionConflict).toBe(true)
    expect(conflict.executionId).toBeNull()
  })

  it('fails closed for duplicate and incomplete nested coordinates', () => {
    expect(isRestorableCodingRoute(codingRouteSelection({
      team: [fixtureIds.teamPlatform, fixtureIds.teamSecurity],
      project: fixtureIds.projectCrewScope,
      task: 'task-1',
    } as LocationQuery))).toBe(false)
    expect(isRestorableCodingRoute(codingRouteSelection({
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
      task: 'task-1',
      workspace: 'workspace-without-execution',
    }))).toBe(false)
  })

  it('writes the unified parameter only, and closes the focus in both spellings', () => {
    const linked = withCodingRoute({ view: 'board', task: 'task-1', attempt: 'attempt-legacy' } as LocationQuery, {
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
      workItemId: 'work-item-1',
      taskId: 'task-1',
      executionId: 'attempt-1',
      workspaceId: 'workspace-1',
    })

    // Contract §4.1: `taskExecution=` is the one write-side parameter; the legacy alias is cleared,
    // so writing from a restored legacy link can never leave a conflict behind.
    expect(linked).toMatchObject({
      view: 'board', task: 'task-1', taskExecution: 'attempt-1', attempt: undefined, workspace: 'workspace-1',
    })
    expect(withoutCodingRoute({ ...linked, taskExecution: 'attempt-1' } as LocationQuery)).toMatchObject({
      view: 'board', task: 'task-1', taskExecution: undefined, attempt: undefined, workspace: undefined,
    })
  })
})
