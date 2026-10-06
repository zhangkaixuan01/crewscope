import { apiClient, type CrewScopeApiClient } from '../../api/client'
import {
  observabilityCostSources,
  observabilityCostStatuses,
  observabilityUsageRoles,
  isObservabilityMonth,
  type ObservabilityCostModelRow,
  type ObservabilityCostMonth,
  type ObservabilityCostMonthDetail,
  type ObservabilityCostMonthsPage,
  type ObservabilityCostSource,
  type ObservabilityCostStatus,
  type ObservabilityCurrencyAmount,
  type ObservabilityQualityMonth,
  type ObservabilityRoleSubtotal,
  type ObservabilityScope,
  type ObservabilityUsageRole,
} from './types'

export interface ObservabilityGateway {
  /** Month-descending keyset page of usage summaries; `after` is a month cursor. */
  listCostMonths(scope: ObservabilityScope, after?: string | null, limit?: number, signal?: AbortSignal): Promise<ObservabilityCostMonthsPage>
  costMonth(scope: ObservabilityScope, month: string, signal?: AbortSignal): Promise<ObservabilityCostMonthDetail>
  qualityMonth(scope: ObservabilityScope, month: string, signal?: AbortSignal): Promise<ObservabilityQualityMonth>
}

/**
 * Strict allowlist adapter for the M10-F03 read contract. Amounts must arrive as plain decimal
 * text (never scientific notation) and every enum must land in its closed set — an observability
 * page that silently renders a mangled number is worse than one that refuses to render.
 */
export class HttpObservabilityGateway implements ObservabilityGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listCostMonths(
    scope: ObservabilityScope,
    after?: string | null,
    limit = 12,
    signal?: AbortSignal,
  ): Promise<ObservabilityCostMonthsPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (after) search.set('after', after)
    const value = record(await this.client.get(`${root(scope)}/cost/months?${search}`, { signal }))
    const months = Array.isArray(value.months) ? value.months.map(mapCostMonth) : fail('months page')
    const nextAfter = value.nextAfter == null ? null : month(value.nextAfter, 'nextAfter')
    return { months, nextAfter }
  }

  async costMonth(scope: ObservabilityScope, monthValue: string, signal?: AbortSignal): Promise<ObservabilityCostMonthDetail> {
    const value = record(await this.client.get(`${root(scope)}/cost/months/${segment(month(monthValue, 'month'))}`, { signal }))
    const detailMonth = month(typeof value.month === 'string' ? value.month : monthValue, 'detail month')
    return { month: detailMonth, rows: Array.isArray(value.rows) ? value.rows.map(mapModelRow) : fail('detail rows') }
  }

  async qualityMonth(scope: ObservabilityScope, monthValue: string, signal?: AbortSignal): Promise<ObservabilityQualityMonth> {
    const value = record(await this.client.get(`${root(scope)}/quality/months/${segment(month(monthValue, 'month'))}`, { signal }))
    const execution = record(value.executionAttempts, 'executionAttempts')
    const review = record(value.reviewFirstPass, 'reviewFirstPass')
    return {
      month: month(typeof value.month === 'string' ? value.month : monthValue, 'quality month'),
      executionAttempts: {
        total: integer(execution.total, 'executionAttempts.total'),
        completed: integer(execution.completed, 'executionAttempts.completed'),
        failed: integer(execution.failed, 'executionAttempts.failed'),
        cancelled: integer(execution.cancelled, 'executionAttempts.cancelled'),
        successRate: rate(execution.successRate, 'executionAttempts.successRate'),
      },
      reviewFirstPass: {
        enteredReview: integer(review.enteredReview, 'reviewFirstPass.enteredReview'),
        firstPassApproved: integer(review.firstPassApproved, 'reviewFirstPass.firstPassApproved'),
        firstPassRate: rate(review.firstPassRate, 'reviewFirstPass.firstPassRate'),
      },
    }
  }
}

