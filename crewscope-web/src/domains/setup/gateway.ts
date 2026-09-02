import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import type { SetupCapability, SetupReadinessItem, SetupReadinessView, SetupStatus } from './types'

export interface SetupGateway {
  getReadiness(scope: SettingsScope, signal?: AbortSignal): Promise<SetupReadinessView>
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
}

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

function segment(value: string): string { return encodeURIComponent(value) }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid Setup Readiness response')
  return value as Record<string, unknown>
}
function array(value: unknown): unknown[] { if (!Array.isArray(value)) throw new TypeError('Invalid Setup Readiness capabilities'); return value }
function string(value: unknown): string { if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid Setup Readiness text'); return value }
function boolean(value: unknown): boolean { if (typeof value !== 'boolean') throw new TypeError('Invalid Setup Readiness boolean'); return value }
