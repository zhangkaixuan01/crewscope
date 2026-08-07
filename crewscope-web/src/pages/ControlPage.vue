<script setup lang="ts">
import { AlertTriangle, ArrowRight, Bot, CheckCircle2, Clock3, MessageSquare, Plus, UsersRound } from '@lucide/vue'
import { RouterLink, useRoute } from 'vue-router'
import AppShell from '../components/layout/AppShell.vue'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import ResponsibilityChain from '../components/domain/ResponsibilityChain.vue'
import { demoResponsibilities, demoWorkItems, statusPresentation } from '../domains/demo/fixtures'

const route = useRoute()
</script>

<template>
  <AppShell eyebrow="Control · Team workspace" title="Platform Engineering">
    <template #actions><RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation', query: route.query }"><BaseButton variant="secondary" size="small" @click="navigate"><MessageSquare :size="14" />带到对话</BaseButton></RouterLink><BaseButton size="small"><Plus :size="14" />新建工作</BaseButton></template>

    <div class="control-page page-shell">
      <section class="pulse-hero">
        <div><p class="eyebrow">Team pulse · Thursday</p><h2>团队的工作、责任与决策在同一处汇合。</h2><p>当前有 2 个 Agent 在执行，3 项工作需要人工关注。</p></div>
        <p class="demo-note"><span />M0 产品骨架 · 使用演示数据</p>
      </section>

      <section class="metric-grid" aria-label="团队摘要">
        <article><span class="metric-icon metric-icon--green"><CheckCircle2 :size="18" /></span><div><small>本周完成</small><strong>14</strong><p>比上周多 3 项</p></div></article>
        <article><span class="metric-icon metric-icon--purple"><Bot :size="18" /></span><div><small>Agent 运行中</small><strong>2</strong><p>1 项等待工具结果</p></div></article>
        <article><span class="metric-icon metric-icon--orange"><Clock3 :size="18" /></span><div><small>等待 Review</small><strong>3</strong><p>最久等待 47 分钟</p></div></article>
        <article><span class="metric-icon metric-icon--red"><AlertTriangle :size="18" /></span><div><small>阻塞工作</small><strong>1</strong><p>等待网络策略决定</p></div></article>
      </section>

      <div class="control-grid">
        <section class="panel work-list">
          <div class="panel-heading"><div><p class="eyebrow">Active work</p><h2>需要关注的工作</h2><p>状态、责任和执行者来自同一团队投影。</p></div><button type="button">查看全部 <ArrowRight :size="13" /></button></div>
          <div class="work-table" role="table" aria-label="工作项列表">
            <div class="work-table__head" role="row"><span>工作</span><span>状态</span><span>Owner</span><span>Executor</span><span>更新</span></div>
            <RouterLink v-for="item in demoWorkItems" :key="item.key" :to="{ name: 'control', query: { ...route.query, focus: item.key } }" class="work-row" role="row" :aria-label="`${item.key} ${item.title}`">
              <span class="work-title"><small class="mono">{{ item.key }}</small><strong>{{ item.title }}</strong></span>
              <span><StatusBadge :tone="statusPresentation[item.status].tone" dot>{{ statusPresentation[item.status].label }}</StatusBadge></span>
              <span class="person"><i>{{ item.owner.slice(0, 1) }}</i>{{ item.owner }}</span>
              <span class="executor"><Bot :size="14" />{{ item.executor }}</span>
              <span class="updated">{{ item.updatedAt }}<ArrowRight :size="13" /></span>
            </RouterLink>
          </div>
        </section>

        <aside class="control-aside">
          <section class="panel"><div class="panel-heading"><div><p class="eyebrow">Selected · {{ route.query.focus || 'CRW-18' }}</p><h3>当前责任</h3><p>一屏确认谁负责、谁执行、谁把关</p></div></div><div class="aside-body"><ResponsibilityChain :members="demoResponsibilities" /><RouterLink class="context-link" :to="{ name: 'conversation', query: { ...route.query, focus: route.query.focus || 'CRW-18' } }"><MessageSquare :size="14" />在对话中继续处理<ArrowRight :size="13" /></RouterLink></div></section>
          <section class="panel activity-panel"><div class="panel-heading"><div><p class="eyebrow">Team activity</p><h3>团队动态</h3></div><UsersRound :size="18" /></div><ol><li><i class="agent"><Bot :size="13" /></i><p><strong>Coding Agent</strong> 完成权限边界检查<span>刚刚 · CRW-18</span></p></li><li><i>林</i><p><strong>林晨</strong> 请求修改责任转移规则<span>18 分钟前 · CRW-15</span></p></li><li><i>王</i><p><strong>王博</strong> 标记网络策略阻塞<span>1 小时前 · CRW-12</span></p></li></ol></section>
        </aside>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.pulse-hero { display: flex; min-height: 150px; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 25px 28px; overflow: hidden; border: 1px solid #cfe2d3; border-radius: var(--cs-radius-lg); background: radial-gradient(circle at 88% 4%, rgb(142 213 167 / 32%), transparent 34%), linear-gradient(135deg, #f1faf4, #e5f3e9); color: var(--cs-text); }
.pulse-hero h2 { max-width: 680px; margin-bottom: 9px; font: 25px/1.2 var(--cs-font-display); }.pulse-hero > div > p:last-child { margin-bottom: 0; color: var(--cs-text-muted); font-size: 12px; }.pulse-hero .eyebrow { color: var(--cs-brand-600); }.pulse-hero .demo-note { color: var(--cs-text-muted); }.demo-note span { width: 6px; height: 6px; border-radius: 50%; background: var(--cs-brand-400); }
.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }.metric-grid article { display: grid; grid-template-columns: 40px 1fr; gap: 12px; padding: 16px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.metric-icon { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 11px; }.metric-icon--green { background: var(--cs-success-soft); color: var(--cs-success); }.metric-icon--purple { background: var(--cs-agent-soft); color: var(--cs-agent); }.metric-icon--orange { background: var(--cs-warning-soft); color: var(--cs-warning); }.metric-icon--red { background: var(--cs-danger-soft); color: var(--cs-danger); }.metric-grid small, .metric-grid strong, .metric-grid p { display: block; }.metric-grid small { color: var(--cs-text-muted); font-size: 10px; font-weight: 650; }.metric-grid strong { font-size: 23px; font-variant-numeric: tabular-nums; }.metric-grid p { margin-bottom: 0; color: var(--cs-text-muted); font-size: 9px; }
.control-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; }.work-list { overflow: hidden; }.panel-heading > button { display: flex; align-items: center; gap: 5px; background: transparent; color: var(--cs-brand-600); font-size: 11px; font-weight: 700; cursor: pointer; }
.work-table__head, .work-row { display: grid; grid-template-columns: minmax(260px, 1.7fr) 110px 110px 140px 90px; align-items: center; gap: 10px; padding-inline: 18px; }.work-table__head { min-height: 36px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .05em; text-transform: uppercase; }.work-row { min-height: 68px; border-bottom: 1px solid var(--cs-border); transition: background var(--cs-transition-fast); }.work-row:last-child { border-bottom: 0; }.work-row:hover { background: var(--cs-brand-50); }.work-title small, .work-title strong { display: block; }.work-title small { color: var(--cs-brand-600); font-size: 9px; }.work-title strong { margin-top: 2px; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.person, .executor, .updated { display: flex; align-items: center; gap: 6px; color: var(--cs-text-secondary); font-size: 10px; }.person i { display: grid; width: 23px; height: 23px; place-items: center; border-radius: 50%; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 9px; font-style: normal; font-weight: 750; }.executor svg { color: var(--cs-agent); }.updated { justify-content: space-between; color: var(--cs-text-muted); }
.control-aside { display: grid; align-content: start; gap: 14px; }.control-aside .panel { overflow: hidden; }.aside-body { padding: 16px; }.context-link { display: flex; align-items: center; gap: 6px; margin-top: 5px; padding: 10px; border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-700); font-size: 10px; font-weight: 700; }.context-link svg:last-child { margin-left: auto; }.activity-panel .panel-heading > svg { color: var(--cs-text-muted); }.activity-panel ol { display: grid; gap: 14px; padding: 15px 16px 18px; margin: 0; list-style: none; }.activity-panel li { display: grid; grid-template-columns: 28px 1fr; gap: 9px; }.activity-panel i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 50%; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 9px; font-style: normal; font-weight: 750; }.activity-panel i.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.activity-panel p { margin: 0; color: var(--cs-text-secondary); font-size: 10px; }.activity-panel p strong { color: var(--cs-text); }.activity-panel p span { display: block; margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }
@media (max-width: 1180px) { .metric-grid { grid-template-columns: 1fr 1fr; }.control-grid { grid-template-columns: 1fr; }.control-aside { grid-template-columns: 1fr 1fr; } }
@media (max-width: 820px) { .work-table__head { display: none; }.work-row { grid-template-columns: 1fr auto; gap: 7px; padding-block: 12px; }.work-title { grid-column: 1 / -1; }.work-row > .person, .work-row > .executor { display: none; }.updated { justify-content: flex-end; }.control-aside { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .pulse-hero { min-height: 170px; align-items: flex-start; flex-direction: column; padding: 20px; }.pulse-hero h2 { font-size: 22px; }.metric-grid { grid-template-columns: 1fr 1fr; gap: 8px; }.metric-grid article { grid-template-columns: 32px 1fr; gap: 9px; padding: 12px; }.metric-icon { width: 32px; height: 32px; }.metric-grid strong { font-size: 19px; }.metric-grid p { display: none; } }
</style>
