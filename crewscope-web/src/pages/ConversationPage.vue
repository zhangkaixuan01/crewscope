<script setup lang="ts">
import { ArrowRight, Circle, GitPullRequest, Paperclip, Send, ShieldCheck, Sparkles } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AppShell from '../components/layout/AppShell.vue'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import { useScopeStore } from '../domains/scope/store'

const route = useRoute()
const scopeStore = useScopeStore()
const focus = computed(() => String(route.query.focus || 'CRW-18'))
const teamName = computed(() => scopeStore.selectedTeam.value?.name ?? 'Team workspace')
</script>

<template>
  <AppShell :eyebrow="`Conversation · ${teamName}`" :title="`${focus} · 对话工作区预览`">
    <template #actions>
      <StatusBadge tone="neutral">M2 原型预览</StatusBadge>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'today', query: route.query }"><BaseButton variant="secondary" size="small" @click="navigate">在工作台查看<ArrowRight :size="14" /></BaseButton></RouterLink>
    </template>

    <div class="conversation-layout">
      <section class="panel conversation-stream" aria-label="对话流">
        <div class="panel-heading">
          <div><p class="eyebrow">Personal Agent blueprint</p><h2>和 CrewScope 一起推进工作</h2><p>以下内容用于验证对话、任务事实和 {{ focus }} 的交互结构，不会创建真实对话或执行。</p></div>
          <StatusBadge tone="neutral">交互示例</StatusBadge>
        </div>
        <div class="messages" aria-live="polite">
          <article class="message message--human">
            <div class="message__avatar">张</div>
            <div><header><strong>成员示例</strong><time>输入示例</time></header><p>帮我把 GitHub Provider 接入方案推进起来。先检查认证边界和仓库绑定，再运行测试；涉及真实写操作先让我确认。</p></div>
          </article>
          <article class="message message--agent">
            <div class="message__avatar"><Sparkles :size="16" /></div>
            <div><header><strong>Personal Agent</strong><StatusBadge tone="agent">规划角色</StatusBadge><time>回复示例</time></header><p>真实能力接入后，Personal Agent 会把目标关联到 <strong>{{ focus }}</strong>，澄清边界并形成可确认的 TaskIntent；当前示例不会调度 Coding Agent。</p>
              <div class="intent-card"><span>TaskIntent 预览</span><strong>建立 GitHub Provider 最小安全连接</strong><small>预期策略：代码读取可自动执行 · 外部写操作进入 Review Gate</small></div>
            </div>
          </article>
          <article class="message message--agent message--active">
            <div class="message__avatar"><Sparkles :size="16" /></div>
            <div><header><strong>能力边界</strong><StatusBadge tone="neutral">尚未接入</StatusBadge><time>M2</time></header><p>当前仅展示交互蓝图，不会创建 Conversation、TaskIntent、TaskExecution、AgentRun，也不会调用 Provider 或修改代码。</p></div>
          </article>
        </div>
        <form class="composer" aria-label="消息输入预览" @submit.prevent>
          <label class="sr-only" for="message">给 Personal Agent 发消息</label>
          <textarea id="message" rows="2" disabled placeholder="M2 接入后可继续说明目标，或 @成员 / Agent 协作…" />
          <footer><button type="button" disabled aria-label="添加附件（规划中）"><Paperclip :size="17" /></button><span>M2 接入后开放消息与附件</span><button class="send" type="submit" disabled aria-label="发送消息（规划中）"><Send :size="16" /></button></footer>
        </form>
      </section>

      <section class="panel execution-canvas" aria-label="执行交互蓝图">
        <div class="panel-heading"><div><p class="eyebrow">Execution blueprint</p><h2>计划与证据结构</h2><p>接入真实 Runtime 后由服务端事实填充</p></div><StatusBadge tone="neutral">规划预览</StatusBadge></div>
        <ol class="plan-list">
          <li class="blueprint"><Circle :size="13" /><div><strong>确认 Provider 权限边界</strong><span>预期输出：连接范围与最小权限说明</span></div><time>计划步骤</time></li>
          <li class="blueprint"><Circle :size="13" /><div><strong>解析仓库绑定关系</strong><span>预期输出：仓库、分支与工作区事实</span></div><time>计划步骤</time></li>
          <li class="blueprint"><Circle :size="13" /><div><strong>生成并确认 TaskIntent</strong><span>确认后才允许创建受治理的 TaskExecution</span></div><time>Review Gate</time></li>
          <li class="blueprint"><Circle :size="13" /><div><strong>运行测试与静态检查</strong><span>未来由真实 Step、ToolCall 和 Artifact 提供证据</span></div><time>规划中</time></li>
          <li class="blueprint"><Circle :size="13" /><div><strong>整理交付证据</strong><span>预期包含 Diff、测试、风险与下一步</span></div><time>规划中</time></li>
        </ol>
        <div class="artifact-preview"><header><span><GitPullRequest :size="15" />预期证据区域</span><strong>当前没有真实 Diff</strong></header><div class="code-lines" aria-hidden="true"><i /><i /><i /><i /><i /></div><footer><span>接入后展示可追溯的变更与测试证据</span><span class="artifact-stage">M2 规划</span></footer></div>
        <div class="review-gate"><div><p class="eyebrow">Planned decision</p><strong>外部写操作进入人工确认</strong><span>真实执行接入后，目标、权限和变更证据会在 Review Gate 中锁定。</span></div><StatusBadge tone="warning">规划中</StatusBadge></div>
      </section>

      <aside class="context-column" aria-label="规划上下文">
        <section class="panel compact-panel"><div class="panel-heading"><div><h3>执行者模型</h3><p>真实 Agent Session 接入后显示</p></div></div><div class="panel-body prototype-fact"><Sparkles :size="18" /><div><strong>Personal Agent 负责理解与协作</strong><span>Specialist Agent 只在 TaskExecution 创建后承担具体执行。</span></div></div></section>
        <section class="panel compact-panel"><div class="panel-heading"><div><h3>责任模型</h3><p>人工责任不会转移给 Agent</p></div></div><div class="panel-body prototype-fact"><ShieldCheck :size="18" /><div><strong>人类 Owner 与 Gate Reviewer</strong><span>服务端裁决责任、资格、职责分离和接管权限。</span></div></div></section>
        <section class="panel context-facts"><div class="panel-heading"><div><h3>当前范围事实</h3><p>来自已接入的 Scope API</p></div></div><dl><div><dt>Team</dt><dd>{{ teamName }}</dd></div><div><dt>WorkProject</dt><dd class="mono">{{ scopeStore.selectedProject.value?.key ?? '—' }}</dd></div><div><dt>Provider</dt><dd>尚未连接</dd></div><div><dt>风险策略</dt><dd>外部写入需确认</dd></div></dl></section>
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
.composer textarea { width: 100%; resize: none; padding: 12px 13px 4px; border: 0; outline: 0; background: transparent; font-size: 12px; }.composer textarea:disabled { color: var(--cs-text-muted); cursor: not-allowed; }
.composer footer { display: flex; align-items: center; gap: 8px; padding: 6px 8px; color: var(--cs-text-muted); font-size: 9px; }.composer footer button { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 7px; background: transparent; color: inherit; }.composer footer button:disabled { cursor: not-allowed; opacity: .55; }.composer footer span { flex: 1; }.composer footer .send { background: var(--cs-brand-950); color: white; }
.execution-canvas { overflow: hidden; }
.plan-list { display: grid; gap: 0; padding: 4px 20px; margin: 0; list-style: none; }
.plan-list li { position: relative; display: grid; min-height: 61px; grid-template-columns: 23px 1fr auto; align-items: center; gap: 7px; border-bottom: 1px solid var(--cs-border); color: var(--cs-text-muted); }
.plan-list li:last-child { border-bottom: 0; }.plan-list li > svg { padding: 3px; border: 1px solid var(--cs-border); border-radius: 50%; box-sizing: content-box; }.plan-list strong, .plan-list span { display: block; }.plan-list strong { color: var(--cs-text-secondary); font-size: 12px; }.plan-list span, .plan-list time { font-size: 10px; }.plan-list .blueprint > svg { border-color: var(--cs-brand-300); background: var(--cs-brand-50); color: var(--cs-brand-700); }.plan-list .blueprint strong { color: var(--cs-text-secondary); }
.artifact-preview { margin: 11px 20px; overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.artifact-preview header, .artifact-preview footer { display: flex; align-items: center; justify-content: space-between; padding: 9px 11px; font-size: 10px; }.artifact-preview header span { display: flex; align-items: center; gap: 5px; }.artifact-preview header strong { font-family: var(--cs-font-mono); font-size: 9px; }.code-lines { display: grid; gap: 5px; padding: 12px; border-block: 1px solid var(--cs-border); background: #f7f9f7; }.code-lines i { width: 88%; height: 5px; border-radius: 3px; background: #d7e3da; }.code-lines i:nth-child(2) { width: 61%; background: #c7eacf; }.code-lines i:nth-child(3) { width: 73%; background: #c7eacf; }.code-lines i:nth-child(4) { width: 52%; }.artifact-preview footer { color: var(--cs-text-muted); }.artifact-stage { color: var(--cs-brand-600); font-weight: 700; }
.review-gate { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin: 16px 20px; padding: 13px; border: 1px solid #f0d5ad; border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); }.review-gate strong, .review-gate span { display: block; }.review-gate strong { font-size: 12px; }.review-gate span { margin-top: 3px; color: var(--cs-text-muted); font-size: 10px; }
.context-column { display: grid; align-content: start; gap: 14px; }.compact-panel { overflow: hidden; }.panel-body { padding: 14px; }.prototype-fact { display: flex; align-items: flex-start; gap: 10px; }.prototype-fact > svg { flex: 0 0 auto; color: var(--cs-agent); }.prototype-fact strong, .prototype-fact span { display: block; }.prototype-fact strong { font-size: 11px; }.prototype-fact span { margin-top: 3px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.context-facts { overflow: hidden; }.context-facts dl { padding: 6px 16px 12px; margin: 0; }.context-facts dl > div { display: flex; justify-content: space-between; gap: 8px; padding: 9px 0; border-bottom: 1px solid var(--cs-border); font-size: 10px; }.context-facts dl > div:last-child { border: 0; }.context-facts dt { color: var(--cs-text-muted); }.context-facts dd { margin: 0; font-weight: 650; text-align: right; }
@media (max-width: 1360px) { .conversation-layout { grid-template-columns: minmax(360px, 1.1fr) minmax(340px, .9fr); }.context-column { grid-column: 1 / -1; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 950px) { .conversation-layout { grid-template-columns: 1fr; }.conversation-stream { min-height: 640px; }.context-column { grid-template-columns: 1fr 1fr; }.context-column > :last-child { grid-column: 1 / -1; } }
@media (max-width: 767px) { .conversation-stream { min-height: 600px; }.execution-canvas { order: 2; }.context-column { order: 3; grid-template-columns: 1fr; }.context-column > :last-child { grid-column: auto; }.messages { padding: 18px 14px; }.message { grid-template-columns: 28px 1fr; }.message__avatar { width: 28px; height: 28px; }.composer footer span { display: none; } }
</style>
