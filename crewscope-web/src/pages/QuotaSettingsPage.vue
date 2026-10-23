<script setup lang="ts">
import { Gauge, TrendingUp } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BarChart from '../components/charts/BarChart.vue'
import LineChart from '../components/charts/LineChart.vue'
import BaseButton from '../components/base/BaseButton.vue'
import BaseTable from '../components/base/BaseTable.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useInlineSettingEditor } from '../composables/useInlineSettingEditor'
import { quotaTokenPartLabels } from '../domains/quota/labels'
import { intakeTrendChart, quotaProblem, tokenCompositionChart, tokenTrendChart } from '../domains/quota/presentation'
import { useQuotaStore } from '../domains/quota/store'
import { isQuotaMonth } from '../domains/quota/types'

/**
 * M12-F02 quota & usage. The route is not permission-gated (F01 precedent): pure ORG
 * role holders reach the page, and the member without org:audit-read gets the static
 * forbidden panel — the gate must not dispatch, or the global 403 sink would fire and
 * clear the F05 team scope for a request the UI could have answered locally.
 */
const principal = inject(AUTH_PRINCIPAL)
const route = useRoute()
const router = useRouter()
const store = useQuotaStore()

const organizationId = computed(() => principal?.organizationId ?? '')
const canReadSettings = computed(() => Boolean(principal && can(principal, permissions.orgAuditRead)))
const canWriteSettings = computed(() => Boolean(principal && can(principal, permissions.orgManageSettings)))

const configuration = computed(() => store.state.configuration)
const configurationValue = computed(() => configuration.value.value)
const command = computed(() => store.state.command)
const problem = computed(() => command.value.phase === 'error'
  ? quotaProblem(command.value.errorStatus, command.value.errorCode) : null)

// ── 配置编辑（F01 retention 编辑先例：空输入=清除该维度） ─────────────────────
const intakeInput = ref('')
const budgetInput = ref('')
// v-model on type=number inputs hands back numbers, so both refs hold string|number at runtime.
const intakeNumber = computed(() => Number.parseInt(String(intakeInput.value), 10))
const budgetNumber = computed(() => Number.parseInt(String(budgetInput.value), 10))
/** Empty means "clear this dimension" (null), not zero — 0/negative is a server 422. */
const intakePayload = computed(() => String(intakeInput.value).trim() === '' ? null : intakeNumber.value)
const budgetPayload = computed(() => String(budgetInput.value).trim() === '' ? null : budgetNumber.value)
const intakeValid = computed(() => intakePayload.value === null || (Number.isInteger(intakePayload.value) && intakePayload.value >= 1))
const budgetValid = computed(() => budgetPayload.value === null || (Number.isInteger(budgetPayload.value) && budgetPayload.value >= 1))
const payload = computed(() => [intakePayload.value, budgetPayload.value] as [number | null, number | null])

function syncInputs(intake: number | null, budget: number | null): void {
  intakeInput.value = intake === null ? '' : String(intake)
  budgetInput.value = budget === null ? '' : String(budget)
}

// Inline editor lifecycle (M12b-F04): dirty tracking, idempotent save, success/reset watches.
const { editing, submitted, dirtyForm, startEditing, cancelEditing, submit: saveQuota } = useInlineSettingEditor<[number | null, number | null]>({
  payload: () => payload.value,
  serverValue: () => configurationValue.value
    ? [configurationValue.value.monthlyTaskIntakeLimit ?? null, configurationValue.value.monthlyTokenBudget ?? null]
    : null,
  applyServerValue: value => syncInputs(value?.[0] ?? null, value?.[1] ?? null),
  valid: () => intakeValid.value && budgetValid.value,
  ready: () => configuration.value.phase === 'ready',
  phase: () => command.value.phase,
  clearCommand: () => store.clearCommand(),
  save: async key => store.updateQuota(
    organizationId.value,
    { monthlyTaskIntakeLimit: intakePayload.value, monthlyTokenBudget: budgetPayload.value },
    configurationValue.value?.version ?? 0,
    key,
  ),
})
const savingReason = computed(() => {
  if (configuration.value.phase !== 'ready') return '正在读取当前配额。'
  if (!intakeValid.value || !budgetValid.value) return '两个限额都必须不小于 1。'
  if (command.value.phase === 'pending') return '正在保存。'
  return ''
})

// ── 用量与趋势（月份导航走 URL，watch 是唯一加载路径——ObservabilityPage 先例） ──
const usage = computed(() => store.state.usage)
const anchorMonth = computed(() => usage.value.anchorMonth)
/** Deep-linked month wins only while it is a real past-or-current reporting month. */
const selectedMonth = computed(() => queryMonth() ?? anchorMonth.value)
const selectedEntry = computed(() => selectedMonth.value ? usage.value.months[selectedMonth.value] ?? null : null)
const selectedUsage = computed(() => selectedEntry.value?.value ?? null)
const trendMonths = computed(() => usage.value.windowMonths
  .map(month => usage.value.months[month]?.value ?? null)
  .filter((value): value is NonNullable<typeof value> => value !== null))
