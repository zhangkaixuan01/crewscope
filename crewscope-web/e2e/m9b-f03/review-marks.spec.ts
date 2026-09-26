import { expect, test, type Page, type Route } from '@playwright/test'
import { f03Ids, f03WorkUrl, mockF03App, taskObjective } from './fixtures'

/**
 * M9b-F03 review marks (R22): the viewed-file state is personal reading progress versioned by
 * content — the same path under a changed patch hash falls back to "not viewed", the marks stay
 * per execution, un-marking is always allowed, and the caption keeps the check from reading as
 * an approval. The line-comment affordance stays inert with its reason until a review can take it.
 */
const createdAt = '2026-08-08T03:30:00Z'
const HASH_A = 'a'.repeat(64)
const HASH_B = 'b'.repeat(64)
const PATCH_BODY = 'diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-old\n+new\n'
/** sha256 of PATCH_BODY — the coding store verifies the artifact against the manifest descriptor. */
const PATCH_SHA256 = 'd6b38e9edc6e65d5a68b2a34507776ea46eeec6ab8311b05e4cb8e5d0df09b56'

function codingWorld(executionId: string, hash: string, attempt: number, status: 'RUNNING' | 'COMPLETED') {
  return {
    executionId, attempt, executionStatus: status, current: executionId === f03Ids.currentExecution, coding: true,
    details: {
      executionId, attempt,
      workspace: {
        id: '00000000-0000-0000-0000-0000000000c1', repositoryKey: 'crewscope/crewscope-java', baselineCommit: 'b'.repeat(40),
        managedBranch: 'refs/heads/crew/task', status, recoveryGeneration: 1,
        completionReason: status === 'COMPLETED' ? 'SUCCEEDED' : null, failureCode: null,
        fingerprint: 'fp', version: 1, retainUntil: '2026-12-01T00:00:00Z', createdAt, updatedAt: createdAt,
      },
      sandbox: null,
      diffManifest: {
        artifactId: `artifact-${executionId}`, generation: 3, manifestHash: 'm'.repeat(64),
        fileCount: 2, additions: 20, deletions: 4, baselineCommit: 'b'.repeat(40), deliveryCommit: 'd'.repeat(40),
        finalHash: 'f'.repeat(64),
        patch: { artifactId: `patch-${executionId}`, kind: 'DIFF_PATCH', contentType: 'text/x-diff', sizeBytes: PATCH_BODY.length, contentHash: PATCH_SHA256 },
        files: [
          { ordinal: 0, path: 'docs/README.md', oldPath: null, changeKind: 'MODIFIED', additions: 4, deletions: 1, binary: false, patchTruncated: false, patchHash: hash },
          { ordinal: 1, path: 'src/Main.java', oldPath: null, changeKind: 'MODIFIED', additions: 16, deletions: 3, binary: false, patchTruncated: false, patchHash: hash },
        ],
        createdAt,
      },
      codingResult: null, commandEvidenceCount: 1, testEvidenceCount: 0,
    },
  }
}

