import { expect, test, type Route } from '@playwright/test'
import { ids, workUrl } from '../m9b-a05/fixtures'
import { f03Ids, mockF03App, taskObjective, type F03World } from './fixtures'

/**
 * M9b-F03 outcome-first screen (contract §4.1 / R11): the WorkItem workspace leads with the
 * delivered output or the honest waiting fact, the four states never merge into one green
 * completion, and a coding-attempt reference opens exactly the Task it provably belongs to —
 * anything else stays raw with a copy affordance instead of a guessed navigation.
 */
const finalHash = 'f'.repeat(64)
let world: F03World

function outcomeSummary(overrides: Record<string, unknown> = {}) {
  return {
    workItemId: ids.workItem,
    workItemVersion: 1,
    workStatus: 'IN_PROGRESS',
    taskCount: 1,
    activeTaskCount: 1,
    pendingReviewCount: 0,
    currentTaskId: f03Ids.task,
    currentExecutionId: f03Ids.currentExecution,
    executionStatus: 'RUNNING',
    selectionRequired: false,
    blockedReasons: [],
    resultSummary: null,
    resultSourceReference: null,
    projectionVersion: 1,
    observedAt: '2026-08-08T04:00:00Z',
    ...overrides,
  }
}

/** Re-registers the WorkItem detail route with a summary inlined (newest route wins). */
async function mockWorkItemSummary(page: import('@playwright/test').Page, summary: Record<string, unknown> | null): Promise<void> {
  await page.route(`**/work-items/${ids.workItem}`, route => fulfillDetail(route, summary))
}

function fulfillDetail(route: Route, summary: Record<string, unknown> | null) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { Etag: '"1"', 'Cache-Control': 'no-store' },
    body: JSON.stringify({
      workItem: {
        id: ids.workItem, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
        projectId: ids.project, key: 'A05-1', type: 'FEATURE', title: '委托编排验收工作项',
        description: '验证 M9b-A05 的责任、候选与默认值一处置备。', status: 'IN_PROGRESS', priority: 'HIGH',
        labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 1,
        createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
        updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal,
        availableActions: [],
        ...(summary ? { summary } : {}),
      },
      comments: [],
      resourceLinks: [],
    }),
  })
}

