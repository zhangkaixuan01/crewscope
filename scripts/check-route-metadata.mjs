#!/usr/bin/env node

/** M9-Q01 route metadata ratchet: every navigable route needs a unique tab title. */
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const routerPath = resolve(root, 'crewscope-web/src/app/router.ts')
const shellPath = resolve(root, 'crewscope-web/src/components/layout/AppShell.vue')
const router = await readFile(routerPath, 'utf8')
const shell = await readFile(shellPath, 'utf8')

const routeLines = router.split(/\r?\n/).filter(line => /meta:\s*\{/.test(line))
const titles = routeLines.map(line => line.match(/title:\s*'([^']+)'/)?.[1] ?? null)
const missing = routeLines.filter((_, index) => !titles[index])
const duplicates = [...new Set(titles.filter(Boolean).filter((title, index, all) => all.indexOf(title) !== index))]
const violations = []
if (missing.length) violations.push(`${missing.length} 条路由缺少 meta.title`)
if (duplicates.length) violations.push(`重复页面标题：${duplicates.join('、')}`)
if (!/document\.title\s*=/.test(router)) violations.push('router 未在导航后写入 document.title')
if (!/aria-current=/.test(shell)) violations.push('AppShell 导航缺少 aria-current')

if (violations.length) {
  console.error('M9-Q01 route metadata gate failed:')
  violations.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 route metadata gate: PASS (${routeLines.length} routes, ${titles.length} unique titles, aria-current present)`)
