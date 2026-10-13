<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { createFormCommandKeys } from '../../api/formCommandKeys'
import BaseBadge from '../base/BaseBadge.vue'
import BaseButton from '../base/BaseButton.vue'
import BaseTable from '../base/BaseTable.vue'
import { enumLabel } from '../../domains/shared/labels'
import {
  deletionItemStateLabels,
  deletionJobStatusLabels,
  deletionStepLabels,
} from '../../domains/organization/labels'
import { organizationProblem } from '../../domains/organization/presentation'
import { useOrganizationStore } from '../../domains/organization/store'

/**
 * M12-F01 account deletion workflow (OWNER-only entry, gated by the page). The two-step
 * confirmation is the contract: precheck blocks on live responsibilities, create freezes the
 * checklist in PENDING_CONFIRMATION, and only the target's username echo (observed job
 * version) unlocks processing. The confirmation token never persists anywhere — the field is
 * autocomplete=off and cleared the moment the answer arrives.
 */
const props = defineProps<{ organizationId: string }>()

const store = useOrganizationStore()
const precheck = computed(() => store.state.deletionPrecheck)
const flow = computed(() => store.state.deletionFlow)
const job = computed(() => flow.value.job)
const items = computed(() => job.value?.items ?? [])
const busy = computed(() => flow.value.busy !== null || precheck.value.phase === 'loading')
const problem = computed(() => flow.value.errorMessage !== null
  ? organizationProblem(flow.value.errorStatus, flow.value.errorCode, deletionSurface.value) : null)
const problemText = computed(() => problem.value
  ? `${problem.value.title}：${problem.value.message}` : flow.value.errorMessage)
/** The 422 copy depends on which face raised it: create (preconditions) vs confirm (token). */
const deletionSurface = computed(() => (flow.value.busy === 'confirm' || confirming.value
  ? 'deletion-confirm' : 'deletion-precondition'))

const intentKeys = createFormCommandKeys()
const accountIdInput = ref('')
const accountIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const accountIdValid = computed(() => accountIdPattern.test(accountIdInput.value.trim()))
const precheckReady = computed(() => precheck.value.phase === 'ready' && precheck.value.value !== null
  && precheck.value.value.accountId === accountIdInput.value.trim())
/** All three preconditions must be zero before create; null openReservations counts as zero. */
const precheckClear = computed(() => {
  const value = precheck.value.value
  if (!precheckReady.value || !value) return false
  return value.inFlightTaskExecutions === 0 && value.activeResponsibilities === 0
    && (value.openReservations ?? 0) === 0
})

const reasonInput = ref('')
const reasonLength = computed(() => reasonInput.value.trim().length)
const reasonValid = computed(() => reasonLength.value >= 20 && reasonLength.value <= 500)
const createDisabledReason = computed(() => {
  if (!accountIdValid.value) return '请输入目标账号 ID（UUID 格式）。'
  if (precheck.value.phase === 'idle') return '请先运行删除预检。'
  if (precheck.value.phase === 'loading') return '正在预检。'
  if (precheck.value.phase === 'error') return '预检失败，请重试。'
  if (!precheckReady.value) return '账号 ID 与预检结果不一致，请重新预检。'
  if (!precheckClear.value) return '存在未收尾的在途执行或未转移的责任，暂不能发起删除。'
  if (!reasonValid.value) return '删除原因需 20 至 500 字。'
  if (flow.value.busy) return '正在提交。'
  return ''
})

const precheckDisabledReason = computed(() => {
  if (!accountIdValid.value) return '账号 ID 需为 36 位 UUID 格式。'
  if (precheck.value.phase === 'loading') return '正在预检。'
  return ''
})

const confirmationToken = ref('')
const confirmSubmitted = ref(false)
const confirming = computed(() => job.value?.status === 'PENDING_CONFIRMATION')
const confirmed = computed(() => job.value?.status === 'CONFIRMED')
const tokenTrimmed = computed(() => confirmationToken.value.trim())
const tokenMissing = computed(() => tokenTrimmed.value.length === 0)
/** The confirm lock is either the in-flight request or the missing username echo, both named. */
const confirmDisabledReason = computed(() => {
  if (flow.value.busy) return '正在提交。'
  if (tokenMissing.value) return '请输入目标账号的用户名作为确认令牌。'
  return ''
})

async function runPrecheck(): Promise<void> {
  if (!accountIdValid.value) return
  await store.precheckDeletion(props.organizationId, accountIdInput.value.trim(), true)
}

async function createJob(): Promise<void> {
  store.clearDeletionFlow()
  await store.createDeletion(props.organizationId, {
    accountId: accountIdInput.value.trim(),
    reason: reasonInput.value.trim(),
  }, intentKeys.forInput([accountIdInput.value.trim(), reasonInput.value.trim()]))
}

async function confirmJob(): Promise<void> {
  confirmSubmitted.value = true
  if (tokenMissing.value) return
  const ok = await store.confirmDeletion(props.organizationId, tokenTrimmed.value)
  // Never keep the confirmed token in the field: it is a one-shot echo, not durable state.
  if (ok) confirmationToken.value = ''
}

