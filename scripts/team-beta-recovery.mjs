#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import {
  chmodSync,
  chownSync,
  closeSync,
  lstatSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  renameSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { arch, cpus, freemem, platform, release, totalmem } from 'node:os'
import { basename, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const COMPONENTS = Object.freeze({
  postgres: Object.freeze({ file: 'postgres.dump', format: 'postgres-custom' }),
  artifacts: Object.freeze({ file: 'artifacts.tar.gz', format: 'tar-gzip' }),
  redis: Object.freeze({ file: 'redis.rdb', format: 'redis-rdb' }),
})
const MINIMUM_SCHEMA_VERSION = 26
const CURRENT_MAXIMUM_SCHEMA_VERSION = 36
const ACCEPTED_MANIFEST_MAXIMUM_SCHEMA_VERSIONS = new Set([34, CURRENT_MAXIMUM_SCHEMA_VERSION])
const MODULE_DIRECTORY = resolve(fileURLToPath(new URL('.', import.meta.url)))

const command = process.argv[2]

try {
  switch (command) {
    case 'create-manifest':
      createManifest(requiredArg(3), requiredArg(4))
      break
    case 'create-envelope':
      createEnvelope(requiredArg(3), requiredArg(4), requiredArg(5))
      break
    case 'verify-ciphertext':
      verifyCiphertext(requiredArg(3), requiredArg(4))
      break
    case 'verify-payload':
      verifyPayload(requiredArg(3), requiredArg(4), requiredInteger(5), requiredInteger(6))
      break
    case 'verify-artifacts':
      verifyArtifacts(requiredArg(3))
      break
    case 'relocate-artifacts':
      relocateArtifacts(requiredArg(3))
      break
    case 'fingerprint':
      createFingerprint(requiredArg(3), requiredArg(4))
      break
    default:
      fail('Usage: team-beta-recovery.mjs create-manifest|create-envelope|verify-ciphertext|verify-payload|verify-artifacts|relocate-artifacts|fingerprint ...')
  }
} catch (error) {
  fail(error instanceof Error ? error.message : String(error))
}

function createManifest(payloadInput, metadataInput) {
  const payload = safeDirectory(payloadInput)
  const metadata = parseJsonFile(metadataInput)
  requireExactKeys(metadata, [
    'applicationVersion',
    'backupClass',
    'backupId',
    'createdAt',
    'credentialKeyIds',
    'environmentFingerprint',
    'gitRevision',
    'maintenance',
    'schemaVersion',
  ], 'metadata')
  requireText(metadata.backupId, 'backupId')
  if (!['daily', 'weekly', 'release', 'on-demand'].includes(metadata.backupClass)) {
    throw new Error('backupClass must be daily, weekly, release or on-demand')
  }
  requireText(metadata.applicationVersion, 'applicationVersion')
  requireText(metadata.gitRevision, 'gitRevision')
  requireIsoInstant(metadata.createdAt, 'createdAt')
  requireInteger(metadata.schemaVersion, 'schemaVersion')
  requireCredentialKeyIds(metadata.credentialKeyIds, 'credentialKeyIds')
  requireSha256(metadata.environmentFingerprint, 'environmentFingerprint')
  for (const field of ['ingressStopped', 'apiStopped', 'workerStopped']) {
    if (metadata.maintenance?.[field] !== true) {
      throw new Error(`maintenance.${field} must be true`)
    }
  }
  for (const field of ['activeTaskExecutions', 'activeActionDispatches', 'activeNotificationDispatches']) {
    if (metadata.maintenance?.[field] !== 0) {
      throw new Error(`maintenance.${field} must be zero`)
    }
  }

  const components = {}
  for (const [name, definition] of Object.entries(COMPONENTS)) {
    const path = safeChild(payload, definition.file)
    const stats = statSync(path)
    if (!stats.isFile()) {
      throw new Error(`${definition.file} must be a regular file`)
    }
    components[name] = {
      bytes: stats.size,
      file: definition.file,
      format: definition.format,
      sha256: sha256File(path),
    }
  }

  const manifest = {
    formatVersion: 1,
    backupId: metadata.backupId,
    backupClass: metadata.backupClass,
    createdAt: metadata.createdAt,
    applicationVersion: metadata.applicationVersion,
    gitRevision: metadata.gitRevision,
    schemaVersion: metadata.schemaVersion,
    encrypted: true,
    cipher: 'aes-256-cbc-pbkdf2-sha256',
    pbkdf2Iterations: 200000,
    credentialKeyIds: [...metadata.credentialKeyIds].sort(),
    environmentFingerprint: metadata.environmentFingerprint,
    maintenance: metadata.maintenance,
    compatibility: {
      minimumSchemaVersion: MINIMUM_SCHEMA_VERSION,
      maximumSchemaVersion: CURRENT_MAXIMUM_SCHEMA_VERSION,
    },
    components,
  }
  const output = safeChild(payload, 'manifest.json')
  writeFileSync(output, `${stableJson(manifest)}\n`, { mode: 0o600 })
  process.stdout.write(`${output}\n`)
}

function createEnvelope(ciphertextInput, manifestInput, outputInput) {
  const ciphertext = resolve(ciphertextInput)
  const manifestPath = resolve(manifestInput)
  const manifest = parseJsonFile(manifestPath)
  const ciphertextStats = statSync(ciphertext)
  if (!ciphertextStats.isFile()) throw new Error('Ciphertext must be a regular file')
  const envelope = {
    formatVersion: 1,
    backupId: requireText(manifest.backupId, 'manifest.backupId'),
    createdAt: requireIsoInstant(manifest.createdAt, 'manifest.createdAt'),
    cipher: requireText(manifest.cipher, 'manifest.cipher'),
    ciphertextFile: basename(ciphertext),
    ciphertextBytes: ciphertextStats.size,
    ciphertextSha256: sha256File(ciphertext),
    manifestSha256: sha256File(manifestPath),
  }
  const output = resolve(outputInput)
  writeFileSync(output, `${stableJson(envelope)}\n`, { mode: 0o600 })
  process.stdout.write(`${output}\n`)
}

function verifyCiphertext(ciphertextInput, envelopeInput) {
  const ciphertext = resolve(ciphertextInput)
  const envelope = parseJsonFile(envelopeInput)
  requireExactKeys(envelope, [
    'backupId',
    'cipher',
    'ciphertextBytes',
    'ciphertextFile',
    'ciphertextSha256',
    'createdAt',
    'formatVersion',
    'manifestSha256',
  ], 'envelope')
  if (envelope.formatVersion !== 1) {
    throw new Error('Unsupported backup envelope formatVersion')
  }
  requireText(envelope.backupId, 'envelope.backupId')
  requireIsoInstant(envelope.createdAt, 'envelope.createdAt')
  if (envelope.cipher !== 'aes-256-cbc-pbkdf2-sha256') {
    throw new Error('Envelope does not declare the required cipher')
  }
  requireInteger(envelope.ciphertextBytes, 'envelope.ciphertextBytes')
  requireSha256(envelope.ciphertextSha256, 'envelope.ciphertextSha256')
  requireSha256(envelope.manifestSha256, 'envelope.manifestSha256')
  if (basename(ciphertext) !== envelope.ciphertextFile) {
    throw new Error('Ciphertext filename does not match the envelope')
  }
  if (statSync(ciphertext).size !== envelope.ciphertextBytes) {
    throw new Error('Ciphertext length does not match the envelope')
  }
  if (sha256File(ciphertext) !== envelope.ciphertextSha256) {
    throw new Error('Ciphertext SHA-256 does not match the envelope')
  }
  process.stdout.write(`${envelope.backupId}\n`)
}

function verifyPayload(payloadInput, envelopeInput, minimumSchema, maximumSchema) {
  const payload = safeDirectory(payloadInput)
  const envelope = parseJsonFile(envelopeInput)
  if (minimumSchema > maximumSchema) throw new Error('Schema compatibility boundary is inverted')
  const manifestPath = safeChild(payload, 'manifest.json')
  const manifest = parseJsonFile(manifestPath)
  if (sha256File(manifestPath) !== envelope.manifestSha256) {
    throw new Error('Manifest SHA-256 does not match the encrypted envelope')
  }
  if (manifest.formatVersion !== 1 || manifest.backupId !== envelope.backupId) {
    throw new Error('Manifest identity does not match the encrypted envelope')
  }
  if (manifest.encrypted !== true || manifest.cipher !== 'aes-256-cbc-pbkdf2-sha256') {
    throw new Error('Backup manifest does not declare the required encryption contract')
  }
  if (manifest.pbkdf2Iterations !== 200000) {
    throw new Error('Backup manifest has an unsupported PBKDF2 iteration count')
  }
  if (manifest.compatibility?.minimumSchemaVersion !== MINIMUM_SCHEMA_VERSION
      || !ACCEPTED_MANIFEST_MAXIMUM_SCHEMA_VERSIONS.has(
        manifest.compatibility?.maximumSchemaVersion,
      )) {
    throw new Error('Backup manifest has an unsupported compatibility declaration')
  }
  requireIsoInstant(manifest.createdAt, 'manifest.createdAt')
  const ageMillis = Date.now() - Date.parse(manifest.createdAt)
  if (ageMillis < 0 || ageMillis > 24 * 60 * 60 * 1000) {
    throw new Error('Backup age is outside the Team Beta 24-hour RPO window')
  }
  requireInteger(manifest.schemaVersion, 'manifest.schemaVersion')
  if (manifest.schemaVersion > manifest.compatibility.maximumSchemaVersion) {
    throw new Error('Backup schema exceeds its declared compatibility boundary')
  }
  if (manifest.schemaVersion < minimumSchema || manifest.schemaVersion > maximumSchema) {
    throw new Error(`Backup schema V${manifest.schemaVersion} is outside V${minimumSchema}..V${maximumSchema}`)
  }
  requireCredentialKeyIds(manifest.credentialKeyIds, 'manifest.credentialKeyIds')
  for (const [name, definition] of Object.entries(COMPONENTS)) {
    const component = manifest.components?.[name]
    if (component?.file !== definition.file || component?.format !== definition.format) {
      throw new Error(`Component ${name} has an invalid file or format`)
    }
    const path = safeChild(payload, definition.file)
    if (!statSync(path).isFile() || statSync(path).size !== component.bytes) {
      throw new Error(`Component ${name} length does not match the manifest`)
    }
    if (sha256File(path) !== component.sha256) {
      throw new Error(`Component ${name} SHA-256 does not match the manifest`)
    }
  }
  process.stdout.write(`${stableJson(manifest)}\n`)
}

function verifyArtifacts(rootInput) {
  const result = inspectArtifacts(rootInput, true)
  process.stdout.write(`${stableJson(result.summary)}\n`)
}

function relocateArtifacts(rootInput) {
  const result = inspectArtifacts(rootInput, false)
  for (const reference of result.references) {
    const descriptor = reference.descriptor
    descriptor.storageUri = pathToFileURL(reference.blob).href
    const stats = statSync(reference.path)
    const staged = `${reference.path}.relocate-${process.pid}`
    writeFileSync(staged, `${stableJson(descriptor)}\n`, { mode: stats.mode & 0o777 })
    if (typeof process.getuid === 'function'
        && (stats.uid !== process.getuid() || stats.gid !== process.getgid())) {
      chownSync(staged, stats.uid, stats.gid)
    }
    chmodSync(staged, stats.mode & 0o777)
    renameSync(staged, reference.path)
  }
  process.stdout.write(`${stableJson({ ...result.summary, relocated: result.references.length })}\n`)
}

function inspectArtifacts(rootInput, requireCurrentLocation) {
  const root = safeDirectory(rootInput)
  const references = join(root, 'references')
  const objects = join(root, 'objects', 'sha256')
  if (!existsDirectory(references) && !existsDirectory(objects)) {
    return {
      references: [],
      summary: { references: 0, objects: 0, referencedObjects: 0 },
    }
  }
  if (!existsDirectory(references) || !existsDirectory(objects)) {
    throw new Error('Artifact references and objects must both exist')
  }
  rejectSymbolicLink(references)
  rejectSymbolicLink(join(root, 'objects'))
  rejectSymbolicLink(objects)

  let referenceCount = 0
  const referencedHashes = new Set()
  const verifiedReferences = []
  for (const path of walkFiles(references)) {
    rejectSymbolicLink(path)
    if (!path.endsWith('.json')) {
      throw new Error(`Unexpected Artifact reference file: ${relative(root, path)}`)
    }
    if (statSync(path).size > 64 * 1024) {
      throw new Error(`Artifact reference exceeds 64 KiB: ${relative(root, path)}`)
    }
    const descriptor = parseJsonFile(path)
    requireArtifactId(descriptor.artifactId, 'Artifact artifactId')
    requireSha256(descriptor.sha256, 'Artifact sha256')
    requireInteger(descriptor.size, 'Artifact size')
    if (descriptor.schemaVersion !== 1) throw new Error('Artifact schemaVersion must be 1')
    requireText(descriptor.storageUri, 'Artifact storageUri')
    const expectedReference = join(references, descriptor.artifactId.slice(0, 2), `${descriptor.artifactId}.json`)
    if (resolve(path) !== resolve(expectedReference)) {
      throw new Error(`Artifact reference path does not match its ID: ${relative(root, path)}`)
    }
    const blob = join(objects, descriptor.sha256.slice(0, 2), `${descriptor.sha256}.blob`)
    rejectSymbolicLink(blob)
    if (!statSync(blob).isFile() || statSync(blob).size !== descriptor.size) {
      throw new Error(`Artifact blob length mismatch: ${descriptor.sha256}`)
    }
    if (sha256File(blob) !== descriptor.sha256) {
      throw new Error(`Artifact blob SHA-256 mismatch: ${descriptor.sha256}`)
    }
    if (requireCurrentLocation && descriptor.storageUri !== pathToFileURL(blob).href) {
      throw new Error(`Artifact storage URI mismatch: ${descriptor.artifactId}`)
    }
    referencedHashes.add(descriptor.sha256)
    verifiedReferences.push({ blob, descriptor, path })
    referenceCount += 1
  }
  const objectFiles = walkFiles(objects)
  const objectCount = objectFiles.length
  for (const path of objectFiles) {
    rejectSymbolicLink(path)
    const file = basename(path)
    const match = /^([0-9a-f]{64})\.blob$/.exec(file)
    if (!match) throw new Error(`Unexpected Artifact object file: ${relative(root, path)}`)
    const hash = match[1]
    const expectedObject = join(objects, hash.slice(0, 2), file)
    if (resolve(path) !== resolve(expectedObject)) {
      throw new Error(`Artifact object path does not match its hash: ${relative(root, path)}`)
    }
    if (sha256File(path) !== hash) throw new Error(`Artifact object SHA-256 mismatch: ${hash}`)
  }
  return {
    references: verifiedReferences,
    summary: {
      references: referenceCount,
      objects: objectCount,
      referencedObjects: referencedHashes.size,
    },
  }
}

function createFingerprint(metadataInput, outputInput) {
  const metadata = parseJsonFile(metadataInput)
  const dataRoot = resolve(requireText(metadata.dataRoot, 'dataRoot'))
  const coordinates = {
    formatVersion: 1,
    os: `${platform()} ${release()}`,
    architecture: arch(),
    cpuCount: cpus().length,
    memoryMiB: Math.floor(totalmem() / 1024 / 1024),
    freeMemoryMiB: Math.floor(freemem() / 1024 / 1024),
    disk: diskCoordinates(dataRoot),
    // Build tools are useful evidence when the host has them, but production backup must never
    // bootstrap Maven from the network or fail because Java/pnpm are absent from a container-only
    // deployment host.
    java: firstLine(optionalCommandOutput('java', ['-version'], true)),
    maven: firstLine(optionalCommandOutput('mvn', ['--version'], true)),
    node: process.version,
    pnpm: optionalCommandOutput('pnpm', ['--version'], true).trim(),
    docker: commandOutput('docker', ['version', '--format', '{{.Server.Version}}'], true).trim(),
    dockerCompose: commandOutput('docker', ['compose', 'version', '--short'], true).trim(),
    gitRevision: requireText(metadata.gitRevision, 'gitRevision'),
    backendImage: requireText(metadata.backendImage, 'backendImage'),
    webImage: requireText(metadata.webImage, 'webImage'),
    schemaVersion: metadata.schemaVersion,
    datasetVersion: requireText(metadata.datasetVersion, 'datasetVersion'),
    seed: requireText(metadata.seed, 'seed'),
  }
  // The fingerprint excludes paths, usernames, environment variables and Secret material.
  const fingerprint = sha256Text(stableJson(coordinates))
  const document = { ...coordinates, fingerprint }
  writeFileSync(resolve(outputInput), `${stableJson(document)}\n`, { mode: 0o600 })
  process.stdout.write(`${fingerprint}\n`)
}

function walkFiles(root) {
  const files = []
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name)
    if (entry.isSymbolicLink()) {
      throw new Error(`Symbolic links are forbidden in Artifact storage: ${relative(root, path)}`)
    }
    if (entry.isDirectory()) {
      files.push(...walkFiles(path))
    } else if (entry.isFile()) {
      files.push(path)
    } else {
      throw new Error(`Unsupported Artifact filesystem entry: ${relative(root, path)}`)
    }
  }
  return files
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function parseJsonFile(path) {
  return JSON.parse(readFileSync(resolve(path), 'utf8'))
}

