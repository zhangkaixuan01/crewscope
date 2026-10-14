<script setup lang="ts">
import { computed } from 'vue'
import BaseTable from '../base/BaseTable.vue'
import { gridTicks, niceCeiling, thinnedIndexes } from './scale'
import { CHART_DEGRADE_THRESHOLD, type ChartBar, type ChartValueFormatter } from './types'

/**
 * M12-F02 bar chart: same rules as LineChart — deterministic hand-written SVG, an
 * sr-only equivalent table, and table-only rendering past the degrade threshold.
 * The formatted value rides each bar's top so color never carries the data alone.
 */
const props = withDefaults(defineProps<{
  label: string
  bars: ChartBar[]
  valueFormatter?: ChartValueFormatter
}>(), {})

const format = computed<ChartValueFormatter>(() => props.valueFormatter ?? defaultFormat)

const degraded = computed(() => props.bars.length > CHART_DEGRADE_THRESHOLD)

const WIDTH = 640
const HEIGHT = 240
const PAD_LEFT = 64
const PAD_RIGHT = 16
const PAD_TOP = 20
const PAD_BOTTOM = 32
const plotWidth = WIDTH - PAD_LEFT - PAD_RIGHT
const plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM

const ceiling = computed(() => niceCeiling(Math.max(...props.bars.map(bar => bar.value), 0)))
const ticks = computed(() => gridTicks(ceiling.value))
const barSlot = computed(() => props.bars.length > 0 ? plotWidth / props.bars.length : plotWidth)

function xCenterOf(index: number): number {
  return PAD_LEFT + (index + 0.5) * barSlot.value
}

function yOf(value: number): number {
  return PAD_TOP + (1 - value / ceiling.value) * plotHeight
}

function barHeight(value: number): number {
  return Math.max(yOf(0) - yOf(value), 1)
}

const labeledX = computed(() => thinnedIndexes(props.bars.length, 8)
  .map(index => ({ index, text: props.bars[index]!.label })))

function defaultFormat(value: number): string {
  return value.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="bar-chart">
    <BaseTable v-if="degraded" :caption="label">
      <thead>
        <tr><th scope="col">维度</th><th scope="col">数值</th></tr>
      </thead>
      <tbody>
        <tr v-for="bar in bars" :key="bar.key">
          <th scope="row">{{ bar.label }}</th>
          <td>{{ format(bar.value) }}</td>
        </tr>
      </tbody>
    </BaseTable>

    <template v-else>
      <svg
        class="bar-chart__svg"
        :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
        role="group"
        :aria-label="label"
        focusable="false"
      >
        <g v-for="tick in ticks" :key="tick" class="bar-chart__grid">
          <line :x1="PAD_LEFT" :y1="yOf(tick)" :x2="WIDTH - PAD_RIGHT" :y2="yOf(tick)" />
          <text :x="PAD_LEFT - 8" :y="yOf(tick) + 4" text-anchor="end">{{ format(tick) }}</text>
        </g>
        <rect
          v-for="(bar, index) in bars"
          :key="bar.key"
          class="bar-chart__bar"
          :class="`bar-chart__bar--${bar.tone ?? 'info'}`"
          :x="xCenterOf(index) - barSlot * 0.3"
          :y="yOf(bar.value)"
          :width="barSlot * 0.6"
          :height="barHeight(bar.value)"
        />
        <text
          v-for="(bar, index) in bars"
          :key="`${bar.key}-value`"
          class="bar-chart__value"
          :x="xCenterOf(index)" :y="yOf(bar.value) - 6"
          text-anchor="middle"
        >{{ format(bar.value) }}</text>
        <g class="bar-chart__axis">
          <text v-for="item in labeledX" :key="item.text" :x="xCenterOf(item.index)" :y="HEIGHT - 10" text-anchor="middle">{{ item.text }}</text>
        </g>
      </svg>
      <!-- 非图形等价物：每根柱的标签与数值逐行可读。The wrapper owns the 1px clip box
           (a bare sr-only table ignores width:1px and leaks into the page scroll area). -->
      <div class="sr-only">
        <table :aria-label="`${label}（数据表）`">
          <caption>{{ label }}</caption>
          <thead>
            <tr><th scope="col">维度</th><th scope="col">数值</th></tr>
          </thead>
          <tbody>
            <tr v-for="bar in bars" :key="bar.key">
              <th scope="row">{{ bar.label }}</th>
              <td>{{ format(bar.value) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.bar-chart { display: grid; gap: var(--cs-space-8); min-width: 0; }
.bar-chart__svg { display: block; width: 100%; height: auto; color: var(--cs-text-muted); }
.bar-chart__grid line { stroke: var(--cs-border); stroke-width: 1; }
.bar-chart__grid text { fill: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.bar-chart__axis text { fill: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.bar-chart__bar--info { fill: var(--cs-info); }
.bar-chart__bar--agent { fill: var(--cs-agent); }
.bar-chart__value { fill: var(--cs-text-secondary); font-size: var(--cs-text-xs); }
</style>