async function processJob(): Promise<void> {
  await store.processDeletion(props.organizationId)
}

async function cancelJob(): Promise<void> {
  await store.cancelDeletion(props.organizationId)
}

function reservationSummary(value: string): string {
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>
    const inFlight = typeof parsed.inFlightTaskExecutions === 'number' ? parsed.inFlightTaskExecutions : '—'
    const active = typeof parsed.activeResponsibilities === 'number' ? parsed.activeResponsibilities : '—'
    const open = typeof parsed.openReservations === 'number' ? parsed.openReservations : '—'
    const checkedAt = typeof parsed.checkedAt === 'string' ? parsed.checkedAt : ''
    return `在途执行 ${inFlight}、活跃责任 ${active}、未关闭预留 ${open}${checkedAt ? `（${new Date(checkedAt).toLocaleString('zh-CN', { hour12: false })}）` : ''}`
  } catch {
    return value
  }
}

function timeText(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<template>
  <div class="org-deletion">
    <form class="org-deletion-form" @submit.prevent="runPrecheck">
      <h3>第一步：删除预检</h3>
      <label for="org-deletion-account">目标账号 ID（UUID）</label>
      <input
        id="org-deletion-account"
        v-model="accountIdInput"
        type="text"
        inputmode="text"
        autocomplete="off"
        :aria-invalid="!accountIdValid"
        placeholder="00000000-0000-0000-0000-000000000000"
      />
      <div class="org-actions">
        <BaseButton type="submit" :disabled="Boolean(precheckDisabledReason)">
          {{ precheck.phase === 'loading' ? '预检中…' : '运行预检' }}
        </BaseButton>
      </div>
      <p v-if="precheckDisabledReason" class="org-reason" aria-live="polite">{{ precheckDisabledReason }}</p>
      <p v-if="precheck.phase === 'error'" role="alert" class="org-error">预检失败：{{ precheck.errorMessage }}</p>
      <template v-else-if="precheckReady && precheck.value">
        <dl class="org-precheck-facts" :class="{ 'org-precheck-blocked': !precheckClear }">
          <div><dt>在途任务执行</dt><dd>{{ precheck.value.inFlightTaskExecutions }}</dd></div>
          <div><dt>活跃责任</dt><dd>{{ precheck.value.activeResponsibilities }}</dd></div>
          <div><dt>未关闭预留</dt><dd>{{ precheck.value.openReservations ?? '—' }}</dd></div>
        </dl>
        <p class="org-reason" :class="{ 'org-error': !precheckClear }">
          {{ precheckClear
            ? '三项先决均为零，可以发起删除作业。'
            : '目标账号仍有在途任务执行或未转移的责任。请先完成执行收尾与责任移交（团队成员页），再回到本页发起删除。' }}
        </p>
      </template>
    </form>

    <form class="org-deletion-form" @submit.prevent="createJob">
      <h3>第二步：创建删除作业</h3>
      <label for="org-deletion-reason">删除原因（20 至 500 字）</label>
      <textarea
        id="org-deletion-reason"
        v-model="reasonInput"
        rows="3"
        :aria-invalid="reasonLength > 0 && !reasonValid"
      ></textarea>
      <p class="org-reason" aria-live="polite">{{ reasonLength }}/500</p>
      <p v-if="flow.errorMessage && flow.busy !== 'confirm'" role="alert" class="org-error">{{ problemText }}</p>
      <p v-else-if="flow.notice" class="org-ok" role="status">{{ flow.notice }}</p>
      <div class="org-actions">
        <BaseButton type="submit" :disabled="Boolean(createDisabledReason)">
          {{ flow.busy === 'create' ? '创建中…' : '创建删除作业' }}
        </BaseButton>
      </div>
      <p v-if="createDisabledReason" class="org-reason" aria-live="polite">{{ createDisabledReason }}</p>
    </form>

    <template v-if="job">
      <section class="org-job" aria-labelledby="org-deletion-job-heading">
        <h3 id="org-deletion-job-heading">删除作业</h3>
        <dl class="org-job-facts">
          <div><dt>作业 ID</dt><dd class="org-mono">{{ job.id }}</dd></div>
          <div><dt>状态</dt><dd><BaseBadge>{{ enumLabel(job.status, deletionJobStatusLabels) }}</BaseBadge></dd></div>
          <div><dt>目标账号</dt><dd class="org-mono">{{ job.accountId }}</dd></div>
          <div><dt>目标 Principal</dt><dd class="org-mono">{{ job.targetPrincipalId }}</dd></div>
          <div><dt>删除原因</dt><dd>{{ job.reason }}</dd></div>
          <div><dt>预留快照</dt><dd>{{ reservationSummary(job.reservationCheck) }}</dd></div>
          <div><dt>数据版本</dt><dd>{{ job.version }}</dd></div>
        </dl>

        <form v-if="confirming" class="org-confirm" @submit.prevent="confirmJob">
          <label for="org-deletion-token">确认令牌（目标账号的用户名）</label>
          <input
            id="org-deletion-token"
            v-model="confirmationToken"
            type="text"
            autocomplete="off"
            spellcheck="false"
            :aria-invalid="confirmSubmitted && tokenMissing"
          />
          <p class="org-reason">必须与目标账号用户名完全一致；作业将以当前数据版本（{{ job.version }}）确认。</p>
          <p v-if="flow.errorMessage" role="alert" class="org-error">{{ problemText }}</p>
          <div class="org-actions">
            <BaseButton type="submit" variant="danger" :disabled="Boolean(confirmDisabledReason)">
              {{ flow.busy === 'confirm' ? '确认中…' : '确认删除作业' }}
            </BaseButton>
            <BaseButton variant="ghost" :disabled="busy" @click="cancelJob">
              {{ flow.busy === 'cancel' ? '取消中…' : '取消作业' }}
            </BaseButton>
          </div>
          <p v-if="confirmDisabledReason" class="org-reason" aria-live="polite">{{ confirmDisabledReason }}</p>
        </form>

        <div v-else class="org-actions">
          <BaseButton v-if="confirmed" variant="danger" :disabled="busy" @click="processJob">
            {{ flow.busy === 'process' ? '执行中…' : '开始执行' }}
          </BaseButton>
          <BaseButton v-if="confirming || confirmed" variant="ghost" :disabled="busy" @click="cancelJob">
            {{ flow.busy === 'cancel' ? '取消中…' : '取消作业' }}
          </BaseButton>
        </div>
        <p v-if="!confirming && flow.errorMessage" role="alert" class="org-error">{{ problemText }}</p>
        <p v-else-if="!confirming && flow.notice" class="org-ok" role="status">{{ flow.notice }}</p>
        <p v-if="confirmed" class="org-reason">执行将按固定顺序结算全部 13 个协调步骤；中断后重新执行会从第一个未完成步骤续跑。</p>
      </section>

      <BaseTable v-if="items.length" caption="删除协调步骤">
        <thead>
          <tr>
            <th scope="col">步骤</th>
            <th scope="col">目标</th>
            <th scope="col">预期数量</th>
            <th scope="col">结果数量</th>
            <th scope="col">状态</th>
            <th scope="col">错误码</th>
            <th scope="col">处理时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>{{ enumLabel(item.stepKey, deletionStepLabels) }}</td>
            <td class="org-mono">{{ item.targetRef }}</td>
            <td>{{ item.expectedCount }}</td>
            <td>{{ item.resultCount ?? '—' }}</td>
            <td><BaseBadge>{{ enumLabel(item.state, deletionItemStateLabels) }}</BaseBadge></td>
            <td>{{ item.errorCode ?? '—' }}</td>
            <td>{{ timeText(item.processedAt) }}</td>
          </tr>
        </tbody>
      </BaseTable>

      <section v-if="job.status === 'COMPLETED'" class="org-backup" aria-labelledby="org-deletion-backup-heading">
        <h3 id="org-deletion-backup-heading">备份保留声明</h3>
        <p class="org-backup-text">{{ job.backupRetentionStatement }}</p>
        <p class="org-reason">
          作业已完成：登录面已移除、账号与 Principal 已停用。若从备份窗口内恢复过数据，请重新执行本作业对账
          （已删除步骤会以无操作完成或冲突收敛）。操作明细见
          <RouterLink to="/audit">审计中心</RouterLink>。
        </p>
      </section>
    </template>

    <p v-else class="org-reason">
      删除作业仅在预检通过后创建。作业没有列表端点：历史作业请在
      <RouterLink to="/audit">审计中心</RouterLink>查询治理审计事件。
    </p>
  </div>
</template>

<style scoped>
.org-deletion { display: grid; gap: var(--cs-space-16); }
.org-deletion-form, .org-job, .org-backup { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.org-deletion-form h3, .org-job h3, .org-backup h3 { margin: 0; font-size: var(--cs-text-md); color: var(--cs-text); }
.org-deletion-form label { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-deletion-form input, .org-deletion-form textarea, .org-confirm input { min-height: var(--cs-density-control-height); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); font-size: var(--cs-text-base); }
.org-deletion-form textarea { resize: vertical; }
.org-precheck-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-8); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent-soft, transparent); }
.org-precheck-blocked { border: 1px solid var(--cs-danger-border); }
.org-precheck-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-precheck-facts dd { margin: 0; font-size: var(--cs-text-md); color: var(--cs-text); }
.org-job { gap: var(--cs-space-12); }
.org-job-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--cs-space-8); margin: 0; }
.org-job-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-job-facts dd { margin: 0; min-width: 0; overflow-wrap: anywhere; font-size: var(--cs-text-sm); color: var(--cs-text-secondary); }
.org-confirm { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px dashed var(--cs-border); border-radius: var(--cs-radius-sm); }
.org-confirm label { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-backup-text { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-mono { font-family: var(--cs-font-mono, monospace); font-size: var(--cs-text-sm); }
.org-reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.org-ok { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
</style>
