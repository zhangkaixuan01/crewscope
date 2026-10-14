/**
 * M12-F02 quota domain types, mirrored field-by-field from the A02 contract
 * (docs/api/M12-配额与用量API契约.md §1/§4). Null dimensions mean "unconfigured"
 * (the admission path skips them), never zero — the server rejects 0/negative
 * limits as configuration errors (422 invalid_value).
 */

/** GET /quota — both dimensions plus the optimistic-lock version. */
export interface OrganizationQuotaConfiguration {
  monthlyTaskIntakeLimit: number | null
  monthlyTokenBudget: number | null
  version: number
}

/** PUT /quota answers the shared command receipt; replay re-answers the original. */
export interface QuotaCommandReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
  replayed: boolean
}

/** The actual-token breakdown of one month (org+month rollup aggregation). */
export interface QuotaTokenUsage {
  input: number
  output: number
  cachedInput: number
  total: number
}

/** GET /quota/usage — one reporting month; limits mirror the configuration view. */
export interface QuotaUsageView {
  month: string
  monthlyTaskIntake: { limit: number | null, count: number }
  monthlyTokenBudget: { limit: number | null, reservedTokens: number, actualTokens: QuotaTokenUsage }
  /** False while the rollup projection has not caught up with this month yet. */
  materialized: boolean
}

/**
 * The reporting-month shape the server accepts — `YYYY-MM`. The browser never derives
 * months from its own clock: the anchor month comes from the parameterless usage call
 * (server single source, QuotaMonthCalendar) and trend months are pure string arithmetic
 * backwards from it, so a timezone skew can never manufacture a future month (400).
 */
export const QUOTA_MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/

export function isQuotaMonth(value: string): boolean {
  return QUOTA_MONTH_PATTERN.test(value)
}

/**
 * The `count` months ending at `anchor`, oldest first (`quotaMonthsBack('2026-10', 6)`
 * → ['2026-05' … '2026-10']). String arithmetic only — no Date, no timezone.
 */
export function quotaMonthsBack(anchor: string, count: number): string[] {
  if (!isQuotaMonth(anchor) || !Number.isInteger(count) || count < 1) {
    throw new TypeError('Quota month window arguments are invalid')
  }
  const months: string[] = []
  let year = Number(anchor.slice(0, 4))
  let month = Number(anchor.slice(5, 7))
  for (let offset = count - 1; offset >= 0; offset -= 1) {
    let cursorYear = year
    let cursorMonth = month - offset
    while (cursorMonth <= 0) {
      cursorMonth += 12
      cursorYear -= 1
    }
    months.push(`${String(cursorYear).padStart(4, '0')}-${String(cursorMonth).padStart(2, '0')}`)
  }
  return months
}
