import { spawn, type ChildProcess } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { resolve, extname, sep } from 'node:path'
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import { expect, test } from '@playwright/test'

const origin = 'http://a01-browser.test'
const assets = resolve('test-results/a01-assets')
let server: ChildProcess | undefined
let port = 0
let scope: { organizationId: string; teamId: string; projectId: string; actor: string; otherActor: string; intentId: string; intentConversationId: string }
async function start() {
  if (!process.env.A01_TEST_JDBC) throw new Error('Run through A01BrowserIntegrationTest, never a deployment database')
  server = spawn(process.env.A01_TEST_JAVA!, ['-cp', process.env.A01_TEST_CLASSPATH!, 'io.crewscope.server.a01.A01TestServer'], { stdio: ['ignore', 'pipe', 'pipe'] })
  let output = ''
  await new Promise<void>((accept, reject) => {
    const timeout = setTimeout(() => reject(new Error(`A01 test server startup timeout: ${output.slice(-6000)}`)), 35_000)
    server!.once('exit', code => { clearTimeout(timeout); reject(new Error(`A01 server exited ${code}: ${output.slice(-6000)}`)) })
    const read = (data: Buffer) => {
      output += data.toString()
      const metadata = output.match(/A01_SCOPE=(\{[^\n]+\})/)
      if (metadata) scope = JSON.parse(metadata[1]!)
      const ready = output.match(/A01_READY=(\d+)/)
      if (ready) { port = Number(ready[1]); clearTimeout(timeout); accept() }
    }
    server!.stdout!.on('data', read)
    server!.stderr!.on('data', read)
  })
}
async function stop() {
  const active = server
  server = undefined
  if (!active || active.exitCode !== null) return
  await new Promise<void>(accept => { active.once('exit', () => accept()); active.kill('SIGKILL') })
}
const root = () => `/api/v1/organizations/${scope.organizationId}/teams/${scope.teamId}`
async function request(path: string, method = 'GET', body?: unknown, key?: string, actor?: string, version?: number) {
  return fetch(`http://127.0.0.1:${port}${path}`, { method,
    headers: { 'Content-Type': 'application/json', 'X-A01-Test-Actor': actor ?? scope.actor,
      ...(key ? { 'Idempotency-Key': key } : {}), ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) },
    body: body === undefined ? undefined : JSON.stringify(body) })
}
test.afterAll(stop)

