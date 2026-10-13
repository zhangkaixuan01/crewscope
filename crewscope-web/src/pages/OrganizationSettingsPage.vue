<script setup lang="ts">
import { Building2, ShieldCheck } from '@lucide/vue'
import { computed, inject, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseBadge from '../components/base/BaseBadge.vue'
import BaseButton from '../components/base/BaseButton.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { createFormCommandKeys } from '../api/formCommandKeys'
import { useDirtyForm } from '../composables/useDirtyForm'
import { organizationStatusLabels } from '../domains/organization/labels'
import { organizationProblem } from '../domains/organization/presentation'
import { useOrganizationStore } from '../domains/organization/store'
import { enumLabel } from '../domains/shared/labels'

/**
 * M12-F01 organization settings. The route is intentionally not permission-gated: every
 * active member may read the organization card, while the settings card and its editor
 * render progressively from the org:* vocabulary (pure ORG roles hold no team capability,
 * so a team permission would lock auditors out of the page).
 */
const principal = inject(AUTH_PRINCIPAL)
const router = useRouter()
const store = useOrganizationStore()
const organizationId = computed(() => principal?.organizationId ?? '')
const canReadSettings = computed(() => Boolean(principal && can(principal, permissions.orgAuditRead)))
const canWriteSettings = computed(() => Boolean(principal && can(principal, permissions.orgManageSettings)))
const profile = computed(() => store.state.profile)

const intentKeys = createFormCommandKeys()
const attemptKey = ref('')
/** Editor state: null keeps the field untouched, 'forever' maps to null retention days. */
const retentionChoice = ref<'forever' | 'limited'>('forever')
const retentionDaysInput = ref('')
const editing = ref(false)
const submitted = ref(false)
const days = computed(() => Number.parseInt(retentionDaysInput.value, 10))
const daysValid = computed(() => Number.isInteger(days.value) && days.value >= 30)
const retentionPayload = computed(() => retentionChoice.value === 'forever' ? null : days.value)
const dirtyForm = useDirtyForm(retentionPayload)
const settings = computed(() => store.state.settings)
const settingsValue = computed(() => settings.value.value)
const command = computed(() => store.state.settingsCommand)
const problem = computed(() => command.value.phase === 'error'
  ? organizationProblem(command.value.errorStatus, command.value.errorCode, 'retention') : null)
const savingReason = computed(() => {
  if (settings.value.phase !== 'ready') return '正在读取当前保留策略。'
  if (retentionChoice.value === 'limited' && !daysValid.value) return '限期保留至少 30 天。'
  if (command.value.phase === 'pending') return '正在保存。'
  return ''
})

watch(retentionPayload, value => {
  if (!editing.value) return
  dirtyForm.sync(value)
})

watch(() => command.value.phase, phase => {
  if (phase !== 'success') return
  editing.value = false
  submitted.value = false
  dirtyForm.markClean(settingsValue.value?.auditEventRetentionDays ?? null)
})

watch(settingsValue, value => {
  if (editing.value || !value) return
  retentionChoice.value = value.auditEventRetentionDays === null ? 'forever' : 'limited'
  retentionDaysInput.value = value.auditEventRetentionDays === null ? '' : String(value.auditEventRetentionDays)
  dirtyForm.markClean(value.auditEventRetentionDays)
})

onMounted(() => {
  if (organizationId.value) void store.loadProfile(organizationId.value)
})

watch([organizationId, canReadSettings], ([id, readable]) => {
  if (!id) return
  if (readable) void store.loadSettings(id)
}, { immediate: true })

function startEditing(): void {
  editing.value = true
  submitted.value = false
  // Entering the editor retires the previous save's status line so it cannot read as if the
  // untouched form had just been saved.
  store.clearCommand()
  const current = settingsValue.value?.auditEventRetentionDays ?? null
  retentionChoice.value = current === null ? 'forever' : 'limited'
  retentionDaysInput.value = current === null ? '' : String(current)
  dirtyForm.markClean(current)
}

function cancelEditing(): void {
  editing.value = false
  submitted.value = false
  const current = settingsValue.value?.auditEventRetentionDays ?? null
  retentionChoice.value = current === null ? 'forever' : 'limited'
  retentionDaysInput.value = current === null ? '' : String(current)
  attemptKey.value = ''
  dirtyForm.markClean(current)
}

async function saveRetention(): Promise<void> {
  submitted.value = true
  if (retentionChoice.value === 'limited' && !daysValid.value) return
  if (settings.value.phase !== 'ready') return
  attemptKey.value = intentKeys.forInput([retentionChoice.value, retentionPayload.value])
  const ok = await store.updateSettings(
    organizationId.value,
    { auditEventRetentionDays: retentionPayload.value },
    settingsValue.value?.version ?? 0,
    attemptKey.value,
  )
  if (!ok) attemptKey.value = ''
}

function retentionText(days: number | null): string {
  return days === null ? '永久保留' : `保留 ${days} 天`
}
</script>

<template>
  <SettingsShell title="组织设置">
    <div class="org-settings">
      <section class="org-card" aria-labelledby="org-profile-heading">
        <h2 id="org-profile-heading"><Building2 :size="16" aria-hidden="true" />组织信息</h2>
        <StatePanel v-if="profile.phase === 'loading'" state="loading" />
        <StatePanel
          v-else-if="profile.phase === 'error'"
          state="error"
          description="组织信息读取失败。"
          @retry="organizationId && store.loadProfile(organizationId, true)"
        />
        <dl v-else-if="profile.phase === 'ready' && profile.value" class="org-facts">
          <div class="org-fact">
            <dt>名称</dt>
            <dd>{{ profile.value.name }}</dd>
          </div>
          <div class="org-fact">
            <dt>状态</dt>
            <dd><BaseBadge>{{ enumLabel(profile.value.status, organizationStatusLabels) }}</BaseBadge></dd>
          </div>
          <div class="org-fact">
            <dt>组织 ID</dt>
            <dd class="org-id">{{ profile.value.organizationId }}</dd>
          </div>
          <div class="org-fact">
            <dt>数据版本</dt>
            <dd>{{ profile.value.version }}</dd>
          </div>
        </dl>
      </section>

      <section class="org-card" aria-labelledby="org-retention-heading">
        <h2 id="org-retention-heading"><ShieldCheck :size="16" aria-hidden="true" />审计保留策略</h2>
        <p v-if="!canReadSettings" class="org-note">审计保留策略由组织管理员配置，当前角色无权查看。</p>
        <template v-else>
          <StatePanel v-if="settings.phase === 'loading' || settings.phase === 'idle'" state="loading" />
          <StatePanel
            v-else-if="settings.phase === 'error'"
            state="error"
            description="保留策略读取失败。"
            @retry="organizationId && store.loadSettings(organizationId, true)"
          />
          <StatePanel v-else-if="settings.phase === 'forbidden'" state="forbidden" title="需要组织审计角色" description="组织 Owner、组织管理员或组织审计员可以查看审计保留策略。">
            <template #action>
              <BaseButton variant="secondary" size="small" @click="router.push({ name: 'access-denied', query: { requiredPermission: permissions.orgAuditRead } })">查看权限说明</BaseButton>
            </template>
          </StatePanel>
          <template v-else-if="settings.phase === 'ready' && settingsValue">
            <p v-if="!editing" class="org-retention-current">
              当前策略：{{ retentionText(settingsValue.auditEventRetentionDays) }}
              <BaseButton
                v-if="canWriteSettings"
                variant="secondary"
                size="small"
                @click="startEditing"
              >调整策略</BaseButton>
            </p>
            <!-- Outside the form on purpose: a successful save exits the editor, so an in-form
              status line would be unmounted the moment it became visible. -->
            <p v-if="command.phase === 'success' && !editing" class="org-ok" role="status">保留策略已保存。</p>
            <form v-else class="org-form" @submit.prevent="saveRetention">
              <fieldset>
                <legend>保留方式</legend>
                <label class="org-choice">
                  <input v-model="retentionChoice" type="radio" name="retention" value="forever" @change="dirtyForm.markDirty()" />
                  永久保留（默认）
                </label>
                <label class="org-choice">
                  <input v-model="retentionChoice" type="radio" name="retention" value="limited" @change="dirtyForm.markDirty()" />
                  限期保留
                </label>
                <div v-if="retentionChoice === 'limited'" class="org-days">
                  <label for="org-retention-days">保留天数（至少 30 天）</label>
                  <input
                    id="org-retention-days"
                    v-model="retentionDaysInput"
                    type="number"
                    min="30"
                    inputmode="numeric"
                    :aria-invalid="submitted && !daysValid"
                    aria-describedby="org-retention-days-reason"
                    @input="dirtyForm.markDirty()"
                  />
                  <p id="org-retention-days-reason" class="org-reason" aria-live="polite">
                    {{ submitted && retentionChoice === 'limited' && !daysValid ? '保留天数至少为 30 天。' : '缩短保留期会周期性清扫更早的审计事件；少于 30 天不被允许。' }}
                  </p>
                </div>
              </fieldset>
              <p v-if="command.phase === 'error'" role="alert" class="org-error">
                {{ problem?.title ? `${problem.title}：${problem.message}` : command.errorMessage }}
              </p>
              <div class="org-actions">
                <BaseButton type="submit" :disabled="Boolean(savingReason) || command.phase === 'pending'">
                  {{ command.phase === 'pending' ? '保存中…' : '保存策略' }}
                </BaseButton>
                <BaseButton variant="ghost" @click="cancelEditing">取消</BaseButton>
              </div>
              <p v-if="savingReason" class="org-reason" aria-live="polite">{{ savingReason }}</p>
            </form>
          </template>
        </template>
      </section>

      <section class="org-card" aria-labelledby="org-audit-heading">
        <h2 id="org-audit-heading">审计入口</h2>
        <p class="org-note">
          治理动作（角色授予、导出、删除）全部写入 SECURITY 审计事件；事件级审计在
          <RouterLink to="/audit">审计中心</RouterLink>按团队查询。
        </p>
      </section>
    </div>
  </SettingsShell>
</template>

<style scoped>
.org-settings { display: grid; gap: var(--cs-space-16); }
.org-card { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.org-card h2 { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; font-size: var(--cs-text-md); color: var(--cs-text); }
.org-facts { display: grid; gap: var(--cs-space-8); margin: 0; }
.org-fact { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: var(--cs-space-8); align-items: center; }
.org-fact dt { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-fact dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.org-id { font-family: var(--cs-font-mono, monospace); font-size: var(--cs-text-sm); }
.org-note { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-base); }
.org-retention-current { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-12); margin: 0; color: var(--cs-text-secondary); }
.org-form { display: grid; gap: var(--cs-space-12); }
.org-form fieldset { display: grid; gap: var(--cs-space-8); margin: 0; padding: 0; border: 0; }
.org-form legend { color: var(--cs-text-muted); font-size: var(--cs-text-sm); padding: 0; }
.org-choice { display: flex; align-items: center; gap: var(--cs-space-8); }
.org-days { display: grid; gap: var(--cs-space-4); padding-left: var(--cs-space-24); }
.org-days label { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.org-days input { max-width: 160px; min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }
.org-reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-error { margin: 0; color: var(--cs-text-danger, #b3261e); font-size: var(--cs-text-sm); }
.org-ok { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-actions { display: flex; gap: var(--cs-space-8); }
</style>
