import { mount } from '@vue/test-utils'
import BarChart from './BarChart.vue'
import type { ChartBar } from './types'

const bars: ChartBar[] = [
  { key: 'input', label: '输入', value: 100_000, tone: 'info' },
  { key: 'output', label: '输出', value: 50_000, tone: 'info' },
  { key: 'cachedInput', label: '缓存输入', value: 25_000, tone: 'agent' },
  { key: 'total', label: '合计', value: 175_000, tone: 'agent' },
]

function mountChart(overrides: Record<string, unknown> = {}) {
  return mount(BarChart, { props: { label: '2026-10 实际 Token 构成', bars, ...overrides } })
}

describe('BarChart', () => {
  it('draws one rect per bar, naming the group after the label', () => {
    const wrapper = mountChart()

    const svg = wrapper.get('svg')
    expect(svg.attributes('role')).toBe('group')
    expect(svg.attributes('aria-label')).toBe('2026-10 实际 Token 构成')
    expect(wrapper.findAll('.bar-chart__bar')).toHaveLength(4)
  })

  it('rides the formatted value on each bar top so color never carries the data', () => {
    const wrapper = mountChart({ valueFormatter: (value: number) => `${value / 1000}k` })

    const values = wrapper.findAll('.bar-chart__value')
    expect(values.map(node => node.text())).toEqual(['100k', '50k', '25k', '175k'])
  })

  it('states every bar in the sr-only equivalent table', () => {
    const wrapper = mountChart()

    const table = wrapper.get('.sr-only table')
    expect(table.attributes('aria-label')).toBe('2026-10 实际 Token 构成（数据表）')
    const rows = table.findAll('tbody tr')
    expect(rows).toHaveLength(4)
    expect(rows[0]!.text()).toContain('输入')
    expect(rows[0]!.text()).toContain('100,000')
    expect(rows[3]!.text()).toContain('175,000')
  })

  it('degrades to a visible table past the threshold instead of an unreadable picture', () => {
    const many: ChartBar[] = Array.from({ length: 201 }, (_, index) => ({ key: `b${index}`, label: `维度${index}`, value: index }))
    const wrapper = mountChart({ bars: many })

    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.findAll('.base-table tbody tr')).toHaveLength(201)
  })
})
