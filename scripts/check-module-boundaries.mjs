#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const rules = [
  {
    module: 'crewscope-domain',
    forbidden: [/^org\.springframework\./, /^jakarta\.persistence\./, /^jakarta\.spring\./, /^io\.agentscope\./, /^io\.crewscope\.(application|agentscope|infrastructure|integration|server)\./],
  },
  {
    module: 'crewscope-application',
    // Validation annotations are part of the public command/query contract; adapters and
    // runtime frameworks remain outside the application boundary.
    forbidden: [/^org\.springframework\./, /^io\.agentscope\./, /^io\.crewscope\.(agentscope|infrastructure|integration|server)\./],
  },
  {
    module: 'crewscope-agentscope',
    forbidden: [/^io\.crewscope\.(infrastructure|integration|server)\./],
  },
  {
    module: 'crewscope-integration',
    forbidden: [/^io\.crewscope\.(agentscope|infrastructure|server)\./],
  },
]

async function javaFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await javaFiles(path))
    else if (entry.name.endsWith('.java')) files.push(path)
  }
  return files
}

const violations = []
for (const rule of rules) {
  const source = join(root, rule.module, 'src/main/java')
  let files = []
  try { files = await javaFiles(source) } catch { continue }
  for (const file of files) {
    const content = await readFile(file, 'utf8')
    for (const line of content.split(/\r?\n/)) {
      const match = line.match(/^import\s+([^;]+);/)
      if (!match) continue
      if (rule.forbidden.some(pattern => pattern.test(match[1]))) {
        violations.push(`${file.replace(`${root}/`, '')}: ${match[1]}`)
      }
    }
  }
}

if (violations.length > 0) {
  console.error('Module boundary violations detected:')
  for (const violation of violations) console.error(`- ${violation}`)
  process.exitCode = 1
} else {
  console.log('Module boundaries: PASS')
}
