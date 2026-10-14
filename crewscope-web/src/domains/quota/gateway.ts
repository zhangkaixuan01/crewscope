import { inject, type App, type InjectionKey } from 'vue'
import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { OrganizationQuotaConfiguration, QuotaCommandReceipt, QuotaUsageView } from './types'

/**
 * M12-F02 quota HTTP adapter. Every method maps the response through an explicit
 * whitelist (organization gateway precedent): unknown shapes and missing fields fail
 * loudly at the browser boundary instead of rendering phantom zeros.
 */
export interface QuotaGateway {
  quota(organizationId: string, signal?: AbortSignal): Promise<OrganizationQuotaConfiguration>
  /**
   * `month === null` is the anchor call: no query parameter, so the server answers its
   * current reporting month (single source — the browser never guesses the month).
   */
  usage(organizationId: string, month: string | null, signal?: AbortSignal): Promise<QuotaUsageView>
  updateQuota(
    organizationId: string,
    input: { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<QuotaCommandReceipt>
}

export const QUOTA_GATEWAY: InjectionKey<QuotaGateway> = Symbol('crewscope-quota-gateway')

export class HttpQuotaGateway implements QuotaGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async quota(organizationId: string, signal?: AbortSignal): Promise<OrganizationQuotaConfiguration> {
    return mapConfiguration(await this.client.get(quotaRoot(organizationId), { signal }))
  }

  async usage(organizationId: string, month: string | null, signal?: AbortSignal): Promise<QuotaUsageView> {
    const path = month === null
      ? `${quotaRoot(organizationId)}/usage`
      : `${quotaRoot(organizationId)}/usage?month=${encodeURIComponent(month)}`
    return mapUsage(await this.client.get(path, { signal }))
  }

  async updateQuota(
    organizationId: string,
    input: { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<QuotaCommandReceipt> {
    // Both dimensions ride every write explicitly: the contract's "absent = clear" wire
    // default is a foot-gun for a full-form editor, so the page always speaks both values.
    const response = await this.client.open(quotaRoot(organizationId), {
      method: 'PUT',
      idempotencyKey,
      expectedVersion,
      body: {
        monthlyTaskIntakeLimit: input.monthlyTaskIntakeLimit,
        monthlyTokenBudget: input.monthlyTokenBudget,
      },
    })
    const receipt = mapReceipt(await response.json())
    return response.headers.get('Idempotency-Replayed') === 'true'
      ? { ...receipt, replayed: true }
      : receipt
  }
}

export function installQuotaGateway(app: App, gateway: QuotaGateway): void {
  app.provide(QUOTA_GATEWAY, gateway)
}

export function useQuotaGateway(): QuotaGateway {
  const gateway = inject(QUOTA_GATEWAY)
  if (!gateway) throw new Error('CrewScope Quota Gateway is not installed')
  return gateway
}

function quotaRoot(organizationId: string): string {
  return `/organizations/${encodeURIComponent(organizationId)}/quota`
}

function mapConfiguration(input: unknown): OrganizationQuotaConfiguration {
  const value = record(input)
  return {
    monthlyTaskIntakeLimit: nullableInteger(value.monthlyTaskIntakeLimit),
    monthlyTokenBudget: nullableInteger(value.monthlyTokenBudget),
    version: nonNegativeInteger(value.version),
  }
}

function mapUsage(input: unknown): QuotaUsageView {
  const value = record(input)
  const intake = record(value.monthlyTaskIntake)
  const budget = record(value.monthlyTokenBudget)
  const actual = record(budget.actualTokens)
  return {
    month: monthOf(value.month),
    monthlyTaskIntake: { limit: nullableInteger(intake.limit), count: nonNegativeInteger(intake.count) },
    monthlyTokenBudget: {
      limit: nullableInteger(budget.limit),
      reservedTokens: nonNegativeInteger(budget.reservedTokens),
      actualTokens: {
        input: nonNegativeInteger(actual.input),
        output: nonNegativeInteger(actual.output),
        cachedInput: nonNegativeInteger(actual.cachedInput),
        total: nonNegativeInteger(actual.total),
      },
    },
    materialized: booleanOf(value.materialized),
  }
}

function mapReceipt(input: unknown): QuotaCommandReceipt {
  const value = record(input)
  return {
    commandId: nonEmptyString(value.commandId),
    domainEventId: nonEmptyString(value.domainEventId),
    committedVersion: nonNegativeInteger(value.committedVersion),
    correlationId: nonEmptyString(value.correlationId),
    replayed: false,
  }
}

function monthOf(input: unknown): string {
  const value = nonEmptyString(input)
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(value)) throw new TypeError('Quota usage month is invalid')
  return value
}

function booleanOf(input: unknown): boolean {
  if (typeof input !== 'boolean') throw new TypeError('Quota response boolean is invalid')
  return input
}

function nullableInteger(input: unknown): number | null {
  return input === null ? null : nonNegativeInteger(input)
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Quota response object is invalid')
  }
  return input as Record<string, unknown>
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Quota response string is invalid')
  return input
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) {
    throw new TypeError('Quota response integer is invalid')
  }
  return input
}
