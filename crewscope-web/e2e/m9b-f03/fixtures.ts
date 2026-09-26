import type { Page, Route } from '@playwright/test'
import { ids, mockA05App, taskRow, workUrl, type A05World } from '../m9b-a05/fixtures'

/**
 * M9b-F03 browser fixtures：复用 A05 的「保留域源 + 全拦截 API」世界（会话、WorkItem、Task
 * 表面、命令端点），在其上叠加一个固定三执行的非 coding Task——当前执行 RUNNING、一条
 * COMPLETED 历史执行、一个只存在于旧深链的幽灵执行——供统一工作区的执行选择规范
 * （taskExecution 写侧 / attempt 只读别名 / 坐标冲突禁写 / 历史视图不 fallback）验收。
 */
export const f03Ids = {
  task: '00000000-0000-0000-0000-000000000f31',
  currentExecution: '00000000-0000-0000-0000-00000000f311',
  previousExecution: '00000000-0000-0000-0000-00000000f312',
  /** Only ever seen in a stale deep link: no attempt row answers to this id. */
  ghostExecution: '00000000-0000-0000-0000-00000000f399',
}

export const taskObjective = '统一工作区选择验收 Task'
const createdAt = '2026-08-08T03:30:00Z'

export interface F03World extends A05World {
  /** Every POST under /tasks/ the page attempted — the conflict gate must keep this empty. */
  taskCommandPosts: string[]
}

export function f03WorkUrl(extra: Record<string, string> = {}): string {
  return workUrl({ task: f03Ids.task, ...extra })
}

export function executionRow(id: string, attempt: number, status: 'RUNNING' | 'COMPLETED') {
  return {
    id, attempt, maxAttempts: 3, parentExecutionId: null,
    priority: 50, notBefore: createdAt, status,
    waiting: null, controlRequest: null, terminal: null,
    executorPrincipalId: ids.principal,
    currentPlanVersionId: null, version: 0,
    audit: { createdByPrincipalId: ids.principal, createdAt, updatedByPrincipalId: ids.principal, updatedAt: createdAt },
  }
}

const attempts = () => [executionRow(f03Ids.currentExecution, 2, 'RUNNING'), executionRow(f03Ids.previousExecution, 1, 'COMPLETED')]

export async function mockF03App(page: Page): Promise<F03World> {
  const world = await mockA05App(page) as F03World
  world.taskCommandPosts = []
  world.tasks.push(taskRow(f03Ids.task, taskObjective, 'ACTIVE', 'RUNNING'))
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes(`/tasks/${f03Ids.task}`)) {
      world.taskCommandPosts.push(request.url())
    }
  })

  // Routes registered after mockA05App win (Playwright matches newest first); these three own the
  // execution inventory for the F03 Task, everything else keeps the A05 answers.
  await page.route(`**/tasks/${f03Ids.task}`, route => fulfillTaskDetail(route))
  await page.route(`**/tasks/${f03Ids.task}/attempts`, route => json(route, attempts()))
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/runtime-facts$`), route => json(route, {
    execution: executionRow(f03Ids.currentExecution, 2, 'RUNNING'),
    planVersions: [], steps: [], sessions: [], agentRuns: [], interrupts: [], snapshots: [], leases: [],
  }))
  // The F03 Task is not a coding Task: every coding endpoint answers with explicit summaries
  // (coding=false) instead of a 404, so the Execution Studio restores into its honest empty state
  // rather than an error while execution coordinates are being selected.
  const nonCoding = (id: string, attempt: number, status: 'RUNNING' | 'COMPLETED') => ({
    executionId: id, attempt, executionStatus: status, current: id === f03Ids.currentExecution,
    coding: false, details: null,
  })
  await page.route(`**/tasks/${f03Ids.task}/coding`, route => json(route, {
    taskId: f03Ids.task, currentAttempt: nonCoding(f03Ids.currentExecution, 2, 'RUNNING'),
  }))
  await page.route(`**/tasks/${f03Ids.task}/coding-attempts`, route => json(route, [
    nonCoding(f03Ids.currentExecution, 2, 'RUNNING'),
    nonCoding(f03Ids.previousExecution, 1, 'COMPLETED'),
  ]))
  await page.route(new RegExp(`/tasks/${f03Ids.task}/attempts/[^/]+/coding$`), route => {
    const executionId = new URL(route.request().url()).pathname.split('/').at(-2)!
    return json(route, executionId === f03Ids.previousExecution
      ? nonCoding(f03Ids.previousExecution, 1, 'COMPLETED')
      : nonCoding(f03Ids.currentExecution, 2, 'RUNNING'))
  })
  return world
}

function fulfillTaskDetail(route: Route) {
  return json(route, {
    id: f03Ids.task, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project,
    workItemId: ids.workItem, objective: taskObjective, acceptanceCriteria: ['执行选择参数统一为 taskExecution'],
    source: { type: 'WORK_ITEM', workItemVersion: 3, conversationId: null, inputType: null, inputId: null, inputVersion: null },
    responsibilitySnapshot: [
      { assignmentId: '00000000-0000-0000-0000-000000000901', assignmentVersion: 0, role: 'OWNER', principalId: ids.principal, principalType: 'USER', memberId: '00000000-0000-0000-0000-000000000301', assignedAt: createdAt, acceptedAt: createdAt },
    ],
    responsibilityCapturedAt: createdAt, status: 'ACTIVE',
    currentExecutionId: f03Ids.currentExecution, cancellation: null, version: 0,
    audit: { createdByPrincipalId: ids.principal, createdAt, updatedByPrincipalId: ids.principal, updatedAt: createdAt },
    attempts: attempts(),
  })
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, json: body })
}
