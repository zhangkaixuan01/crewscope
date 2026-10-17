<script setup lang="ts">
import { AlertTriangle } from '@lucide/vue'
import { computed, ref, watch, useId } from 'vue'
import { SKILL_DISABLE_REASON_MAX } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import BaseTextarea from '../base/BaseTextarea.vue'

/**
 * One alertdialog, three confirmations (F02 D3, governance §10.6 "explain the impact and
 * confirm"): publish, disable and rollback each carry contract-specific consequences that a
 * bare "确定？" would hide.
 */
export type SkillActionIntent =
  | { kind: 'publish', skillKey: string, nextRevision: number }
  | { kind: 'disable', skillKey: string }
  | { kind: 'rollback', skillKey: string, toRevision: number, effectiveRevision: number }

const props = defineProps<{
  intent: SkillActionIntent
  submitting: boolean
  errorMessage: string | null
}>()

const emit = defineEmits<{
  cancel: []
  confirm: [reason: string | null]
}>()

const reason = ref('')
const confirmFormId = useId()
const impactId = useId()

watch(() => props.intent, () => {
  reason.value = ''
})

const reasonOver = computed(() => reason.value.length > SKILL_DISABLE_REASON_MAX)

const heading = computed(() => {
  if (props.intent.kind === 'publish') return '发布当前草稿？'
  if (props.intent.kind === 'disable') return '禁用这个 Skill？'
  return `回滚到 r${props.intent.toRevision}？`
})
const confirmLabel = computed(() => {
  if (props.intent.kind === 'publish') return '确认发布'
  if (props.intent.kind === 'disable') return '确认禁用'
  return '确认回滚'
})
const confirmDisabled = computed(() => props.submitting || reasonOver.value)

function submit(): void {
  if (confirmDisabled.value) return
  emit('confirm', props.intent.kind === 'disable' && reason.value.trim() !== '' ? reason.value.trim() : null)
}

function requestClose(): void {
  if (props.submitting) return
  emit('cancel')
}
</script>

<template>
  <BaseDialog
    open
    role="alertdialog"
    :title="heading"
    eyebrow="Team skill"
    :subtitle="intent.skillKey"
    width="min(560px, 100%)"
    :described-by="impactId"
    :close-disabled="submitting"
    close-label="取消操作"
    @close="requestClose"
  >
    <template #icon><AlertTriangle :size="20" /></template>
    <form :id="confirmFormId" class="skill-confirm__content" @submit.prevent="submit">
      <p v-if="intent.kind === 'publish'" :id="impactId" class="skill-confirm__impact">
        发布会产出新的不可变修订 r{{ intent.nextRevision }} 并更新生效指针。服务端会对全部内容（frontmatter 与正文）执行无条件披露扫描——Skill 会注入未来任务的指令，审核标准比知识条目更严。发布后仍可回滚。
      </p>
      <template v-else-if="intent.kind === 'disable'">
        <p :id="impactId" class="skill-confirm__impact">
          禁用即下线：后续任务不再装载这个 Skill，但目录与历史版本全部保留（没有删除操作）。修改草稿并再次发布即可产出新修订、恢复生效。
        </p>
        <label class="skill-confirm__field">
          <span>禁用原因（可选）<span class="skill-confirm__count" :class="{ over: reasonOver }">{{ reason.length }}/{{ SKILL_DISABLE_REASON_MAX }}</span></span>
          <BaseTextarea v-model="reason" :rows="3" :disabled="submitting" :invalid="reasonOver || undefined" placeholder="会展示给 Team 全体成员。" />
        </label>
      </template>
      <p v-else :id="impactId" class="skill-confirm__impact">
        回滚不是删除：会产出一个新的不可变修订，其内容与哈希与 r{{ intent.toRevision }} 完全相同（相邻修订同哈希属正常形态），当前生效的 r{{ intent.effectiveRevision }} 之后的中间修订全部保留在历史中。
      </p>

      <p v-if="errorMessage" class="skill-confirm__error" role="alert">{{ errorMessage }}</p>
    </form>
    <template #footer>
      <!-- 危险确认的初始焦点必须落在安全侧（取消），而不是 header 的关闭钮或破坏性主钮。 -->
      <BaseButton type="button" variant="ghost" autofocus :disabled="submitting" @click="requestClose">取消</BaseButton>
      <BaseButton type="submit" :form="confirmFormId" :variant="intent.kind === 'disable' ? 'danger' : 'primary'" :loading="submitting" :disabled="confirmDisabled">{{ confirmLabel }}</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.skill-confirm__content { display: grid; gap: var(--cs-space-16); }
.skill-confirm__impact { margin: 0; color: var(--cs-text); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.skill-confirm__field { display: grid; gap: var(--cs-space-4); }
.skill-confirm__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.skill-confirm__count { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); }
.skill-confirm__count.over { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.skill-confirm__error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
</style>
