/**
 * Hand-written mirrors of the M10-F03 observability API DTOs
 * (docs/api/M10-成本与质量观测API契约.md §5). Amounts arrive as `toPlainString()` text per
 * currency — the contract never converts or rounds across currencies, so the domain keeps them
 * as strings and only ever formats them for display.
 */

/** `TeamObservabilityQueryService.CostSource` — the three usage lanes the summary subtotals. */
export const observabilityCostSources = ['EXECUTION', 'EMBEDDING', 'DISTILLATION'] as const
export type ObservabilityCostSource = typeof observabilityCostSources[number]

/**
 * The raw `ModelUsageRole` of one detail row (the rollup grain keeps the five event roles; the
 * CHAT_* trio and COMPACTION all roll up into the EXECUTION source on the summary card).
 */
export const observabilityUsageRoles = ['CHAT_PRIMARY', 'CHAT_FALLBACK', 'COMPACTION', 'EMBEDDING', 'DISTILLATION'] as const
export type ObservabilityUsageRole = typeof observabilityUsageRoles[number]

export const observabilityCostStatuses = ['PRICED', 'UNPRICED'] as const
export type ObservabilityCostStatus = typeof observabilityCostStatuses[number]

export interface ObservabilityScope {
  organizationId: string
  teamId: string
}

/** Token subtotal for one source lane of one month. */
export interface ObservabilityRoleSubtotal {
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  factCount: number
}

/** One currency's priced amounts; absent lanes (no facts) simply do not appear in the list. */
export interface ObservabilityCurrencyAmount {
  currency: string
  inputCost: string | null
  outputCost: string | null
  cachedInputCost: string | null
}

export interface ObservabilityCostMonth {
  month: string
  /** Present only for lanes that recorded facts this month — absence means zero, not unknown. */
  roles: Partial<Record<ObservabilityCostSource, ObservabilityRoleSubtotal>>
  currencies: ObservabilityCurrencyAmount[]
  /** Tokens the projection could see but not price — UNKNOWN is never zero (S01 freeze). */
  unpricedTokens: number
  totalFactCount: number
}

/** Month-descending keyset page; `nextAfter` is the last month on the page. */
export interface ObservabilityCostMonthsPage {
  months: ObservabilityCostMonth[]
  nextAfter: string | null
}

/** One rollup grain row: role × model × currency × price revision × attempt. */
export interface ObservabilityCostModelRow {
  role: ObservabilityUsageRole
  providerKey: string
  modelId: string
  currencyCode: string
  catalogRevision: number | null
  priceRevision: number | null
  attempt: number
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  inputCost: string | null
  outputCost: string | null
  cachedInputCost: string | null
  factCount: number
  unreportedFactCount: number
  costStatus: ObservabilityCostStatus
}

export interface ObservabilityCostMonthDetail {
  month: string
  rows: ObservabilityCostModelRow[]
}

/** Null when the denominator is empty — no samples is not a zero rate (contract §5). */
export interface ObservabilityExecutionAttempts {
  total: number
  completed: number
  failed: number
  cancelled: number
  successRate: number | null
}

export interface ObservabilityReviewFirstPass {
  enteredReview: number
  firstPassApproved: number
  firstPassRate: number | null
}

export interface ObservabilityQualityMonth {
  month: string
  executionAttempts: ObservabilityExecutionAttempts
  reviewFirstPass: ObservabilityReviewFirstPass
}

/** The reporting month shape every path variable accepts — `YYYY-MM`. */
export const OBSERVABILITY_MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/

export function isObservabilityMonth(value: string): boolean {
  return OBSERVABILITY_MONTH_PATTERN.test(value)
}
