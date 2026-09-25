import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { createWorld, ids, installMembersApi } from './fixtures'

test('suspends a member under If-Match and the suspended browser loses the Team everywhere', async ({ browser }) => {
  const world = createWorld()
  const ownerPage = await (await browser.newContext()).newPage()
  const memberPage = await (await browser.newContext()).newPage()
  await installMembersApi(ownerPage, 'owner', world)
  await installMembersApi(memberPage, 'member', world)

  await memberPage.goto(`/team/members?team=${ids.teamPlatform}`)
  await expect(memberPage.getByRole('table', { name: '团队成员列表' })).toContainText('林晨')

  await ownerPage.goto(`/team/members?team=${ids.teamPlatform}`)
  const row = ownerPage.getByRole('row').filter({ hasText: '林晨' })
  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await ownerPage.getByRole('menuitem', { name: /停用成员/ }).click()
  const dialog = ownerPage.getByRole('dialog', { name: '停用 林晨？' })
  await expect(dialog.getByRole('button', { name: '取消' })).toBeFocused()
  await dialog.getByRole('button', { name: '确认停用' }).click()

  await expect(row).toContainText('已暂停')
  expect(world.commands.at(-1)).toMatchObject({
    action: 'suspend', teamId: ids.teamPlatform, memberId: ids.memberLin,
    ifMatch: '"0"', viewer: 'owner', csrf: 'csrf-a07-owner',
  })
  expect(world.commands.at(-1)?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)

  // The suspended member's session no longer carries the Team, so the deep link turns into an
  // explicit-scope failure instead of silently falling back to another Team.
  await memberPage.reload()
  await expect(memberPage.getByRole('alert').first()).toContainText('加载失败')
  await expect(memberPage.getByRole('table', { name: '团队成员列表' })).toHaveCount(0)

  // The other Team of the same account is untouched by the suspension.
  await memberPage.goto(`/team/members?team=${ids.teamGrowth}`)
  await expect(memberPage.getByRole('table', { name: '团队成员列表' })).toContainText('活跃')

  expect(await ownerPage.evaluate(() => Object.keys(localStorage)
    .filter(key => key !== 'cs.user.epoch.v1' && key !== 'crewscope.command-recovery.v1'))).toEqual([])
  expect((await new AxeBuilder({ page: ownerPage }).analyze()).violations).toEqual([])
  expect(await ownerPage.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
})

test('refuses to let the last Owner leave and surfaces the server protection verbatim', async ({ page }) => {
  const world = createWorld()
  await installMembersApi(page, 'owner', world)
  await page.goto(`/team/members?team=${ids.teamPlatform}`)

  const row = page.getByRole('row').filter({ hasText: '张凯旋' })
  await row.getByRole('button', { name: '更多操作：张凯旋' }).click()
  await page.getByRole('menuitem', { name: /离开这个 Team/ }).click()
  const dialog = page.getByRole('dialog', { name: '离开这个 Team？' })
  await dialog.getByRole('button', { name: '确认离开' }).click()

  await expect(dialog.getByRole('alert')).toContainText('最后一个 Owner 保护')
  await expect(dialog.getByRole('alert')).toContainText('先把 Owner 转让')
  await expect(row).toContainText('活跃')
  expect(world.commands.at(-1)).toMatchObject({ action: 'leave', memberId: ids.memberOwner, error: 'last_owner_protection' })
})

test('reactivation restores access without reviving revoked role grants', async ({ page }) => {
  const world = createWorld()
  await installMembersApi(page, 'owner', world)
  await page.goto(`/team/members?team=${ids.teamPlatform}`)
  const row = page.getByRole('row').filter({ hasText: '林晨' })
  const roles = row.locator('.member-roles')
  await expect(roles).toHaveText('成员')

  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await page.getByRole('menuitem', { name: /授予角色/ }).click()
  const grantDialog = page.getByRole('dialog', { name: '给 林晨 授予角色' })
  await grantDialog.locator('#lifecycle-role').selectOption('TEAM_LEAD')
  await grantDialog.getByRole('button', { name: '确认授予' }).click()
  await expect(roles).toHaveText('团队负责人')

  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await page.getByRole('menuitem', { name: /停用成员/ }).click()
  await page.getByRole('dialog', { name: '停用 林晨？' }).getByRole('button', { name: '确认停用' }).click()
  await expect(row).toContainText('已暂停')

  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await page.getByRole('menuitem', { name: /恢复成员/ }).click()
  await page.getByRole('dialog', { name: '恢复 林晨？' }).getByRole('button', { name: '确认恢复' }).click()
  await expect(row).toContainText('活跃')
  await expect(roles).toHaveText('成员')

  // Each command carries the version re-read after the previous one committed.
  expect(world.commands.map(command => [command.action, command.ifMatch])).toEqual([
    ['grantRole', '"0"'],
    ['suspend', '"1"'],
    ['activate', '"2"'],
  ])
  const lin = world.members[ids.teamPlatform]!.find(member => member.id === ids.memberLin)!
  expect(lin.status).toBe('ACTIVE')
  // Activation restores exactly the default MEMBER role — the suspended TEAM_LEAD grant is gone.
  expect(lin.roles).toEqual(['MEMBER'])
  expect(lin.grants.map(grant => grant.roleKey)).toEqual(['MEMBER'])
  expect(lin.authorizationVersion).toBe(4)
})

test('removes a member and routes the reinvitation path through the invitations tab', async ({ page }) => {
  const world = createWorld()
  await installMembersApi(page, 'owner', world)
  await page.goto(`/team/members?team=${ids.teamPlatform}`)
  const row = page.getByRole('row').filter({ hasText: '林晨' })

  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await page.getByRole('menuitem', { name: /移除成员/ }).click()
  await page.getByRole('dialog', { name: '移除 林晨？' }).getByRole('button', { name: '确认移除' }).click()
  await expect(row).toContainText('已移除')
  expect(world.commands.at(-1)).toMatchObject({ action: 'remove', memberId: ids.memberLin, ifMatch: '"0"' })

  await row.getByRole('button', { name: '更多操作：林晨' }).click()
  await page.getByRole('menuitem', { name: /发送加入邀请/ }).click()
  await expect(page).toHaveURL(/tab=invitations$/)
  const landing = new URL(page.url())
  expect(landing.searchParams.get('team')).toBe(ids.teamPlatform)
  expect(landing.searchParams.get('tab')).toBe('invitations')
  await expect(page.getByRole('heading', { name: '团队邀请', exact: true })).toBeVisible()
})
