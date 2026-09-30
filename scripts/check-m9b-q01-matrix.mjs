#!/usr/bin/env node

/**
 * M9b-Q01 回归矩阵文档的结构校验（设计裁定 6：只做结构托底——字段非空、证据路径存在；
 * 映射正确性靠 review 把关，不在脚本里复述语义）。
 *
 * 校验对象 docs/testing/M9b-Q01-全流程回归矩阵.md 的台账表（边界契约 :341 十字段）：
 *   切片ID｜父包｜R编号｜菜单/操作｜C验收组｜提交/版本｜命令与环境｜结果与证据路径｜未执行原因｜后续动作
 *
 * 规则：
 * 1. 文档与台账表头必须存在，十字段一个不少（顺序不敏感，列以表头解析）。
 * 2. 每数据行核心字段非空；「未执行原因」在结果含「未执行」时必填；「后续动作」可空。
 * 3. 「结果与证据路径」等单元格里出现的仓库相对路径（含 `:行号` 后缀）必须真实存在——
 *    路径提取只认「至少一个 `/` 且形如文件」的 token，命令词（pnpm/mvn）天然不匹配。
 */

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const matrixPath = join(repositoryRoot, 'docs/testing/M9b-Q01-全流程回归矩阵.md')

const LEDGER_COLUMNS = [
  '切片ID', '父包', 'R编号', '菜单/操作', 'C验收组',
  '提交/版本', '命令与环境', '结果与证据路径', '未执行原因', '后续动作',
]
const CORE_COLUMNS = ['切片ID', '父包', 'R编号', '菜单/操作', 'C验收组', '命令与环境', '结果与证据路径']

if (!existsSync(matrixPath)) {
  fail(`矩阵文档不存在: docs/testing/M9b-Q01-全流程回归矩阵.md（S8 交付物，缺失即失败）`)
}

const failures = []
const markdown = readFileSync(matrixPath, 'utf8')
const lines = markdown.split('\n')

// 表格数据行 = 以 `|` 开头；先在全文里找台账表头行（十字段全中出现的那一行）。
let headerIndex = -1
for (const [index, line] of lines.entries()) {
  const cells = splitRow(line)
  if (cells.length > 0 && LEDGER_COLUMNS.every(column => cells.includes(column))) {
    headerIndex = index
    break
  }
}
if (headerIndex < 0) {
  fail(`台账表头缺失：需含全部 ${LEDGER_COLUMNS.length} 个字段（${LEDGER_COLUMNS.join('｜')}）`)
}

const columns = splitRow(lines[headerIndex])
const columnIndex = new Map(LEDGER_COLUMNS.map(column => [column, columns.indexOf(column)]))
let dataRows = 0

for (const line of lines.slice(headerIndex + 1)) {
  const cells = splitRow(line)
  if (cells.length === 0) break // 空行 = 表格结束
  if (cells.every(cell => /^:?-{3,}:?$/.test(cell))) continue // 表头分隔行
  if (cells.length !== columns.length) {
    failures.push(`台账行列数 ${cells.length} ≠ 表头 ${columns.length}: ${cells[0] ?? ''}`)
    continue
  }
  dataRows += 1
  const row = new Map([...columnIndex].map(([column, index]) => [column, (cells[index] ?? '').trim()]))

  for (const column of CORE_COLUMNS) {
    if (!row.get(column)) failures.push(`行 ${row.get('切片ID')}: 「${column}」为空`)
  }
  // 「提交/版本」结项前允许 pending 占位，但必须显式写出来（非空）。
  if (!row.get('提交/版本')) failures.push(`行 ${row.get('切片ID')}: 「提交/版本」为空（结项前可写 pending）`)

  const outcome = row.get('结果与证据路径') ?? ''
  if (outcome.includes('未执行') && !row.get('未执行原因')) {
    failures.push(`行 ${row.get('切片ID')}: 结果含「未执行」但「未执行原因」为空`)
  }

  // 证据路径存在性：全行扫（证据路径也可能出现在命令列里）。只把「纯 ASCII 且以文件
  // 扩展名结尾」的 token 当路径——中文短语、q01/S1、22/22 这类斜杠文本不是路径，
  // 校验它们属于误伤（首次空跑 83 项误报实证后收紧，保持「形如文件」的设计意图）。
  for (const token of line.match(/[A-Za-z0-9_./-]+\/[A-Za-z0-9_./-]+/g) ?? []) {
    if (!/\.[A-Za-z][A-Za-z0-9]*$/.test(token)) continue
    const filePath = token.replace(/:\d+$/, '')
    if (!existsSync(join(repositoryRoot, filePath))) {
      failures.push(`行 ${row.get('切片ID')}: 证据路径不存在: ${token}`)
    }
  }
}

if (dataRows === 0) {
  failures.push('台账表没有数据行')
}

if (failures.length > 0) {
  console.error(`M9b-Q01 矩阵结构校验失败（${failures.length}）:`)
  for (const failure of failures) console.error(`- ${failure}`)
  process.exit(1)
}

console.log(`M9b-Q01 矩阵结构校验通过: ${dataRows} 条台账行，证据路径全部存在。`)

function splitRow(line) {
  const trimmed = line.trim()
  if (!trimmed.startsWith('|')) return []
  return trimmed.slice(1, -1).split('|').map(cell => cell.trim())
}

function fail(message) {
  console.error(`M9b-Q01 矩阵结构校验失败: ${message}`)
  process.exit(2)
}
