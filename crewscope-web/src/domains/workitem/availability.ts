import { safeRoute } from '../shared/route'
import {
  workItemStatuses,
  workItemTransitionBlockReasons,
  workItemTransitionStrengths,
} from './types'
import type {
  WorkItemAvailableTransition,
  WorkItemStatus,
  WorkItemTransitionBlockReason,
  WorkItemTransitionStrength,
} from './types'

/**
 * Reads one availability entry defensively.
 *
 * Two invariants are enforced here rather than in the component: a disabled action must carry a
 * reason (otherwise the UI would render a silent dead button, the exact defect M9 set out to fix),
 * and a remedy route must pass {@link safeRoute} before it can ever reach the router.
 *
 * Shared by the per-object availability endpoint and the WorkDesk projection so both surfaces accept
 * exactly the same object — the WorkDesk returns the same entries, only fewer of them.
 */
export function readAvailableTransition(input: unknown): WorkItemAvailableTransition {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Invalid transition availability entry')
  }
  const value = input as Record<string, unknown>
  const targetStatus = text(value.targetStatus)
  if (!workItemStatuses.includes(targetStatus as WorkItemStatus)) {
    throw new TypeError('Invalid transition target status')
  }
  const strength = text(value.strength)
  if (!workItemTransitionStrengths.includes(strength as WorkItemTransitionStrength)) {
    throw new TypeError('Invalid transition strength')
  }
  const enabled = value.enabled === true
  const reason = value.reason == null ? null : text(value.reason)
  if (reason !== null && !workItemTransitionBlockReasons.includes(reason as WorkItemTransitionBlockReason)) {
    throw new TypeError('Invalid transition block reason')
  }
  if (!enabled && reason === null) throw new TypeError('Disabled transition without a reason')
  const remedyRoute = value.remedyRoute == null ? null : text(value.remedyRoute)
  if (remedyRoute !== null && !safeRoute(remedyRoute)) throw new TypeError('Unsafe transition remedy route')
  return {
    actionId: text(value.actionId),
    targetStatus: targetStatus as WorkItemStatus,
    label: text(value.label),
    strength: strength as WorkItemTransitionStrength,
    reversible: value.reversible === true,
    enabled,
    reason: reason as WorkItemTransitionBlockReason | null,
    reasonMessage: value.reasonMessage == null ? null : text(value.reasonMessage),
    remedyLabel: value.remedyLabel == null ? null : text(value.remedyLabel),
    remedyRoute,
  }
}

/** Lower rank wins. A DANGER action is only ever the headline when nothing safer is available. */
const strengthPriority: Record<WorkItemTransitionStrength, number> = {
  PRIMARY: 0,
  SECONDARY: 1,
  DANGER: 2,
}

/**
 * Picks the one action a compact surface should headline, or `null` when the member may execute none.
 *
 * Ordering is deliberate and not the server's: the server sorts by target status so the list is
 * stable, which would otherwise headline `标记阻塞` over `提交评审` on an in-progress item. Within one
 * strength the server's order is kept, so this narrows the list without inventing a second ranking.
 */
export function primaryAction(
  actions: readonly WorkItemAvailableTransition[],
): WorkItemAvailableTransition | null {
  let best: WorkItemAvailableTransition | null = null
  let bestRank = Number.POSITIVE_INFINITY
  for (const action of actions) {
    if (!action.enabled) continue
    const rank = strengthPriority[action.strength]
    if (rank < bestRank) {
      best = action
      bestRank = rank
    }
  }
  return best
}

function text(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid transition text')
  return value
}
