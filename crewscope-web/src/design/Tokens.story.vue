<script setup lang="ts">
import './tokens.css'
import './base.css'

const colors = [
  ['Brand 950', '#15231d'], ['Brand 600', '#3f7257'], ['Brand 300', '#8ed5a7'], ['Brand 200', '#b8efca'],
  ['Canvas', '#f3f5f2'], ['Surface', '#ffffff'], ['Info', '#3f78b5'], ['Agent', '#7257b5'],
  ['Warning', '#a86820'], ['Danger', '#b84c4c'], ['Success', '#43845e'],
]
</script>

<template>
  <Story title="Design/Token">
    <Variant title="Color and type">
      <div class="token-story">
        <header><p>CREWSCOPE DESIGN SYSTEM</p><h1>团队工作需要清楚的责任与证据。</h1><span>Sans 负责高密度工作界面，Serif 只用于品牌识别和低频标题。</span></header>
        <section><article v-for="color in colors" :key="color[0]"><i :style="{ background: color[1] }" /><strong>{{ color[0] }}</strong><code>{{ color[1] }}</code></article></section>
      </div>
    </Variant>
  </Story>
</template>

<style scoped>
.token-story { min-height: 100vh; padding: 32px; background: var(--cs-canvas); color: var(--cs-text); font-family: var(--cs-font-sans); }.token-story header { max-width: 700px; }.token-story header p { color: var(--cs-brand-600); font-size: 11px; font-weight: 800; letter-spacing: .1em; }.token-story h1 { font: 32px var(--cs-font-display); }.token-story header span { color: var(--cs-text-muted); }.token-story section { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; margin-top: 28px; }.token-story article { overflow: hidden; border: 1px solid var(--cs-border); border-radius: 12px; background: var(--cs-surface); }.token-story i { display: block; height: 76px; }.token-story strong, .token-story code { display: block; padding-inline: 10px; }.token-story strong { padding-top: 8px; }.token-story code { padding-bottom: 9px; color: var(--cs-text-muted); font-size: 10px; }
</style>
