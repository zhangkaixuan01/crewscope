#!/usr/bin/env node
/**
 * M10-Q02 comparison aggregator: folds the two arm JSONL files into the frozen-protocol
 * verdict (S01 §4 + plan rulings B2/B3/B4).
 *
 * Judge correctness is the primary metric (B3) with the product-observable outcome
 * (COMPLETED + tool evidence + diff manifest) reported alongside. Review first-pass
 * rate's denominator is the runs whose review actually executed (S01: "requests that
 * entered review"), not all runs.
 *
 * Pre-frozen acceptable regression bounds (B2): latency delta ≤ 1000 ms/task
 * (retrieval-stack overhead envelope: SQL P99 69 ms + embedding query latency), token
 * delta ≤ injection budget cap (knowledge 3072 + chunk 4096 = 7168 per attempt).
 * Presence of citations is never treated as evidence of accuracy.
 *
 * Usage:
 *   node scripts/m10-q02/report.mjs [--report-dir <dir>]
 *   node scripts/m10-q02/report.mjs --selftest
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const LATENCY_BOUND_MS = 1000
const TOKEN_BOUND_PER_RUN = 3072 + 4096

function readRuns(reportDir, arm) {
  const path = join(reportDir, `runs-${arm}.jsonl`)
  if (!existsSync(path)) return []
  return readFileSync(path, 'utf8').split('\n').filter(Boolean).map(line => {
    try { return JSON.parse(line) } catch { return null }
  }).filter(Boolean)
}

function percentile(sortedValues, p) {
  if (!sortedValues.length) return null
  const index = Math.min(sortedValues.length - 1, Math.floor(p * sortedValues.length))
  return sortedValues[index]
}

function aggregate(runs) {
  const total = runs.length
  const judgePassed = runs.filter(r => r.judge?.outcome === 'PASSED').length
  const productPassed = runs.filter(r => r.productOutcome === 'PASS').length
  const reviewEntered = runs.filter(r => r.review?.opened && r.review?.findings).length
  const reviewFirstPass = runs.filter(r => r.review?.firstPass === true).length
  const latencies = runs.filter(r => typeof r.wallMs === 'number').map(r => r.wallMs).sort((a, b) => a - b)
  const tokens = runs.reduce((acc, r) => ({
    input: acc.input + (r.tokens?.input ?? 0),
    output: acc.output + (r.tokens?.output ?? 0),
    cached: acc.cached + (r.tokens?.cached ?? 0),
  }), { input: 0, output: 0, cached: 0 })
  const rate = (numerator, denominator) => (denominator === 0 ? null : numerator / denominator)
  return {
    total,
    judgePassed, judgePassRate: rate(judgePassed, total),
    productPassed, productPassRate: rate(productPassed, total),
    reviewEntered, reviewFirstPass, reviewFirstPassRate: rate(reviewFirstPass, reviewEntered),
    tokenTotals: tokens,
    tokensPerRun: total === 0 ? null : {
      input: tokens.input / total, output: tokens.output / total, cached: tokens.cached / total,
    },
    latencyMeanMs: latencies.length ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : null,
    latencyP95Ms: percentile(latencies, 0.95),
    retries: runs.filter(r => r.retried).length,
    fatals: runs.filter(r => r.fatal).length,
    judgeOutcomes: runs.reduce((acc, r) => ({ ...acc, [r.judge?.outcome ?? 'MISSING']: (acc[r.judge?.outcome ?? 'MISSING'] ?? 0) + 1 }), {}),
    injectedRefsPerRun: total === 0 ? null : runs.reduce((a, r) => a + (r.injection?.injected ?? 0), 0) / total,
    reviewUnavailable: runs.filter(r => r.review?.unavailable).length,
  }
}

function verdictLine(off, on) {
  const checks = []
  const comparable = off.total > 0 && on.total > 0
  if (!comparable) {
    return { comparable: false, passed: false, checks: [{ name: 'both arms have runs', ok: false, detail: `off=${off.total} on=${on.total}` }] }
  }
  if (off.judgePassRate !== null && on.judgePassRate !== null) {
    checks.push({ name: 'judge correctness not degraded', ok: on.judgePassRate >= off.judgePassRate, detail: `on=${on.judgePassRate.toFixed(3)} off=${off.judgePassRate.toFixed(3)}` })
  }
  if (off.reviewFirstPassRate !== null && on.reviewFirstPassRate !== null) {
    checks.push({ name: 'review first-pass not degraded', ok: on.reviewFirstPassRate >= off.reviewFirstPassRate, detail: `on=${on.reviewFirstPassRate.toFixed(3)} off=${off.reviewFirstPassRate.toFixed(3)}` })
  }
  const latencyDelta = (on.latencyMeanMs ?? 0) - (off.latencyMeanMs ?? 0)
  checks.push({ name: `latency delta ≤ ${LATENCY_BOUND_MS} ms/task`, ok: latencyDelta <= LATENCY_BOUND_MS, detail: `${latencyDelta >= 0 ? '+' : ''}${latencyDelta} ms` })
  const tokenDelta = ((on.tokensPerRun?.input ?? 0) + (on.tokensPerRun?.output ?? 0))
    - ((off.tokensPerRun?.input ?? 0) + (off.tokensPerRun?.output ?? 0))
  checks.push({ name: `token delta ≤ ${TOKEN_BOUND_PER_RUN}/run (injection budget cap)`, ok: tokenDelta <= TOKEN_BOUND_PER_RUN, detail: `${tokenDelta >= 0 ? '+' : ''}${Math.round(tokenDelta)} tokens` })
  return { comparable: true, passed: checks.every(c => c.ok), checks }
}

function renderMarkdown(off, on, verdict) {
  const percent = rate => (rate === null ? '—' : `${(rate * 100).toFixed(1)}%`)
  const lines = []
  lines.push('# M10-Q02 对照实验汇总（S01 §4 冻结协议）')
  lines.push('')
  lines.push('| 指标 | 臂-off（检索/注入关） | 臂-on（六开关全开） |')
  lines.push('| --- | --- | --- |')
  lines.push(`| 完成任务运行 | ${off.total} | ${on.total} |`)
  lines.push(`| 正确率（judge 主口径） | ${percent(off.judgePassRate)} | ${percent(on.judgePassRate)} |`)
  lines.push(`| 正确率（产品口径并列） | ${percent(off.productPassRate)} | ${percent(on.productPassRate)} |`)
  lines.push(`| Review 进入数 / 一次通过 | ${off.reviewEntered} / ${percent(off.reviewFirstPassRate)} | ${on.reviewEntered} / ${percent(on.reviewFirstPassRate)} |`)
  lines.push(`| Token in/out/cached 合计 | ${off.tokenTotals.input}/${off.tokenTotals.output}/${off.tokenTotals.cached} | ${on.tokenTotals.input}/${on.tokenTotals.output}/${on.tokenTotals.cached} |`)
  lines.push(`| Token in+out 均值/运行 | ${off.tokensPerRun ? Math.round(off.tokensPerRun.input + off.tokensPerRun.output) : '—'} | ${on.tokensPerRun ? Math.round(on.tokensPerRun.input + on.tokensPerRun.output) : '—'} |`)
  lines.push(`| 延迟均值 / P95 (ms) | ${off.latencyMeanMs ?? '—'} / ${off.latencyP95Ms ?? '—'} | ${on.latencyMeanMs ?? '—'} / ${on.latencyP95Ms ?? '—'} |`)
  lines.push(`| infra 重试 / 致命错误 | ${off.retries} / ${off.fatals} | ${on.retries} / ${on.fatals} |`)
  lines.push(`| 注入引用均值/运行 | ${off.injectedRefsPerRun !== null ? off.injectedRefsPerRun.toFixed(1) : '—'} | ${on.injectedRefsPerRun !== null ? on.injectedRefsPerRun.toFixed(1) : '—'} |`)
  lines.push(`| judge 结果分布 | ${JSON.stringify(off.judgeOutcomes)} | ${JSON.stringify(on.judgeOutcomes)} |`)
  lines.push('')
  lines.push('## 冻结验收判定行（B2 预冻结界限）')
  lines.push('')
  if (verdict.comparable) {
    for (const check of verdict.checks) lines.push(`- ${check.ok ? '✅' : '❌'} ${check.name}（${check.detail}）`)
    lines.push('')
    lines.push(`**判定：${verdict.passed ? '通过' : '未通过'}**`)
  } else {
    lines.push(`- ❌ ${verdict.checks[0].name}（${verdict.checks[0].detail}）`)
  }
  lines.push('')
  return lines.join('\n')
}

function main() {
  const args = process.argv.slice(2)
  if (args.includes('--selftest')) {
    const fixtureOff = [
      { judge: { outcome: 'PASSED' }, productOutcome: 'PASS', wallMs: 100_000, tokens: { input: 100_000, output: 2_000, cached: 0 }, review: { opened: true, findings: {}, firstPass: true }, injection: { injected: 0 } },
      { judge: { outcome: 'JUDGE_FAILED' }, productOutcome: 'FAIL', wallMs: 120_000, tokens: { input: 110_000, output: 2_200, cached: 0 }, review: { opened: true, findings: {}, firstPass: false }, injection: { injected: 0 } },
    ]
    const fixtureOn = [
      { judge: { outcome: 'PASSED' }, productOutcome: 'PASS', wallMs: 100_400, tokens: { input: 103_000, output: 2_000, cached: 0 }, review: { opened: true, findings: {}, firstPass: true }, injection: { injected: 3 } },
      { judge: { outcome: 'PASSED' }, productOutcome: 'PASS', wallMs: 120_300, tokens: { input: 112_000, output: 2_100, cached: 0 }, review: { opened: true, findings: {}, firstPass: true }, injection: { injected: 4 } },
    ]
    const off = aggregate(fixtureOff)
    const on = aggregate(fixtureOn)
    const verdict = verdictLine(off, on)
    const assertions = [
      ['off judge rate 0.5', off.judgePassRate === 0.5],
      ['on judge rate 1.0', on.judgePassRate === 1.0],
      ['off review first-pass 0.5', off.reviewFirstPassRate === 0.5],
      ['latency delta +350ms within bound', verdict.checks.find(c => c.name.startsWith('latency')).ok === true],
      ['token delta +2500 within budget cap', verdict.checks.find(c => c.name.startsWith('token')).ok === true],
      ['verdict passes', verdict.passed === true],
    ]
    let failed = 0
    for (const [name, ok] of assertions) {
      if (!ok) { failed += 1; console.error(`[selftest] FAIL ${name}`) }
    }
    if (failed) process.exit(1)
    console.log('[selftest] aggregate + verdict assertions passed')
    console.log(renderMarkdown(off, on, verdict))
    return
  }
  const reportDirIndex = args.indexOf('--report-dir')
  const reportDir = resolve(reportDirIndex >= 0 ? args[reportDirIndex + 1] : 'var/release/m10-q02/reports')
  const off = aggregate(readRuns(reportDir, 'off'))
  const on = aggregate(readRuns(reportDir, 'on'))
  const verdict = verdictLine(off, on)
  const markdown = renderMarkdown(off, on, verdict)
  mkdirSync(reportDir, { recursive: true })
  writeFileSync(join(reportDir, 'comparison-summary.md'), markdown)
  writeFileSync(join(reportDir, 'comparison-summary.json'), JSON.stringify({ off, on, verdict, bounds: { latencyMs: LATENCY_BOUND_MS, tokensPerRun: TOKEN_BOUND_PER_RUN } }, null, 2))
  console.log(markdown)
  console.error(`written: ${join(reportDir, 'comparison-summary.md')}`)
  process.exit(verdict.passed ? 0 : 1)
}

main()
