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
  join(webRoot, 'domains', 'teamops'),
  join(webRoot, 'domains', 'teamobserver'),
  join(webRoot, 'domains', 'identity'),
  join(webRoot, 'domains', 'account'),
  join(webRoot, 'domains', 'onboarding'),
  join(webRoot, 'domains', 'invitation'),
  join(webRoot, 'components', 'auth'),
  join(webRoot, 'components', 'account'),
  join(webRoot, 'components', 'team'),
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
  'pages/ActivityPage.vue',
  'pages/InboxPage.vue',
  'pages/AuditPage.vue',
  'pages/LarkSettingsPage.vue',
  'pages/OperationsPage.vue',
  'pages/TeamObserverPage.vue',
  'components/domain/ActivityStream.vue',
  'components/domain/InboxWorkspace.vue',
  'components/domain/AuditExplorer.vue',
  'components/domain/LarkNotificationAdmin.vue',
  'components/domain/OperationsWorkspace.vue',
  'components/domain/TeamObserverWorkspace.vue',
  'pages/LoginPage.vue',
  'pages/RegisterPage.vue',
  'pages/OnboardingPage.vue',
  'pages/AccountPage.vue',
  'pages/InvitePage.vue',
  'pages/TeamMembersPage.vue',
  'components/layout/UserAccountMenu.vue',
].map(path => join(webRoot, path))

const productionFiles = [...protectedRoots.flatMap(walk), ...protectedFiles]
  .filter(path => /\.(?:ts|vue)$/.test(path) && !path.endsWith('.spec.ts'))
const storyFiles = walk(webRoot).filter(path => path.endsWith('.story.vue'))
const webProductionSources = walk(webRoot).filter(path =>
  /\.(?:ts|vue)$/.test(path)
  && !path.endsWith('.spec.ts')
  && !path.endsWith('.story.vue')
  && !path.includes(`${join(webRoot, 'test')}/`)
  && !path.includes(`${join(webRoot, 'stories')}/`)
  && !path.includes(`${join(webRoot, 'spikes')}/`))
const forbiddenPublicKeys = [
  'accessToken', 'credentialId', 'webhookSecret', 'remoteUrl', 'workerId', 'fencingToken',
  'leaseToken', 'rawProviderResponse', 'rawModelOutput', 'businessKey', 'endpoint',
]
const m6ForbiddenPublicKeys = [
  ...forbiddenPublicKeys,
  'apiKey', 'appSecret', 'tenantKey', 'openId', 'unionId', 'providerMessageId',
  'rawBody', 'rawPayload', 'providerBody', 'systemPrompt', 'toolArguments', 'toolResult',
  'reasoning', 'stateSnapshot', 'leaseId', 'claimToken', 'databaseDsn', 'sql',
]
const failures = []

// Browser business routes use CrewScope JSON login and Session state. Keep the legacy test fixture
// identity and machine-only Basic challenge from leaking back into production Web sources.
for (const path of webProductionSources) {
  const source = readFileSync(path, 'utf8')
  const legacyIdentityPattern = /\bbootstrapPrincipal\b|\bBasic Auth\b|WWW-Authenticate\s*:\s*Basic/gi
  for (const match of source.matchAll(legacyIdentityPattern)) {
    report(path, source, match.index, '生产 Web 不得使用占位 Principal 或业务 Basic Auth 文案')
  }
}

const readmePath = join(repositoryRoot, 'README.md')
const readmeSource = readFileSync(readmePath, 'utf8')
const unsafeDevelopmentLogin = /crewscope\s*\/\s*change-me/i.exec(readmeSource)
if (unsafeDevelopmentLogin) {
  report(readmePath, readmeSource, unsafeDevelopmentLogin.index, 'README 不得发布固定占位业务登录凭证')
}

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
  const storyPattern = /\b(?:apiKey|appSecret|tenantKey|openId|unionId|accessToken|credentialId|webhookSecret|remoteUrl|workerId|fencingToken|leaseId|leaseToken|claimToken|providerMessageId|rawBody|rawPayload|rawProviderResponse|rawModelOutput|businessKey|systemPrompt|toolArguments|toolResult|reasoning|stateSnapshot|databaseDsn)\s*[?:]/gi
  for (const match of source.matchAll(storyPattern)) report(path, source, match.index, 'Story 不得保存或构造敏感字段')
  const valuePattern = /(?:sk-[a-z0-9_-]{8,}|https:\/\/[^\s/@]+:[^\s/@]+@)/gi
  for (const match of source.matchAll(valuePattern)) report(path, source, match.index, 'Story 包含疑似真实凭证值')
}

