import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const webRoot = join(repositoryRoot, 'crewscope-web', 'src')
const protectedRoots = [
  join(webRoot, 'domains', 'agent'),
  join(webRoot, 'domains', 'model'),
  join(webRoot, 'domains', 'review'),
  join(webRoot, 'domains', 'delivery'),
]
const protectedFiles = [
  'pages/AgentSettingsPage.vue',
  'pages/ModelSettingsPage.vue',
  'components/domain/AgentCreateDialog.vue',
  'components/domain/AgentConfigurationPanel.vue',
  'components/domain/ModelConnectionDetail.vue',
  'components/domain/ModelCredentialDialog.vue',
  'components/domain/ReviewWorkbench.vue',
  'components/domain/ActionDeliveryWorkbench.vue',
].map(path => join(webRoot, path))

const productionFiles = [...protectedRoots.flatMap(walk), ...protectedFiles]
  .filter(path => /\.(?:ts|vue)$/.test(path) && !path.endsWith('.spec.ts'))
const storyFiles = walk(webRoot).filter(path => path.endsWith('.story.vue'))
const forbiddenPublicKeys = [
  'accessToken', 'credentialId', 'webhookSecret', 'remoteUrl', 'workerId', 'fencingToken',
  'leaseToken', 'rawProviderResponse', 'rawModelOutput', 'businessKey', 'endpoint',
]
const failures = []

for (const path of new Set(productionFiles)) {
  const source = readFileSync(path, 'utf8')
  for (const key of forbiddenPublicKeys) {
    // Match DTO/interface/object keys, while allowing prose that explains an intentionally absent field.
    const keyPattern = new RegExp(`\\b${key}\\s*[?:]`, 'g')
    for (const match of source.matchAll(keyPattern)) report(path, source, match.index, `公开 Web 契约包含敏感字段 ${key}`)
  }
}

for (const path of storyFiles) {
  const source = readFileSync(path, 'utf8')
  const storyPattern = /\b(?:apiKey|accessToken|credentialId|webhookSecret|remoteUrl|workerId|fencingToken|leaseToken|rawProviderResponse|rawModelOutput|businessKey)\s*[?:]/gi
  for (const match of source.matchAll(storyPattern)) report(path, source, match.index, 'Story 不得保存或构造敏感字段')
  const valuePattern = /(?:sk-[a-z0-9_-]{8,}|https:\/\/[^\s/@]+:[^\s/@]+@)/gi
  for (const match of source.matchAll(valuePattern)) report(path, source, match.index, 'Story 包含疑似真实凭证值')
}

// API Key is an allowed one-way command input, but it must never become reactive Model Store state.
const modelStorePath = join(webRoot, 'domains', 'model', 'store.ts')
const modelStoreSource = readFileSync(modelStorePath, 'utf8')
const stateStart = modelStoreSource.indexOf('export interface ModelStoreState')
const stateEnd = modelStoreSource.indexOf('export interface ModelStore', stateStart + 1)
const stateBlock = modelStoreSource.slice(stateStart, stateEnd)
if (/\bapiKey\b/i.test(stateBlock)) report(modelStorePath, modelStoreSource, stateStart, 'API Key 不得进入 ModelStoreState')

if (failures.length > 0) {
  console.error('Web sensitive-field gate failed:')
  for (const failure of failures) console.error(`- ${failure}`)
  process.exit(1)
}

console.log(`Web sensitive-field gate passed (${new Set(productionFiles).size} production files, ${storyFiles.length} stories).`)

function walk(root) {
  if (!statSync(root).isDirectory()) return [root]
  return readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    const path = join(root, entry.name)
    return entry.isDirectory() ? walk(path) : [path]
  })
}

function report(path, source, index, message) {
  const line = source.slice(0, index ?? 0).split('\n').length
  failures.push(`${relative(repositoryRoot, path)}:${line}: ${message}`)
}