function sha256File(path) {
  const digest = createHash('sha256')
  const descriptor = openSync(path, 'r')
  const buffer = Buffer.allocUnsafe(1024 * 1024)
  try {
    let length
    while ((length = readSync(descriptor, buffer, 0, buffer.length, null)) > 0) {
      digest.update(buffer.subarray(0, length))
    }
    return digest.digest('hex')
  } finally {
    closeSync(descriptor)
  }
}

function sha256Text(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

function safeDirectory(path) {
  const resolved = resolve(path)
  if (lstatSync(resolved).isSymbolicLink()) throw new Error(`${path} must not be a symbolic link`)
  if (!statSync(resolved).isDirectory()) throw new Error(`${path} is not a directory`)
  return resolved
}

function safeChild(parent, name) {
  const path = resolve(parent, name)
  if (!path.startsWith(`${resolve(parent)}${sep}`)) throw new Error('Path escapes the payload root')
  return path
}

function existsDirectory(path) {
  try { return statSync(path).isDirectory() } catch { return false }
}

function rejectSymbolicLink(path) {
  if (lstatSync(path).isSymbolicLink()) throw new Error(`Symbolic link is forbidden: ${path}`)
}

function requireText(value, field) {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${field} must be non-blank text`)
  return value
}

function requireInteger(value, field) {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${field} must be a non-negative integer`)
  return value
}

