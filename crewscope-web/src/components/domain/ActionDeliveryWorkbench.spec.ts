import { flushPromises, mount } from '@vue/test-utils'
import { actionBundle, etaggedActionBundle, githubBinding, githubConnection, githubHealth, githubPreflight, githubRepository } from '../../test/deliveryFixtures'
import { etaggedReview } from '../../test/reviewFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { taskIds } from '../../test/taskFixtures'
import type { DeliveryGateway } from '../../domains/delivery/gateway'
import { createDeliveryStore, DELIVERY_STORE } from '../../domains/delivery/store'
import type { ActionBundle, DeliveryCoordinates, DeliveryScope, EtaggedActionBundle, GitHubConnectionOwnerType, PlanActionBundleInput } from '../../domains/delivery/types'
import type { CommandReceipt } from '../../domains/scope/types'
import ActionDeliveryWorkbench from './ActionDeliveryWorkbench.vue'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const coordinates = { taskId: taskIds.first, executionId: taskIds.execution }

describe('ActionDeliveryWorkbench', () => {
  it('fails closed before a current member APPROVED decision', async () => {
    const { wrapper } = await mountWorkbench(new ComponentDeliveryGateway(), etaggedReview({ status: 'COMPLETED', decisions: [] }))

    expect(wrapper.text()).toContain('需要当前成员 Gate Approval')
    expect(wrapper.find('button').text()).not.toContain('生成 ActionBundle')
  })

  it('shows exact risk, parameters and a partial Push-success/PR-failure result without retrying Push', async () => {
    const gateway = new ComponentDeliveryGateway()
    const value = actionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId })
    value.actions[0] = { ...value.actions[0]!, dispatch: dispatch('SUCCEEDED'), receipt: actionReceipt('SUCCEEDED') }
    value.actions[1] = { ...value.actions[1]!, dispatch: dispatch('FAILED'), receipt: actionReceipt('FAILED') }
    gateway.bundle = { value, etag: '"0"' }
    const { wrapper } = await mountWorkbench(gateway, approvedReview())

    expect(wrapper.text()).toContain('HIGH_RISK_WRITE')
    expect(wrapper.text()).toContain('refs/heads/crewscope/tasks/example/attempt-1')
    expect(wrapper.text()).toContain('Create Draft PR')
    expect(wrapper.text()).toContain('FAILED')
    expect(gateway.confirm).not.toHaveBeenCalled()
  })

  it('requires an explicit Digest acknowledgement before exact confirmation', async () => {
    const gateway = new ComponentDeliveryGateway()
    gateway.confirm = vi.fn(async () => receipt())
    const { wrapper } = await mountWorkbench(gateway, approvedReview())

    const open = wrapper.findAll('button').find(item => item.text().includes('审查并确认'))!
    await open.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    const confirm = dialog.findAll('button').find(item => item.text().includes('精确确认'))!
    expect(confirm.attributes('disabled')).toBeDefined()
    await dialog.get('input[type="checkbox"]').setValue(true)
    expect(confirm.attributes('disabled')).toBeUndefined()
    await confirm.trigger('click')
    await flushPromises()

    expect(gateway.confirm).toHaveBeenCalledTimes(1)
    const exact = vi.mocked(gateway.confirm).mock.calls[0]![2]
    expect(exact.value.digest).toBe('a'.repeat(64))
    expect(exact.value.version).toBe(0)
  })

  it('traps confirmation focus and restores the exact opener after Escape', async () => {
    const { wrapper } = await mountWorkbench(new ComponentDeliveryGateway(), approvedReview())
    const opener = wrapper.findAll('button').find(item => item.text().includes('审查并确认'))!
    await opener.trigger('click')

    const dialog = wrapper.get('[role="dialog"]')
    const close = dialog.get<HTMLButtonElement>('[aria-label="关闭确认对话框"]')
    const final = dialog.findAll<HTMLButtonElement>('button').find(item => item.text() === '取消')!
    close.element.focus()
    await close.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(final.element)
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('keeps loaded facts readable while offline and disables every external write entry', async () => {
    const gateway = new ComponentDeliveryGateway()
    const store = createDeliveryStore(gateway)
    await store.synchronize(scope, coordinates)
    const wrapper = mount(ActionDeliveryWorkbench, {
      props: {
        taskId: coordinates.taskId, executionId: coordinates.executionId,
        objective: '完成精确 GitHub 交付', review: approvedReview(), online: false, canConfirm: true,
      },
      global: { provide: { [DELIVERY_STORE as symbol]: store } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('交付写操作已暂停')
    const writes = wrapper.findAll('button')
      .filter(item => ['Remote Preflight', '审查并确认', '同步 Catalog'].some(text => item.text().includes(text)))
    expect(writes.every(item => item.attributes('disabled') !== undefined)).toBe(true)
  })
})

async function mountWorkbench(gateway: ComponentDeliveryGateway, review: ReturnType<typeof etaggedReview>) {
  const store = createDeliveryStore(gateway)
  await store.synchronize(scope, coordinates)
  const wrapper = mount(ActionDeliveryWorkbench, {
    props: {
      taskId: coordinates.taskId, executionId: coordinates.executionId,
      objective: '完成精确 GitHub 交付', review, online: true, canConfirm: true,
    },
    global: { provide: { [DELIVERY_STORE as symbol]: store } },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, store }
}

function approvedReview() {
  return etaggedReview({
    status: 'COMPLETED', reviewerRelationship: 'INDEPENDENT', decisions: [{
      id: crypto.randomUUID(), revision: 1, type: 'APPROVED', rationale: '证据完整',
      reviewerMemberId: crypto.randomUUID(), eligibilityMode: 'ASSIGNED_REVIEWER', decidedAt: '2026-08-25T08:00:00Z',
    }],
  })
}

class ComponentDeliveryGateway implements DeliveryGateway {
  bundle = etaggedActionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId })
  async listConnections(_scope: DeliveryScope, ownerType: GitHubConnectionOwnerType) { return ownerType === 'TEAM' ? [githubConnection()] : [] }
  async listBindings() { return [githubBinding()] }
  async listRepositories() { return [githubRepository()] }
  async synchronizeRepositories() { return [githubRepository()] }
  async preflight() { return githubPreflight() }
  async health() { return githubHealth() }
  async listBundles(): Promise<ActionBundle[]> { return [this.bundle.value] }
  async getBundle(): Promise<EtaggedActionBundle> { return this.bundle }
  async plan(_scope: DeliveryScope, _coordinates: DeliveryCoordinates, _input: PlanActionBundleInput, _key: string) { return receipt() }
  confirm = vi.fn(async (_scope: DeliveryScope, _coordinates: DeliveryCoordinates, _bundle: EtaggedActionBundle, _key: string) => receipt())
  async cancel() { return receipt() }
  async resolveFailure() { return receipt() }
}

function dispatch(status: string) {
  return {
    id: crypto.randomUUID(), version: 2, status, claimAttempts: 1, reconciliationAttempts: 0,
    nextAttemptAt: '2026-08-25T08:00:00Z', cancellationReason: null, compensationDisposition: 'NONE',
  }
}

function actionReceipt(result: string) {
  return {
    id: crypto.randomUUID(), result, source: 'WORKER', externalObjectType: result === 'SUCCEEDED' ? 'BRANCH' : null,
    externalIdentityHash: result === 'SUCCEEDED' ? '1'.repeat(64) : null, targetVersion: null,
    evidenceCode: result === 'SUCCEEDED' ? 'REMOTE_HEAD_MATCHED' : 'PROVIDER_UNAVAILABLE', manualReason: null,
    receivedAt: '2026-08-25T08:00:00Z',
  }
}

function receipt(): CommandReceipt {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 1, correlationId: crypto.randomUUID() }
}
