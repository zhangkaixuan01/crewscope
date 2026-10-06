<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import AppShell from '../components/layout/AppShell.vue'
import BaseButton from '../components/base/BaseButton.vue'
import BaseTable from '../components/base/BaseTable.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useObservabilityStore } from '../domains/observability/store'
import {
  OBSERVABILITY_UNPRICED_CURRENCY,
  OBSERVABILITY_UNPRICED_HINT,
  observabilityCostSourceLabels,
  observabilityCostStatusLabels,
  observabilityUsageRoleLabels,
} from '../domains/observability/labels'
import {
  isObservabilityMonth,
  observabilityCostSources,
  observabilityUsageRoles,
  type ObservabilityCostSource,
  type ObservabilityUsageRole,
} from '../domains/observability/types'
import type { SettingsScope } from '../domains/settings/types'
import { useScopeStore } from '../domains/scope/store'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useObservabilityStore()
const online = useNetworkStatus()

const scope = computed<SettingsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const scopeMissing = computed(() => scopeStore.state.phase === 'ready' && !scope.value)

const months = computed(() => store.state.months.value ?? [])
/** Deep-linked month wins while it exists on the page; otherwise the newest month leads. */
const selectedMonth = computed(() => {
  const deep = queryMonth()
  return months.value.find(item => item.month === deep)?.month ?? months.value[0]?.month ?? null
})
const selectedSummary = computed(() => months.value.find(item => item.month === selectedMonth.value) ?? null)
const roleFilter = computed<ObservabilityUsageRole | 'ALL'>(() => {
  const value = queryText(route.query.role)
  return value && (observabilityUsageRoles as readonly string[]).includes(value)
    ? value as ObservabilityUsageRole
    : 'ALL'
})
const detailRows = computed(() => {
  const rows = store.state.detail.value?.rows ?? []
  return roleFilter.value === 'ALL' ? rows : rows.filter(row => row.role === roleFilter.value)
})

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId] as const,
  ([phase]) => {
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    void store.loadMonths(false, true)
  },
  { immediate: true },
)

/** The single month-load path: navigation clicks only rewrite the query, this watcher loads. */
watch(selectedMonth, async month => {
  const pageOwner = pageRequests.capture()
  if (!month || !scope.value) return
  await store.loadMonth(month)
  if (!pageOwner.isCurrent()) return
  const query = { ...route.query }
  if (queryText(query.month) === month) return
  // A deep-linked month that is not on the loaded page yet keeps its URL — overwriting it
  // with the newest month would silently discard the shared link until older months load.
  if (queryMonth()) return
  query.month = month
  void router.replace({ query })
})

async function refresh(): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!scope.value) return
  await store.loadMonths(false, true)
  if (!pageOwner.isCurrent()) return
  if (selectedMonth.value) await store.loadMonth(selectedMonth.value)
}

function selectMonth(month: string): void {
  const query = { ...route.query }
  query.month = month
  void router.replace({ query })
}

function changeRole(value: ObservabilityUsageRole | 'ALL'): void {
  const query = { ...route.query }
  if (value === 'ALL') delete query.role
  else query.role = value
  void router.replace({ query })
}

function sourceTokens(source: ObservabilityCostSource): { tokens: number, facts: number } | null {
  const subtotal = selectedSummary.value?.roles[source]
  if (!subtotal) return null
  return {
    tokens: subtotal.inputTokens + subtotal.outputTokens,
    facts: subtotal.factCount,
  }
}

function formatTokens(value: number): string {
  return value.toLocaleString('zh-CN')
}

/** Rates arrive at scale 4; the card keeps one decimal and always names its denominator. */
function formatRate(rate: number | null): string {
  return rate == null ? '暂无样本' : `${(rate * 100).toFixed(1)}%`
}

function queryMonth(): string | null {
  const value = queryText(route.query.month)
  return value && isObservabilityMonth(value) ? value : null
}

function queryText(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}
</script>

