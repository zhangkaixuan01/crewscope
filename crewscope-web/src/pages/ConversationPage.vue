<script setup lang="ts">
import { ArrowRight, Check, Circle, GitPullRequest, Paperclip, Play, Send, Sparkles } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AppShell from '../components/layout/AppShell.vue'
import AgentPresence from '../components/domain/AgentPresence.vue'
import ResponsibilityChain from '../components/domain/ResponsibilityChain.vue'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import { demoAgent, demoResponsibilities } from '../domains/demo/fixtures'

const route = useRoute()
const focus = computed(() => String(route.query.focus || 'CRW-18'))
</script>

<template>
  <AppShell eyebrow="Conversation · Platform Engineering" :title="`${focus} · GitHub Provider 接入`">
    <template #actions>
      <StatusBadge tone="info" dot>执行中</StatusBadge>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'control', query: route.query }"><BaseButton variant="secondary" size="small" @click="navigate">在控制台查看<ArrowRight :size="14" /></BaseButton></RouterLink>
    </template>

    <div class="conversation-layout">
      <section class="panel conversation-stream" aria-label="对话流">
        <div class="panel-heading">
          <div><p class="eyebrow">Personal Agent</p><h2>和 CrewScope 一起推进工作</h2><p>对话内容与任务事实分别保存，并通过 {{ focus }} 关联。</p></div>
          <StatusBadge tone="neutral">演示数据</StatusBadge>
        </div>
        <div class="messages" aria-live="polite">
          <article class="message message--human">
            <div class="message__avatar">张</div>
            <div><header><strong>你</strong><time>10:24</time></header><p>帮我把 GitHub Provider 接入方案推进起来。先检查认证边界和仓库绑定，再运行测试；涉及真实写操作先让我确认。</p></div>
          </article>
          <article class="message message--agent">
            <div class="message__avatar"><Sparkles :size="16" /></div>
            <div><header><strong>Personal Agent</strong><StatusBadge tone="agent">Agent</StatusBadge><time>10:25</time></header><p>已把目标关联到 <strong>{{ focus }}</strong>。Coding Agent 正在执行前三步，我会保留 Provider 权限范围、测试结果和变更摘要作为交付证据。</p>
              <div class="intent-card"><span>已确认任务意图</span><strong>建立 GitHub Provider 最小安全连接</strong><small>代码读取可自动执行 · 外部写操作进入 Review Gate</small></div>
            </div>
          </article>
          <article class="message message--agent message--active">
            <div class="message__avatar"><Sparkles :size="16" /></div>
            <div><header><strong>Personal Agent</strong><StatusBadge tone="agent" dot>正在协作</StatusBadge><time>刚刚</time></header><p>认证边界检查完成。发现 Webhook 自身事件过滤需要补一条回归测试，正在交给 Coding Agent 处理。</p></div>
          </article>
        </div>
        <form class="composer" @submit.prevent>
          <label class="sr-only" for="message">给 Personal Agent 发消息</label>
          <textarea id="message" rows="2" placeholder="继续说明目标，或 @成员 / Agent 协作…" />
          <footer><button type="button" aria-label="添加附件"><Paperclip :size="17" /></button><span>Enter 发送 · Shift + Enter 换行</span><button class="send" type="submit" aria-label="发送消息"><Send :size="16" /></button></footer>
        </form>
      </section>

      <section class="panel execution-canvas" aria-label="执行计划">
        <div class="panel-heading"><div><p class="eyebrow">Execution canvas</p><h2>计划与证据</h2><p class="mono">plan v3 · run_01K4…8F2</p></div><StatusBadge tone="agent" dot>3 / 5</StatusBadge></div>
        <ol class="plan-list">
          <li class="done"><Check :size="14" /><div><strong>确认 Provider 权限边界</strong><span>3 个 Scope · 最小权限校验通过</span></div><time>18s</time></li>
          <li class="done"><Check :size="14" /><div><strong>解析仓库绑定关系</strong><span>crewscope-java · main</span></div><time>9s</time></li>
          <li class="running"><Play :size="13" /><div><strong>补齐 Webhook 回归测试</strong><span>Agent 正在修改 2 个文件</span></div><time>运行中</time></li>
          <li><Circle :size="13" /><div><strong>运行测试与静态检查</strong><span>等待上一步完成</span></div></li>
          <li><Circle :size="13" /><div><strong>整理交付证据</strong><span>Diff、测试、风险与下一步</span></div></li>
        </ol>
        <div class="artifact-preview"><header><span><GitPullRequest :size="15" />工作区变更</span><strong>2 files · +41 −3</strong></header><div class="code-lines"><i /><i /><i /><i /><i /></div><footer><span>变更尚未产生外部副作用</span><button type="button">查看 Diff <ArrowRight :size="12" /></button></footer></div>
        <div class="review-gate"><div><p class="eyebrow">Next decision</p><strong>创建 Draft PR 前需要你的确认</strong><span>目标、权限和变更证据将在执行完成后锁定。</span></div><StatusBadge tone="warning">Review Gate</StatusBadge></div>
      </section>

      <aside class="context-column" aria-label="运行上下文">
        <section class="panel compact-panel"><div class="panel-heading"><div><h3>当前执行者</h3><p>可观测、可暂停、可接管</p></div></div><div class="panel-body"><AgentPresence :agent="demoAgent" /></div></section>
        <section class="panel compact-panel"><div class="panel-heading"><div><h3>责任链</h3><p>人工责任不会转移给 Agent</p></div></div><div class="panel-body"><ResponsibilityChain :members="demoResponsibilities" /></div></section>
        <section class="panel context-facts"><div class="panel-heading"><div><h3>工作上下文</h3><p>共享给参与执行的 Agent</p></div></div><dl><div><dt>Team</dt><dd>Platform Engineering</dd></div><div><dt>Repository</dt><dd class="mono">crewscope-java</dd></div><div><dt>Provider</dt><dd>GitHub · 待连接</dd></div><div><dt>风险策略</dt><dd>外部写入需确认</dd></div></dl></section>
      </aside>
    </div>
  </AppShell>
