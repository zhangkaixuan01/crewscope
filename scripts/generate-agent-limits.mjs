#!/usr/bin/env node

/**
 * Generates the Web copy of the configurable GenerateOptions ranges.
 *
 * The domain table stays authoritative; this script only republishes it so the configuration form
 * renders the same `min`/`max`/`step` the server validates against. Run with --check to compare
 * instead of writing, which is what `scripts/check-openapi-drift.mjs` does in CI.
 */
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, resolve } from 'node:path'

const repoRoot = resolve(new URL('..', import.meta.url).pathname)
const sourcePath = join(
  repoRoot,
  'crewscope-domain/src/main/java/io/crewscope/domain/agent/AgentGenerateOptionsLimits.java',
)
const outputPath = join(repoRoot, 'crewscope-web/src/api/generated/agent-limits.ts')
const checkOnly = process.argv.includes('--check')

const source = await readFile(sourcePath, 'utf8')
const sourceHash = createHash('sha256').update(source).digest('hex')

// One declaration is one `new Limit(...)`, whichever line it is wrapped over. The bounds are read as
// text: the exact spelling is part of what the form publishes, so it must survive verbatim.
const declaration = /public static final Limit ([A-Z][A-Z0-9_]*)\s*=\s*new Limit\(\s*"([^"]+)"\s*,\s*Shape\.([A-Z]+)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*(true|false)\s*,\s*"([^"]*)"\s*,\s*(true|false)\s*\)/g
const declared = new Map()
for (const match of source.matchAll(declaration)) {
  declared.set(match[1], {
    field: match[2],
    shape: match[3],
    minimum: match[4],
    maximum: match[5],
    includeMinimum: match[6] === 'true',
    step: match[7],
    required: match[8] === 'true',
  })
}
if (!declared.size) {
  console.error(`No GenerateOptions limits found in ${sourcePath}`)
  process.exit(1)
}

const listing = source.match(/public static final List<Limit> ALL = List\.of\(([\s\S]*?)\);/)?.[1]
if (listing === undefined) {
  console.error(`No ALL declaration found in ${sourcePath}`)
  process.exit(1)
}
// ALL is the form's rendering order. A declared limit missing from it would exist in the domain and
// never reach the form, which is the drift this generator is here to prevent.
const order = [...listing.matchAll(/[A-Z][A-Z0-9_]*/g)].map(match => match[0])
const missing = [...declared.keys()].filter(name => !order.includes(name))
if (missing.length) {
  console.error(`Declared but not listed in ALL: ${missing.join(', ')}`)
  process.exit(1)
}
if (order.length !== declared.size) {
  console.error(`ALL lists ${order.length} limits but ${declared.size} are declared`)
  process.exit(1)
}

const limits = {}
for (const name of order) {
  const limit = declared.get(name)
  if (!limit) {
    console.error(`ALL references an unknown limit: ${name}`)
    process.exit(1)
  }
  if (limit.shape !== 'DECIMAL' && limit.shape !== 'INTEGER') {
    console.error(`Unknown shape for ${limit.field}: ${limit.shape}`)
    process.exit(1)
  }
  limits[limit.field] = limit
}

const generated = `/* eslint-disable */
// GENERATED FILE. Source: crewscope-domain AgentGenerateOptionsLimits.
// Regenerate with: node scripts/generate-agent-limits.mjs
// Domain source SHA-256: ${sourceHash}

/**
 * One configurable GenerateOptions field, exactly as the domain publishes it.
 *
 * The bounds are strings because their spelling is part of the contract: the temperature step is
 * "0.01" and not 0.010 or 1E-2. Parse them with \`Number()\` and format them from the record, so a
 * label and the input's own attributes can never disagree.
 */
export interface AgentGenerateOptionLimit {
  readonly field: string
  readonly shape: 'DECIMAL' | 'INTEGER'
  readonly minimum: string
  readonly maximum: string
  /** Whether \`minimum\` itself is inside the range. */
  readonly includeMinimum: boolean
  readonly step: string
  /** Whether the field must carry a number: an empty value is not the model default for this one. */
  readonly required: boolean
}

/** Declaration order is the order the form renders, and \`Object.keys\` preserves it. */
export const agentGenerateOptionLimits = ${JSON.stringify(limits, null, 2)} as const satisfies Readonly<Record<string, AgentGenerateOptionLimit>>

export type AgentGenerateOptionField = keyof typeof agentGenerateOptionLimits
`

if (checkOnly) {
  const current = await readFile(outputPath, 'utf8').catch(() => '')
  if (current !== generated) {
    console.error(`Agent limit output is stale: ${outputPath}`)
    process.exitCode = 1
  } else {
    console.log(`Agent limit output is up to date (${order.length} fields).`)
  }
} else {
  await writeFile(outputPath, generated)
  console.log(`Generated ${outputPath} (${order.length} fields).`)
}
