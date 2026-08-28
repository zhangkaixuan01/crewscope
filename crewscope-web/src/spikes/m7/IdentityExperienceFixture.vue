<script lang="ts">
export const identityFixtureStates = [
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
  'account',
] as const

export type IdentityFixtureState = typeof identityFixtureStates[number]
</script>

<script setup lang="ts">
import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  Bot,
  Check,
  Clock3,
  Eye,
  EyeOff,
  KeyRound,
  LoaderCircle,
  LockKeyhole,
  LogOut,
  Mail,
  MonitorSmartphone,
  ShieldCheck,
  Sparkles,
  UserRound,
  UsersRound,
} from '@lucide/vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import crewScopeMark from '../../design/crewscope-mark.svg'

const props = defineProps<{ state: IdentityFixtureState }>()

const root = ref<HTMLElement | null>(null)
const passwordVisible = ref(false)
const isLogin = computed(() => ['login', 'login-error', 'locked'].includes(props.state))
const isRegistrationUnavailable = computed(() => ['registration-invite-only', 'registration-closed'].includes(props.state))

function focusInitial(): void {
  void nextTick(() => root.value?.querySelector<HTMLElement>('[data-initial-focus]')?.focus())
}

onMounted(focusInitial)
watch(() => props.state, () => {
  // 身份页切换后重置显隐状态，避免下一个密码字段意外使用明文模式。
  passwordVisible.value = false
  focusInitial()
})
</script>

