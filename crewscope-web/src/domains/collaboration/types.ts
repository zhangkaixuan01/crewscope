/**
 * Frame and scope types for the M11 collaboration signal channel
 * (docs/api/M11-协作信号API契约.md, frozen V1).
 *
 * Types are derived from the contract, not from observed server behavior: every
 * inbound frame shape mirrors its contract section (§1 connection/subscribe,
 * §2 signals), so a contract change fails typechecking here first.
 */

/** §1.2 — strictly lowercase; the Team granularity carries no resource pair. */
export type CollaborationResourceType = 'work_project' | 'work_item' | 'conversation'

/** §1.2 — subscription coordinates; byte-compatible with every signal frame's scope. */
export interface CollaborationScope {
  organization: string
  team: string
  resourceType?: CollaborationResourceType
  resourceId?: string
}

/** Stable aggregation key for one scope across presence/typing/change state. */
export function collaborationScopeKey(scope: CollaborationScope): string {
  return scope.resourceType
    ? `${scope.organization}/${scope.team}/${scope.resourceType}/${scope.resourceId}`
    : `${scope.organization}/${scope.team}`
}

// ------------------------------------------------------------------ inbound frames

/** §1.1 — runtime parameters are per-connection and must never be hardcoded. */
export interface WelcomeFrame {
  type: 'welcome'
  connectionId: string
  principalId: string
  typingWindowSeconds: number
  maxInboundSignalsPerSecond: number
}

export interface SubscribedFrame {
  type: 'subscribed'
  subscriptionId: string
  scope: CollaborationScope
}

export interface UnsubscribedFrame {
  type: 'unsubscribed'
  subscriptionId: string
}

/** §3 — error frames; `subscriptionId` rides only on unknown_subscription. */
export interface ErrorFrame {
  type: 'error'
  code: string
  subscriptionId?: string
  frameType?: string
}

/** §2.1/§2.2 — the public presence fields; a principal's total connection count. */
export interface PresenceSignalEntry {
  principalId: string
  displayName: string
  connections: number
}

/** §2.1 — resource/project granularities only; the snapshot includes the subscriber. */
export interface PresenceSnapshotFrame {
  type: 'presence_snapshot'
  subscriptionId: string
  scope: CollaborationScope
  present: PresenceSignalEntry[]
}

/** §2.2 — leave with connections > 0 keeps the principal present. */
export interface PresenceDeltaFrame {
  type: 'presence_delta'
  subscriptionId: string
  scope: CollaborationScope
  action: 'enter' | 'leave'
  principalId: string
  displayName: string
  connections: number
}

/** §2.3 — the server never re-sends stopped; client self-clears. */
export interface TypingSignalFrame {
  type: 'typing'
  subscriptionId: string
  scope: CollaborationScope
  principalId: string
  displayName: string
  state: 'started' | 'stopped'
}

/** §2.4 — coordinates + version only; the scope names the changed resource. */
export interface ResourceChangedFrame {
  type: 'resource_changed'
  subscriptionId: string
  scope: CollaborationScope
  version: number
}

export interface PingFrame {
  type: 'ping'
}

export type CollaborationInboundFrame =
  | WelcomeFrame
  | SubscribedFrame
  | UnsubscribedFrame
  | ErrorFrame
  | PresenceSnapshotFrame
  | PresenceDeltaFrame
  | TypingSignalFrame
  | ResourceChangedFrame
  | PingFrame

// ------------------------------------------------------------------ outbound frames

export type PongFrame = { type: 'pong' }

export type SubscribeFrame = { type: 'subscribe'; scope: CollaborationScope }

export type UnsubscribeFrame = { type: 'unsubscribe'; subscriptionId: string }

export type TypingCommandFrame = {
  type: 'typing'
  subscriptionId: string
  state: 'started' | 'stopped'
}
