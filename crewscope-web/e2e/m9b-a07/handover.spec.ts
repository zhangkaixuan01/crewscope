import { expect, test, type Page } from '@playwright/test'
import { createWorld, ids, installMembersApi } from './fixtures'

const workItemAlpha = '00000000-0000-0000-0000-000000000a01'
const workItemBeta = '00000000-0000-0000-0000-000000000a02'
const assignmentAlpha = '00000000-0000-0000-0000-000000000b01'
const assignmentBeta = '00000000-0000-0000-0000-000000000b02'

function worldWithTwoOwnerResponsibilities() {
  const world = createWorld()
  world.responsibilities[ids.memberLin] = [
    { assignmentId: assignmentAlpha, workItemId: workItemAlpha, role: 'OWNER', version: 3 },
    { assignmentId: assignmentBeta, workItemId: workItemBeta, role: 'OWNER', version: 1 },
  ]
  return world
}

async function openHandoverDialog(page: Page) {
  await page.goto(`/team/members?team=${ids.teamPlatform}`)
  const row = page.getByRole('row').filter({ hasText: '林晨' })
  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await page.getByRole('menuitem', { name: /交接责任/ }).click()
  return page.getByRole('dialog', { name: '交接 林晨 的责任' })
}

test('creates a handover from the preview and reports DONE and CONFLICT items', async ({ page }) => {
  const world = worldWithTwoOwnerResponsibilities()
  world.conflictWorkItemIds.add(workItemBeta)
  await installMembersApi(page, 'owner', world)

  const dialog = await openHandoverDialog(page)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('status')).toContainText('该角色当前有 2 项在途责任')
  await dialog.locator('#handover-target').selectOption({ label: '张凯旋' })
  await dialog.getByRole('button', { name: '创建交接' }).click()

  await expect(dialog).toContainText('0/2 项完成')
  expect(world.commands.at(-1)).toMatchObject({
    action: 'createHandover', memberId: ids.memberLin,
    targetPrincipalId: ids.principalOwner, roleKey: 'OWNER', viewer: 'owner',
  })

  await dialog.getByRole('button', { name: '处理交接' }).click()
  await expect(dialog).toContainText('1/2 项完成')
  await expect(dialog).toContainText('已完成')
  await expect(dialog).toContainText('版本冲突')
  await expect(dialog).toContainText('分派版本已变化，需人工处理')
  await expect(dialog).toContainText('有 1 项版本冲突、0 项被拒绝')

  await dialog.getByRole('button', { name: '关闭' }).click()
  await expect(dialog).toHaveCount(0)
})

test('cancels the remaining items without touching the queued assignments', async ({ page }) => {
  const world = worldWithTwoOwnerResponsibilities()
  await installMembersApi(page, 'owner', world)

  const dialog = await openHandoverDialog(page)
  await dialog.locator('#handover-target').selectOption({ label: '张凯旋' })
  await dialog.getByRole('button', { name: '创建交接' }).click()
  await expect(dialog).toContainText('0/2 项完成')

  await dialog.getByRole('button', { name: '取消剩余项' }).click()
  await expect(dialog).toContainText('已取消')
  await expect(dialog.getByRole('button', { name: '处理交接' })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '关闭' })).toBeVisible()

  const job = Object.values(world.jobs)[0]!
  expect(job.status).toBe('CANCELLED')
  expect(job.items.map(item => item.state)).toEqual(['PENDING', 'PENDING'])
  expect(world.commands.at(-1)).toMatchObject({ action: 'cancelHandover', memberId: ids.memberLin, viewer: 'owner' })
})