test('real components recover after response loss, browser reload and JVM restart; concurrent numbering and editing are safe', async ({ page }) => {
  await build({ configFile: false, root: resolve('e2e/m9b-a01'), plugins: [vue()],
    build: { outDir: assets, emptyOutDir: true }, logLevel: 'warn' })
  await start()
  await page.addInitScript(value => { Object.assign(window, { a01Scope: value }) }, scope)
  let drop = true
  const posts: string[] = []
  let originalId = ''
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== origin) return route.abort()
    if (url.pathname.startsWith('/api/')) {
      if (drop && url.pathname.endsWith('/command-results')) return route.abort()
      const response = await fetch(`http://127.0.0.1:${port}${url.pathname}${url.search}`, {
        method: req.method(), headers: { ...req.headers(), 'X-A01-Test-Actor': scope.actor }, body: req.postData() ?? undefined,
      })
      if (req.method() === 'POST' && url.pathname.endsWith('/work-items')) {
        expect(response.status, await response.clone().text()).toBe(202)
        const key = req.headers()['idempotency-key']!
        const found = await request(`/api/v1/organizations/${scope.organizationId}/command-results`, 'GET', undefined, key)
        originalId = (await found.json()).result.resourceId
        posts.push(key)
        if (drop) return route.abort() // Real commit already happened; only transport response is lost.
      }
      return route.fulfill({ status: response.status, headers: { 'content-type': 'application/json' }, body: await response.text() })
    }
    const path = resolve(assets, `.${url.pathname === '/' ? '/index.html' : url.pathname}`)
    if (!path.startsWith(`${assets}${sep}`)) return route.abort()
    return route.fulfill({ body: await readFile(path), contentType: extname(path) === '.js' ? 'application/javascript' : extname(path) === '.css' ? 'text/css' : 'text/html' })
  })
  await page.goto(origin)
  await page.getByRole('button', { name: '新建工作项', exact: true }).click()
  await expect(page.getByLabel('标题', { exact: true })).toBeVisible()
  await expect(page.locator('input[name="key"]')).toHaveCount(0)
  await page.getByLabel('标题', { exact: true }).fill('Lost response work')
  await page.getByRole('button', { name: '创建工作项', exact: true }).click()
  await expect.poll(() => posts.length).toBe(1)
  const workPath = `${root()}/work-projects/${scope.projectId}/work-items`
  const later = await Promise.all(Array.from({ length: 25 }, (_, i) => request(workPath, 'POST', { title: `Later work ${i}` }, `later-${i}`)))
  for (const response of later) expect(response.status, await response.text()).toBe(202)
  const firstPage = await (await request(`${workPath}?limit=20`)).json()
  expect(JSON.stringify(firstPage.items)).not.toContain(originalId)
  const projectCreates = await Promise.all(['project-one', 'project-two'].map(key => request(`${root()}/work-projects`, 'POST', { name: '同名项目' }, key)))
  for (const response of projectCreates) expect(response.status, await response.text()).toBe(202)
  const conversationCreates = await Promise.all(['conversation-one', 'conversation-two'].map(key => request(`${root()}/conversations`, 'POST', { title: '同名对话', visibility: 'PRIVATE' }, key)))
  for (const response of conversationCreates) expect(response.status, await response.text()).toBe(202)
  const beforeRestart = await Promise.all(['project-one', 'project-two', 'conversation-one', 'conversation-two'].map(async key =>
    (await (await request(`/api/v1/organizations/${scope.organizationId}/command-results`, 'GET', undefined, key)).json()).result))
  expect(new Set(beforeRestart.map(result => result.resourceId)).size).toBe(4)
  const firstPid = server!.pid
  await stop()
  await start()
  expect(server!.pid).not.toBe(firstPid)
  drop = false
  await page.reload()
  await page.getByText('创建结果待确认（1）', { exact: true }).click()
  await page.getByRole('button', { name: '再次确认并打开' }).click()
  await expect(page.getByRole('heading', { name: 'Lost response work' })).toBeVisible()
  await expect(page.locator('code')).toHaveText(originalId)
  expect(posts).toHaveLength(1)
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(originalId)
  for (const [i, key] of ['project-one', 'project-two', 'conversation-one', 'conversation-two'].entries()) {
    const recovered = await (await request(`/api/v1/organizations/${scope.organizationId}/command-results`, 'GET', undefined, key)).json()
    expect(recovered.result).toEqual(beforeRestart[i])
  }

  // Same-name, two-actor concurrent creates cannot steal each other's number or result coordinate.
  const path = `${root()}/work-projects/${scope.projectId}/work-items`
  const intentPath = `${root()}/conversations/${scope.intentConversationId}/task-intents/${scope.intentId}/confirmations`
  const concurrent = await Promise.all([
    ...Array.from({ length: 8 }, (_, i) => request(path, 'POST', { title: 'Concurrent same title' }, `concurrent-${i}`, i % 2 ? scope.otherActor : scope.actor)),
    request(intentPath, 'POST', undefined, 'confirm-intent', scope.actor, 1),
  ])
  for (const response of concurrent) expect(response.status, await response.text()).toBe(202)
  const listing = await (await request(`${path}?limit=100`)).json()
  const items = listing.items.map((row: { workItem?: { id: string; key: string }; id: string; key: string }) => row.workItem ?? row)
  expect(new Set(items.map((item: { key: string }) => item.key)).size).toBe(35)
  expect((await request(intentPath, 'POST', undefined, 'confirm-intent', scope.actor, 1)).status).toBe(202)
  const replay = await request(path, 'POST', { title: 'Lost response work' }, posts[0])
  expect(replay.status).toBe(202)
  const foreign = await request(`/api/v1/organizations/${scope.organizationId}/command-results`, 'GET', undefined, posts[0], scope.otherActor)
  expect(foreign.status).toBe(404)
  const legacy = await request(path, 'POST', { title: 'Legacy explicit key', key: 'CRW-50' }, 'legacy-explicit')
  expect(legacy.status).toBe(202)
  expect((await request(path, 'POST', { title: 'After legacy' }, 'after-legacy')).status).toBe(202)
  const automatic = await (await request(`/api/v1/organizations/${scope.organizationId}/command-results`, 'GET', undefined, 'after-legacy')).json()
  const detailPath = `${path}/${automatic.result.resourceId}`
  const detail = await (await request(detailPath)).json()
  expect(detail.workItem.key).toBe('CRW-51')
  const edits = await Promise.all([
    request(detailPath, 'PATCH', { title: 'Writer one' }, 'edit-one', scope.actor, 0),
    request(detailPath, 'PATCH', { title: 'Writer two' }, 'edit-two', scope.otherActor, 0),
  ])
  expect(edits.map(response => response.status).sort()).toEqual([202, 409])
  const current = await (await request(detailPath)).json()
  expect(current.workItem.version).toBe(1)
  expect(current.workItem.status).toBe('BACKLOG')
  expect((await request(detailPath, 'PATCH', { key: 'CRW-99' }, 'immutable', undefined, 1)).status).toBe(400)
  expect((await request(`${detailPath}/transitions`, 'POST', { targetStatus: 'CANCELLED' }, 'cancel-highest', undefined, 1)).status).toBe(202)
  expect((await request(detailPath, 'PATCH', { description: null }, 'edit-cancelled', undefined, 2)).status).toBe(202)
  expect((await (await request(detailPath)).json()).workItem.status).toBe('CANCELLED')
  expect((await request(`${detailPath}/transitions`, 'POST', { targetStatus: 'ARCHIVED' }, 'archive-highest', undefined, 3)).status).toBe(202)
  expect((await request(detailPath, 'PATCH', { title: 'Not allowed' }, 'edit-archived', undefined, 4)).status).not.toBe(202)
  expect((await request(path, 'POST', { title: 'After archive' }, 'after-archive')).status).toBe(202)
  const afterArchive = await (await request(`/api/v1/organizations/${scope.organizationId}/command-results`, 'GET', undefined, 'after-archive')).json()
  expect((await (await request(`${path}/${afterArchive.result.resourceId}`)).json()).workItem.key).toBe('CRW-52')

  // Exercise the shipped editor against the real PATCH endpoint, not a component mock.
  await page.getByRole('button', { name: '编辑内容' }).click()
  await page.getByLabel('描述', { exact: true }).fill('Edited without starting an execution')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByRole('status')).toContainText('修改已保存')
  const edited = await (await request(`${path}/${originalId}`)).json()
  expect(edited.workItem.description).toBe('Edited without starting an execution')
  expect(edited.workItem.title).toBe('Lost response work')
})
