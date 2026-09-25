import { expect, test } from '@playwright/test'
import { f05UserKeys, ids, mockF05App, origin, seedDrafts, sessionFor, workUrl } from './fixtures'

test.describe('M9b-F05 local content trust', () => {
  test('recents stay account-scoped, vanish on sign-out and never leak to another account', async ({ page }) => {
    const world = await mockF05App(page)
    await page.goto(workUrl())
    await expect(page.getByRole('button', { name: '新建工作项', exact: true })).toBeVisible()

    // Executing a palette action is the real write path for a recent entry.
    await page.keyboard.press('Control+k')
    const palette = page.getByRole('dialog', { name: '命令面板' })
    await expect(palette).toBeVisible()
    const firstAction = palette.locator('.command-palette__results li button').first()
    const actionLabel = await firstAction.getAttribute('aria-label')
    expect(actionLabel).toBeTruthy()
    await firstAction.click()
    await expect(palette).toBeHidden()

    // The recent entry landed under account A's namespace and the palette lists it again.
    expect((await f05UserKeys(page)).some(key => key.split(':')[6] === 'recent')).toBe(true)
    await page.goto(workUrl())
    await page.keyboard.press('Control+k')
    await expect(palette).toBeVisible()
    // `filter({ has })` resolves the inner locator relative to each candidate section, so it
    // must not carry the palette prefix — that would look for another dialog inside the section.
    const recentGroup = palette.locator('section').filter({ has: page.getByRole('heading', { name: '最近访问' }) })
    await expect(recentGroup.getByRole('button', { name: actionLabel! })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(palette).toBeHidden()

    // Signing out wipes the whole scoped namespace, not only the visible recents.
    await page.getByRole('button', { name: /^账号菜单/ }).click()
    await page.getByRole('menuitem', { name: '退出当前设备' }).click()
    await expect(page).toHaveURL(/\/login/)
    expect(await f05UserKeys(page)).toEqual([])

    // Another account on the same browser starts clean: no recents group at all.
    world.session = sessionFor('B')
    world.signedOut = false
    await page.goto(workUrl())
    await expect(page.getByRole('button', { name: '新建工作项', exact: true })).toBeVisible()
    await page.keyboard.press('Control+k')
    await expect(palette).toBeVisible()
    await expect(palette.getByRole('heading', { name: '最近访问' })).toBeHidden()
  })

  test('create drafts survive close and reopen; only a successful submit discards them', async ({ page }) => {
    const world = await mockF05App(page)
    await page.goto(workUrl())
    const openCreate = async () => {
      await page.getByRole('button', { name: '新建工作项', exact: true }).click()
      const title = page.getByLabel('标题', { exact: true })
      await expect(title).toBeVisible()
      return title
    }

    // Typed input survives closing and re-opening the dialog.
    let title = await openCreate()
    await title.fill('浏览器草稿：重构筛选逻辑')
    await page.getByRole('button', { name: '关闭新建工作项' }).click()
    title = await openCreate()
    await expect(title).toHaveValue('浏览器草稿：重构筛选逻辑')

    // A failed submit keeps the draft for retry.
    world.createFail = true
    await page.getByRole('button', { name: '创建工作项', exact: true }).click()
    // A definitive rejection reports the API envelope's message; no silent success, no recovery poll.
    await expect(page.getByText('fixture rejected this command')).toBeVisible()
    await page.getByRole('button', { name: '关闭新建工作项' }).click()
    title = await openCreate()
    await expect(title).toHaveValue('浏览器草稿：重构筛选逻辑')

    // A successful submit navigates to the created item and discards the draft for good.
    world.createFail = false
    await page.getByRole('button', { name: '创建工作项', exact: true }).click()
    await expect(page.locator('.detail-drawer')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`workItem=${ids.createdItem}`))
    await page.getByRole('button', { name: '关闭工作项详情' }).click()
    title = await openCreate()
    await expect(title).toHaveValue('')
    expect((await f05UserKeys(page)).every(key => !decodeURIComponent(key).includes('create:CRW'))).toBe(true)
  })

  test('comment drafts restore across drawer reloads and follow the item version', async ({ page }) => {
    const world = await mockF05App(page)
    const comment = () => page.locator('#work-item-comment')
    const openDrawer = async () => {
      await page.goto(`${workUrl()}&workItem=${ids.workItem}`)
      await expect(comment()).toBeVisible()
    }

    await openDrawer()
    await comment().fill('先记录结论，稍后补证据')
    // A reload must restore the draft: it lived in the scoped browser storage, not memory.
    await openDrawer()
    await expect(comment()).toHaveValue('先记录结论，稍后补证据')

    // A successful submit removes exactly this revision's draft.
    await page.getByRole('button', { name: '发送评论' }).click()
    await expect(page.getByText('先记录结论，稍后补证据').last()).toBeVisible()
    await openDrawer()
    await expect(comment()).toHaveValue('')

    // A failed submit keeps the draft for retry.
    await comment().fill('失败后要保留的草稿')
    world.commentFail = true
    await page.getByRole('button', { name: '发送评论' }).click()
    await expect(page.getByText('提交结果尚未确认')).toBeVisible()
    await openDrawer()
    await expect(comment()).toHaveValue('失败后要保留的草稿')

    // Someone else's comment bumps the item version. A comment outlives item edits by design,
    // so the unsubmitted draft still restores — only a successful submit of its exact revision
    // may remove it (checked above).
    world.workItemVersion += 1
    await openDrawer()
    await expect(comment()).toHaveValue('失败后要保留的草稿')
  })

  test('a 403 clears only the current Team scoped content', async ({ page }) => {
    const world = await mockF05App(page)
    const draftKeyOf = (projectKey: string) => (keys: string[]) =>
      keys.filter(key => key.split(':')[6] === 'draft' && decodeURIComponent(key).includes(`create:${projectKey}`))

    await page.goto(workUrl())
    await page.getByRole('button', { name: '新建工作项', exact: true }).click()
    await page.getByLabel('标题', { exact: true }).fill('Platform 的草稿')
    await page.getByRole('button', { name: '关闭新建工作项' }).click()
    await page.goto(workUrl(ids.teamB))
    await page.getByRole('button', { name: '新建工作项', exact: true }).click()
    await page.getByLabel('标题', { exact: true }).fill('Growth 的草稿')
    await page.getByRole('button', { name: '关闭新建工作项' }).click()

    const keys = await f05UserKeys(page)
    expect(draftKeyOf('CRW')(keys)).toHaveLength(1)
    expect(draftKeyOf('GRW')(keys)).toHaveLength(1)

    // Membership in team B is revoked while team B is the active scope.
    world.forbiddenTeams.add(ids.teamB)
    const forbidden = page.waitForResponse(response => response.status() === 403)
    await page.getByRole('button', { name: /^打开 / }).click()
    await forbidden

    const after = await f05UserKeys(page)
    expect(draftKeyOf('GRW')(after)).toHaveLength(0)
    expect(draftKeyOf('CRW')(after)).toHaveLength(1)

    // The untouched Team's draft still restores for its own members.
    world.forbiddenTeams.delete(ids.teamB)
    await page.goto(workUrl())
    await page.getByRole('button', { name: '新建工作项', exact: true }).click()
    await expect(page.getByLabel('标题', { exact: true })).toHaveValue('Platform 的草稿')
  })

  test('the conversation composer offers no fake attachments or slash commands', async ({ page }) => {
    await mockF05App(page)
    // The project segment must match the restored Scope, or URL canonicalization drops the
    // conversation identity (object identity never survives a Scope change).
    await page.goto(`${origin}/conversation?team=${ids.teamA}&project=${ids.projectA}&conversation=${ids.conversation}`)
    const composer = page.locator('.conversation-composer')
    await expect(composer).toBeVisible()
    // 发送 is the only control: no attachment stub, no slash button, no icon-only fake entries.
    expect((await composer.locator('button').allTextContents()).map(text => text.trim())).toEqual(['发送'])
  })

  test('a full draft budget never claims a save that did not happen', async ({ page }) => {
    await mockF05App(page)
    await page.goto(workUrl())
    await expect(page.getByRole('button', { name: '新建工作项', exact: true })).toBeVisible()
    // Twenty valid drafts already occupy the whole per-account budget.
    await seedDrafts(page, 20)

    await page.getByRole('button', { name: '新建工作项', exact: true }).click()
    const title = page.getByLabel('标题', { exact: true })
    await expect(title).toBeVisible()
    await title.fill('第二十一份草稿')
    await page.getByRole('button', { name: '关闭新建工作项' }).click()

    await page.getByRole('button', { name: '新建工作项', exact: true }).click()
    // The write was refused, so nothing comes back and nothing claims it was saved.
    await expect(page.getByLabel('标题', { exact: true })).toHaveValue('')
    await expect(page.getByText(/已保存|草稿已保存|已自动保存/)).toBeHidden()
    expect((await f05UserKeys(page)).filter(key => decodeURIComponent(key).includes('create:CRW'))).toHaveLength(0)
  })
})
