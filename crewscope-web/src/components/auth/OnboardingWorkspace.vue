<script setup lang="ts">
import { ArrowRight, Bot, Check, CircleAlert, RefreshCw, ShieldCheck, UsersRound } from '@lucide/vue'
import { computed } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import AuthCard from './AuthCard.vue'
import AuthErrorSummary from './AuthErrorSummary.vue'
import AuthField from './AuthField.vue'
import AuthLayout from './AuthLayout.vue'
import type { OnboardingProblem } from '../../domains/onboarding/presentation'
import type { OnboardingPhase } from '../../domains/onboarding/store'

const teamName = defineModel<string>('teamName', { default: '' })
const props = withDefaults(defineProps<{
  phase: OnboardingPhase
  problem?: OnboardingProblem | null
  errorGeneration?: number
  teamError?: string
  currentStage?: 'team' | 'workspace' | 'agent' | 'ready'
  canEdit?: boolean
  online?: boolean
  personalAgentName?: string
  /** F02: the readiness projection says the conversation capability still needs configuration. */
  conversationSetupReady?: boolean
}>(), {
  problem: null,
  errorGeneration: 0,
  teamError: undefined,
  currentStage: 'team',
  canEdit: false,
  online: true,
  personalAgentName: 'Personal Agent',
  conversationSetupReady: true,
})

const emit = defineEmits<{
  submit: []
  retry: []
  enter: []
  configure: []
}>()

const loading = computed(() => props.phase === 'idle' || props.phase === 'loading')
const busy = computed(() => props.phase === 'submitting' || props.phase === 'verifying')
const showForm = computed(() => props.phase === 'required' || (props.phase === 'error' && props.canEdit))
const teamDone = computed(() => ['workspace', 'agent', 'ready'].includes(props.currentStage))
const workspaceDone = computed(() => ['agent', 'ready'].includes(props.currentStage))
const agentDone = computed(() => props.currentStage === 'ready')

function current(step: 'team' | 'workspace'): 'step' | undefined {
  if (step === 'team' && props.currentStage === 'team') return 'step'
  if (step === 'workspace' && ['workspace', 'agent'].includes(props.currentStage)) return 'step'
  return undefined
}
</script>

<template>
  <AuthLayout>
    <AuthCard
      v-if="loading"
      kicker="准备团队入口"
      title="正在检查你的工作空间"
      description="确认当前账号是否需要建立第一个团队。"
      busy
      focus-on-mount
    >
      <p class="onboarding__status" role="status">正在读取 Onboarding 状态…</p>
    </AuthCard>

    <AuthCard
      v-else-if="phase === 'complete'"
      kicker="团队已准备好"
      title="你的工作入口已经就绪"
      description="Team、Owner 权限、共享工作空间和默认 Personal Agent 已通过服务端投影确认。"
      size="wide"
      focus-on-mount
    >
      <ol class="onboarding__steps" aria-label="初始化步骤">
        <li class="done"><Check :size="12" aria-hidden="true" />账号</li>
        <li class="done"><Check :size="12" aria-hidden="true" />团队</li>
        <li class="done"><Check :size="12" aria-hidden="true" />工作入口</li>
      </ol>
      <section class="onboarding__ready" aria-label="已完成的初始化">
        <span class="onboarding__ready-icon"><Bot :size="20" aria-hidden="true" /></span>
        <div><strong>{{ personalAgentName }}</strong><small>默认对话式 Personal Agent</small></div>
        <span>已就绪</span>
      </section>
      <!-- F02 决策 7：readiness 缺口只提示，不阻断进入对话。 -->
      <p v-if="!conversationSetupReady" class="onboarding__notice">
        <CircleAlert :size="14" aria-hidden="true" />Personal Agent 尚未完成模型配置，进入对话前可先到配置中心补齐。
      </p>
      <div class="onboarding__entry">
        <BaseButton class="onboarding__primary" @click="emit('enter')">
          进入团队对话<template #icon><ArrowRight :size="16" /></template>
        </BaseButton>
        <BaseButton variant="secondary" @click="emit('configure')">先完成 Coding 配置</BaseButton>
      </div>
    </AuthCard>

    <AuthCard
      v-else
      kicker="建立第一个团队"
      title="从一个共同工作空间开始"
      description="团队承载成员、Agent、任务和共享连接。你将成为第一个 Owner。"
      size="wide"
      :busy="busy"
    >
      <template #before>
        <ol class="onboarding__steps" aria-label="初始化步骤">
          <li class="done"><Check :size="12" aria-hidden="true" />账号</li>
          <li :class="{ done: teamDone }" :aria-current="current('team')">
            <Check v-if="teamDone" :size="12" aria-hidden="true" />团队
          </li>
          <li :class="{ done: workspaceDone }" :aria-current="current('workspace')">
            <Check v-if="workspaceDone" :size="12" aria-hidden="true" />工作入口
          </li>
        </ol>
      </template>

      <AuthErrorSummary
        v-if="problem"
        :title="problem.title"
        :messages="[problem.message]"
        :tone="problem.tone"
        :focus-key="errorGeneration"
      />

      <form v-if="showForm" class="onboarding__form" @submit.prevent="emit('submit')">
        <AuthField
          v-model="teamName"
          label="团队名称"
          name="teamName"
          autocomplete="organization"
          placeholder="例如：Platform Engineering"
          hint="1 至 200 个字符；创建后可在团队设置中调整。"
          required
          :maxlength="200"
          :disabled="busy"
          :error="teamError"
          :focus-on-mount="!problem"
        >
          <template #leading><UsersRound :size="16" /></template>
        </AuthField>
        <section class="onboarding__creation" aria-label="将要创建的内容">
          <h3>服务端将原子准备</h3>
          <ul>
            <li><span><UsersRound :size="15" />团队工作空间</span><small>成员与任务的共享边界</small></li>
            <li><span><Bot :size="15" />你的 Personal Agent</span><small>默认对话式执行入口</small></li>
            <li><span><ShieldCheck :size="15" />Owner 责任与权限</span><small>可邀请成员并管理团队</small></li>
          </ul>
        </section>
        <BaseButton class="onboarding__primary" type="submit" :disabled="!online" :loading="busy">
          {{ phase === 'error' ? '安全重试' : '创建团队' }}<template #icon><ArrowRight :size="16" /></template>
        </BaseButton>
      </form>

      <section v-else-if="busy" class="onboarding__progress" aria-live="polite" aria-label="团队初始化进度">
        <p>{{ phase === 'submitting' ? '正在提交原子初始化请求…' : '正在确认服务端初始化结果…' }}</p>
        <!-- L10：只列出已确认与正在确认的步骤，不预告尚未发生的结果。 -->
        <ul>
          <li v-if="currentStage === 'team' || teamDone" :class="{ active: currentStage === 'team', done: teamDone }"><span />创建 Team 与 Owner 成员关系</li>
          <li v-if="teamDone" :class="{ active: currentStage === 'workspace', done: workspaceDone }"><span />确认共享 Workspace 与权限</li>
          <li v-if="currentStage === 'agent' || agentDone" :class="{ active: currentStage === 'agent', done: agentDone }"><span />读取默认 Personal Agent</li>
        </ul>
      </section>

      <BaseButton
        v-else-if="phase === 'error'"
        class="onboarding__primary"
        :disabled="!online"
        @click="emit('retry')"
      >
        重新检查初始化结果<template #icon><RefreshCw :size="16" /></template>
      </BaseButton>
    </AuthCard>
  </AuthLayout>
