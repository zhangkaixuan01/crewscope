import type { LocationQuery } from 'vue-router'
import { fixtureIds } from '../../test/scopeFixtures'
import { taskIds } from '../../test/taskFixtures'
import {
  isRestorableTaskRoute,
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
})
