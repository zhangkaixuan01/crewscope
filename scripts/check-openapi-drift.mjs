#!/usr/bin/env node

/** Contract gate for the OpenAPI, state-machine and GenerateOptions-limits generated artefacts. */
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawn } from 'node:child_process'

const root = resolve(new URL('..', import.meta.url).pathname)

/** Runs one generator in --check mode; its own output and exit code are the report. */
async function checkGenerated(generator) {
  const result = await new Promise(resolveResult => {
    const child = spawn(process.execPath, [generator, '--check'], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.on('close', code => resolveResult({ code: code ?? 1, stdout, stderr }))
  })
  if (result.stdout) process.stdout.write(result.stdout)
  if (result.stderr) process.stderr.write(result.stderr)
  return result.code
}

for (const name of ['generate-state-machine.mjs', 'generate-openapi-types.mjs', 'generate-agent-limits.mjs']) {
  const code = await checkGenerated(resolve(root, 'scripts', name))
  if (code !== 0) process.exit(code)
}

const openApiPath = resolve(root, 'crewscope-web/src/api/generated/openapi.ts')
const openApi = await readFile(openApiPath, 'utf8').catch(() => '')
if (!openApi.includes('OpenApiDocument')) {
  console.error(`Missing generated OpenAPI document: ${openApiPath}`)
  process.exit(1)
}
if (!openApi.includes('openapi: \'3.1.0\'')) {
  console.error(`Generated OpenAPI document must declare 3.1.0: ${openApiPath}`)
  process.exit(1)
}
const operationCount = Number(openApi.match(/openApiOperationCount = (\d+)/)?.[1] ?? 0)
if (operationCount !== 217) {
  console.error(`OpenAPI operation baseline changed: expected 217, found ${operationCount}`)
  process.exit(1)
}
if (openApi.includes('Mapping}}')) {
  console.error(`Generated OpenAPI path contains an unbalanced mapping placeholder: ${openApiPath}`)
  process.exit(1)
}

const stateMachinePath = resolve(root, 'crewscope-web/src/api/generated/state-machines.ts')
const stateMachines = await readFile(stateMachinePath, 'utf8').catch(() => '')
const aggregateCount = (stateMachines.match(/^  "[^"]+": \{$/gm) ?? []).length
if (aggregateCount !== 16) {
  console.error(`State-machine aggregate baseline changed: expected 16, found ${aggregateCount}`)
  process.exit(1)
}
const workItemBlock = stateMachines.match(/"WorkItem": \{([\s\S]*?)\n  \}\n\} as const/)
const workItemStatesBlock = workItemBlock?.[1].match(/"states": \[\n([\s\S]*?)\n    \]/)?.[1] ?? ''
const workItemStates = workItemStatesBlock.match(/^      "[A-Z_]+"[,]?$/gm) ?? []
if (workItemStates.length !== 8) {
  console.error(`WorkItem state baseline changed: expected 8 states, found ${workItemStates.length}`)
  process.exit(1)
}
const workItemTransitionsBlock = workItemBlock?.[1].match(/"transitions": \{\n([\s\S]*?)\n    \},/)?.[1] ?? ''
const workItemTransitions = workItemTransitionsBlock.match(/^      "[A-Z_]+": \[/gm) ?? []
if (workItemTransitions.length !== 8) {
  console.error(`WorkItem transition baseline changed: expected 8 source states, found ${workItemTransitions.length}`)
  process.exit(1)
}
const workItemEdgeCount = [...workItemBlock?.[1].matchAll(/^        "[A-Z_]+"[,]?$/gm) ?? []].length
if (workItemEdgeCount !== 17) {
  console.error(`WorkItem transition baseline changed: expected 17 edges, found ${workItemEdgeCount}`)
  process.exit(1)
}
const agentLimitsPath = resolve(root, 'crewscope-web/src/api/generated/agent-limits.ts')
const agentLimits = await readFile(agentLimitsPath, 'utf8').catch(() => '')
const limitFields = [...agentLimits.matchAll(/^  "([A-Za-z]+)": \{$/gm)].map(match => match[1])
if (limitFields.join(',') !== 'temperature,topP,maximumOutputTokens,maximumAttempts') {
  console.error(`GenerateOptions limit baseline changed: found ${limitFields.join(',') || 'nothing'}`)
  process.exit(1)
}
// The form's labelled ranges are built from these bounds, so a bound that goes missing or becomes
// non-numeric would render as an empty promise rather than failing loudly.
const boundsMissing = ['temperature', 'topP', 'maximumOutputTokens', 'maximumAttempts']
  .filter(field => !new RegExp(`"${field}": \\{[^}]*"minimum": "-?\\d+(\\.\\d+)?"`, 's').test(agentLimits)
    || !new RegExp(`"${field}": \\{[^}]*"step": "\\d+(\\.\\d+)?"`, 's').test(agentLimits))
if (boundsMissing.length) {
  console.error(`GenerateOptions limit bounds are missing or unparseable: ${boundsMissing.join(', ')}`)
  process.exit(1)
}

console.log('OpenAPI generated artefact is present and declares OpenAPI 3.1.0.')
console.log('State-machine generated artefact covers 16 aggregates and the 8-state/17-edge WorkItem machine.')
console.log('GenerateOptions limit artefact covers 4 bounded fields with numeric bounds and whole steps.')
