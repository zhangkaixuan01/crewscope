<script setup lang="ts">
import { computed } from 'vue'
import BaseTable from '../base/BaseTable.vue'
import { gridTicks, niceCeiling, thinnedIndexes } from './scale'
import { CHART_DEGRADE_THRESHOLD, type ChartReference, type ChartSeries, type ChartValueFormatter } from './types'

/**
 * M12-F02 line chart: a deterministic hand-written SVG (WorkGraph precedent — no chart
 * library, zero animation). The picture is never the only voice: an sr-only table lists
 * every point, and past the degrade threshold the visible surface IS the table.
 */
const props = withDefaults(defineProps<{
  label: string
  series: ChartSeries[]
  reference?: ChartReference | null
  valueFormatter?: ChartValueFormatter
}>(), {
  reference: null,
})

const format = computed<ChartValueFormatter>(() => props.valueFormatter ?? defaultFormat)

/** All series share one x sequence (builder contract), so series[0] names the categories. */
const categories = computed(() => props.series[0]?.points.map(point => point.x) ?? [])
const counts = computed(() => props.series.map(entry => entry.points.length))
const degraded = computed(() => counts.value.some(count => count > CHART_DEGRADE_THRESHOLD))

const WIDTH = 640
const HEIGHT = 240
const PAD_LEFT = 64
const PAD_RIGHT = 16
const PAD_TOP = 16
const PAD_BOTTOM = 32
const plotWidth = WIDTH - PAD_LEFT - PAD_RIGHT
const plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM

const ceiling = computed(() => {
  const values = props.series.flatMap(entry => entry.points.map(point => point.y))
  if (props.reference) values.push(props.reference.value)
  return niceCeiling(Math.max(...values, 0))
})
const ticks = computed(() => gridTicks(ceiling.value))

function xOf(index: number): number {
  const count = categories.value.length
  if (count <= 1) return PAD_LEFT + plotWidth / 2
  return PAD_LEFT + (index / (count - 1)) * plotWidth
}

function yOf(value: number): number {
  return PAD_TOP + (1 - value / ceiling.value) * plotHeight
}

function seriesValue(series: ChartSeries, index: number): number {
  return series.points[index]?.y ?? 0
}

function polylineOf(series: ChartSeries): string {
  return series.points.map((_, index) => `${xOf(index)},${yOf(seriesValue(series, index))}`).join(' ')
}

const labeledX = computed(() => thinnedIndexes(categories.value.length, 8)
  .map(index => ({ index, text: categories.value[index]! })))

