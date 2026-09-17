import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import type {
  ConfigurationComponent,
  ConfigurationHealthItem,
  ConfigurationHealthView,
  ConfigurationSearchHit,
  SetupCapability,
  SetupReadinessItem,
  SetupReadinessView,
  SetupStatus,
} from './types'

/*
 * The health and search projections are optional on the interface: readiness is the only call every
 * embedder needs, and the store degrades to an explicit "unavailable" state rather than failing when
 * a gateway does not implement them.
 */
export interface SetupGateway {
  getReadiness(scope: SettingsScope, signal?: AbortSignal): Promise<SetupReadinessView>
  getConfigurationHealth?(scope: SettingsScope, signal?: AbortSignal): Promise<ConfigurationHealthView>
  searchConfiguration?(scope: SettingsScope, query: string, signal?: AbortSignal): Promise<ConfigurationSearchHit[]>
}

/** Read-only adapter for Setup Readiness. Unknown values are rejected at the browser boundary. */
export class HttpSetupGateway implements SetupGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async getReadiness(scope: SettingsScope, signal?: AbortSignal): Promise<SetupReadinessView> {
    const value = record(await this.client.get<unknown>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/setup-readiness`,
      { signal },
    ))
    if (string(value.organizationId) !== scope.organizationId || string(value.teamId) !== scope.teamId) {
      throw new TypeError('Setup Readiness scope does not match the active Team')
    }
    const capabilities = array(value.capabilities).map(mapItem)
    if (capabilities.length !== 6 || new Set(capabilities.map(item => item.capability)).size !== 6) {
      throw new TypeError('Setup Readiness capability set is incomplete')
    }
    return {
      scope: { ...scope },
      snapshotVersion: string(value.snapshotVersion),
      observedAt: string(value.observedAt),
      capabilities,
      requiredReady: boolean(value.requiredReady),
    }
  }

  async getConfigurationHealth(scope: SettingsScope, signal?: AbortSignal): Promise<ConfigurationHealthView> {
    const value = record(await this.client.get<unknown>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/configuration-health`,
      { signal },
    ))
    if (string(value.organizationId) !== scope.organizationId || string(value.teamId) !== scope.teamId) {
      throw new TypeError('Configuration health scope does not match the active Team')
    }
    const items = array(value.items).map(mapHealthItem)
    if (items.length !== 4 || new Set(items.map(item => item.component)).size !== 4) {
      throw new TypeError('Configuration health component set is incomplete')
    }
    const overallStatus = string(value.overallStatus) as SetupStatus
    if (!statusSet.has(overallStatus)) throw new TypeError('Invalid Configuration health status')
    return {
      scope: { ...scope },
      observedAt: string(value.observedAt),
      overallStatus,
      items,
    }
  }

  async searchConfiguration(
    scope: SettingsScope,
    query: string,
    signal?: AbortSignal,
  ): Promise<ConfigurationSearchHit[]> {
    const term = query.trim()
    if (!term || term.length > 100) throw new TypeError('Configuration search query is out of range')
    const value = record(await this.client.get<unknown>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
        + `/configuration-search?q=${encodeURIComponent(term)}`,
      { signal },
    ))
    const hits = array(value.items).map(mapSearchHit)
    if (hits.length > 100) throw new TypeError('Configuration search returned more results than the contract allows')
    return hits
  }
}

const componentSet = new Set<ConfigurationComponent>([
  'AGENT_CONFIGURATION', 'MODEL_CONNECTION', 'CREDENTIAL', 'INTEGRATION',
])

const capabilitySet = new Set<SetupCapability>([
  'PERSONAL_CONVERSATION', 'TEAM_TASK', 'CODING_REVIEW', 'GITHUB_DRAFT_PR', 'LARK_NOTIFICATIONS', 'TEAM_OBSERVER',
])
const statusSet = new Set<SetupStatus>(['READY', 'ACTION_REQUIRED', 'BLOCKED', 'UNAVAILABLE'])

function mapItem(input: unknown): SetupReadinessItem {
  const value = record(input)
  const capability = string(value.capability) as SetupCapability
  const status = string(value.status) as SetupStatus
  if (!capabilitySet.has(capability) || !statusSet.has(status)) throw new TypeError('Invalid Setup Readiness item')
  const actionKey = value.actionKey == null ? null : string(value.actionKey)
  if (actionKey && !/^[A-Z][A-Z0-9_]{2,80}$/.test(actionKey)) throw new TypeError('Invalid Setup Readiness action')
  return {
    capability,
    required: boolean(value.required),
    status,
    reasonCode: string(value.reasonCode),
    canConfigure: boolean(value.canConfigure),
    responsibleParty: string(value.responsibleParty),
    actionKey,
  }
}

function mapHealthItem(input: unknown): ConfigurationHealthItem {
  const value = record(input)
  const component = string(value.component) as ConfigurationComponent
  const status = string(value.status) as SetupStatus
  if (!componentSet.has(component) || !statusSet.has(status)) throw new TypeError('Invalid Configuration health item')
  const actionKey = value.actionKey == null ? null : string(value.actionKey)
  if (actionKey && !/^[A-Z][A-Z0-9_]{2,80}$/.test(actionKey)) throw new TypeError('Invalid Configuration health action')
  return {
    component,
    status,
    reasonCode: string(value.reasonCode),
    responsibleParty: string(value.responsibleParty),
    actionKey,
  }
}

function mapSearchHit(input: unknown): ConfigurationSearchHit {
  const value = record(input)
  const revision = value.revision
  if (typeof revision !== 'number' || !Number.isInteger(revision) || revision < 1) {
    throw new TypeError('Invalid Configuration search revision')
  }
  const route = string(value.route)
  if (!route.startsWith('/')) throw new TypeError('Invalid Configuration search route')
  return {
    profileId: string(value.profileId),
    revision,
    field: string(value.field),
    label: string(value.label),
    route,
  }
}

function segment(value: string): string { return encodeURIComponent(value) }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid Setup Readiness response')
  return value as Record<string, unknown>
}
function array(value: unknown): unknown[] { if (!Array.isArray(value)) throw new TypeError('Invalid Setup Readiness capabilities'); return value }
function string(value: unknown): string { if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid Setup Readiness text'); return value }
function boolean(value: unknown): boolean { if (typeof value !== 'boolean') throw new TypeError('Invalid Setup Readiness boolean'); return value }
