<script lang="ts">
export const authPreviewStates = [
  'loading',
  'service-error',
  'login',
  'login-error',
  'locked',
  'register',
  'registration-invite-only',
  'registration-closed',
  'onboarding',
  'invite',
  'invite-expired',
] as const

export type AuthPreviewState = typeof authPreviewStates[number]
</script>

<script setup lang="ts">
import { ArrowRight, Bot, Check, Clock3, LoaderCircle, Mail, ShieldCheck, UserRound, UsersRound } from '@lucide/vue'
import { computed, ref } from 'vue'
import BaseButton from '../components/base/BaseButton.vue'
import AuthCard from '../components/auth/AuthCard.vue'
import AuthCheckbox from '../components/auth/AuthCheckbox.vue'
import AuthErrorSummary from '../components/auth/AuthErrorSummary.vue'
import AuthField from '../components/auth/AuthField.vue'
import AuthLayout from '../components/auth/AuthLayout.vue'
import AuthPasswordField from '../components/auth/AuthPasswordField.vue'

const props = withDefaults(defineProps<{
  state: AuthPreviewState
  brandTone?: 'light' | 'dark'
  stageTone?: 'mist' | 'neutral'
  motion?: 'system' | 'reduced'
}>(), {
  brandTone: 'light',
  stageTone: 'mist',
  motion: 'system',
})

const identifier = ref('')
const password = ref('')
const username = ref('')
const email = ref('')
const displayName = ref('')
const newPassword = ref('')
const teamName = ref('Platform Engineering')
const remember = ref(false)
const isLogin = computed(() => ['login', 'login-error', 'locked'].includes(props.state))
const registrationUnavailable = computed(() => ['registration-invite-only', 'registration-closed'].includes(props.state))
</script>