function defaultFormat(value: number): string {
  return value.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="line-chart">
    <BaseTable v-if="degraded" :caption="label">
      <thead>
        <tr>
          <th scope="col">月份</th>
          <th v-for="entry in series" :key="entry.key" scope="col">{{ entry.label }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(month, index) in categories" :key="month">
          <th scope="row">{{ month }}</th>
          <td v-for="entry in series" :key="entry.key">{{ format(seriesValue(entry, index)) }}</td>
        </tr>
      </tbody>
    </BaseTable>
    <p v-if="degraded && reference" class="line-chart__note">参考线：{{ reference.label }} = {{ format(reference.value) }}。</p>

    <template v-else>
      <svg
        class="line-chart__svg"
        :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
        role="group"
        :aria-label="label"
        focusable="false"
      >
        <g v-for="tick in ticks" :key="tick" class="line-chart__grid">
          <line :x1="PAD_LEFT" :y1="yOf(tick)" :x2="WIDTH - PAD_RIGHT" :y2="yOf(tick)" />
          <text :x="PAD_LEFT - 8" :y="yOf(tick) + 4" text-anchor="end">{{ format(tick) }}</text>
        </g>
        <line
          v-if="reference"
          class="line-chart__reference-line"
          stroke-dasharray="6 4"
          :x1="PAD_LEFT" :y1="yOf(reference.value)" :x2="WIDTH - PAD_RIGHT" :y2="yOf(reference.value)"
        />
        <g
          v-for="entry in series"
          :key="entry.key"
          class="line-chart__series"
          :class="`line-chart__series--${entry.tone}`"
        >
          <polyline :points="polylineOf(entry)" />
          <circle v-for="(point, index) in entry.points" :key="point.x" :cx="xOf(index)" :cy="yOf(seriesValue(entry, index))" r="3" />
        </g>
        <text
          v-if="reference"
          class="line-chart__reference-label"
          :x="WIDTH - PAD_RIGHT" :y="yOf(reference.value) - 6"
          text-anchor="end"
        >{{ reference.label }}</text>
        <g class="line-chart__axis">
          <text v-for="item in labeledX" :key="item.text" :x="xOf(item.index)" :y="HEIGHT - 10" text-anchor="middle">{{ item.text }}</text>
        </g>
      </svg>
      <ul class="line-chart__legend" aria-label="图例">
        <li v-for="entry in series" :key="entry.key">
          <span class="line-chart__swatch" :class="`line-chart__swatch--${entry.tone}`" aria-hidden="true"></span>{{ entry.label }}
        </li>
        <li v-if="reference">
          <span class="line-chart__swatch line-chart__swatch--reference" aria-hidden="true"></span>{{ reference.label }}
        </li>
      </ul>
      <!-- 非图形等价物：图上说的每一条事实，逐月一行（WorkGraphCanvas 清单先例）。
           The wrapper owns the 1px clip box: a bare sr-only table ignores width:1px
           (table min-content) and leaks its full width into the page scroll area. -->
      <div class="sr-only">
        <table :aria-label="`${label}（数据表）`">
          <caption>{{ label }}</caption>
          <thead>
            <tr>
              <th scope="col">月份</th>
              <th v-for="entry in series" :key="entry.key" scope="col">{{ entry.label }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(month, index) in categories" :key="month">
              <th scope="row">{{ month }}</th>
              <td v-for="entry in series" :key="entry.key">{{ format(seriesValue(entry, index)) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="reference" class="sr-only">参考线：{{ reference.label }} = {{ format(reference.value) }}。</p>
    </template>
  </div>
</template>

<style scoped>
.line-chart { display: grid; gap: var(--cs-space-8); min-width: 0; }
.line-chart__svg { display: block; width: 100%; height: auto; color: var(--cs-text-muted); }
.line-chart__grid line { stroke: var(--cs-border); stroke-width: 1; }
.line-chart__grid text { fill: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.line-chart__axis text { fill: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.line-chart__series--info polyline { fill: none; stroke: var(--cs-info); stroke-width: 2; }
.line-chart__series--info circle { fill: var(--cs-info); }
.line-chart__series--agent polyline { fill: none; stroke: var(--cs-agent); stroke-width: 2; }
.line-chart__series--agent circle { fill: var(--cs-agent); }
/* The dash pattern lives on the element as an SVG attribute so the dashed style is
   assertable and stays a non-color signal even where scoped CSS is unavailable. */
.line-chart__reference-line { stroke: var(--cs-warning); stroke-width: 1.5; }
.line-chart__reference-label { fill: var(--cs-warning); font-size: var(--cs-text-xs); }
.line-chart__legend { display: flex; flex-wrap: wrap; gap: var(--cs-space-12); margin: 0; padding: 0; list-style: none; font-size: var(--cs-text-sm); color: var(--cs-text-secondary); }
.line-chart__legend li { display: inline-flex; align-items: center; gap: var(--cs-space-4); }
.line-chart__swatch { width: var(--cs-space-12); height: var(--cs-space-4); border-radius: var(--cs-radius-sm); }
.line-chart__swatch--info { background: var(--cs-info); }
.line-chart__swatch--agent { background: var(--cs-agent); }
.line-chart__swatch--reference { background: repeating-linear-gradient(90deg, var(--cs-warning) 0 6px, transparent 6px 10px); }
.line-chart__note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
</style>