/** Turns the F03 Task into a coding one whose manifest files all carry the given patch hash. */
async function mockCodingTask(page: Page): Promise<void> {
  await page.route(`**/tasks/${f03Ids.task}/coding`, route => fulfill(route, {
    taskId: f03Ids.task, currentAttempt: codingWorld(f03Ids.currentExecution, HASH_A, 2, 'RUNNING'),
  }))
  await page.route(`**/tasks/${f03Ids.task}/coding-attempts`, route => fulfill(route, [
    codingWorld(f03Ids.currentExecution, HASH_A, 2, 'RUNNING'),
    codingWorld(f03Ids.previousExecution, HASH_B, 1, 'COMPLETED'),
  ]))
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/coding$`), route => {
    const executionId = new URL(route.request().url()).pathname.split('/').at(-2)!
    const attempt = executionId === f03Ids.previousExecution ? 1 : 2
    const status = executionId === f03Ids.previousExecution ? 'COMPLETED' as const : 'RUNNING' as const
    return fulfill(route, codingWorld(executionId, executionId === f03Ids.previousExecution ? HASH_B : HASH_A, attempt, status))
  })
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/coding/artifacts/patch`), route => {
    const body = PATCH_BODY
    return route.fulfill({
      status: 206,
      contentType: 'text/x-diff',
      headers: { 'Content-Range': `bytes 0-${body.length - 1}/${body.length}`, Etag: '"patch-1"' },
      body,
    })
  })
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/reviews$`), route =>
    fulfill(route, { items: [] }))
}

function fulfill(route: Route, body: unknown) {
  return route.fulfill({ status: 200, json: body })
}

async function openDiff(page: Page) {
  const dialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByTestId('coding-diff-explorer')).toBeVisible()
  return dialog
}

test.beforeEach(async ({ page }) => {
  await mockF03App(page)
  await mockCodingTask(page)
})

test('marks personal reading progress that survives reloads and can be taken back', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution }))
  const dialog = await openDiff(page)

  await expect(dialog.getByText('个人阅读进度，不等于批准')).toBeVisible()
  const readmeRow = dialog.locator('.diff-tree__file', { hasText: 'README.md' })
  await expect(readmeRow).toBeVisible()
  expect(await readmeRow.locator('[aria-label="已查看"]').count()).toBe(0)

  await readmeRow.click()
  await dialog.getByRole('button', { name: '标记已查看' }).click()
  await expect(readmeRow.locator('[aria-label="已查看"]')).toBeVisible()

  await page.reload()
  const reloaded = await openDiff(page)
  await expect(reloaded.locator('.diff-tree__file', { hasText: 'README.md' }).locator('[aria-label="已查看"]')).toBeVisible()
  await reloaded.locator('.diff-tree__file', { hasText: 'README.md' }).click()
  await reloaded.getByRole('button', { name: '取消已查看' }).click()
  await expect(reloaded.locator('.diff-tree__file', { hasText: 'README.md' }).locator('[aria-label="已查看"]')).toHaveCount(0)
})

test('drops the mark when the same path arrives as a new content version', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution }))
  const dialog = await openDiff(page)
  const mainRow = dialog.locator('.diff-tree__file', { hasText: 'Main.java' })
  await mainRow.click()
  await dialog.getByRole('button', { name: '标记已查看' }).click()
  await expect(mainRow.locator('[aria-label="已查看"]')).toBeVisible()

  // The projection returns the same path under a different patch hash: the mark never speaks
  // for content it has not seen, so it silently falls back to "not viewed" (R22).
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/coding$`), route => {
    const executionId = new URL(route.request().url()).pathname.split('/').at(-2)!
    return fulfill(route, executionId === f03Ids.previousExecution
      ? codingWorld(executionId, HASH_B, 1, 'COMPLETED')
      : codingWorld(executionId, 'z'.repeat(64), 2, 'RUNNING'))
  })
  await page.reload()
  const rotated = await openDiff(page)
  const rotatedRow = rotated.locator('.diff-tree__file', { hasText: 'Main.java' })
  await expect(rotatedRow).toBeVisible()
  await expect(rotatedRow.locator('[aria-label="已查看"]')).toHaveCount(0)
  await rotatedRow.click()
  await expect(rotated.getByRole('button', { name: '标记已查看' })).toBeVisible()
})

test('keeps marks scoped to one execution instead of smearing them across the history', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution }))
  const dialog = await openDiff(page)
  const readmeRow = dialog.locator('.diff-tree__file', { hasText: 'README.md' })
  await readmeRow.click()
  await dialog.getByRole('button', { name: '标记已查看' }).click()
  await expect(readmeRow.locator('[aria-label="已查看"]')).toBeVisible()

  await page.goto(f03WorkUrl({ taskExecution: f03Ids.previousExecution }))
  const history = await openDiff(page)
  await expect(history.locator('.diff-tree__file', { hasText: 'README.md' })).toBeVisible()
  await expect(history.locator('.diff-tree__file', { hasText: 'README.md' }).locator('[aria-label="已查看"]')).toHaveCount(0)

  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution }))
  const back = await openDiff(page)
  await expect(back.locator('.diff-tree__file', { hasText: 'README.md' }).locator('[aria-label="已查看"]')).toBeVisible()
})

test('keeps the line-comment affordance inert with its reason until an open review can take it', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution }))
  const dialog = await openDiff(page)

  await dialog.locator('.diff-tree__file', { hasText: 'Main.java' }).click()
  await dialog.getByRole('button', { name: '读取单文件 Patch' }).click()
  const lineButton = dialog.locator('.patch-line--addition .line-comment-button').first()
  await expect(lineButton).toBeDisabled()
  // The gate's reason is an sr-only note the button points at — a title attribute cannot be read
  // back reliably and the Q01 gates forbid bare ones.
  await expect(lineButton).toHaveAttribute('aria-describedby', 'diff-comment-gate-note')
  await expect(page.locator('#diff-comment-gate-note')).toHaveText('当前没有可提交评论的 Review，请先发起 Review')

  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/reviews$`), route =>
    fulfill(route, { items: [{
      id: '00000000-0000-0000-0000-00000000f901', revision: 1, version: 2, status: 'OPEN',
      invalidationReason: null, contextHash: 'ctx', findingCount: 0, blockerCount: 0, highCount: 0,
      latestDecisionType: null, modificationRound: 0,
    }] }))
  await page.reload()
  const reopened = await openDiff(page)
  await reopened.locator('.diff-tree__file', { hasText: 'Main.java' }).click()
  await reopened.getByRole('button', { name: '读取单文件 Patch' }).click()
  const enabledButton = reopened.locator('.patch-line--addition .line-comment-button').first()
  await expect(enabledButton).toBeEnabled()
  await enabledButton.click()
  await expect(reopened.getByTestId('review-comment-input')).toBeVisible()
})