<template>
  <AppShell title="成本质量" eyebrow="团队 · 用量与质量">
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scope ? undefined : 'observability-scope-reason'" @click="refresh">
        <RefreshCw :size="14" />刷新
      </BaseButton>
    </template>

    <StatePanel v-if="scopeMissing" id="observability-scope-reason" state="empty" title="请选择 Team" description="用量与成本始终属于明确的 Organization 与 Team。" />

    <div v-if="scopeStore.state.phase === 'ready' && scope" class="observability-workspace">
      <StatePanel v-if="store.state.months.phase === 'loading'" state="loading" title="正在加载用量月份" />
      <StatePanel v-else-if="store.state.months.phase === 'error'" state="error" :title="store.state.months.errorMessage ?? '暂时无法加载用量月份'">
        <template #action><BaseButton variant="secondary" size="small" @click="refresh">重试</BaseButton></template>
      </StatePanel>
      <StatePanel v-else-if="store.state.months.phase === 'empty'" state="empty" title="本月还没有模型用量事实" description="执行任务、提炼知识或构建索引后，用量会按月聚合在这里。" />

      <template v-if="selectedSummary && selectedMonth">
        <nav class="month-nav" aria-label="用量月份">
          <button
            v-for="item in months" :key="item.month" type="button"
            class="month-nav__item" :aria-pressed="item.month === selectedMonth"
            @click="selectMonth(item.month)"
          >{{ item.month }}</button>
          <BaseButton
            v-if="store.state.months.nextAfter" variant="secondary" size="small"
            :loading="store.state.months.loadingMore" @click="store.loadMonths(true)"
          >加载更早月份</BaseButton>
        </nav>

        <section class="panel" aria-labelledby="cost-heading">
          <h2 id="cost-heading" class="panel__title">{{ selectedMonth }} 成本概览</h2>
          <p v-if="selectedSummary.unpricedTokens > 0" class="unpriced-hint">{{ OBSERVABILITY_UNPRICED_HINT }}（{{ formatTokens(selectedSummary.unpricedTokens) }} token，{{ OBSERVABILITY_UNPRICED_CURRENCY }} 行）</p>
          <dl class="metric-grid">
            <template v-for="source in observabilityCostSources" :key="source">
              <div v-if="sourceTokens(source)" class="metric">
                <dt>{{ observabilityCostSourceLabels[source] }}</dt>
                <dd>{{ formatTokens(sourceTokens(source)!.tokens) }} token / {{ sourceTokens(source)!.facts }} 次调用</dd>
              </div>
            </template>
            <div class="metric">
              <dt>事实总数</dt>
              <dd>{{ selectedSummary.totalFactCount }} 条</dd>
            </div>
          </dl>
          <table v-if="selectedSummary.currencies.length > 0" class="currency-table">
            <caption class="sr-only">{{ selectedMonth }} 分币种费用</caption>
            <thead>
              <tr><th scope="col">币种</th><th scope="col">输入费用</th><th scope="col">输出费用</th><th scope="col">缓存费用</th></tr>
            </thead>
            <tbody>
              <tr v-for="currency in selectedSummary.currencies" :key="currency.currency">
                <th scope="row">{{ currency.currency }}</th>
                <td>{{ currency.inputCost ?? '—' }}</td>
                <td>{{ currency.outputCost ?? '—' }}</td>
                <td>{{ currency.cachedInputCost ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="panel__note">本月没有已计价的用量。</p>
        </section>

        <section class="panel" aria-labelledby="detail-heading">
          <h2 id="detail-heading" class="panel__title">模型明细</h2>
          <label class="role-filter">
            角色筛选
            <select :value="roleFilter" @change="changeRole(($event.target as HTMLSelectElement).value as ObservabilityUsageRole | 'ALL')">
              <option value="ALL">全部角色</option>
              <option v-for="role in observabilityUsageRoles" :key="role" :value="role">{{ observabilityUsageRoleLabels[role] }}</option>
            </select>
          </label>
          <StatePanel v-if="store.state.detail.phase === 'loading'" state="loading" title="正在加载当月成本明细" />
          <StatePanel v-else-if="store.state.detail.phase === 'error'" state="error" :title="store.state.detail.errorMessage ?? '暂时无法加载当月成本明细'" />
          <StatePanel v-else-if="detailRows.length === 0" state="empty" title="该角色本月没有用量行" />
          <BaseTable v-else :caption="`${selectedMonth} 模型用量明细`">
            <thead>
              <tr>
                <th scope="col">角色</th><th scope="col">Provider / 模型</th><th scope="col">价格修订</th>
                <th scope="col">Attempt</th><th scope="col">输入 token</th><th scope="col">输出 token</th>
                <th scope="col">缓存 token</th><th scope="col">费用（币种）</th><th scope="col">事实</th><th scope="col">计价</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in detailRows" :key="`${row.role}-${row.providerKey}-${row.modelId}-${row.currencyCode}-${row.catalogRevision}-${row.priceRevision}-${row.attempt}`">
                <td>{{ observabilityUsageRoleLabels[row.role] }}</td>
                <td>{{ row.providerKey }} / {{ row.modelId }}</td>
                <td>
                  <span v-if="row.priceRevision != null" class="price-revision">目录 {{ row.catalogRevision }} · 价格 {{ row.priceRevision }}</span>
                  <span v-else aria-label="无价格修订">—</span>
                </td>
                <td>{{ row.attempt }}</td>
                <td>{{ formatTokens(row.inputTokens) }}</td>
                <td>{{ formatTokens(row.outputTokens) }}</td>
                <td>{{ formatTokens(row.cachedTokens) }}</td>
                <td>{{ row.inputCost == null ? '—' : `${row.inputCost} + ${row.outputCost ?? '0'} ${row.currencyCode}` }}</td>
                <td>{{ row.factCount }}<span v-if="row.unreportedFactCount > 0" class="unreported">（未回显 {{ row.unreportedFactCount }}）</span></td>
                <td><StatusBadge :tone="row.costStatus === 'PRICED' ? 'success' : 'warning'">{{ observabilityCostStatusLabels[row.costStatus] }}</StatusBadge></td>
              </tr>
            </tbody>
          </BaseTable>
        </section>

        <section class="panel" aria-labelledby="quality-heading">
          <h2 id="quality-heading" class="panel__title">{{ selectedMonth }} 质量统计</h2>
          <StatePanel v-if="store.state.quality.phase === 'loading'" state="loading" title="正在加载当月质量统计" />
          <StatePanel v-else-if="store.state.quality.phase === 'error'" state="error" :title="store.state.quality.errorMessage ?? '暂时无法加载当月质量统计'" />
          <div v-else-if="store.state.quality.value" class="quality-grid">
            <div class="quality-card">
              <h3>执行成功率</h3>
              <p class="quality-card__rate">{{ formatRate(store.state.quality.value.executionAttempts.successRate) }}</p>
              <p class="quality-card__denominator">分母：{{ store.state.quality.value.executionAttempts.total }} 次执行尝试</p>
              <dl>
                <div><dt>完成</dt><dd>{{ store.state.quality.value.executionAttempts.completed }}</dd></div>
                <div><dt>失败</dt><dd>{{ store.state.quality.value.executionAttempts.failed }}</dd></div>
                <div><dt>取消</dt><dd>{{ store.state.quality.value.executionAttempts.cancelled }}</dd></div>
              </dl>
            </div>
            <div class="quality-card">
              <h3>Review 一次通过率</h3>
              <p class="quality-card__rate">{{ formatRate(store.state.quality.value.reviewFirstPass.firstPassRate) }}</p>
              <p class="quality-card__denominator">分母：{{ store.state.quality.value.reviewFirstPass.enteredReview }} 个进入 Review 的请求</p>
              <dl>
                <div><dt>一次通过</dt><dd>{{ store.state.quality.value.reviewFirstPass.firstPassApproved }}</dd></div>
              </dl>
            </div>
          </div>
        </section>
      </template>
    </div>
  </AppShell>
</template>

<style scoped>
.observability-workspace { display: grid; min-width: 0; gap: var(--cs-space-12); }
.month-nav { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.month-nav__item { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text-secondary); padding: var(--cs-space-4) var(--cs-space-12); font-size: var(--cs-text-sm); cursor: pointer; }
.month-nav__item[aria-pressed='true'] { border-color: var(--cs-focus); color: var(--cs-text); font-weight: var(--cs-weight-semibold); }
.month-nav__item:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.panel { display: grid; gap: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); padding: var(--cs-space-16); }
.panel__title { margin: 0; font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.panel__note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.unpriced-hint { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--cs-space-12); margin: 0; }
.metric { display: grid; gap: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); padding: var(--cs-space-12); }
.metric dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.metric dd { margin: 0; font-size: var(--cs-text-base); }
.currency-table { width: 100%; border-collapse: collapse; font-size: var(--cs-text-sm); }
.currency-table th, .currency-table td { padding: var(--cs-space-8) var(--cs-space-12); border-bottom: 1px solid var(--cs-border); text-align: left; }
.currency-table thead th { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }
.role-filter { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); justify-self: start; }
.role-filter select { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); padding: var(--cs-space-4) var(--cs-space-8); }
.price-revision { font-variant-numeric: tabular-nums; }
.unreported { color: var(--cs-text-muted); }
.quality-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: var(--cs-space-12); }
.quality-card { display: grid; gap: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); padding: var(--cs-space-16); }
.quality-card h3 { margin: 0; font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.quality-card__rate { margin: 0; font-size: var(--cs-text-2xl); font-weight: var(--cs-weight-semibold); }
.quality-card__denominator { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.quality-card dl { display: flex; gap: var(--cs-space-16); margin: 0; }
.quality-card dl div { display: grid; gap: var(--cs-space-2); }
.quality-card dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.quality-card dd { margin: 0; }
</style>
