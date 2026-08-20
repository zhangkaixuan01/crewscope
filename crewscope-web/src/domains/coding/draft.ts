import type { CodingScope } from './types'

/** Stable, Scope-partitioned key for safe browser-only CodingTarget form recovery. */
export function codingTargetDraftKey(scope: CodingScope, workItemId: string): string {
  return `crewscope:coding-target:v1:${scope.organizationId}:${scope.teamId}:${scope.projectId}:${workItemId}`
}

export function clearCodingTargetDraft(scope: CodingScope, workItemId: string): void {
  try {
    sessionStorage.removeItem(codingTargetDraftKey(scope, workItemId))
  } catch {
    // Storage availability never changes the server-side Task creation contract.
  }
}