const intakeChart = computed(() => intakeTrendChart(trendMonths.value, configurationValue.value))
const tokenChart = computed(() => tokenTrendChart(trendMonths.value, configurationValue.value))
const compositionChart = computed(() => selectedUsage.value ? tokenCompositionChart(selectedUsage.value) : null)
const compositionRows = computed(() => selectedUsage.value
  ? (Object.keys(quotaTokenPartLabels) as Array<keyof typeof quotaTokenPartLabels>).map(key => ({
      key,
      label: quotaTokenPartLabels[key],
      value: selectedUsage.value!.monthlyTokenBudget.actualTokens[key],
    }))
  : [])

watch([organizationId, canReadSettings], ([id, readable]) => {
  if (!id || !readable) return
  void store.loadConfiguration(id)
  void store.loadUsageWindow(id)
}, { immediate: true })

// The anchor joining (null → month) re-triggers the load: a deep-linked month was refused
// while the ceiling was unknown, and it only becomes dispatchable against the anchor.
watch([selectedMonth, anchorMonth], ([month]) => {
  if (!month || !organizationId.value) return
  void store.loadUsageMonth(organizationId.value, month)
}, { immediate: true })

function selectMonth(month: string): void {
  // M13b-F11（I05）：重选同月只 replace 同一 query，watch 不触发——失败月份从此是一条死路。
  // 同月点击显式重发（loadUsageMonth 只缓存 ready，error 会重新请求）。
  if (month === selectedMonth.value && organizationId.value) {
    void store.loadUsageMonth(organizationId.value, month)
    return
  }
  void router.replace({ query: { ...route.query, month } })
}

function queryMonth(): string | null {
  const value = typeof route.query.month === 'string' ? route.query.month : ''
  // A future (or malformed) deep link falls back to the anchor instead of dispatching
  // a request the contract answers with 400.
  return value && isQuotaMonth(value) && (anchorMonth.value === null || value <= anchorMonth.value) ? value : null
}

// 无效深链不能只回退显示——URL 也要收敛，否则地址栏永远留着下一次刷新仍会被拒绝的
// 月份（M12b-F01⑥）。仅在 anchor 就绪后裁决：anchor 未知时无从判定「未来月份」。
watch([anchorMonth, () => route.query.month], ([anchor, month]) => {
  if (anchor === null || typeof month !== 'string') return
  if (isQuotaMonth(month) && month <= anchor) return
  const rest = { ...route.query }
  delete rest.month
  void router.replace({ query: rest })
})

function limitText(limit: number | null): string {
  return limit === null ? '未配置' : formatTokens(limit)
}

function formatTokens(value: number): string {
  return value.toLocaleString('zh-CN')
}
</script>