function root(scope: ObservabilityScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/observability`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function mapCostMonth(input: unknown): ObservabilityCostMonth {
  const value = record(input)
  const roles: Partial<Record<ObservabilityCostSource, ObservabilityRoleSubtotal>> = {}
  if (value.roles != null) {
    const source = record(value.roles, 'roles')
    for (const key of Object.keys(source)) {
      if (!(observabilityCostSources as readonly string[]).includes(key)) fail(`role source ${key}`)
      const row = record(source[key], 'role subtotal')
      roles[key as ObservabilityCostSource] = {
        inputTokens: integer(row.inputTokens, 'inputTokens'),
        outputTokens: integer(row.outputTokens, 'outputTokens'),
        cachedTokens: integer(row.cachedTokens, 'cachedTokens'),
        factCount: integer(row.factCount, 'factCount'),
      }
    }
  }
  return {
    month: month(value.month, 'month'),
    roles,
    currencies: Array.isArray(value.currencies) ? value.currencies.map(mapCurrency) : fail('currencies'),
    unpricedTokens: integer(value.unpricedTokens, 'unpricedTokens'),
    totalFactCount: integer(value.totalFactCount, 'totalFactCount'),
  }
}

function mapCurrency(input: unknown): ObservabilityCurrencyAmount {
  const value = record(input)
  return {
    currency: text(value.currency, 3, 'currencies.currency'),
    inputCost: amount(value.inputCost),
    outputCost: amount(value.outputCost),
    cachedInputCost: amount(value.cachedInputCost),
  }
}

function mapModelRow(input: unknown): ObservabilityCostModelRow {
  const value = record(input)
  const role = value.role
  if (typeof role !== 'string' || !(observabilityUsageRoles as readonly string[]).includes(role)) fail(`usage role ${role}`)
  const costStatus = value.costStatus
  if (typeof costStatus !== 'string' || !(observabilityCostStatuses as readonly string[]).includes(costStatus)) fail(`cost status ${costStatus}`)
  const catalogRevision = optionalInteger(value.catalogRevision, 'catalogRevision')
  const priceRevision = optionalInteger(value.priceRevision, 'priceRevision')
  // Contract §1.1 shape gates: the price triple is either fully present (PRICED) or fully absent.
  if ((catalogRevision == null) !== (priceRevision == null)) fail('price revision pair')
  return {
    role: role as ObservabilityUsageRole,
    providerKey: text(value.providerKey, 64, 'providerKey'),
    modelId: text(value.modelId, 128, 'modelId'),
    currencyCode: text(value.currencyCode, 3, 'currencyCode'),
    catalogRevision,
    priceRevision,
    attempt: integer(value.attempt, 'attempt'),
    inputTokens: integer(value.inputTokens, 'inputTokens'),
    outputTokens: integer(value.outputTokens, 'outputTokens'),
    cachedTokens: integer(value.cachedTokens, 'cachedTokens'),
    inputCost: amount(value.inputCost),
    outputCost: amount(value.outputCost),
    cachedInputCost: amount(value.cachedInputCost),
    factCount: integer(value.factCount, 'factCount'),
    unreportedFactCount: integer(value.unreportedFactCount, 'unreportedFactCount'),
    costStatus: costStatus as ObservabilityCostStatus,
  }
}

/** `toPlainString()` text — plain decimal only, never scientific notation (contract §5). */
function amount(value: unknown): string | null {
  if (value == null) return null
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)(\.\d+)?$/.test(value)) fail('amount')
  return value
}

/** Null when the denominator is empty; otherwise a rate at scale 4 within [0, 1]. */
function rate(value: unknown, field: string): number | null {
  if (value == null) return null
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1) fail(field)
  return value
}

function month(value: unknown, field: string): string {
  if (typeof value !== 'string' || !isObservabilityMonth(value)) fail(field)
  return value
}

function text(value: unknown, maxLength: number, field: string): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > maxLength) fail(field)
  return value
}

function integer(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) fail(field)
  return value
}

function optionalInteger(value: unknown, field: string): number | null {
  if (value == null) return null
  return integer(value, field)
}

function record(value: unknown, field = 'response'): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(field)
  return value as Record<string, unknown>
}

function fail(field: string): never {
  throw new TypeError(`Invalid observability ${field}`)
}
