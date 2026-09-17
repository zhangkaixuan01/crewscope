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
  padding: var(--cs-space-32) clamp(var(--cs-space-32), 4vw, var(--cs-space-64));
  border-right: 1px solid var(--cs-auth-story-border);
  background: var(--cs-auth-story-surface);
  color: var(--cs-auth-story-text, var(--cs-text));
}
.auth-brand-panel::before,
.auth-brand-panel::after {
  position: absolute;
  border: 1px solid var(--cs-auth-story-ring);
  border-radius: 50%;
  content: "";
  pointer-events: none;
}
.auth-brand-panel::before {
  width: 520px;
  height: 520px;
  right: -230px;
  bottom: -180px;
  box-shadow: 0 0 0 70px var(--cs-auth-story-halo), 0 0 0 140px var(--cs-auth-story-halo-outer);
}
.auth-brand-panel::after {
  width: 160px;
  height: 160px;
  top: 23%;
  left: -110px;
  box-shadow: 0 0 0 48px var(--cs-auth-story-halo);
}
.auth-brand-panel__brand {
  position: relative;
  z-index: 1;
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: var(--cs-space-12);
  font-family: var(--cs-font-display);
  font-size: var(--cs-text-xl);
}
.auth-brand-panel__brand img { border-radius: 12px; box-shadow: var(--cs-shadow-raised); }
.auth-brand-panel__brand span,
.auth-brand-panel__brand small { display: block; }
.auth-brand-panel__brand small {
  color: var(--cs-auth-story-muted, var(--cs-text-muted));
  font-family: var(--cs-font-sans);
  font-size: var(--cs-text-xs);
  font-weight: var(--cs-weight-semibold);
  letter-spacing: .1em;
  text-transform: uppercase;
}
.auth-brand-panel__story { position: relative; z-index: 1; margin-block: auto; }
.auth-brand-panel__kicker {
  margin-bottom: var(--cs-space-8);
  color: var(--cs-auth-story-kicker, var(--cs-text-brand));
  font-size: var(--cs-text-sm);
  font-weight: var(--cs-weight-semibold);
  letter-spacing: .1em;
  text-transform: uppercase;
}
.auth-brand-panel__story h1 {
  max-width: 540px;
  margin-bottom: var(--cs-space-20);
  font-family: var(--cs-font-display);
  font-size: clamp(var(--cs-text-2xl), 4vw, 56px);
  font-weight: var(--cs-weight-medium);
  letter-spacing: -.035em;
}
.auth-brand-panel__story > p:last-of-type {
  max-width: 500px;
  color: var(--cs-auth-story-muted, var(--cs-text-secondary));
  font-size: var(--cs-text-md);
  line-height: var(--cs-leading-relaxed);
}
.auth-crew-map { display: flex; align-items: center; margin-top: var(--cs-space-32); }
.auth-crew-map__node {
  display: flex;
  min-width: 124px;
  align-items: center;
  gap: var(--cs-space-8);
  padding: var(--cs-space-12) var(--cs-space-12);
  border: 1px solid var(--cs-auth-story-node-border, var(--cs-border));
  border-radius: 12px;
  background: var(--cs-auth-story-node-surface);
  font-size: var(--cs-text-sm);
  font-weight: var(--cs-weight-semibold);
}
.auth-crew-map__node--agent { color: var(--cs-auth-story-agent); }
.auth-crew-map__node span,
.auth-crew-map__node small { display: block; }
.auth-crew-map__node small { color: var(--cs-auth-story-muted, var(--cs-text-muted)); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-medium); }
.auth-crew-map__line { width: clamp(14px, 2vw, 34px); height: 1px; background: var(--cs-auth-story-node-border, var(--cs-border-strong)); }
.auth-brand-panel__proof {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: var(--cs-space-12) var(--cs-space-20);
  padding: 0;
  margin: 0;
  color: var(--cs-auth-story-muted, var(--cs-text-muted));
  font-size: var(--cs-text-xs);
  list-style: none;
}
.auth-brand-panel__proof li { display: inline-flex; align-items: center; gap: var(--cs-space-8); }

@media (max-width: 900px) {
  .auth-brand-panel { padding-inline: var(--cs-space-24); }
  .auth-crew-map { display: grid; grid-template-columns: 1fr; gap: var(--cs-space-8); }
  .auth-crew-map__line { width: 1px; height: 10px; margin-left: var(--cs-space-20); }
  .auth-brand-panel__story h1 { font-size: var(--cs-text-2xl); }
}

@media (max-width: 680px) {
  .auth-brand-panel {
    min-height: 190px;
    padding: var(--cs-space-24) var(--cs-space-20);
    border-right: 0;
    border-bottom: 1px solid var(--cs-auth-story-border);
  }
  .auth-brand-panel__brand { font-size: var(--cs-text-lg); }
  .auth-brand-panel__brand img { width: 36px; height: 36px; }
  .auth-brand-panel__story { margin: var(--cs-space-24) 0 0; }
  .auth-brand-panel__story h1 { margin-bottom: var(--cs-space-8); font-size: var(--cs-text-2xl); }
  .auth-brand-panel__story > p:last-of-type { margin: 0; font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
  .auth-brand-panel__kicker,
  .auth-crew-map,
  .auth-brand-panel__proof { display: none; }
}
</style>
