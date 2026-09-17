import { CrewScopeApiError } from '../../api/client'
import type { TaskAssociationPage, TaskAssociations, TaskDelegationSelection, TaskEventItem, TaskEventPage, TaskScope, TaskStatus } from './types'

/** Pure/cache helpers kept outside the orchestration store so they can be tested independently. */
export function delegationPreflightKey(projectId: string, workItemId: string, selection: TaskDelegationSelection): string {
  return `${projectId}:${workItemId}:${selection.executorAgentProfileId}:${selection.agentConfigurationRevision ?? 'current'}`
}

export function defaultReconnectDelay(attempt: number, signal: AbortSignal): Promise<void> {
  const delay = Math.min(1_000 * 2 ** Math.min(attempt - 1, 4), 15_000)
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(resolve, delay)
    signal.addEventListener('abort', () => { window.clearTimeout(timeout); reject(new DOMException('Aborted', 'AbortError')) }, { once: true })
  })
}

export function browserStorage(): Storage | null {
  try { return typeof sessionStorage === 'undefined' ? null : sessionStorage } catch { return null }
}
export function liveCursorKey(scope: TaskScope, taskId: string): string { return `crewscope:task-cursor:${scope.organizationId}:${scope.teamId}:${taskId}` }
export function safeGet(storage: Storage | null, key: string): string | null { try { return storage?.getItem(key) ?? null } catch { return null } }
export function safeSet(storage: Storage | null, key: string, value: string): void { try { storage?.setItem(key, value) } catch { /* Storage is an optimization. */ } }
export function safeRemove(storage: Storage | null, key: string): void { try { storage?.removeItem(key) } catch { /* A 410 remains recoverable without storage. */ } }
export function rememberBounded(value: string, seen: Set<string>, order: string[]): boolean {
  if (seen.has(value)) return false
  seen.add(value); order.push(value)
  if (order.length > 500) { const oldest = order.shift(); if (oldest) seen.delete(oldest) }
  return true
}

export function scopeKey(scope: TaskScope): string { return `${scope.organizationId}:${scope.teamId}` }
export function queryKey(scope: TaskScope, projectId?: string, status?: TaskStatus, ownerPrincipalId?: string): string {
  return `${scopeKey(scope)}:${projectId ?? 'ALL'}:${status ?? 'ALL'}:${ownerPrincipalId ?? 'ALL'}`
}
export function mergeEventPages(current: TaskEventPage, incoming: TaskEventPage): TaskEventPage {
  return { items: deduplicateTaskEvents([...current.items, ...incoming.items]), hasMore: incoming.hasMore, taskTerminal: incoming.taskTerminal, nextCursor: incoming.nextCursor }
}
function deduplicateTaskEvents(items: TaskEventItem[]): TaskEventItem[] {
  const eventIds = new Set<string>(); const domainEventIds = new Set<string>()
  return items.filter(item => { if (eventIds.has(item.event.eventId)) return false; if (item.event.domainEventId && domainEventIds.has(item.event.domainEventId)) return false; eventIds.add(item.event.eventId); if (item.event.domainEventId) domainEventIds.add(item.event.domainEventId); return true })
}
export function mergeAssociationPages(current: TaskAssociationPage, incoming: TaskAssociationPage): TaskAssociationPage {
  const known = new Set(current.items.map(item => item.task.id))
  return { items: [...current.items, ...incoming.items.filter(item => !known.has(item.task.id))], nextCursor: incoming.nextCursor }
}
export function mergeTaskAssociations(current: TaskAssociations, incoming: TaskAssociations): TaskAssociations {
  const known = new Set(current.conversations.items.map(item => item.id))
  return { task: incoming.task, workItem: incoming.workItem, conversations: { items: [...current.conversations.items, ...incoming.conversations.items.filter(item => !known.has(item.id))], nextCursor: incoming.conversations.nextCursor } }
}
export function isEmpty(value: unknown): boolean {
  if (Array.isArray(value)) return value.length === 0
  if (value && typeof value === 'object' && 'items' in value) return Array.isArray((value as { items: unknown }).items) && (value as { items: unknown[] }).items.length === 0
  return false
}
export function isAbort(error: unknown): boolean { return error instanceof DOMException && error.name === 'AbortError' }
export function statusOf(error: unknown): number | null { return error instanceof CrewScopeApiError ? error.status : null }
export function isTaskCommandConflict(error: unknown): error is CrewScopeApiError { return error instanceof CrewScopeApiError && (error.status === 409 || error.status === 412) }
export function presentError(error: unknown, fallback: string): string { return error instanceof CrewScopeApiError ? error.envelope.message : fallback }