<template>
  <SettingsShell title="配额与用量">
    <div class="quota-settings">
      <StatePanel
        v-if="!canReadSettings"
        state="forbidden"
        title="需要组织审计角色"
        description="组织 Owner、组织管理员或组织审计员可以查看配额与用量。"
      >
        <template #action>
          <BaseButton variant="secondary" size="small" @click="router.push({ name: 'access-denied', query: { requiredPermission: permissions.orgAuditRead } })">查看权限说明</BaseButton>
        </template>
      </StatePanel>

      <template v-else>
        <section class="quota-card" aria-labelledby="quota-configuration-heading">
          <h2 id="quota-configuration-heading"><Gauge :size="16" aria-hidden="true" />配额配置</h2>
          <StatePanel v-if="configuration.phase === 'loading' || configuration.phase === 'idle'" state="loading" />
          <StatePanel
            v-else-if="configuration.phase === 'error'"
            state="error"
            description="配额配置读取失败。"
            @retry="organizationId && store.loadConfiguration(organizationId, true)"
          />
          <StatePanel
            v-else-if="configuration.phase === 'forbidden'"
            state="forbidden"
            title="需要组织审计角色"
            description="组织 Owner、组织管理员或组织审计员可以查看配额配置。"
          >
            <template #action>
              <BaseButton variant="secondary" size="small" @click="router.push({ name: 'access-denied', query: { requiredPermission: permissions.orgAuditRead } })">查看权限说明</BaseButton>
            </template>
          </StatePanel>
          <template v-else-if="configuration.phase === 'ready' && configurationValue">
            <p v-if="!editing" class="quota-current">
              每月任务受理上限 {{ limitText(configurationValue.monthlyTaskIntakeLimit) }}；每月 Token 预算 {{ limitText(configurationValue.monthlyTokenBudget) }}（数据版本 {{ configurationValue.version }}）。
              <BaseButton v-if="canWriteSettings" variant="secondary" size="small" @click="startEditing">调整配额</BaseButton>
            </p>
            <p v-else class="quota-current">未配置的维度不限制对应用量；0 不是合法限额。</p>
            <p v-if="command.phase === 'success' && !editing" class="quota-ok" role="status">配额已保存{{ command.receipt?.replayed ? '（该命令此前已受理，幂等重放）' : '' }}。</p>
            <form v-if="editing" class="quota-form" @submit.prevent="saveQuota">
              <div class="quota-field">
                <label for="quota-intake">每月任务受理上限</label>
                <input
                  id="quota-intake"
                  v-model="intakeInput"
                  type="number"
                  min="1"
                  inputmode="numeric"
                  :aria-invalid="submitted && !intakeValid"
                  aria-describedby="quota-intake-reason"
                  @input="dirtyForm.markDirty()"
                />
                <p id="quota-intake-reason" class="quota-reason" aria-live="polite">
                  {{ submitted && !intakeValid ? '限额必须不小于 1。' : '留空表示不限制每月任务受理数。' }}
                </p>
              </div>
              <div class="quota-field">
                <label for="quota-budget">每月 Token 预算</label>
                <input
                  id="quota-budget"
                  v-model="budgetInput"
                  type="number"
                  min="1"
                  inputmode="numeric"
                  :aria-invalid="submitted && !budgetValid"
                  aria-describedby="quota-budget-reason"
                  @input="dirtyForm.markDirty()"
                />
                <p id="quota-budget-reason" class="quota-reason" aria-live="polite">
                  {{ submitted && !budgetValid ? '限额必须不小于 1。' : '留空表示不限制每月 Token 预算。' }}
                </p>
              </div>
              <p v-if="command.phase === 'error'" role="alert" class="quota-error">
                {{ problem ? `${problem.title}：${problem.message}` : command.errorMessage }}
              </p>
              <div class="quota-actions">
                <BaseButton type="submit" aria-describedby="quota-save-reason" :disabled="Boolean(savingReason) || command.phase === 'pending'">
                  {{ command.phase === 'pending' ? '保存中…' : '保存配额' }}
                </BaseButton>
                <BaseButton variant="ghost" @click="cancelEditing">取消</BaseButton>
              </div>
              <!-- The id only resolves while disabled (savingReason non-empty) — exactly when
                   the assistive pairing is wanted; the p doubles as the aria-live status. -->
              <p v-if="savingReason" id="quota-save-reason" class="quota-reason" aria-live="polite">{{ savingReason }}</p>
            </form>
          </template>
        </section>

        <section class="quota-card" aria-labelledby="quota-usage-heading">
          <h2 id="quota-usage-heading">当月用量</h2>
          <StatePanel v-if="usage.phase === 'bootstrap' || usage.phase === 'loading'" state="loading" title="正在加载用量" />
          <StatePanel
            v-else-if="usage.phase === 'error'"
            state="error"
            :description="usage.errorMessage ?? '用量读取失败。'"
            @retry="organizationId && store.loadUsageWindow(organizationId, true)"
          />
          <StatePanel
            v-else-if="usage.phase === 'forbidden'"
            state="forbidden"
            title="需要组织审计角色"
            description="组织 Owner、组织管理员或组织审计员可以查看组织用量。"
          >
            <template #action>
              <BaseButton variant="secondary" size="small" @click="router.push({ name: 'access-denied', query: { requiredPermission: permissions.orgAuditRead } })">查看权限说明</BaseButton>
            </template>
          </StatePanel>
          <template v-else-if="usage.phase === 'ready' && selectedMonth">
            <nav class="quota-month-nav" aria-label="用量月份">
              <button
                v-for="month in usage.windowMonths" :key="month" type="button"
                class="quota-month-nav__item" :aria-pressed="month === selectedMonth"
                @click="selectMonth(month)"
              >{{ month }}</button>
            </nav>
            <!-- M13b-F11（I05）：单月错误不再是死路——面板自带重试，只重载该月（C2 局部重试局部）。 -->
            <StatePanel v-if="selectedEntry?.phase === 'error'" state="error" :description="selectedEntry?.errorMessage ?? '该月用量读取失败。'" @retry="organizationId && store.loadUsageMonth(organizationId, selectedMonth)" />
            <StatePanel v-else-if="selectedEntry?.phase === 'loading'" state="loading" :title="`正在加载 ${selectedMonth} 用量`" />
            <template v-else-if="selectedUsage">
              <p v-if="!selectedUsage.materialized" class="quota-note" role="status">实际用量统计存在投影延迟，本视图可能尚未包含最近的模型调用。</p>
              <dl class="quota-metrics">
                <div class="quota-metric">
                  <dt>任务受理（{{ selectedUsage.month }}）</dt>
                  <dd>{{ formatTokens(selectedUsage.monthlyTaskIntake.count) }} / {{ limitText(selectedUsage.monthlyTaskIntake.limit) }}</dd>
                </div>
                <div class="quota-metric">
                  <dt>预占 Token（承诺口径）</dt>
                  <dd>{{ formatTokens(selectedUsage.monthlyTokenBudget.reservedTokens) }} / {{ limitText(selectedUsage.monthlyTokenBudget.limit) }}</dd>
                </div>
                <div class="quota-metric">
                  <dt>实际 Token（rollup）</dt>
                  <dd>{{ formatTokens(selectedUsage.monthlyTokenBudget.actualTokens.total) }}</dd>
                </div>
              </dl>
              <BaseTable :caption="`${selectedUsage.month} 实际 Token 构成`">
                <thead>
                  <tr><th scope="col">维度</th><th scope="col">Token</th></tr>
                </thead>
                <tbody>
                  <tr v-for="row in compositionRows" :key="row.key">
                    <th scope="row">{{ row.label }}</th>
                    <td>{{ formatTokens(row.value) }}</td>
                  </tr>
                </tbody>
              </BaseTable>
              <p class="quota-note">
                预占按受理时任务预算上限记账，任务终态不释放；实际用量是跨团队的月度聚合，两者口径不同，不做归因对账。
              </p>
              <!-- M13b-F04（R21）: cross-link — the budget reminder face lives beside the usage it tracks. -->
              <p class="quota-note">
                预算提醒线（警戒比例）的消耗视图在<RouterLink class="quota-link" :to="{ name: 'observability' }">成本质量</RouterLink>页的本月预算卡。
              </p>
            </template>
          </template>
        </section>

        <section class="quota-card" aria-labelledby="quota-trend-heading">
          <h2 id="quota-trend-heading"><TrendingUp :size="16" aria-hidden="true" />近 6 个月趋势</h2>
          <StatePanel v-if="usage.phase === 'bootstrap' || usage.phase === 'loading'" state="loading" title="正在加载趋势" />
          <template v-else-if="usage.phase === 'ready'">
            <p class="quota-note">
              趋势由本页按月拼合（后端无多月端点）；参考线是当前配额的投影，不代表历史月的限额。窗口内读取失败的月份不参与绘图。
            </p>
            <LineChart label="每月任务受理数趋势" :series="intakeChart.series" :reference="intakeChart.reference" />
            <LineChart label="每月 Token 趋势" :series="tokenChart.series" :reference="tokenChart.reference" :value-formatter="formatTokens" />
            <BarChart v-if="compositionChart" :label="`${selectedUsage?.month ?? ''} 实际 Token 构成`" :bars="compositionChart.bars" :value-formatter="formatTokens" />
          </template>
        </section>
      </template>
    </div>
  </SettingsShell>
