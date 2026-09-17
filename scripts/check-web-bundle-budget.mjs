#!/usr/bin/env node

/** M9-Q01 build budget: keep the initial JS payload bounded without prescribing a bundler. */
import { readdir, stat } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const assets = resolve(root, 'crewscope-web/dist/assets')
const files = (await readdir(assets)).filter(name => name.endsWith('.js'))
const sizes = await Promise.all(files.map(async name => ({ name, bytes: (await stat(join(assets, name))).size })))
const largest = sizes.reduce((current, item) => item.bytes > current.bytes ? item : current, { name: 'none', bytes: 0 })
const total = sizes.reduce((sum, item) => sum + item.bytes, 0)
const maxInitialBytes = 500 * 1024
const maxTotalBytes = 2 * 1024 * 1024
const violations = []
if (largest.bytes > maxInitialBytes) violations.push(`largest chunk ${largest.name}: ${largest.bytes} > ${maxInitialBytes} bytes`)
if (total > maxTotalBytes) violations.push(`total JS ${total} > ${maxTotalBytes} bytes`)
if (violations.length) {
  console.error('M9-Q01 bundle budget failed:')
  violations.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 bundle budget: PASS (largest ${largest.name} ${largest.bytes} bytes, total ${total} bytes)`)
