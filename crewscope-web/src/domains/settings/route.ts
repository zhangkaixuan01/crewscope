import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { SettingsScope } from './types'

export const AGENT_SETTINGS_PATH = '/settings/agents'
export const MODEL_SETTINGS_PATH = '/settings/models'

export type ModelConnectionOwnerType = 'USER' | 'TEAM' | 'ORGANIZATION'

export interface AgentSettingsSelection {
  teamId: string | null
  agentId: string | null
  configurationRevision: number | null
}

export interface ModelSettingsSelection {
  teamId: string | null
  providerKey: string | null
  connectionId: string | null
  ownerType: ModelConnectionOwnerType | null
}

/** Reads Agent settings coordinates without accepting duplicate or malformed query values. */
export function agentSettingsSelection(query: LocationQuery): AgentSettingsSelection {
  const configurationRevision = positiveInteger(query.configurationRevision)
  const invalidRevision = query.configurationRevision !== undefined && configurationRevision === null
  return {
    teamId: scalar(query.team),
    agentId: invalidRevision ? null : scalar(query.agent),
    configurationRevision,
  }
}

/** Reads Model settings coordinates and fails closed for unknown Connection owner types. */
export function modelSettingsSelection(query: LocationQuery): ModelSettingsSelection {
  const parsedOwnerType = ownerType(query.ownerType)
  const invalidOwnerType = query.ownerType !== undefined && parsedOwnerType === null
  return {
    teamId: scalar(query.team),
    providerKey: invalidOwnerType ? null : scalar(query.provider),
    connectionId: invalidOwnerType ? null : scalar(query.connection),
    ownerType: parsedOwnerType,
  }
}

export function agentSettingsMatchesScope(selection: AgentSettingsSelection, scope: SettingsScope): boolean {
  return selection.teamId === scope.teamId
}

export function modelSettingsMatchesScope(selection: ModelSettingsSelection, scope: SettingsScope): boolean {
  return selection.teamId === scope.teamId
}

export function isRestorableAgentSettings(selection: AgentSettingsSelection): boolean {
  return Boolean(selection.teamId && selection.agentId)
}

export function isRestorableModelSettings(selection: ModelSettingsSelection): boolean {
  return Boolean(selection.teamId && (selection.providerKey || selection.connectionId))
}

export function withAgentSettingsRoute(
  query: LocationQuery,
  value: { teamId: string, agentId?: string | null, configurationRevision?: number | null },
): LocationQueryRaw {
  return {
    ...query,
    team: value.teamId,
    agent: value.agentId ?? undefined,
    configurationRevision: value.configurationRevision?.toString(),
  }
}

export function withModelSettingsRoute(
  query: LocationQuery,
  value: {
    teamId: string
    providerKey?: string | null
    connectionId?: string | null
    ownerType?: ModelConnectionOwnerType | null
  },
): LocationQueryRaw {
  return {
    ...query,
    team: value.teamId,
    provider: value.providerKey ?? undefined,
    connection: value.connectionId ?? undefined,
    ownerType: value.ownerType ?? undefined,
  }
}

function scalar(value: LocationQuery[string]): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function positiveInteger(value: LocationQuery[string]): number | null {
  const scalarValue = scalar(value)
  if (!scalarValue || !/^[1-9]\d*$/.test(scalarValue)) return null
  const parsed = Number(scalarValue)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function ownerType(value: LocationQuery[string]): ModelConnectionOwnerType | null {
  const candidate = scalar(value)
  return candidate === 'USER' || candidate === 'TEAM' || candidate === 'ORGANIZATION'
    ? candidate
    : null
}
