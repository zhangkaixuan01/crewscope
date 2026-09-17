import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { safeRoute } from '../shared/route'
import { searchObjectTypes, type SearchFilter, type SearchResultPage, type SearchScope } from './types'
import type { SearchResultItem, SearchObjectType } from './types'

export interface SearchGateway { search(scope: SearchScope, filter: SearchFilter, signal?: AbortSignal): Promise<SearchResultPage> }
/** Strict adapter for the six-type search contract; routes remain internal navigation coordinates. */
export class HttpSearchGateway implements SearchGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}
  async search(scope: SearchScope, filter: SearchFilter, signal?: AbortSignal): Promise<SearchResultPage> {
    const text = filter.text.trim(); if (!text || text.length > 100) throw new TypeError('Search text must be 1..100 characters')
    const params = new URLSearchParams({ q: text, limit: String(Math.min(Math.max(filter.limit ?? 20, 1), 50)) })
    if (filter.projectId) params.set('projectId', filter.projectId)
    filter.types?.forEach(type => params.append('types', type))
    if (filter.after) params.set('after', filter.after)
    const value = record(await this.client.get(`/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/search?${params}`, { signal }))
    const items = array(value.items).map(mapItem)
    return { items, nextCursor: nullableString(value.nextCursor) }
  }
}
function mapItem(input: unknown): SearchResultItem {
  const value = record(input); const type = string(value.objectType) as SearchObjectType
  if (!searchObjectTypes.includes(type)) throw new TypeError('Invalid Search object type')
  const route = string(value.route); if (!safeRoute(route)) throw new TypeError('Unsafe Search route')
  return { objectType: type, objectId: string(value.objectId), projectId: nullableString(value.projectId), title: string(value.title), subtitle: nullableString(value.subtitle), status: string(value.status), updatedAt: string(value.updatedAt), route, snippet: nullableString(value.snippet) }
}
function segment(value: string): string { return encodeURIComponent(value) }
function record(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid Search response'); return value as Record<string, unknown> }
function array(value: unknown): unknown[] { if (!Array.isArray(value)) throw new TypeError('Invalid Search items'); return value }
function string(value: unknown): string { if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid Search text'); return value }
function nullableString(value: unknown): string | null { return value == null ? null : string(value) }