<template>
  <div ref="root" class="identity-fixture" :class="{ 'identity-fixture--account': state === 'account' }">
    <a class="identity-skip" href="#identity-primary">跳到主要内容</a>

    <template v-if="state !== 'account'">
      <aside class="identity-story" aria-label="CrewScope 团队协作说明">
        <a class="identity-brand" href="#" aria-label="CrewScope 首页">
          <img :src="crewScopeMark" alt="" width="42" height="42">
          <span>CrewScope<small>Team execution</small></span>
        </a>

        <div class="identity-story__copy">
          <p class="identity-kicker">Your work. Your agent. One crew.</p>
          <h1>把 AI 执行带回团队</h1>
          <p>每位成员拥有自己的 Personal Agent，任务、责任、Review 与交付证据在同一个团队工作空间汇合。</p>

          <div class="crew-map" aria-label="成员、Personal Agent 与团队协作关系">
            <div class="crew-node crew-node--person"><UserRound :size="17" aria-hidden="true" /><span>你<small>最终责任人</small></span></div>
            <span class="crew-map__line" aria-hidden="true" />
            <div class="crew-node crew-node--agent"><Bot :size="17" aria-hidden="true" /><span>Personal Agent<small>理解并执行</small></span></div>
            <span class="crew-map__line" aria-hidden="true" />
            <div class="crew-node crew-node--team"><UsersRound :size="17" aria-hidden="true" /><span>你的团队<small>协作与 Review</small></span></div>
          </div>
        </div>

        <ul class="identity-proof">
          <li><ShieldCheck :size="15" aria-hidden="true" />服务端 Session 与完整审计</li>
          <li><Sparkles :size="15" aria-hidden="true" />基于 AgentScope Java 2.0</li>
        </ul>
      </aside>

      <main id="identity-primary" class="identity-stage">
        <p class="prototype-label"><span aria-hidden="true" />M7 交互原型 · 不提交数据</p>

        <article v-if="state === 'loading'" class="identity-card identity-card--status" aria-busy="true">
          <LoaderCircle class="spin" :size="24" aria-hidden="true" />
          <div role="status" aria-live="polite" aria-atomic="true">
            <p class="card-kicker">正在恢复工作入口</p>
            <h2 data-initial-focus tabindex="-1">正在确认你的会话</h2>
            <p>检查服务端 Session、组织绑定和团队入口后继续。</p>
          </div>
          <div class="skeleton-stack" aria-hidden="true"><span /><span /><span /></div>
        </article>

        <article v-else-if="state === 'service-error'" class="identity-card identity-card--status">
          <div class="state-icon state-icon--danger"><AlertCircle :size="21" aria-hidden="true" /></div>
          <div class="error-summary" role="alert" data-initial-focus tabindex="-1">
            <p class="card-kicker">暂时无法连接</p>
            <h2>没有完成会话检查</h2>
            <p>你的登录信息没有提交。请确认网络后重新尝试。</p>
          </div>
          <button class="primary-action" type="button">重新检查<ArrowRight :size="16" aria-hidden="true" /></button>
          <button class="text-action" type="button">查看服务状态</button>
        </article>

        <article v-else-if="isLogin" class="identity-card">
          <header class="identity-card__heading">
            <p class="card-kicker">欢迎回来</p>
            <h2>继续你的团队工作</h2>
            <p>使用用户名或邮箱进入 CrewScope。</p>
          </header>

          <div v-if="state === 'login-error'" class="error-summary" role="alert" data-initial-focus tabindex="-1">
            <AlertCircle :size="17" aria-hidden="true" />
            <div><strong>无法登录</strong><span>登录信息无效，请检查后重试。</span></div>
          </div>
          <div v-else-if="state === 'locked'" class="error-summary" role="alert" data-initial-focus tabindex="-1">
            <Clock3 :size="17" aria-hidden="true" />
            <div><strong>暂时无法继续尝试</strong><span>请稍后再试，或联系部署管理员。</span></div>
          </div>

          <form class="identity-form" @submit.prevent>
            <label>
              <span>用户名或邮箱</span>
              <span class="input-frame"><Mail :size="16" aria-hidden="true" /><input :data-initial-focus="state === 'login' ? '' : undefined" name="identifier" autocomplete="username" inputmode="email" placeholder="name@example.com"></span>
            </label>
            <div class="form-field">
              <label for="fixture-login-password">密码</label>
              <span class="input-frame"><KeyRound :size="16" aria-hidden="true" /><input id="fixture-login-password" name="password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="输入你的密码"><button type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible"><EyeOff v-if="passwordVisible" :size="16" aria-hidden="true" /><Eye v-else :size="16" aria-hidden="true" /></button></span>
            </div>
            <div class="form-meta"><label class="check-label"><input type="checkbox">保持登录</label><span>密码帮助由管理员提供</span></div>
            <button class="primary-action" type="submit" :disabled="state === 'locked'">进入 CrewScope<ArrowRight :size="16" aria-hidden="true" /></button>
          </form>

          <p class="card-switch">第一次使用 CrewScope？<button type="button">创建账号</button></p>
        </article>

        <article v-else-if="state === 'register'" class="identity-card identity-card--wide">
          <header class="identity-card__heading">
            <p class="card-kicker">创建你的执行席位</p>
            <h2>加入 CrewScope</h2>
            <p>账号属于你，团队和 Personal Agent 将在下一步建立。</p>
          </header>
          <form class="identity-form identity-form--register" @submit.prevent>
            <label><span>用户名</span><span class="input-frame"><UserRound :size="16" aria-hidden="true" /><input data-initial-focus name="username" autocomplete="username" placeholder="zhangsan"></span></label>
            <label><span>工作邮箱</span><span class="input-frame"><Mail :size="16" aria-hidden="true" /><input name="email" autocomplete="email" inputmode="email" placeholder="name@example.com"></span></label>
            <label class="form-span"><span>展示名称</span><span class="input-frame"><UsersRound :size="16" aria-hidden="true" /><input name="displayName" autocomplete="name" placeholder="团队成员看到的名称"></span></label>
            <div class="form-field form-span"><label for="fixture-new-password">密码</label><span class="input-frame"><KeyRound :size="16" aria-hidden="true" /><input id="fixture-new-password" name="newPassword" :type="passwordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="创建安全密码"><button type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible"><EyeOff v-if="passwordVisible" :size="16" aria-hidden="true" /><Eye v-else :size="16" aria-hidden="true" /></button></span></div>
            <ul class="password-guidance form-span" aria-label="密码要求"><li><Check :size="13" aria-hidden="true" />至少 12 个字符</li><li><Check :size="13" aria-hidden="true" />支持完整短语</li><li><Check :size="13" aria-hidden="true" />最多 128 个字符</li></ul>
            <button class="primary-action form-span" type="submit">创建账号并继续<ArrowRight :size="16" aria-hidden="true" /></button>
          </form>
          <p class="card-switch">已经有账号？<button type="button">返回登录</button></p>
        </article>

        <article v-else-if="isRegistrationUnavailable" class="identity-card identity-card--status">
          <div class="state-icon"><LockKeyhole :size="21" aria-hidden="true" /></div>
          <div>
            <p class="card-kicker">注册方式</p>
            <h2 data-initial-focus tabindex="-1">{{ state === 'registration-invite-only' ? '通过团队邀请加入' : '当前未开放注册' }}</h2>
            <p>{{ state === 'registration-invite-only' ? '请从团队成员分享的邀请链接进入。已有账号仍可正常登录。' : '这个部署当前不接受新账号，请联系部署管理员。' }}</p>
          </div>
          <button class="primary-action" type="button">返回登录<ArrowRight :size="16" aria-hidden="true" /></button>
        </article>

        <article v-else-if="state === 'onboarding'" class="identity-card identity-card--wide">
          <ol class="step-track" aria-label="初始化步骤"><li class="done"><Check :size="12" />账号</li><li aria-current="step">团队</li><li>工作入口</li></ol>
          <header class="identity-card__heading">
            <p class="card-kicker">建立第一个团队</p>
            <h2>从一个共同工作空间开始</h2>
            <p>团队承载成员、Agent、任务和共享连接。你将成为第一个 Owner。</p>
          </header>
          <form class="identity-form" @submit.prevent>
            <label><span>团队名称</span><span class="input-frame"><UsersRound :size="16" aria-hidden="true" /><input data-initial-focus name="teamName" autocomplete="organization" value="Platform Engineering"></span></label>
            <section class="creation-preview" aria-label="将要创建的内容">
              <h3>将同时准备</h3>
              <ul><li><span><UsersRound :size="15" />团队工作空间</span><small>成员与任务的共享边界</small></li><li><span><Bot :size="15" />你的 Personal Agent</span><small>默认对话式执行入口</small></li><li><span><ShieldCheck :size="15" />Owner 责任与权限</span><small>可邀请成员并管理团队</small></li></ul>
            </section>
            <button class="primary-action" type="submit">创建团队<ArrowRight :size="16" aria-hidden="true" /></button>
          </form>
        </article>

        <article v-else-if="state === 'invite'" class="identity-card identity-card--wide">
          <p class="invite-mark"><UsersRound :size="19" aria-hidden="true" />团队邀请</p>
          <header class="identity-card__heading">
            <p class="card-kicker">Platform Engineering 邀请你加入</p>
            <h2>一起完成 CrewScope 的下一次交付</h2>
            <p>由林默邀请，加入后你将拥有自己的 Personal Agent，并以 Member 身份参与团队工作。</p>
          </header>
          <dl class="invite-facts"><div><dt>团队</dt><dd>Platform Engineering</dd></div><div><dt>角色</dt><dd>Member</dd></div><div><dt>有效期</dt><dd>还有 6 天</dd></div></dl>
          <div class="invite-actions"><button class="primary-action" type="button" data-initial-focus>创建账号并加入<ArrowRight :size="16" /></button><button class="secondary-action" type="button">使用已有账号登录</button></div>
          <p class="privacy-note"><ShieldCheck :size="14" />接受前不会创建成员关系，邀请只能使用一次。</p>
        </article>

        <article v-else-if="state === 'invite-expired'" class="identity-card identity-card--status">
          <div class="state-icon state-icon--warning"><Clock3 :size="21" aria-hidden="true" /></div>
          <div>
            <p class="card-kicker">团队邀请</p>
            <h2 data-initial-focus tabindex="-1">这个邀请已失效</h2>
            <p>邀请可能已过期、被撤销或已经使用。请联系邀请人获取新链接。</p>
          </div>
          <button class="primary-action" type="button">前往登录<ArrowRight :size="16" /></button>
        </article>
      </main>
    </template>

    <template v-else>
      <header class="account-topbar">
        <a class="identity-brand identity-brand--compact" href="#" aria-label="CrewScope 首页"><img :src="crewScopeMark" alt="" width="36" height="36"><span>CrewScope<small>Identity & security</small></span></a>
        <button class="secondary-action" type="button"><ArrowLeft :size="15" />返回工作区</button>
      </header>
      <main id="identity-primary" class="account-workspace">
        <header class="account-heading"><div><p class="card-kicker">账号设置</p><h1 data-initial-focus tabindex="-1">身份与安全</h1><p>管理你的个人资料、密码和登录会话。</p></div><span class="account-avatar">张</span></header>
        <div class="account-layout">
          <nav aria-label="账号设置导航"><a href="#profile" aria-current="page"><UserRound :size="16" />个人资料</a><a href="#security"><KeyRound :size="16" />密码与安全</a><a href="#sessions"><MonitorSmartphone :size="16" />登录会话</a></nav>
          <div class="account-sections">
            <section id="profile"><header><div><h2>个人资料</h2><p>团队成员和 Agent 会看到这些信息。</p></div><button class="secondary-action" type="button">编辑资料</button></header><dl class="profile-facts"><div><dt>展示名称</dt><dd>张凯旋</dd></div><div><dt>用户名</dt><dd>zhangkaixuan</dd></div><div><dt>邮箱</dt><dd>zh***@example.com</dd></div><div><dt>平台角色</dt><dd>USER</dd></div></dl></section>
            <section id="security"><header><div><h2>密码与安全</h2><p>修改密码需要验证当前密码，并会撤销其他会话。</p></div><button class="secondary-action" type="button"><KeyRound :size="15" />修改密码</button></header><p class="section-fact"><ShieldCheck :size="16" />密码最近更新于 2026-08-28</p></section>
            <section id="sessions"><header><div><h2>登录会话</h2><p>当前浏览器保持登录，其他设备可以一次性撤销。</p></div><button class="danger-action" type="button"><LogOut :size="15" />退出全部设备</button></header><p class="section-fact"><MonitorSmartphone :size="16" />当前设备 · 活跃</p></section>
          </div>
        </div>
      </main>
    </template>
  </div>