</template>

<style scoped>
.onboarding__steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--cs-space-8);
  padding: 0;
  margin: 0 0 var(--cs-space-24);
  list-style: none;
}
.onboarding__steps li {
  display: flex;
  min-height: 28px;
  align-items: center;
  justify-content: center;
  gap: var(--cs-space-4);
  border-radius: 999px;
  background: var(--cs-surface-subtle);
  color: var(--cs-text-muted);
  font-size: var(--cs-text-xs);
  font-weight: var(--cs-weight-semibold);
}
.onboarding__steps li[aria-current="step"] { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand-strong); }
.onboarding__steps li.done { background: var(--cs-success-soft); color: var(--cs-success); }
.onboarding__form { display: grid; gap: var(--cs-space-16); }
.onboarding__creation {
  padding: var(--cs-space-16);
  border: 1px solid var(--cs-border);
  border-radius: 12px;
  background: var(--cs-surface-subtle);
}
.onboarding__creation h3 { margin: 0 0 var(--cs-space-12); font-size: var(--cs-text-sm); }
.onboarding__creation ul { display: grid; gap: var(--cs-space-8); padding: 0; margin: 0; list-style: none; }
.onboarding__creation li { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-16); }
.onboarding__creation span { display: inline-flex; align-items: center; gap: var(--cs-space-8); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.onboarding__creation small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-align: right; }
.onboarding__primary { width: 100%; }
.onboarding__notice { display: flex; align-items: flex-start; gap: var(--cs-space-8); margin: 0 0 var(--cs-space-16); padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.onboarding__notice svg { flex: 0 0 auto; }
.onboarding__entry { display: grid; gap: var(--cs-space-8); }
.onboarding__status { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.onboarding__progress { display: grid; gap: var(--cs-space-16); }
.onboarding__progress > p { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.onboarding__progress ul { display: grid; gap: var(--cs-space-12); padding: 0; margin: 0; list-style: none; }
.onboarding__progress li { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.onboarding__progress li > span { width: 8px; height: 8px; border-radius: 50%; background: var(--cs-border-strong); }
.onboarding__progress li.active { color: var(--cs-text-brand-strong); font-weight: var(--cs-weight-semibold); }
.onboarding__progress li.active > span { background: var(--cs-brand-400); box-shadow: 0 0 0 4px var(--cs-ring-brand); }
.onboarding__progress li.done > span { background: var(--cs-success); }
.onboarding__ready {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--cs-space-12);
  padding: var(--cs-space-16);
  margin-bottom: var(--cs-space-16);
  border: 1px solid var(--cs-border-accent);
  border-radius: 12px;
  background: var(--cs-success-soft);
}
.onboarding__ready-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 11px; background: var(--cs-surface); color: var(--cs-text-brand-strong); }
.onboarding__ready div { display: grid; gap: var(--cs-space-2); }
.onboarding__ready strong { font-size: var(--cs-text-sm); }
.onboarding__ready small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.onboarding__ready > span:last-child { color: var(--cs-success); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }

@media (max-width: 680px) {
  .onboarding__creation li { align-items: flex-start; flex-direction: column; gap: var(--cs-space-2); }
  .onboarding__creation small { padding-left: var(--cs-space-24); text-align: left; }
  .onboarding__ready { grid-template-columns: auto minmax(0, 1fr); }
  .onboarding__ready > span:last-child { grid-column: 2; }
}
</style>