</template>

<style scoped>
.quota-settings { display: grid; gap: var(--cs-space-16); }
.quota-card { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.quota-card h2 { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; font-size: var(--cs-text-md); color: var(--cs-text); }
.quota-current { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-12); margin: 0; color: var(--cs-text-secondary); }
.quota-form { display: grid; gap: var(--cs-space-12); }
.quota-field { display: grid; gap: var(--cs-space-4); max-width: 360px; }
.quota-field label { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.quota-field input { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }
.quota-reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.quota-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.quota-ok { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.quota-actions { display: flex; gap: var(--cs-space-8); }
.quota-month-nav { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.quota-month-nav__item { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text-secondary); padding: var(--cs-space-4) var(--cs-space-12); font-size: var(--cs-text-sm); cursor: pointer; }
.quota-month-nav__item[aria-pressed='true'] { border-color: var(--cs-focus); color: var(--cs-text); font-weight: var(--cs-weight-semibold); }
.quota-month-nav__item:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.quota-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--cs-space-12); margin: 0; }
.quota-metric { display: grid; gap: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); padding: var(--cs-space-12); }
.quota-metric dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.quota-metric dd { margin: 0; font-size: var(--cs-text-base); }
.quota-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
/* 正文内链接除颜色外必须有非颜色区分（axe link-in-text-block）：常驻下划线。 */
.quota-link { color: var(--cs-text-brand); text-decoration: underline; }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.quota-month-nav__item:hover:not(:disabled) { background: var(--cs-surface-subtle); border-color: var(--cs-border-accent); }
.quota-month-nav__item { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out); }
</style>
