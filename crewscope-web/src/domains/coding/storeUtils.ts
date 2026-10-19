import { CrewScopeApiError } from '../../api/client'
import { sha256Digest } from '../shared/sha256'
import type { CodingScope, EvidencePage } from './types'

/** Pure key, paging and integrity helpers for the Coding store. */
export function scopeKey(scope: CodingScope): string { return `${scope.organizationId}:${scope.teamId}:${scope.projectId}` }
export function attemptKey(taskId: string, executionId: string): string { return `${taskId}:${executionId}` }
export function artifactKey(taskId: string, executionId: string, evidenceId: string): string { return `${attemptKey(taskId, executionId)}:${evidenceId}` }
export function mergeEvidencePages<T extends { id: string }>(current: EvidencePage<T>, incoming: EvidencePage<T>): EvidencePage<T> {
  const known = new Set(current.items.map(item => item.id))
  return { items: [...current.items, ...incoming.items.filter(item => !known.has(item.id))], nextCursor: incoming.nextCursor }
}
export function deleteByPrefix<T>(cache: Record<string, T>, prefix: string): void { for (const key of Object.keys(cache)) if (key.startsWith(prefix)) delete cache[key] }
export function isEmpty(value: unknown): boolean {
  if (Array.isArray(value)) return value.length === 0
  if (value && typeof value === 'object' && 'items' in value) return Array.isArray((value as { items: unknown }).items) && (value as { items: unknown[] }).items.length === 0
  return false
}
export async function sha256(bytes: Uint8Array): Promise<string> {
  // Evidence descriptors are verified against server digests, so this must also work without
  // WebCrypto (plain-HTTP deployments) instead of throwing on every content check.
  return sha256Digest(bytes)
}