<template>
  <AuthLayout :brand-tone="brandTone" :stage-tone="stageTone" :motion="motion">
    <AuthCard
      v-if="state === 'loading'"
      kicker="正在恢复工作入口"
      title="正在确认你的会话"
      description="检查服务端 Session、组织绑定和团队入口后继续。"
      busy
      focus-on-mount
    >
      <template #before><LoaderCircle class="auth-preview__spinner" :size="24" aria-hidden="true" /></template>
      <div class="auth-preview__skeleton" aria-hidden="true"><span /><span /><span /></div>
    </AuthCard>

    <AuthCard
      v-else-if="state === 'service-error'"
      kicker="暂时无法连接"
      title="没有完成会话检查"
      description="你的登录信息没有提交。请确认网络后重新尝试。"
    >
      <AuthErrorSummary title="会话服务暂时不可用" :messages="['请确认网络后重新检查。']" />
      <BaseButton class="auth-preview__primary">重新检查<template #icon><ArrowRight :size="16" /></template></BaseButton>
    </AuthCard>

    <AuthCard
      v-else-if="isLogin"
      kicker="欢迎回来"
      title="继续你的团队工作"
      description="使用用户名或邮箱进入 CrewScope。"
    >
      <AuthErrorSummary
        v-if="state === 'login-error'"
        title="无法登录"
        :messages="['登录信息无效，请检查后重试。']"
      />
      <AuthErrorSummary
        v-else-if="state === 'locked'"
        title="暂时无法继续尝试"
        tone="warning"
        :messages="['请稍后再试，或联系部署管理员。']"
      />
      <form class="auth-preview__form" @submit.prevent>
        <AuthField
          v-model="identifier"
          label="用户名或邮箱"
          name="identifier"
          autocomplete="username"
          inputmode="email"
          placeholder="name@example.com"
          :disabled="state === 'locked'"
          :focus-on-mount="state === 'login'"
        >
          <template #leading><Mail :size="16" /></template>
        </AuthField>
        <AuthPasswordField
          v-model="password"
          name="password"
          autocomplete="current-password"
          placeholder="输入你的密码"
          :disabled="state === 'locked'"
        />
        <AuthCheckbox v-model="remember" label="保持登录" :disabled="state === 'locked'" />
        <BaseButton class="auth-preview__primary" type="submit" :disabled="state === 'locked'">
          进入 CrewScope<template #icon><ArrowRight :size="16" /></template>
        </BaseButton>
      </form>
      <template #footer><p class="auth-preview__switch">第一次使用 CrewScope？<button type="button">创建账号</button></p></template>
    </AuthCard>

    <AuthCard
      v-else-if="state === 'register'"
      kicker="创建你的执行席位"
      title="加入 CrewScope"
      description="账号属于你，团队和 Personal Agent 将在下一步建立。"
      size="wide"
    >
      <form class="auth-preview__form auth-preview__form--register" @submit.prevent>
        <AuthField v-model="username" label="用户名" name="username" autocomplete="username" placeholder="zhangsan" focus-on-mount>
          <template #leading><UserRound :size="16" /></template>
        </AuthField>
        <AuthField v-model="email" label="工作邮箱" name="email" type="email" autocomplete="email" inputmode="email" placeholder="name@example.com">
          <template #leading><Mail :size="16" /></template>
        </AuthField>
        <AuthField v-model="displayName" class="auth-preview__span" label="展示名称" name="displayName" autocomplete="name" placeholder="团队成员看到的名称">
          <template #leading><UsersRound :size="16" /></template>
        </AuthField>
        <AuthPasswordField
          v-model="newPassword"
          class="auth-preview__span"
          label="密码"
          name="newPassword"
          autocomplete="new-password"
          placeholder="创建安全密码"
          show-guidance
        />
        <BaseButton class="auth-preview__primary auth-preview__span" type="submit">
          创建账号并继续<template #icon><ArrowRight :size="16" /></template>
        </BaseButton>
      </form>
      <template #footer><p class="auth-preview__switch">已经有账号？<button type="button">返回登录</button></p></template>
    </AuthCard>

    <AuthCard
      v-else-if="registrationUnavailable"
      kicker="注册方式"
      :title="state === 'registration-invite-only' ? '通过团队邀请加入' : '当前未开放注册'"
      :description="state === 'registration-invite-only' ? '请从团队成员分享的邀请链接进入。已有账号仍可正常登录。' : '这个部署当前不接受新账号，请联系部署管理员。'"
      focus-on-mount
    >
      <BaseButton class="auth-preview__primary">返回登录<template #icon><ArrowRight :size="16" /></template></BaseButton>
    </AuthCard>

    <AuthCard
      v-else-if="state === 'onboarding'"
      kicker="建立第一个团队"
      title="从一个共同工作空间开始"
      description="团队承载成员、Agent、任务和共享连接。你将成为第一个 Owner。"
      size="wide"
    >
      <template #before>
        <ol class="auth-preview__steps" aria-label="初始化步骤">
          <li class="done"><Check :size="12" aria-hidden="true" />账号</li>
          <li aria-current="step">团队</li>
          <li>工作入口</li>
        </ol>
      </template>
      <form class="auth-preview__form" @submit.prevent>
        <AuthField v-model="teamName" label="团队名称" name="teamName" autocomplete="organization" focus-on-mount>
          <template #leading><UsersRound :size="16" /></template>
        </AuthField>
        <section class="auth-preview__creation" aria-label="将要创建的内容">
          <h3>将同时准备</h3>
          <ul>
            <li><span><UsersRound :size="15" />团队工作空间</span><small>成员与任务的共享边界</small></li>
            <li><span><Bot :size="15" />你的 Personal Agent</span><small>默认对话式执行入口</small></li>
            <li><span><ShieldCheck :size="15" />Owner 责任与权限</span><small>可邀请成员并管理团队</small></li>
          </ul>
        </section>
        <BaseButton class="auth-preview__primary" type="submit">创建团队<template #icon><ArrowRight :size="16" /></template></BaseButton>
      </form>
    </AuthCard>

    <AuthCard
      v-else-if="state === 'invite'"
      kicker="Platform Engineering 邀请你加入"
      title="一起完成 CrewScope 的下一次交付"
      description="由林默邀请，加入后你将拥有自己的 Personal Agent，并以 Member 身份参与团队工作。"
      size="wide"
    >
      <template #before><p class="auth-preview__invite-mark"><UsersRound :size="18" />团队邀请</p></template>
      <dl class="auth-preview__facts">
        <div><dt>团队</dt><dd>Platform Engineering</dd></div>
        <div><dt>角色</dt><dd>Member</dd></div>
        <div><dt>有效期</dt><dd>还有 6 天</dd></div>
      </dl>
      <div class="auth-preview__actions">
        <BaseButton autofocus>创建账号并加入</BaseButton>
        <BaseButton variant="secondary">使用已有账号登录</BaseButton>
      </div>
      <p class="auth-preview__privacy"><ShieldCheck :size="14" />接受前不会创建成员关系，邀请只能使用一次。</p>
    </AuthCard>

    <AuthCard
      v-else
      kicker="团队邀请"
      title="这个邀请已失效"
      description="邀请可能已过期、被撤销或已经使用。请联系邀请人获取新链接。"
      focus-on-mount
    >
      <template #before><Clock3 class="auth-preview__warning" :size="24" aria-hidden="true" /></template>
      <BaseButton class="auth-preview__primary">前往登录<template #icon><ArrowRight :size="16" /></template></BaseButton>
    </AuthCard>
  </AuthLayout>
