#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'

const root = new URL('../crewscope-web/src/', import.meta.url).pathname
const violations = []

async function inspect(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      if (entry.name !== 'test') await inspect(path)
      continue
    }
    if (!/\.(ts|vue)$/.test(entry.name) || /\.(spec|test)\.(ts|vue)$/.test(entry.name)) continue
    const content = await readFile(path, 'utf8')
    content.split(/\r?\n/).forEach((line, index) => {
      if (/[ \t]+$/.test(line)) violations.push(`${path}:${index + 1}: trailing whitespace`)
      if (/\r$/.test(line)) violations.push(`${path}:${index + 1}: CRLF is not allowed`)
    })
  }
}

await inspect(root)
if (violations.length) {
  console.error('Web quality violations detected:')
  violations.forEach(v => console.error(`- ${v}`))
  process.exitCode = 1
} else {
  console.log('Web quality: PASS')
}