/** The coding attempt the delivered reference points at: a manifest whose finalHash matches. */
async function mockDeliveredCodingAttempt(page: import('@playwright/test').Page): Promise<void> {
  const coding = {
    executionId: f03Ids.currentExecution, attempt: 2, executionStatus: 'COMPLETED', current: true, coding: true,
    details: {
      executionId: f03Ids.currentExecution, attempt: 2,
      workspace: {
        id: '00000000-0000-0000-0000-0000000000c1', repositoryKey: 'crewscope/crewscope-java', baselineCommit: 'b' .repeat(40),
        managedBranch: 'refs/heads/crew/task', status: 'COMPLETED', recoveryGeneration: 1, completionReason: 'SUCCEEDED',
        failureCode: null, fingerprint: 'fp', version: 1, retainUntil: '2026-12-01T00:00:00Z',
        createdAt: '2026-08-08T03:00:00Z', updatedAt: '2026-08-08T04:00:00Z',
      },
      sandbox: null,
      diffManifest: {
        artifactId: '00000000-0000-0000-0000-0000000000c2', generation: 3, manifestHash: 'm'.repeat(64),
        fileCount: 8, additions: 120, deletions: 30, baselineCommit: 'b'.repeat(40), deliveryCommit: 'd'.repeat(40),
        finalHash,
        patch: { artifactId: '00000000-0000-0000-0000-0000000000c2', kind: 'DIFF_PATCH', contentType: 'text/x-diff', sizeBytes: 4096, contentHash: 'c'.repeat(64) },
        files: [], createdAt: '2026-08-08T03:30:00Z',
      },
      codingResult: null, commandEvidenceCount: 2, testEvidenceCount: 1,
    },
  }
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/coding$`), route => route.fulfill({ status: 200, json: coding }))
}

test.beforeEach(async ({ page }) => {
  world = await mockF03App(page)
})

test('leads with the delivered output and jumps to the referenced execution evidence', async ({ page }) => {
  await mockWorkItemSummary(page, outcomeSummary({
    activeTaskCount: 0,
    resultSummary: '已交付 8 个文件变更（+120/−30），测试通过',
    resultSourceReference: `coding-attempt:${f03Ids.currentExecution}@${finalHash}`,
  }))
  await mockDeliveredCodingAttempt(page)
  await page.goto(workUrl())

  // The WorkItem outcome panel shows A06's line verbatim and offers the evidence jump.
  const workItemDialog = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  const panel = workItemDialog.locator('[data-testid="work-item-outcome"]')
  await expect(panel).toContainText('已交付')
  await expect(panel).toContainText('已交付 8 个文件变更（+120/−30），测试通过')
  await panel.getByRole('button', { name: /查看证据锚/ }).click()

  // The jump opens the Task workspace anchored on the referenced execution (unified parameter),
  // and the first-screen outcome block reports the same diff facts with no mismatch notice.
  const taskDialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(taskDialog).toBeVisible()
  await expect.poll(() => new URL(page.url()).searchParams.get('taskExecution')).toBe(f03Ids.currentExecution)
  await expect.poll(() => new URL(page.url()).searchParams.get('task')).toBe(f03Ids.task)
  const outcome = taskDialog.locator('[data-testid="task-outcome"]')
  await expect(outcome).toContainText('本次产出 8 个文件变更（+120/−30）')
  await expect(outcome).toContainText('ffffffff')
  await expect(outcome).not.toContainText('证据版本不一致')

  // The finalHash short code is a link to the changes section, not a decoration (R13).
  await outcome.getByRole('button', { name: /finalHash ffffffff/ }).click()
  await expect.poll(() => page.evaluate(() => document.activeElement?.id)).toBe('ws-changes')
})

test('surfaces awaiting-review and external-delivery attention as their own lines (R11/R13)', async ({ page }) => {
  await mockWorkItemSummary(page, outcomeSummary({
    activeTaskCount: 0,
    resultSummary: '已交付 8 个文件变更（+120/−30），测试通过',
    resultSourceReference: `coding-attempt:${f03Ids.currentExecution}@${finalHash}`,
  }))
  await mockDeliveredCodingAttempt(page)
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/reviews$`), route =>
    route.fulfill({ status: 200, json: { items: [{
      id: '00000000-0000-0000-0000-00000000f901', revision: 1, version: 2, status: 'OPEN',
      invalidationReason: null, contextHash: 'ctx', findingCount: 0, blockerCount: 0, highCount: 0,
      latestDecisionType: null, modificationRound: 2,
    }] } }))
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/actions/bundles$`), route =>
    route.fulfill({ status: 200, json: { items: [{
      id: '00000000-0000-0000-0000-00000000a201', version: 0, digest: 'a'.repeat(64), validity: 'CURRENT', staleReason: null,
      taskId: f03Ids.task, taskExecutionId: f03Ids.currentExecution, reviewDecisionId: '00000000-0000-0000-0000-00000000f902',
      repositoryBindingId: '00000000-0000-0000-0000-00000000b201', repositoryKey: 'crewscope/crewscope-java',
      baselineCommit: 'b'.repeat(40), deliveryCommit: 'd'.repeat(40), confirmation: null,
      actions: [
        {
          id: '00000000-0000-0000-0000-00000000a211', sequence: 1, kind: 'PUSH_BRANCH', risk: 'HIGH_RISK_WRITE',
          digest: 'd'.repeat(64), validUntil: '2026-08-08T05:00:00Z', dependencyActionIds: [], parameters: {},
          dispatch: { id: '00000000-0000-0000-0000-00000000a212', version: 2, status: 'UNKNOWN', claimAttempts: 1, reconciliationAttempts: 1, nextAttemptAt: '2026-08-08T04:03:00Z', cancellationReason: null, compensationDisposition: 'NONE' },
          receipt: null, externalResult: null,
        },
        {
          id: '00000000-0000-0000-0000-00000000a221', sequence: 2, kind: 'CREATE_DRAFT_PR', risk: 'LOW_RISK_WRITE',
          digest: 'e'.repeat(64), validUntil: '2026-08-08T05:00:00Z', dependencyActionIds: [], parameters: {},
          dispatch: { id: '00000000-0000-0000-0000-00000000a222', version: 3, status: 'MANUAL_REVIEW', claimAttempts: 1, reconciliationAttempts: 2, nextAttemptAt: null, cancellationReason: null, compensationDisposition: 'NONE' },
          receipt: null, externalResult: null,
        },
      ],
    }] } }))
  await page.goto(workUrl())

  await page.getByRole('dialog', { name: 'A05-1 工作项详情' }).locator('[data-testid="work-item-outcome"]')
    .getByRole('button', { name: /查看证据锚/ }).click()
  const taskDialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(taskDialog).toBeVisible()

  // Even a delivered diff does not read as "done": the awaiting lines stay separately visible
  // next to the delivery facts instead of being merged into one green completion.
  const outcome = taskDialog.locator('[data-testid="task-outcome"]')
  await expect(outcome).toContainText('本次产出 8 个文件变更（+120/−30）')
  await expect(outcome.locator('.outcome-awaiting')).toContainText('1 项评审待人审（已到第 2 轮要求修改）')
  await expect(outcome.locator('.outcome-awaiting')).toContainText('外部交付 1 项待对账 · 1 项待人工判定')
})

test('states a pending review decision separately instead of a green completion', async ({ page }) => {
  await mockWorkItemSummary(page, outcomeSummary({
    pendingReviewCount: 2,
    blockedReasons: [{ code: 'REVIEW', taskId: f03Ids.task, executionId: f03Ids.currentExecution, since: '2026-08-08T03:30:00Z', waitingOnPrincipalId: ids.principal }],
  }))
  await page.goto(workUrl())

  const panel = page.getByRole('dialog', { name: 'A05-1 工作项详情' }).locator('[data-testid="work-item-outcome"]')
  await expect(panel).toContainText('待成员决定')
  await expect(panel).toContainText('2 个评审等待成员决定')
  await expect(panel).toContainText('等待评审')
  await expect(panel).not.toContainText('已交付')
})

test('reports running work as a waiting fact, never a summary', async ({ page }) => {
  await mockWorkItemSummary(page, outcomeSummary({ activeTaskCount: 1, executionStatus: 'RUNNING' }))
  await page.goto(workUrl())

  const panel = page.getByRole('dialog', { name: 'A05-1 工作项详情' }).locator('[data-testid="work-item-outcome"]')
  await expect(panel).toContainText('执行进行中')
  await expect(panel).toContainText('1 个 Task 进行中 · 执行中')
  await expect(panel).not.toContainText('已交付')
})

test('keeps the no-task and finished-without-result states honest', async ({ page }) => {
  await mockWorkItemSummary(page, outcomeSummary({
    taskCount: 0, activeTaskCount: 0, currentTaskId: null, currentExecutionId: null, executionStatus: null,
  }))
  await page.goto(workUrl())
  const panel = page.getByRole('dialog', { name: 'A05-1 工作项详情' }).locator('[data-testid="work-item-outcome"]')
  await expect(panel).toContainText('这个工作项还没有启动 Task')

  await mockWorkItemSummary(page, outcomeSummary({ taskCount: 1, activeTaskCount: 0, executionStatus: 'COMPLETED' }))
  await page.reload()
  await expect(page.getByRole('dialog', { name: 'A05-1 工作项详情' }).locator('[data-testid="work-item-outcome"]')).toContainText('执行已结束，服务端没有可展示的成果摘要')
})

test('keeps a cross-task historical reference raw — copy, not a guessed jump', async ({ page }) => {
  const staleExecution = '00000000-0000-0000-0000-000000000099'
  await mockWorkItemSummary(page, outcomeSummary({
    taskCount: 2,
    activeTaskCount: 0,
    resultSummary: '已交付 1 个文件变更（+2/−0）',
    resultSourceReference: `coding-attempt:${staleExecution}@${finalHash}`,
  }))
  await page.goto(workUrl())

  const panel = page.getByRole('dialog', { name: 'A05-1 工作项详情' }).locator('[data-testid="work-item-outcome"]')
  await expect(panel).toContainText(`coding-attempt:${staleExecution}@${finalHash}`)
  await expect(panel).toContainText('复制引用')
  await expect(panel.getByRole('button', { name: /查看证据锚/ })).toHaveCount(0)
  expect(world.taskCommandPosts).toEqual([])
})