function requireSha256(value, field) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) throw new Error(`${field} must be lowercase SHA-256`)
  return value
}

function requireArtifactId(value, field) {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value)) {
    throw new Error(`${field} must be a lowercase UUID`)
  }
  return value
}

function requireIsoInstant(value, field) {
  requireText(value, field)
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) || Number.isNaN(Date.parse(value))) {
    throw new Error(`${field} must be a UTC ISO-8601 instant`)
  }
  return value
}

function requireStringArray(value, field) {
  if (!Array.isArray(value) || value.length === 0 || value.some(item => typeof item !== 'string' || item.trim() === '')) {
    throw new Error(`${field} must be a non-empty string array`)
  }
}

function requireCredentialKeyIds(value, field) {
  requireStringArray(value, field)
  if (new Set(value).size !== value.length
      || value.some(item => !/^(?:credential|activity-cursor|task-token):[A-Za-z0-9._-]+$/.test(item))) {
    throw new Error(`${field} must contain unique supported Credential Key IDs`)
  }
}

function requireExactKeys(value, expected, field) {
  const actual = Object.keys(value).sort()
  if (JSON.stringify(actual) !== JSON.stringify([...expected].sort())) throw new Error(`${field} has unexpected fields`)
}

function requiredArg(index) {
  return requireText(process.argv[index], `argument ${index - 2}`)
}

