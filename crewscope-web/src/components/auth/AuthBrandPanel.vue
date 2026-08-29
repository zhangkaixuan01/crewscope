<script setup lang="ts">
import { Bot, ShieldCheck, Sparkles, UserRound, UsersRound } from '@lucide/vue'
import crewScopeMark from '../../design/crewscope-mark.svg'
</script>

<template>
  <aside class="auth-brand-panel" aria-label="CrewScope 团队协作说明">
    <a class="auth-brand-panel__brand" href="/" aria-label="CrewScope 首页">
      <img :src="crewScopeMark" alt="" width="42" height="42">
      <span>CrewScope<small>Team execution</small></span>
    </a>

    <div class="auth-brand-panel__story">
      <p class="auth-brand-panel__kicker">Your work. Your agent. One crew.</p>
      <h1>把 AI 执行带回团队</h1>
      <p>每位成员拥有自己的 Personal Agent，任务、责任、Review 与交付证据在同一个团队工作空间汇合。</p>

      <div class="auth-crew-map" aria-label="成员、Personal Agent 与团队协作关系">
        <div class="auth-crew-map__node">
          <UserRound :size="17" aria-hidden="true" />
          <span>你<small>最终责任人</small></span>
        </div>
        <span class="auth-crew-map__line" aria-hidden="true" />
        <div class="auth-crew-map__node auth-crew-map__node--agent">
          <Bot :size="17" aria-hidden="true" />
          <span>Personal Agent<small>理解并执行</small></span>
        </div>
        <span class="auth-crew-map__line" aria-hidden="true" />
        <div class="auth-crew-map__node">
          <UsersRound :size="17" aria-hidden="true" />
          <span>你的团队<small>协作与 Review</small></span>
        </div>
      </div>
    </div>

    <ul class="auth-brand-panel__proof">
      <li><ShieldCheck :size="15" aria-hidden="true" />服务端 Session 与完整审计</li>
      <li><Sparkles :size="15" aria-hidden="true" />基于 AgentScope Java 2.0</li>
    </ul>
  </aside>
</template>

<style scoped>
.auth-brand-panel {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  overflow: hidden;
  padding: 34px clamp(28px, 4vw, 64px);
  border-right: 1px solid var(--cs-auth-story-border);
  background: var(--cs-auth-story-surface);
  color: var(--cs-auth-story-text, var(--cs-text));
}
.auth-brand-panel::before,
.auth-brand-panel::after {
  position: absolute;
  border: 1px solid rgb(63 114 87 / 13%);
  border-radius: 50%;
  content: "";
  pointer-events: none;
}
.auth-brand-panel::before {
  width: 520px;
  height: 520px;
  right: -230px;
  bottom: -180px;
  box-shadow: 0 0 0 70px rgb(223 246 231 / 32%), 0 0 0 140px rgb(240 251 244 / 36%);
}
.auth-brand-panel::after {
  width: 160px;
  height: 160px;
  top: 23%;
  left: -110px;
  box-shadow: 0 0 0 48px rgb(223 246 231 / 30%);
}
.auth-brand-panel__brand {
  position: relative;
  z-index: 1;
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 11px;
  font-family: var(--cs-font-display);
  font-size: 21px;
}
.auth-brand-panel__brand img { border-radius: 12px; box-shadow: 0 8px 20px rgb(49 89 68 / 12%); }
.auth-brand-panel__brand span,
.auth-brand-panel__brand small { display: block; }
.auth-brand-panel__brand small {
  color: var(--cs-auth-story-muted, var(--cs-text-muted));
  font-family: var(--cs-font-sans);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: .1em;
  text-transform: uppercase;
}
.auth-brand-panel__story { position: relative; z-index: 1; margin-block: auto; }
.auth-brand-panel__kicker {
  margin-bottom: 8px;
  color: var(--cs-auth-story-kicker, var(--cs-brand-700));
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .1em;
  text-transform: uppercase;
}
.auth-brand-panel__story h1 {
  max-width: 540px;
  margin-bottom: 18px;
  font-family: var(--cs-font-display);
  font-size: clamp(36px, 4vw, 56px);
  font-weight: 560;
  letter-spacing: -.035em;
}
.auth-brand-panel__story > p:last-of-type {
  max-width: 500px;
  color: var(--cs-auth-story-muted, var(--cs-text-secondary));
  font-size: 15px;
  line-height: 1.75;
}
.auth-crew-map { display: flex; align-items: center; margin-top: 34px; }
.auth-crew-map__node {
  display: flex;
  min-width: 124px;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  border: 1px solid var(--cs-auth-story-node-border, var(--cs-border));
  border-radius: 12px;
  background: var(--cs-auth-story-node-surface, rgb(255 255 255 / 64%));
  font-size: 10px;
  font-weight: 740;
}
.auth-crew-map__node--agent { color: var(--cs-agent); }
[data-auth-brand-tone="dark"] .auth-crew-map__node--agent { color: #d7c9fb; }
.auth-crew-map__node span,
.auth-crew-map__node small { display: block; }
.auth-crew-map__node small { color: var(--cs-auth-story-muted, var(--cs-text-muted)); font-size: 8px; font-weight: 500; }
.auth-crew-map__line { width: clamp(14px, 2vw, 34px); height: 1px; background: var(--cs-auth-story-node-border, var(--cs-border-strong)); }
.auth-brand-panel__proof {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  padding: 0;
  margin: 0;
  color: var(--cs-auth-story-muted, var(--cs-text-muted));
  font-size: 9px;
  list-style: none;
}
.auth-brand-panel__proof li { display: inline-flex; align-items: center; gap: 6px; }

@media (max-width: 900px) {
  .auth-brand-panel { padding-inline: 24px; }
  .auth-crew-map { display: grid; grid-template-columns: 1fr; gap: 7px; }
  .auth-crew-map__line { width: 1px; height: 10px; margin-left: 20px; }
  .auth-brand-panel__story h1 { font-size: 36px; }
}

@media (max-width: 680px) {
  .auth-brand-panel {
    min-height: 190px;
    padding: 22px 20px;
    border-right: 0;
    border-bottom: 1px solid var(--cs-auth-story-border);
  }
  .auth-brand-panel__brand { font-size: 18px; }
  .auth-brand-panel__brand img { width: 36px; height: 36px; }
  .auth-brand-panel__story { margin: 26px 0 0; }
  .auth-brand-panel__story h1 { margin-bottom: 8px; font-size: 30px; }
  .auth-brand-panel__story > p:last-of-type { margin: 0; font-size: 11px; line-height: 1.55; }
  .auth-brand-panel__kicker,
  .auth-crew-map,
  .auth-brand-panel__proof { display: none; }
}
</style>
