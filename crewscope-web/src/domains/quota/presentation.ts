import type { ChartBar, ChartReference, ChartSeries } from '../../components/charts/types'
import { quotaTokenPartLabels, quotaTrendSeriesLabels } from './labels'
import type { OrganizationQuotaConfiguration, QuotaUsageView } from './types'

/**
 * Structured Chinese problem copy for the quota surfaces (organizationProblem
 * precedent). Store command state carries status+code but no exception, so the core
 * takes those; null for unknown shapes and the caller falls back to the raw message.
 */
export interface QuotaProblem {
  title: string
  message: string
}

export function quotaProblem(status: number | null, code: string | null): QuotaProblem | null {
  if (status === 422) {
    return {
      title: '配额值无效',
      message: '上限必须不小于 1；0 或负数是配置错误。如需限制新增任务，请设置一个明确的小限额。',
    }
  }
  if (status === 409 && code === 'optimistic_lock_conflict') {
    return {
      title: '内容已变化',
      message: '其他操作先一步更新了配额配置。页面会加载最新版本，请基于最新状态重试。',
    }
  }
  if (status === 403) {
    return {
      title: '组织角色不足',
      message: '当前组织角色无权执行此操作。如果你最近被授予或撤销了角色，刷新页面更新会话后重试。',
    }
  }
  return null
}

/**
 * Task creation denied by the organization quota (A02: 403 policy_denied with details.action).
 * The two action constants are the wire contract verbatim — Java source:
 * OrganizationQuotaAdmissionService.java:19-23 (adjacent-string concatenation resolved).
 * Unknown actions return null so the caller keeps the raw server message.
 */
const ADMISSION_ACTIONS: Record<string, string> = {
  'create more agent tasks this month (organization monthly task intake limit reached)':
    '本月组织任务受理数已达上限，任务创建被拒绝。请联系组织管理员在 组织设置 → 配额与用量 调整每月任务受理上限，或等待下月配额重置。',
  'create an agent task reserving additional model tokens this month (organization monthly token budget reached)':
    '本月组织 Token 预算已用尽，任务创建被拒绝。请联系组织管理员在 组织设置 → 配额与用量 调整每月 Token 预算，或等待下月配额重置。',
}

export function quotaAdmissionProblem(action: string | null | undefined): string | null {
  if (action === null || action === undefined) return null
  return ADMISSION_ACTIONS[action] ?? null
}

/** Everything the intake LineChart needs: one series plus the current-limit reference. */
export function intakeTrendChart(months: QuotaUsageView[], configuration: OrganizationQuotaConfiguration | null): {
  series: ChartSeries[],
  reference: ChartReference | null,
} {
  return {
    series: [{
      key: 'intake',
      label: quotaTrendSeriesLabels.intake,
      tone: 'info',
      points: months.map(month => ({ x: month.month, y: month.monthlyTaskIntake.count })),
    }],
    // The reference line is a projection of the *current* limit onto history, not a
    // per-month fact (the configuration endpoint has no history) — the page says so.
    reference: configuration?.monthlyTaskIntakeLimit != null
      ? { label: '当前受理上限', value: configuration.monthlyTaskIntakeLimit }
      : null,
  }
}

/** The token LineChart: reserved (commitment) and actual (rollup) side by side, never merged. */
export function tokenTrendChart(months: QuotaUsageView[], configuration: OrganizationQuotaConfiguration | null): {
  series: ChartSeries[],
  reference: ChartReference | null,
} {
  return {
    series: [
      {
        key: 'reserved',
        label: quotaTrendSeriesLabels.reserved,
        tone: 'info',
        points: months.map(month => ({ x: month.month, y: month.monthlyTokenBudget.reservedTokens })),
      },
      {
        key: 'actual',
        label: quotaTrendSeriesLabels.actual,
        tone: 'agent',
        points: months.map(month => ({ x: month.month, y: month.monthlyTokenBudget.actualTokens.total })),
      },
    ],
    reference: configuration?.monthlyTokenBudget != null
      ? { label: '当前 Token 预算', value: configuration.monthlyTokenBudget }
      : null,
  }
}

/** The composition BarChart of one month's actual tokens (rollup dimensions). */
export function tokenCompositionChart(month: QuotaUsageView): { bars: ChartBar[] } {
  const parts = month.monthlyTokenBudget.actualTokens
  return {
    bars: [
      { key: 'input', label: quotaTokenPartLabels.input, value: parts.input, tone: 'info' },
      { key: 'output', label: quotaTokenPartLabels.output, value: parts.output, tone: 'info' },
      { key: 'cachedInput', label: quotaTokenPartLabels.cachedInput, value: parts.cachedInput, tone: 'agent' },
      { key: 'total', label: quotaTokenPartLabels.total, value: parts.total, tone: 'agent' },
    ],
  }
}