// M7 keeps password and CSRF values as one-way, in-memory inputs. Public identity components and
// adapters must not persist or log any of those values, even when future auth features reuse them.
const m7ProductionFiles = [
  ...walk(join(webRoot, 'domains', 'identity')),
  ...walk(join(webRoot, 'domains', 'account')),
  ...walk(join(webRoot, 'domains', 'onboarding')),
  ...walk(join(webRoot, 'domains', 'invitation')),
  ...walk(join(webRoot, 'components', 'auth')),
  ...walk(join(webRoot, 'components', 'account')),
  ...walk(join(webRoot, 'components', 'team')),
  join(webRoot, 'pages', 'LoginPage.vue'),
  join(webRoot, 'pages', 'RegisterPage.vue'),
  join(webRoot, 'pages', 'OnboardingPage.vue'),
  join(webRoot, 'pages', 'AccountPage.vue'),
  join(webRoot, 'pages', 'InvitePage.vue'),
  join(webRoot, 'pages', 'TeamMembersPage.vue'),
  join(webRoot, 'components', 'layout', 'UserAccountMenu.vue'),
].filter(path => /\.(?:ts|vue)$/.test(path) && !path.endsWith('.spec.ts'))
for (const path of m7ProductionFiles) {
  const source = readFileSync(path, 'utf8')
  const persistencePattern = /\b(?:localStorage|sessionStorage|indexedDB)\b/g
  for (const match of source.matchAll(persistencePattern)) {
    report(path, source, match.index, 'M7 身份数据不得进入浏览器持久化')
  }
  const loggingPattern = /\bconsole\.(?:log|info|warn|error|debug)\s*\(/g
  for (const match of source.matchAll(loggingPattern)) {
    report(path, source, match.index, 'M7 身份组件不得记录认证输入或响应')
  }
}

const identityTypesPath = join(webRoot, 'domains', 'identity', 'types.ts')
const identityTypesSource = readFileSync(identityTypesPath, 'utf8')
const sessionTypesEnd = identityTypesSource.indexOf('export interface LoginCredentials')
const sessionTypes = identityTypesSource.slice(0, sessionTypesEnd)
for (const key of ['password', 'sessionId', 'cookie', 'credentialId', 'passwordHash', 'invitationToken', 'email']) {
  const match = new RegExp(`\\b${key}\\s*[?:]`, 'i').exec(sessionTypes)
  if (match) report(identityTypesPath, identityTypesSource, match.index, `M7 Session 公开 DTO 包含敏感字段 ${key}`)
}

// API Key is an allowed one-way command input, but it must never become reactive Model Store state.
const modelStorePath = join(webRoot, 'domains', 'model', 'store.ts')
const modelStoreSource = readFileSync(modelStorePath, 'utf8')
const stateStart = modelStoreSource.indexOf('export interface ModelStoreState')
const stateEnd = modelStoreSource.indexOf('export interface ModelStore', stateStart + 1)
const stateBlock = modelStoreSource.slice(stateStart, stateEnd)
if (/\bapiKey\b/i.test(stateBlock)) report(modelStorePath, modelStoreSource, stateStart, 'API Key 不得进入 ModelStoreState')

// M6 permits Lark secrets and external identity only as one-way Gateway inputs. Public DTOs and
// reactive Store state must remain reconstructive allowlists with no credential or raw payload.
for (const publicDtoPath of [
  join(webRoot, 'domains', 'teamops', 'types.ts'),
  join(webRoot, 'domains', 'teamobserver', 'types.ts'),
]) {
  const source = readFileSync(publicDtoPath, 'utf8')
  for (const key of m6ForbiddenPublicKeys) {
    const keyPattern = new RegExp(`\\b${key}\\s*[?:]`, 'gi')
    for (const match of source.matchAll(keyPattern)) report(publicDtoPath, source, match.index, `M6 公开 DTO 包含敏感字段 ${key}`)
  }
}

for (const stateDefinition of [
  [join(webRoot, 'domains', 'teamops', 'store.ts'), 'export interface TeamOpsStoreState', 'export interface TeamOpsStore'],
  [join(webRoot, 'domains', 'teamops', 'activityRealtimeStore.ts'), 'export interface ActivityRealtimeState', 'export interface ActivityRealtimeStore'],
  [join(webRoot, 'domains', 'teamobserver', 'store.ts'), 'export interface TeamObserverState', 'export interface TeamObserverStore'],
]) {
  const [path, startMarker, endMarker] = stateDefinition
  const source = readFileSync(path, 'utf8')
  const start = source.indexOf(startMarker)
  const end = source.indexOf(endMarker, start + startMarker.length)
  if (start < 0 || end < 0) {
    report(path, source, 0, `找不到受保护的 Store State 边界 ${startMarker}`)
    continue
  }
  const block = source.slice(start, end)
  for (const key of m6ForbiddenPublicKeys) {
    if (new RegExp(`\\b${key}\\b`, 'i').test(block)) report(path, source, start, `M6 Store State 不得包含敏感字段 ${key}`)
  }
}

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
