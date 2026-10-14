import { mount } from '@vue/test-utils'
import LineChart from './LineChart.vue'
import type { ChartSeries } from './types'

function series(overrides: Partial<ChartSeries> = {}): ChartSeries {
  return {
    key: 'intake',
    label: '任务受理数',
    tone: 'info',
    points: [
      { x: '2026-09', y: 4 },
      { x: '2026-10', y: 7 },
    ],
    ...overrides,
  }
}

function mountChart(overrides: Record<string, unknown> = {}) {
  return mount(LineChart, {
    props: { label: '每月任务受理数趋势', series: [series()], ...overrides },
  })
}

describe('LineChart', () => {
  it('draws one polyline and one point per datum, naming the group after the label', () => {
    const wrapper = mountChart()

    const svg = wrapper.get('svg')
    expect(svg.attributes('role')).toBe('group')
    expect(svg.attributes('aria-label')).toBe('每月任务受理数趋势')
    expect(wrapper.findAll('polyline')).toHaveLength(1)
    expect(wrapper.findAll('circle')).toHaveLength(2)
  })

  it('states every datum in the sr-only equivalent table, formatted', () => {
    const wrapper = mountChart({ valueFormatter: (value: number) => `${value} 个` })

    const table = wrapper.get('.sr-only table')
    expect(table.attributes('aria-label')).toBe('每月任务受理数趋势（数据表）')
    const rows = table.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('2026-09')
    expect(rows[0]!.text()).toContain('4 个')
    expect(rows[1]!.text()).toContain('7 个')
  })

  it('renders the legend with one entry per series plus the reference', () => {
    const wrapper = mountChart({
      series: [series(), series({ key: 'reserved', label: '预占 Token（承诺口径）', tone: 'agent' })],
      reference: { label: '当前受理上限', value: 5 },
    })

    const legend = wrapper.get('[aria-label="图例"]')
    expect(legend.text()).toContain('任务受理数')
    expect(legend.text()).toContain('预占 Token（承诺口径）')
    expect(legend.text()).toContain('当前受理上限')
    // Color is never the only signal: the reference line is dashed and named in text.
    expect(wrapper.find('.line-chart__reference-line').exists()).toBe(true)
    expect(wrapper.get('.line-chart__reference-line').attributes('stroke-dasharray')).toBe('6 4')
  })

  it('degrades to a visible table past the threshold instead of an unreadable picture', () => {
    const points = Array.from({ length: 201 }, (_, index) => ({ x: `m${index}`, y: index }))
    const wrapper = mountChart({ series: [series({ points })], reference: { label: '当前受理上限', value: 5 } })

    expect(wrapper.find('svg').exists()).toBe(false)
    const rows = wrapper.findAll('.base-table tbody tr')
    expect(rows).toHaveLength(201)
    expect(wrapper.text()).toContain('参考线：当前受理上限 = 5')
  })

  it('thins the x-axis labels so neighbors never collide', () => {
    const months = [
      '2025-01', '2025-02', '2025-03', '2025-04', '2025-05', '2025-06', '2025-07', '2025-08',
      '2025-09', '2025-10', '2025-11', '2025-12', '2026-01', '2026-02', '2026-03', '2026-04',
    ]
    const points = months.map((x, index) => ({ x, y: index }))
    const wrapper = mountChart({ series: [series({ points })] })

    const labels = wrapper.findAll('.line-chart__axis text')
    expect(labels.length).toBeLessThanOrEqual(9)
    // The last point is always named even under thinning.
    expect(labels.at(-1)!.text()).toBe('2026-04')
  })
})
