#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
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
  {
    module: 'crewscope-infrastructure',
    forbidden: [/^io\.crewscope\.(agentscope|integration|server)\./],
  },
]

// Onion matrix enforced at the pom dependency-declaration level (M13-S01): a module may
// only declare the internal dependencies listed here. Drift in either direction
// (unexpected dependency, or a missing one after an intentional matrix change) fails
// the gate until this matrix and the review docs are updated together.
const internalDependencyMatrix = {
  'crewscope-domain': [],
  'crewscope-application': ['crewscope-domain'],
  'crewscope-agentscope': ['crewscope-domain', 'crewscope-application'],
  'crewscope-integration': ['crewscope-domain', 'crewscope-application'],
  'crewscope-infrastructure': ['crewscope-domain', 'crewscope-application'],
  'crewscope-server': ['crewscope-domain', 'crewscope-application', 'crewscope-agentscope', 'crewscope-integration', 'crewscope-infrastructure'],
}

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

async function declaredInternalDependencies(pomPath) {
  const xml = await readFile(pomPath, 'utf8')
  const declared = new Set()
  for (const block of xml.matchAll(/<dependency>[\s\S]*?<\/dependency>/g)) {
    const groupId = block[0].match(/<groupId>([^<]+)<\/groupId>/)?.[1]
    const artifactId = block[0].match(/<artifactId>([^<]+)<\/artifactId>/)?.[1]
    if (groupId === 'io.crewscope' && artifactId?.startsWith('crewscope-')) declared.add(artifactId)
  }
  return declared
}

for (const [module, expected] of Object.entries(internalDependencyMatrix)) {
  const pomPath = join(root, module, 'pom.xml')
  if (!existsSync(pomPath)) {
    violations.push(`${module}: matrix expects this module but ${module}/pom.xml is missing`)
    continue
  }
  const declared = await declaredInternalDependencies(pomPath)
  for (const artifactId of declared) {
    if (!expected.includes(artifactId)) {
      violations.push(`${module}/pom.xml: unexpected internal dependency ${artifactId} (allowed: ${expected.length > 0 ? expected.join(', ') : 'none'})`)
    }
  }
  for (const artifactId of expected) {
    if (!declared.has(artifactId)) {
      violations.push(`${module}/pom.xml: expected internal dependency ${artifactId} is not declared`)
    }
  }
}

const rootEntries = await readdir(root, { withFileTypes: true })
for (const entry of rootEntries) {
  if (!entry.isDirectory() || !entry.name.startsWith('crewscope-')) continue
  if (!existsSync(join(root, entry.name, 'pom.xml'))) continue // e.g. the frontend workspace
  if (!(entry.name in internalDependencyMatrix)) {
    violations.push(`${entry.name}: module pom exists but is not registered in internalDependencyMatrix`)
  }
}

if (violations.length > 0) {
  console.error('Module boundary violations detected:')
  for (const violation of violations) console.error(`- ${violation}`)
  process.exitCode = 1
} else {
  console.log('Module boundaries: PASS')
}
