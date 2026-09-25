import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { principalKinds, principalStatuses } from './types'
import type {
  PrincipalDirectoryQuery,
  PrincipalEntry,
  PrincipalKind,
  PrincipalPage,
  PrincipalStatus,
} from './types'

export interface PrincipalDirectoryGateway {
  search(query: PrincipalDirectoryQuery, signal?: AbortSignal): Promise<PrincipalPage>
}

/** Strict adapter for the A07 member-safe subject directory. */
export class HttpPrincipalDirectoryGateway implements PrincipalDirectoryGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async search(query: PrincipalDirectoryQuery, signal?: AbortSignal): Promise<PrincipalPage> {
    const text = query.q?.trim() ?? ''
    const prefix = query.namePrefix?.trim() ?? ''
    if (text.length > 100 || prefix.length > 100) {
      throw new TypeError('Principal directory text must not exceed 100 characters')
    }
    if (text && prefix && text !== prefix) {
      throw new TypeError('Principal directory q and namePrefix must agree')
    }
    // The offset/limit defaults stay unconditional; every A06 parameter joins only when sent,
    // so callers that never adopted them keep their exact request shape.
    const params = new URLSearchParams({
      offset: String(Math.max(query.offset ?? 0, 0)),
      limit: String(Math.min(Math.max(query.limit ?? 20, 1), 200)),
    })
    if (text) params.set('q', text)
    else if (prefix) params.set('namePrefix', prefix)
    if (query.types) {
      for (const kind of query.types) {
        if (!principalKinds.includes(kind)) throw new TypeError('Invalid Principal kind filter')
      }
      params.set('types', query.types.join(','))
    }
    if (query.ids) {
      if (query.ids.length > 50) throw new TypeError('Principal directory ids must not exceed 50')
      for (const id of query.ids) string(id)
      params.set('ids', query.ids.join(','))
    }
    if (query.after) params.set('after', query.after)
    const value = record(await this.client.get(
      `/organizations/${segment(query.organizationId)}/teams/${segment(query.teamId)}/principals?${params}`,
      { signal },
    ))
    return {
      items: array(value.items).map(readEntry),
      nextOffset: value.nextOffset == null ? null : integer(value.nextOffset),
      nextCursor: value.nextCursor == null ? null : string(value.nextCursor),
    }
  }
}

function readEntry(input: unknown): PrincipalEntry {
  const value = record(input)
  const kind = string(value.kind)
  if (!principalKinds.includes(kind as PrincipalKind)) throw new TypeError('Invalid Principal kind')
  const status = string(value.status)
  if (!principalStatuses.includes(status as PrincipalStatus)) throw new TypeError('Invalid Principal status')
  return {
    principalId: string(value.principalId),
    kind: kind as PrincipalKind,
    displayName: string(value.displayName),
    status: status as PrincipalStatus,
    roles: Array.isArray(value.roles) ? value.roles.map(string) : [],
  }
}

function segment(value: string): string { return encodeURIComponent(value) }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid Principal directory response')
  return value as Record<string, unknown>
}
function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new TypeError('Invalid Principal directory items')
  return value
}
function string(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid Principal text')
  return value
}
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) throw new TypeError('Invalid Principal offset')
  return value
}

export const principalDirectoryGateway: PrincipalDirectoryGateway = new HttpPrincipalDirectoryGateway()
