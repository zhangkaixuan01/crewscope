#!/usr/bin/env node

/**
 * Creates a checked-in, type-safe OpenAPI path catalogue from the server
 * controllers. Runtime Springdoc remains the rich schema source; this
 * lightweight catalogue gives the Web build and CI a deterministic contract
 * even when PostgreSQL/Redis are not running.
 */
import { readFile, readdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, resolve } from 'node:path'

const root = resolve(new URL('..', import.meta.url).pathname)
const apiRoot = join(root, 'crewscope-server/src/main/java/io/crewscope/server/api')
const output = join(root, 'crewscope-web/src/api/generated/openapi.ts')
const checkOnly = process.argv.includes('--check')

const files = (await readdir(apiRoot, { withFileTypes: true }))
  .filter(entry => entry.isFile() && entry.name.endsWith('Controller.java'))
  .map(entry => join(apiRoot, entry.name))
  .sort()

const source = await Promise.all(files.map(path => readFile(path, 'utf8')))
const sourceHash = createHash('sha256').update(source.join('\n')).digest('hex')
const operations = []
const mappingPattern = /@(Get|Post|Put|Delete|Patch)Mapping/g
const classPattern = /@RequestMapping\(([\s\S]*?)\)[\s\S]*?public (?:final )?class (\w+)/

function stringConstants(source) {
  return Object.fromEntries([...source.matchAll(/(?:static\s+final\s+)?String\s+(\w+)\s*=\s*"([^"]*)"\s*;/g)]
    .map(match => [match[1], match[2]]))
}

/** Splits annotation arguments without treating commas inside strings as separators. */
function splitAnnotationArguments(value) {
  const parts = []
  let start = 0
  let depth = 0
  let quote = false
  let escaped = false
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]
    if (escaped) {
      escaped = false
      continue
    }
    if (quote && character === '\\') {
      escaped = true
      continue
    }
    if (character === '"') {
      quote = !quote
      continue
    }
    if (quote) continue
    if (character === '(' || character === '{' || character === '[') depth += 1
    if (character === ')' || character === '}' || character === ']') depth -= 1
    if (character === ',' && depth === 0) {
      parts.push(value.slice(start, index).trim())
      start = index + 1
    }
  }
  const tail = value.slice(start).trim()
  if (tail) parts.push(tail)
  return parts
}

function resolvePathExpression(expression, constants) {
  const normalized = expression.trim().replace(/^\(\s*(?:value|path)\s*=\s*/, '(')
  const parts = normalized.replace(/^\(/, '').replace(/\)$/, '').split('+').map(part => part.trim())
  if (!parts.length || parts.some(part => !part)) return ''
  let value = ''
  for (const part of parts) {
    if (part.startsWith('"') && part.endsWith('"')) value += part.slice(1, -1)
    else if (constants[part] !== undefined) value += constants[part]
    else return ''
  }
  return value
}

function resolveAnnotationPath(argument, constants) {
  const body = argument.trim().replace(/^\(/, '').replace(/\)$/, '')
  const parts = splitAnnotationArguments(body)
  const pathPart = parts.find(part => /^(?:value|path)\s*=/.test(part)) ?? parts[0] ?? ''
  const expression = pathPart.replace(/^(?:value|path)\s*=\s*/, '').trim()
  return resolvePathExpression(expression, constants)
}

for (let index = 0; index < files.length; index += 1) {
  const body = source[index]
  const constants = stringConstants(body)
  const classMatch = body.match(classPattern)
  const classExpression = classMatch?.[1] ?? ''
  const base = classMatch ? resolveAnnotationPath(`(${classExpression})`, constants) : ''
  const controller = classMatch?.[2] ?? files[index].split('/').pop().replace('.java', '')
  for (const match of body.matchAll(mappingPattern)) {
    // Read the annotation argument separately so constant-composed paths such
    // as OPERATIONS_ROUTE + "/health" are still represented and counted.
    const annotationStart = match.index + match[0].length
    const argument = body[annotationStart] === '('
      ? body.slice(annotationStart, body.indexOf(')', annotationStart) + 1)
      : ''
    const literalPath = resolveAnnotationPath(argument, constants)
    const method = body.slice(annotationStart + argument.length, annotationStart + argument.length + 1800)
      .match(/\bpublic\s+(?:static\s+)?[\w<>, ?\[\].]+\s+(\w+)\s*\(/)?.[1]
      ?? `unresolved_${match.index}`
    const operationId = `${controller}_${method}`
    // An empty mapping path means "the controller base path". Keep that
    // semantics in the path map; the canonical operation list below retains
    // overloaded mappings that share the same path and HTTP method.
    const path = `${base}/${literalPath}`
      .replaceAll(/\/+/g, '/')
      .replace(/\/$/, '') || '/'
    operations.push({
      method: match[1].toLowerCase(),
      path,
      operationId,
      controller,
    })
  }
}
operations.sort((left, right) => `${left.path}:${left.method}:${left.operationId}`.localeCompare(`${right.path}:${right.method}:${right.operationId}`))
const paths = {}
for (const operation of operations) {
  paths[operation.path] ??= {}
  paths[operation.path][operation.method] = { operationId: operation.operationId, tags: [operation.controller] }
}

const generated = `/* eslint-disable */
// GENERATED FILE. Runtime schemas are served by Springdoc at /v3/api-docs.
// openApiOperations retains every controller operation; paths follows OpenAPI's
// one-operation-per-path+method shape and therefore merges media-type overloads.
// Regenerate with: node scripts/generate-openapi-types.mjs
// Controller source SHA-256: ${sourceHash}

export interface OpenApiOperation {
  readonly operationId: string
  readonly tags: readonly string[]
}

export interface OpenApiDocument {
  readonly openapi: '3.1.0'
  readonly info: { readonly title: string; readonly version: string }
  readonly paths: Readonly<Record<string, Readonly<Record<string, OpenApiOperation>>>>
}

export const openApiDocument = ${JSON.stringify({
  openapi: '3.1.0',
  info: { title: 'CrewScope API', version: '0.1.0' },
  paths,
}, null, 2)} as const satisfies OpenApiDocument

export const openApiOperations = ${JSON.stringify(operations, null, 2)} as const
export const openApiOperationCount = ${operations.length} as const
`

if (checkOnly) {
  const current = await readFile(output, 'utf8').catch(() => '')
  if (current !== generated) {
    console.error(`OpenAPI output is stale: ${output}`)
    process.exitCode = 1
  } else {
    console.log(`OpenAPI output is up to date (${operations.length} operations).`)
  }
} else {
  await writeFile(output, generated)
  console.log(`Generated ${output} (${operations.length} operations).`)
}