</template>

<style scoped>
.conversation-layout { display: grid; grid-template-columns: minmax(350px, 1.05fr) minmax(330px, .9fr) 290px; gap: 14px; min-height: calc(100vh - 176px); }
.conversation-stream { display: grid; min-height: 690px; grid-template-rows: auto 1fr auto; overflow: hidden; }
.messages { display: grid; align-content: start; gap: 24px; overflow: auto; padding: 22px 20px; }
.message { display: grid; grid-template-columns: 32px 1fr; gap: 10px; }
.message__avatar { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 10px; background: var(--cs-brand-800); color: white; font-size: 11px; font-weight: 750; }
.message--agent .message__avatar { background: var(--cs-agent-soft); color: var(--cs-agent); }
.message header { display: flex; align-items: center; gap: 7px; min-height: 25px; }
.message header strong { font-size: 12px; }.message header time { margin-left: auto; color: var(--cs-text-muted); font-size: 10px; }
.message p { margin: 5px 0 0; color: var(--cs-text-secondary); font-size: 13px; line-height: 1.65; }
.message--active > div:last-child { padding: 12px 13px; border: 1px solid #ddd3ef; border-radius: var(--cs-radius-md); background: var(--cs-agent-soft); }
.intent-card { display: grid; gap: 2px; margin-top: 11px; padding: 11px; border-left: 3px solid var(--cs-brand-400); border-radius: 4px var(--cs-radius-sm) var(--cs-radius-sm) 4px; background: var(--cs-brand-50); }
.intent-card span { color: var(--cs-brand-600); font-size: 10px; font-weight: 750; text-transform: uppercase; }.intent-card strong { font-size: 12px; }.intent-card small { color: var(--cs-text-muted); }
.composer { margin: 12px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: 0 7px 22px rgb(21 35 29 / 7%); }
.composer textarea { width: 100%; resize: none; padding: 12px 13px 4px; border: 0; outline: 0; background: transparent; font-size: 12px; }
.composer footer { display: flex; align-items: center; gap: 8px; padding: 6px 8px; color: var(--cs-text-muted); font-size: 9px; }.composer footer button { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 7px; background: transparent; color: inherit; cursor: pointer; }.composer footer span { flex: 1; }.composer footer .send { background: var(--cs-brand-950); color: white; }
.execution-canvas { overflow: hidden; }
.plan-list { display: grid; gap: 0; padding: 4px 20px; margin: 0; list-style: none; }
.plan-list li { position: relative; display: grid; min-height: 61px; grid-template-columns: 23px 1fr auto; align-items: center; gap: 7px; border-bottom: 1px solid var(--cs-border); color: var(--cs-text-muted); }
.plan-list li:last-child { border-bottom: 0; }.plan-list li > svg { padding: 3px; border: 1px solid var(--cs-border); border-radius: 50%; box-sizing: content-box; }.plan-list strong, .plan-list span { display: block; }.plan-list strong { color: var(--cs-text-secondary); font-size: 12px; }.plan-list span, .plan-list time { font-size: 10px; }.plan-list .done > svg { border-color: var(--cs-success); background: var(--cs-success); color: white; }.plan-list .running > svg { border-color: var(--cs-agent); background: var(--cs-agent); color: white; }.plan-list .running strong { color: var(--cs-agent); }
.artifact-preview { margin: 11px 20px; overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.artifact-preview header, .artifact-preview footer { display: flex; align-items: center; justify-content: space-between; padding: 9px 11px; font-size: 10px; }.artifact-preview header span, .artifact-preview footer button { display: flex; align-items: center; gap: 5px; }.artifact-preview header strong { font-family: var(--cs-font-mono); font-size: 9px; }.code-lines { display: grid; gap: 5px; padding: 12px; border-block: 1px solid var(--cs-border); background: #f7f9f7; }.code-lines i { width: 88%; height: 5px; border-radius: 3px; background: #d7e3da; }.code-lines i:nth-child(2) { width: 61%; background: #c7eacf; }.code-lines i:nth-child(3) { width: 73%; background: #c7eacf; }.code-lines i:nth-child(4) { width: 52%; }.artifact-preview footer { color: var(--cs-text-muted); }.artifact-preview footer button { background: transparent; color: var(--cs-brand-600); font-weight: 700; cursor: pointer; }
.review-gate { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin: 16px 20px; padding: 13px; border: 1px solid #f0d5ad; border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); }.review-gate strong, .review-gate span { display: block; }.review-gate strong { font-size: 12px; }.review-gate span { margin-top: 3px; color: var(--cs-text-muted); font-size: 10px; }
.context-column { display: grid; align-content: start; gap: 14px; }.compact-panel { overflow: hidden; }.panel-body { padding: 14px; }.context-facts { overflow: hidden; }.context-facts dl { padding: 6px 16px 12px; margin: 0; }.context-facts dl > div { display: flex; justify-content: space-between; gap: 8px; padding: 9px 0; border-bottom: 1px solid var(--cs-border); font-size: 10px; }.context-facts dl > div:last-child { border: 0; }.context-facts dt { color: var(--cs-text-muted); }.context-facts dd { margin: 0; font-weight: 650; text-align: right; }
@media (max-width: 1360px) { .conversation-layout { grid-template-columns: minmax(360px, 1.1fr) minmax(340px, .9fr); }.context-column { grid-column: 1 / -1; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 950px) { .conversation-layout { grid-template-columns: 1fr; }.conversation-stream { min-height: 640px; }.context-column { grid-template-columns: 1fr 1fr; }.context-column > :last-child { grid-column: 1 / -1; } }
@media (max-width: 767px) { .conversation-stream { min-height: 600px; }.execution-canvas { order: 2; }.context-column { order: 3; grid-template-columns: 1fr; }.context-column > :last-child { grid-column: auto; }.messages { padding: 18px 14px; }.message { grid-template-columns: 28px 1fr; }.message__avatar { width: 28px; height: 28px; }.composer footer span { display: none; } }
</style>
