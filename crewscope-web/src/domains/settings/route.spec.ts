import type { LocationQuery } from 'vue-router'
import { fixtureIds } from '../../test/scopeFixtures'
import {
  AGENT_SETTINGS_PATH,
  MODEL_SETTINGS_PATH,
  agentSettingsMatchesScope,
  agentSettingsSelection,
  isRestorableAgentSettings,
  isRestorableModelSettings,
  modelSettingsMatchesScope,
  modelSettingsSelection,
  withAgentSettingsRoute,
  withModelSettingsRoute,
} from './route'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }

describe('M5 settings route contract', () => {
  it('restores stable Agent and Model setting coordinates inside the selected Team', () => {
    const agent = agentSettingsSelection({
      team: fixtureIds.teamPlatform,
      agent: 'agent-1',
      configurationRevision: '7',
    })
    const model = modelSettingsSelection({
      team: fixtureIds.teamPlatform,
      provider: 'deepseek',
      connection: 'connection-1',
      ownerType: 'TEAM',
    })

    expect(AGENT_SETTINGS_PATH).toBe('/settings/agents')
    expect(MODEL_SETTINGS_PATH).toBe('/settings/models')
    expect(agent).toEqual({ teamId: fixtureIds.teamPlatform, agentId: 'agent-1', configurationRevision: 7 })
    expect(model).toEqual({
      teamId: fixtureIds.teamPlatform,
      providerKey: 'deepseek',
      connectionId: 'connection-1',
      ownerType: 'TEAM',
    })
    expect(isRestorableAgentSettings(agent)).toBe(true)
    expect(isRestorableModelSettings(model)).toBe(true)
    expect(agentSettingsMatchesScope(agent, scope)).toBe(true)
    expect(modelSettingsMatchesScope(model, scope)).toBe(true)
  })

  it('fails closed for duplicate coordinates, unknown owner types and invalid revisions', () => {
    const agent = agentSettingsSelection({
      team: [fixtureIds.teamPlatform, fixtureIds.teamSecurity],
      agent: 'agent-1',
      configurationRevision: '0',
    } as LocationQuery)
    const model = modelSettingsSelection({
      team: fixtureIds.teamPlatform,
      provider: 'deepseek',
      ownerType: 'EXTERNAL',
    } as LocationQuery)

    expect(agent).toEqual({ teamId: null, agentId: null, configurationRevision: null })
    expect(model.ownerType).toBeNull()
    expect(model.providerKey).toBeNull()
    expect(isRestorableAgentSettings(agent)).toBe(false)
    expect(isRestorableModelSettings(model)).toBe(false)
  })

  it('changes only setting focus while preserving shared navigation query', () => {
    const current = { focus: 'CRW-18', view: 'board' } as LocationQuery

    expect(withAgentSettingsRoute(current, {
      teamId: fixtureIds.teamPlatform,
      agentId: 'agent-1',
      configurationRevision: 3,
    })).toMatchObject({ focus: 'CRW-18', team: fixtureIds.teamPlatform, agent: 'agent-1', configurationRevision: '3' })
    expect(withModelSettingsRoute(current, {
      teamId: fixtureIds.teamPlatform,
      providerKey: 'deepseek',
      connectionId: 'connection-1',
      ownerType: 'USER',
    })).toMatchObject({ view: 'board', team: fixtureIds.teamPlatform, provider: 'deepseek', connection: 'connection-1' })
  })
})
