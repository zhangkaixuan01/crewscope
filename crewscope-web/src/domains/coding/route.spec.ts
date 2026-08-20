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
  it('restores a Task/attempt/Workspace only inside the selected WorkProject Scope', () => {
    const selection = codingRouteSelection({
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
      workItem: 'work-item-1',
      task: 'task-1',
      attempt: 'attempt-1',
      workspace: 'workspace-1',
    })

    expect(isRestorableCodingRoute(selection)).toBe(true)
    expect(codingRouteMatchesScope(selection, {
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
    })).toBe(true)
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
      workspace: 'workspace-without-attempt',
    }))).toBe(false)
  })

  it('adds and removes Coding focus while preserving parent Task and Work filters', () => {
    const linked = withCodingRoute({ view: 'board', task: 'task-1' } as LocationQuery, {
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
      workItemId: 'work-item-1',
      taskId: 'task-1',
      executionId: 'attempt-1',
      workspaceId: 'workspace-1',
    })

    expect(linked).toMatchObject({ view: 'board', task: 'task-1', attempt: 'attempt-1', workspace: 'workspace-1' })
    expect(withoutCodingRoute(linked as LocationQuery)).toMatchObject({
      view: 'board', task: 'task-1', attempt: undefined, workspace: undefined,
    })
  })
})
