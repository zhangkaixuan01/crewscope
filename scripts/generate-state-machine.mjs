#!/usr/bin/env node

/**
 * Generates the public state-machine catalogue from domain source maps.
 * The domain aggregates remain authoritative; this script only exposes the
 * states and transitions needed for action discovery in the Web application.
 */
import { readFile, readdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, resolve } from 'node:path'

const repoRoot = resolve(new URL('..', import.meta.url).pathname)
const domainRoot = join(repoRoot, 'crewscope-domain/src/main/java/io/crewscope/domain')
const outputPath = join(repoRoot, 'crewscope-web/src/api/generated/state-machines.ts')
const checkOnly = process.argv.includes('--check')

async function javaFiles(directory) {
  const result = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) result.push(...await javaFiles(path))
    else if (entry.name.endsWith('.java')) result.push(path)
  }
  return result.sort()
}

function splitTopLevel(value) {
  const parts = []
  let start = 0
  let depth = 0
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]
    if (character === '(') depth += 1
    if (character === ')') depth -= 1
    if (character === ',' && depth === 0) {
      parts.push(value.slice(start, index).trim())
      start = index + 1
    }
  }
  const tail = value.slice(start).trim()
  if (tail) parts.push(tail)
  return parts
}

function balancedBody(source, openingIndex) {
  let depth = 0
  for (let index = openingIndex; index < source.length; index += 1) {
    if (source[index] === '(') depth += 1
    if (source[index] === ')') {
      depth -= 1
      if (depth === 0) return source.slice(openingIndex + 1, index)
    }
  }
  throw new Error('Unbalanced transition map')
}

function parseMap(source, filePath, match) {
  const statusType = match[1]
  const fieldName = match[2]
  const openingIndex = source.indexOf('(', match.index)
  const body = balancedBody(source, openingIndex)
  const entries = splitTopLevel(body)
  const transitions = {}
  const add = (key, values) => {
    if (!key) return
    // Preserve the domain declaration order so generated action lists remain
    // backward-compatible with the existing UI while staying deterministic.
    transitions[key] = [...new Set(values)]
  }

  if (fieldName === 'TRANSITIONS' || fieldName === 'ALLOWED_TRANSITIONS' || fieldName === 'ALLOWED_STATUS_TRANSITIONS') {
    if (entries.every(entry => entry.startsWith('Map.entry('))) {
      for (const entry of entries) {
        const inner = entry.slice('Map.entry('.length, -1)
        const comma = inner.indexOf(',')
        const key = inner.slice(0, comma).match(/\.([A-Z][A-Z0-9_]*)/)?.[1]
        const values = [...inner.slice(comma + 1).matchAll(new RegExp(`${statusType}\\.([A-Z][A-Z0-9_]*)`, 'g'))]
          .map(value => value[1])
        add(key, values)
      }
    } else {
      for (let index = 0; index + 1 < entries.length; index += 2) {
        const key = entries[index].match(/\.([A-Z][A-Z0-9_]*)/)?.[1]
        const values = [...entries[index + 1].matchAll(new RegExp(`${statusType}\\.([A-Z][A-Z0-9_]*)`, 'g'))]
          .map(value => value[1])
        add(key, values)
      }
    }
  }
  if (!Object.keys(transitions).length) return null

  const states = [...new Set([
    ...Object.keys(transitions),
    ...Object.values(transitions).flat(),
  ])].sort()
  // A terminal state may be represented either by an explicit empty source
  // entry or only as a destination. Treat missing source entries as empty so
  // both forms remain semantically equivalent in the generated catalogue.
  const terminalStates = states.filter(state => (transitions[state] ?? []).length === 0)
  return {
    name: filePath.replaceAll('\\', '/').split('/').pop().replace(/\.java$/, ''),
    statusType,
    states,
    transitions,
    terminalStates,
  }
}

const files = await javaFiles(domainRoot)
const contents = await Promise.all(files.map(path => readFile(path, 'utf8')))
const sourceHash = createHash('sha256').update(contents.join('\n')).digest('hex')
const machines = []
for (let index = 0; index < files.length; index += 1) {
  const source = contents[index]
  const pattern = /Map<(\w+),\s*Set<\1>>\s+(\w+)\s*=\s*Map\.(?:of|ofEntries)\s*\(/g
  for (const match of source.matchAll(pattern)) {
    const machine = parseMap(source, files[index], match)
    if (machine) machines.push(machine)
  }
}
machines.sort((left, right) => left.name.localeCompare(right.name))

const generated = `/* eslint-disable */
// GENERATED FILE. Source: crewscope-domain transition maps.
// Regenerate with: node scripts/generate-state-machine.mjs
// Domain source SHA-256: ${sourceHash}

export interface GeneratedStateMachine {
  readonly name: string
  readonly statusType: string
  readonly states: readonly string[]
  readonly transitions: Readonly<Record<string, readonly string[]>>
  readonly terminalStates: readonly string[]
}

export const stateMachines = ${JSON.stringify(Object.fromEntries(machines.map(machine => [machine.name, machine])), null, 2)} as const satisfies Readonly<Record<string, GeneratedStateMachine>>

export type StateMachineName = keyof typeof stateMachines
export const workItemStateMachine = stateMachines.WorkItem
`

if (checkOnly) {
  const current = await readFile(outputPath, 'utf8').catch(() => '')
  if (current !== generated) {
    console.error(`State-machine output is stale: ${outputPath}`)
    process.exitCode = 1
  } else {
    console.log(`State-machine output is up to date (${machines.length} aggregates).`)
  }
} else {
  await writeFile(outputPath, generated)
  console.log(`Generated ${outputPath} (${machines.length} aggregates).`)
}