</template>

<style scoped>
.identity-fixture { min-height: 100vh; background: #edf4ef; color: var(--cs-text); font-family: var(--cs-font-sans); }
.identity-skip { position: fixed; top: 10px; left: 10px; z-index: 100; padding: 9px 12px; border-radius: 8px; background: var(--cs-brand-950); color: white; transform: translateY(-160%); }.identity-skip:focus { transform: translateY(0); }
.identity-story { position: fixed; inset: 0 auto 0 0; display: flex; width: min(46vw, 650px); flex-direction: column; overflow: hidden; padding: 34px clamp(28px, 4vw, 64px); background: #f0f7f2; border-right: 1px solid #d6e6da; }
.identity-story::before, .identity-story::after { position: absolute; border: 1px solid rgb(63 114 87 / 13%); border-radius: 50%; content: ""; pointer-events: none; }.identity-story::before { width: 520px; height: 520px; right: -230px; bottom: -180px; box-shadow: 0 0 0 70px rgb(223 246 231 / 50%), 0 0 0 140px rgb(240 251 244 / 65%); }.identity-story::after { width: 160px; height: 160px; top: 23%; left: -110px; box-shadow: 0 0 0 48px rgb(223 246 231 / 45%); }
.identity-brand { position: relative; z-index: 1; display: inline-flex; width: fit-content; align-items: center; gap: 11px; font-family: var(--cs-font-display); font-size: 21px; }.identity-brand img { border-radius: 12px; box-shadow: 0 8px 20px rgb(49 89 68 / 12%); }.identity-brand span, .identity-brand small { display: block; }.identity-brand small { color: var(--cs-text-muted); font-family: var(--cs-font-sans); font-size: 9px; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
.identity-story__copy { position: relative; z-index: 1; margin-block: auto; }.identity-kicker, .card-kicker { margin-bottom: 8px; color: var(--cs-brand-700); font-size: 10px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }.identity-story h1 { max-width: 540px; margin-bottom: 18px; font-family: var(--cs-font-display); font-size: clamp(36px, 4vw, 56px); font-weight: 560; letter-spacing: -.04em; white-space: nowrap; }.identity-story__copy > p:last-of-type { max-width: 520px; margin-bottom: 30px; color: #52645a; font-size: 15px; line-height: 1.75; }
.crew-map { display: grid; max-width: 520px; grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1.28fr) 24px minmax(0, 1fr); align-items: center; }.crew-node { display: flex; min-width: 0; min-height: 66px; align-items: center; gap: 9px; padding: 11px; border: 1px solid #cfe1d4; border-radius: 13px; background: rgb(255 255 255 / 74%); box-shadow: 0 8px 24px rgb(31 56 43 / 5%); }.crew-node svg { flex: 0 0 auto; color: var(--cs-brand-600); }.crew-node span, .crew-node small { display: block; }.crew-node span { min-width: 0; font-size: 11px; font-weight: 760; }.crew-node small { overflow: hidden; color: var(--cs-text-muted); font-size: 8px; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }.crew-node--agent { border-color: #d9cfee; background: #faf8fd; }.crew-node--agent svg { color: var(--cs-agent); }.crew-map__line { height: 1px; background: #b9d2c0; }
.identity-proof { position: relative; z-index: 1; display: flex; flex-wrap: wrap; gap: 18px; padding: 0; margin: 0; color: var(--cs-text-muted); font-size: 10px; list-style: none; }.identity-proof li { display: inline-flex; align-items: center; gap: 6px; }
.identity-stage { display: grid; min-height: 100vh; place-items: center; padding: 68px clamp(24px, 5vw, 84px); margin-left: min(46vw, 650px); }.prototype-label { position: absolute; top: 24px; right: 30px; display: inline-flex; align-items: center; gap: 7px; margin: 0; color: var(--cs-text-muted); font-size: 9px; font-weight: 700; letter-spacing: .05em; text-transform: uppercase; }.prototype-label span { width: 6px; height: 6px; border-radius: 50%; background: var(--cs-warning); }
.identity-card { width: min(100%, 430px); padding: clamp(24px, 3.5vw, 38px); border: 1px solid #d7e2d9; border-radius: 22px; background: rgb(255 255 255 / 94%); box-shadow: 0 24px 70px rgb(37 57 46 / 10%); }.identity-card--wide { width: min(100%, 560px); }.identity-card--status { display: grid; justify-items: start; gap: 20px; }.identity-card--status h2 { margin-bottom: 8px; }.identity-card--status p { margin-bottom: 0; color: var(--cs-text-muted); }
.identity-card__heading { margin-bottom: 24px; }.identity-card__heading h2 { margin-bottom: 8px; font-family: var(--cs-font-display); font-size: clamp(26px, 3vw, 34px); font-weight: 580; letter-spacing: -.025em; }.identity-card__heading > p:last-child { margin-bottom: 0; color: var(--cs-text-muted); font-size: 12px; }
.identity-form { display: grid; gap: 16px; }.identity-form--register { grid-template-columns: 1fr 1fr; }.identity-form label > span:first-child, .form-field > label { display: block; margin-bottom: 6px; color: var(--cs-text-secondary); font-size: 10px; font-weight: 740; }.input-frame { display: grid; min-height: 44px; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 0 12px; border: 1px solid var(--cs-border-strong); border-radius: 10px; background: white; color: var(--cs-text-muted); transition: border-color var(--cs-transition-fast), box-shadow var(--cs-transition-fast); }.input-frame:focus-within { border-color: var(--cs-brand-400); box-shadow: var(--cs-focus-ring); }.input-frame input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; font-size: 12px; }.input-frame input::placeholder { color: #89968e; }.input-frame button { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 7px; background: transparent; color: var(--cs-text-muted); cursor: pointer; }.form-span { grid-column: 1 / -1; }
.form-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--cs-text-muted); font-size: 9px; }.check-label { display: inline-flex; align-items: center; gap: 7px; }.check-label input { accent-color: var(--cs-brand-600); }
.primary-action, .secondary-action, .danger-action, .text-action { display: inline-flex; min-height: 42px; align-items: center; justify-content: center; gap: 8px; border-radius: 10px; font-size: 11px; font-weight: 740; cursor: pointer; }.primary-action { width: 100%; background: var(--cs-brand-950); color: white; }.primary-action:hover:not(:disabled) { background: var(--cs-brand-700); }.primary-action:disabled { cursor: not-allowed; opacity: .46; }.secondary-action { padding: 0 13px; border: 1px solid var(--cs-border-strong); background: white; color: var(--cs-text-secondary); }.danger-action { padding: 0 13px; border: 1px solid #e2b8b4; background: var(--cs-danger-soft); color: #963d37; }.text-action { min-height: 30px; padding: 0; background: transparent; color: var(--cs-brand-700); }
.card-switch { margin: 22px 0 0; color: var(--cs-text-muted); font-size: 10px; text-align: center; }.card-switch button { padding: 2px 4px; background: transparent; color: var(--cs-brand-700); font-weight: 750; cursor: pointer; }
.error-summary { display: flex; align-items: flex-start; gap: 10px; padding: 12px; margin-bottom: 18px; border: 1px solid #e7bbb6; border-radius: 10px; background: #fff6f4; color: #873a34; }.error-summary div { display: grid; gap: 2px; }.error-summary strong { font-size: 10px; }.error-summary span, .error-summary p { color: #874943; font-size: 9px; }.error-summary h2 { color: var(--cs-text); }
.password-guidance { display: flex; flex-wrap: wrap; gap: 7px 14px; padding: 0; margin: -4px 0 0; color: var(--cs-text-muted); font-size: 9px; list-style: none; }.password-guidance li { display: inline-flex; align-items: center; gap: 4px; }.password-guidance svg { color: var(--cs-success); }
.state-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 13px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.state-icon--danger { background: var(--cs-danger-soft); color: var(--cs-danger); }.state-icon--warning { background: var(--cs-warning-soft); color: var(--cs-warning); }.spin { color: var(--cs-brand-600); animation: spin .9s linear infinite; }.skeleton-stack { display: grid; width: 100%; gap: 9px; }.skeleton-stack span { height: 11px; border-radius: 5px; background: #e8eeea; }.skeleton-stack span:nth-child(2) { width: 82%; }.skeleton-stack span:nth-child(3) { width: 64%; }
.step-track { display: grid; grid-template-columns: repeat(3, 1fr); padding: 0; margin: 0 0 26px; list-style: none; counter-reset: step; }.step-track li { position: relative; display: flex; align-items: center; gap: 5px; color: var(--cs-text-muted); font-size: 9px; font-weight: 720; }.step-track li::before { display: grid; width: 20px; height: 20px; place-items: center; border: 1px solid var(--cs-border-strong); border-radius: 50%; background: white; content: counter(step); counter-increment: step; }.step-track li:not(:last-child)::after { position: absolute; z-index: 0; top: 10px; right: 7px; left: 48px; height: 1px; background: var(--cs-border); content: ""; }.step-track li.done::before { content: ""; border-color: var(--cs-brand-300); background: var(--cs-brand-100); }.step-track li.done svg { position: absolute; z-index: 1; left: 4px; color: var(--cs-brand-700); }.step-track li[aria-current="step"] { color: var(--cs-brand-700); }
.creation-preview { padding: 14px; border: 1px solid var(--cs-border); border-radius: 12px; background: var(--cs-surface-subtle); }.creation-preview h3 { margin-bottom: 10px; font-size: 10px; }.creation-preview ul { display: grid; gap: 9px; padding: 0; margin: 0; list-style: none; }.creation-preview li { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.creation-preview li span { display: inline-flex; align-items: center; gap: 7px; font-size: 10px; font-weight: 700; }.creation-preview li small { color: var(--cs-text-muted); font-size: 8px; }
.invite-mark { display: inline-flex; align-items: center; gap: 7px; padding: 6px 9px; margin-bottom: 22px; border-radius: 9px; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 10px; font-weight: 750; }.invite-facts { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0 0 22px; border: 1px solid var(--cs-border); border-radius: 12px; }.invite-facts div { padding: 12px; }.invite-facts div + div { border-left: 1px solid var(--cs-border); }.invite-facts dt { color: var(--cs-text-muted); font-size: 8px; }.invite-facts dd { margin: 3px 0 0; font-size: 10px; font-weight: 720; }.invite-actions { display: grid; grid-template-columns: 1.3fr 1fr; gap: 9px; }.privacy-note { display: flex; align-items: center; gap: 6px; margin: 14px 0 0; color: var(--cs-text-muted); font-size: 8px; }
.account-topbar { display: flex; min-height: 68px; align-items: center; justify-content: space-between; gap: 20px; padding: 12px clamp(18px, 4vw, 54px); border-bottom: 1px solid var(--cs-border); background: rgb(255 255 255 / 92%); }.identity-brand--compact { font-size: 18px; }.account-workspace { width: min(1040px, calc(100% - 36px)); margin: 0 auto; padding: 48px 0 72px; }.account-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 28px; }.account-heading h1 { margin-bottom: 6px; font-family: var(--cs-font-display); font-size: 36px; font-weight: 580; }.account-heading > div > p:last-child { margin: 0; color: var(--cs-text-muted); }.account-avatar { display: grid; width: 58px; height: 58px; place-items: center; border-radius: 50%; background: var(--cs-brand-600); color: white; font-size: 19px; font-weight: 760; }.account-layout { display: grid; grid-template-columns: 210px minmax(0, 1fr); gap: 22px; }.account-layout nav { display: grid; height: fit-content; gap: 4px; }.account-layout nav a { display: flex; min-height: 40px; align-items: center; gap: 8px; padding: 0 11px; border-radius: 9px; color: var(--cs-text-muted); font-size: 10px; font-weight: 700; }.account-layout nav a[aria-current="page"] { background: var(--cs-brand-100); color: var(--cs-brand-800); }.account-sections { display: grid; gap: 14px; }.account-sections section { padding: 20px; border: 1px solid var(--cs-border); border-radius: 15px; background: white; }.account-sections section > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.account-sections h2 { margin-bottom: 4px; font-size: 14px; }.account-sections header p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }.profile-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; padding-top: 18px; margin: 18px 0 0; border-top: 1px solid var(--cs-border); }.profile-facts dt { color: var(--cs-text-muted); font-size: 8px; }.profile-facts dd { margin: 3px 0 0; font-size: 10px; font-weight: 720; }.section-fact { display: flex; align-items: center; gap: 8px; margin: 18px 0 0; color: var(--cs-text-secondary); font-size: 9px; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
@media (max-width: 900px) { .identity-story { width: 40%; padding-inline: 24px; }.identity-stage { margin-left: 40%; padding-inline: 20px; }.crew-map { display: grid; grid-template-columns: 1fr; gap: 7px; }.crew-map__line { width: 1px; height: 10px; margin-left: 20px; }.identity-story h1 { font-size: 36px; white-space: normal; }.identity-story__copy > p:last-of-type { font-size: 13px; } }
@media (max-width: 680px) { .identity-story { position: relative; width: 100%; min-height: 190px; padding: 22px 20px; border-right: 0; border-bottom: 1px solid #d6e6da; }.identity-story__copy { margin: 26px 0 0; }.identity-story h1 { margin-bottom: 8px; font-size: 30px; white-space: normal; }.identity-story__copy > p:last-of-type { margin: 0; font-size: 11px; line-height: 1.55; }.identity-kicker, .crew-map, .identity-proof { display: none; }.identity-brand { font-size: 18px; }.identity-brand img { width: 36px; height: 36px; }.identity-stage { min-height: calc(100vh - 190px); align-content: start; padding: 50px 14px 34px; margin-left: 0; }.prototype-label { top: 207px; right: 16px; }.identity-card { padding: 24px 20px; border-radius: 17px; }.identity-card__heading h2 { font-size: 27px; }.identity-form--register { grid-template-columns: 1fr; }.form-span { grid-column: auto; }.invite-facts { grid-template-columns: 1fr; }.invite-facts div + div { border-top: 1px solid var(--cs-border); border-left: 0; }.invite-actions { grid-template-columns: 1fr; }.creation-preview li { align-items: flex-start; flex-direction: column; gap: 2px; }.account-topbar { min-height: 62px; }.account-topbar .secondary-action { width: 40px; padding: 0; font-size: 0; }.account-workspace { width: min(100% - 24px, 560px); padding: 28px 0 48px; }.account-heading { align-items: flex-start; }.account-heading h1 { font-size: 30px; }.account-avatar { width: 46px; height: 46px; }.account-layout { grid-template-columns: 1fr; }.account-layout nav { grid-template-columns: repeat(3, 1fr); overflow-x: auto; }.account-layout nav a { justify-content: center; padding-inline: 8px; white-space: nowrap; }.account-sections section { padding: 16px; }.account-sections section > header { align-items: stretch; flex-direction: column; }.account-sections section > header button { width: 100%; }.profile-facts { grid-template-columns: 1fr; }.identity-fixture--account { min-height: 100vh; } }
</style>
