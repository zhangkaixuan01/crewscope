/**
 * 中文标签层：模板一律经 labels 取值（check-web-enum-labels 门禁禁裸枚举插值）。
 * F02 的枚举面很小：趋势序列名与 Token 构成维度名——裸值是英文契约词，直接渲染会
 * 把口径差异（预占承诺 vs 实际 rollup）在页面上抹平。
 */

export const quotaTrendSeriesLabels = {
  intake: '任务受理数',
  reserved: '预占 Token（承诺口径）',
  actual: '实际 Token（rollup）',
} as const

export const quotaTokenPartLabels = {
  input: '输入',
  output: '输出',
  cachedInput: '缓存输入',
  total: '合计',
} as const

export type QuotaTrendSeriesKey = keyof typeof quotaTrendSeriesLabels
export type QuotaTokenPartKey = keyof typeof quotaTokenPartLabels