function requiredInteger(index) {
  const value = Number(requiredArg(index))
  return requireInteger(value, `argument ${index - 2}`)
}

function commandOutput(file, args, includeStderr = false) {
  const result = spawnSync(file, args, {
    cwd: resolve(MODULE_DIRECTORY, '..'),
    encoding: 'utf8',
    timeout: 10_000,
  })
  if (result.status !== 0) throw new Error(`${file} failed while collecting the environment fingerprint`)
  return `${result.stdout ?? ''}${includeStderr ? result.stderr ?? '' : ''}`
}

function optionalCommandOutput(file, args, includeStderr = false) {
  try {
    return commandOutput(file, args, includeStderr)
  } catch {
    return 'unavailable'
  }
}

function diskCoordinates(dataRoot) {
  const line = commandOutput('df', ['-Pk', dataRoot]).split('\n').filter(Boolean).at(-1)?.trim()
  const fields = line?.split(/\s+/) ?? []
  if (fields.length < 5 || fields.slice(1, 4).some(value => !/^\d+$/.test(value))) {
    throw new Error('df returned an unsupported format while collecting the environment fingerprint')
  }
  return {
    availableKiB: Number(fields[3]),
    capacity: fields[4],
    totalKiB: Number(fields[1]),
    usedKiB: Number(fields[2]),
  }
}

function firstLine(value) {
  return value.split('\n').find(line => line.trim() !== '')?.trim() ?? ''
}

function fail(message) {
  process.stderr.write(`Team Beta recovery contract failed: ${message}\n`)
  process.exit(2)
}
