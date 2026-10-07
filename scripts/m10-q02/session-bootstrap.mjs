#!/usr/bin/env node
/**
 * Logs in the coordinates-file member and prints environment lines for the A01 mjs
 * retrieval gate (s5) to consume: COOKIE_HEADER / CSRF_HEADER / CSRF_TOKEN, plus the
 * ORG / TEAM / BASE_URL coordinates. The gate reads these lines into exported variables
 * without echoing them; they are throwaway stack session credentials but still follow
 * the credential discipline (never printed to gate logs or documents).
 *
 * Usage:
 *   node scripts/m10-q02/session-bootstrap.mjs
 * Environment:
 *   CREWSCOPE_M10Q02_COORDINATES  default var/release/m10-q02/loop-coordinates.json
 *   CREWSCOPE_M10Q02_BASE_URL     default from the coordinates file
 */
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ApiClient } from './lib/api.mjs'

const REPO_ROOT = resolve(import.meta.dirname, '..', '..')
const COORDINATES_FILE = resolve(REPO_ROOT, process.env.CREWSCOPE_M10Q02_COORDINATES || 'var/release/m10-q02/loop-coordinates.json')
if (!existsSync(COORDINATES_FILE)) {
  console.error(`missing coordinates file ${COORDINATES_FILE} — run the loop spec (s2) first`)
  process.exit(2)
}
const coordinates = JSON.parse(readFileSync(COORDINATES_FILE, 'utf8'))
const baseUrl = process.env.CREWSCOPE_M10Q02_BASE_URL || coordinates.baseUrl

const client = new ApiClient(baseUrl, coordinates.memberA)
await client.ensureSession()

// Single-quote the values so `export "$(line)"` style consumption keeps them verbatim.
const line = (name, value) => `${name}='${String(value).replace(/'/g, "'\\''")}'`
console.log(line('BASE_URL', baseUrl))
console.log(line('ORG', coordinates.orgId))
console.log(line('TEAM', coordinates.teamId))
console.log(line('COOKIE_HEADER', client.cookieHeader()))
console.log(line('CSRF_HEADER', client.csrf.headerName))
console.log(line('CSRF_TOKEN', client.csrf.token))