</template>

<style scoped>
.auth-preview__form { display: grid; gap: 16px; }
.auth-preview__form--register { grid-template-columns: 1fr 1fr; }
.auth-preview__span { grid-column: 1 / -1; }
.auth-preview__primary { width: 100%; min-height: 42px; }
.auth-preview__switch { margin: 22px 0 0; color: var(--cs-text-muted); font-size: 10px; text-align: center; }
.auth-preview__switch button { padding: 2px 4px; background: transparent; color: var(--cs-brand-700); font-weight: 750; cursor: pointer; }
.auth-preview__spinner { margin-bottom: 18px; color: var(--cs-brand-600); animation: auth-spin .9s linear infinite; }
.auth-preview__skeleton { display: grid; gap: 9px; }
.auth-preview__skeleton span { height: 11px; border-radius: 5px; background: #e8eeea; }
.auth-preview__skeleton span:nth-child(2) { width: 82%; }
.auth-preview__skeleton span:nth-child(3) { width: 64%; }
.auth-preview__steps { display: grid; grid-template-columns: repeat(3, 1fr); padding: 0; margin: 0 0 26px; color: var(--cs-text-muted); font-size: 9px; font-weight: 720; list-style: none; }
.auth-preview__steps li { display: flex; align-items: center; gap: 5px; }
.auth-preview__steps li[aria-current="step"] { color: var(--cs-brand-700); }
.auth-preview__steps .done { color: var(--cs-success); }
.auth-preview__creation { padding: 14px; border: 1px solid var(--cs-border); border-radius: 12px; background: var(--cs-surface-subtle); }
.auth-preview__creation h3 { margin-bottom: 10px; font-size: 10px; }
.auth-preview__creation ul { display: grid; gap: 9px; padding: 0; margin: 0; list-style: none; }
.auth-preview__creation li { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.auth-preview__creation li span { display: inline-flex; align-items: center; gap: 7px; font-size: 10px; font-weight: 700; }
.auth-preview__creation li small { color: var(--cs-text-muted); font-size: 8px; }
.auth-preview__invite-mark { display: inline-flex; align-items: center; gap: 7px; padding: 6px 9px; margin-bottom: 22px; border-radius: 9px; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 10px; font-weight: 750; }
.auth-preview__facts { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0 0 22px; border: 1px solid var(--cs-border); border-radius: 12px; }
.auth-preview__facts div { padding: 12px; }
.auth-preview__facts div + div { border-left: 1px solid var(--cs-border); }
.auth-preview__facts dt { color: var(--cs-text-muted); font-size: 8px; }
.auth-preview__facts dd { margin: 3px 0 0; font-size: 10px; font-weight: 720; }
.auth-preview__actions { display: grid; grid-template-columns: 1.3fr 1fr; gap: 9px; }
.auth-preview__privacy { display: flex; align-items: center; gap: 6px; margin: 14px 0 0; color: var(--cs-text-muted); font-size: 8px; }
.auth-preview__warning { margin-bottom: 18px; color: var(--cs-warning); }
@keyframes auth-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .auth-preview__spinner { animation: none; } }
@media (max-width: 680px) {
  .auth-preview__form--register { grid-template-columns: 1fr; }
  .auth-preview__span { grid-column: auto; }
  .auth-preview__creation li { align-items: flex-start; flex-direction: column; gap: 2px; }
  .auth-preview__facts { grid-template-columns: 1fr; }
  .auth-preview__facts div + div { border-top: 1px solid var(--cs-border); border-left: 0; }
  .auth-preview__actions { grid-template-columns: 1fr; }
}
</style>
