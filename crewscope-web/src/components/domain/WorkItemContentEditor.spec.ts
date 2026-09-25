import { flushPromises, mount } from '@vue/test-utils'
import WorkItemContentEditor from './WorkItemContentEditor.vue'
import { HttpWorkItemContentGateway } from '../../domains/workitem/contentGateway'
import { HttpWorkItemGateway } from '../../domains/workitem/gateway'
import { fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { CrewScopeApiError } from '../../api/client'
import { useConfirm } from '../../composables/useConfirm'

const item = () => ({ ...structuredClone(fixtureWorkItemDetails.workItem), dueAt: '2026-10-01T10:20:35.123Z' })
const receipt = { commandId: 'c', domainEventId: 'e', committedVersion: 1, correlationId: 'r' }
afterEach(() => vi.restoreAllMocks())

it('sends only edited fields and never rounds an untouched due date or changes identity/state', async () => {
  const update = vi.spyOn(HttpWorkItemContentGateway.prototype, 'update').mockResolvedValue(receipt)
  const original = item()
  const wrapper = mount(WorkItemContentEditor, { props: { item: original, canParticipate: true } })
  await wrapper.get('button').trigger('click')
  expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  await wrapper.get('input').setValue(' Changed title ')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(update.mock.calls[0]?.[2]).toEqual({ title: 'Changed title' })
  expect(update.mock.calls[0]?.[3]).toBe(original.version)
  expect(wrapper.emitted('saved')).toHaveLength(1)
  wrapper.unmount()
})

it('retains local input on conflict, reloads explicitly and requires confirmation before a new version/key', async () => {
  const failure = new CrewScopeApiError(409, { code: 'optimistic_lock_conflict', message: 'conflict', correlationId: 'c', retryable: false, currentVersion: 8, details: {} })
  const update = vi.spyOn(HttpWorkItemContentGateway.prototype, 'update').mockRejectedValueOnce(failure).mockResolvedValue(receipt)
  const latest = { ...fixtureWorkItemDetails, workItem: { ...item(), version: 8, title: 'Other writer' } }
  const read = vi.spyOn(HttpWorkItemGateway.prototype, 'getWorkItem').mockRejectedValueOnce(new Error('offline')).mockResolvedValue(latest)
  const wrapper = mount(WorkItemContentEditor, { props: { item: item(), canParticipate: true } })
  await wrapper.get('button').trigger('click')
  await wrapper.get('input').setValue('My local title')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('input').element.value).toBe('My local title')
  expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  await wrapper.findAll('button').find(button => button.text() === '获取最新内容')!.trigger('click')
  await flushPromises()
  expect(read).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('Other writer')
  await wrapper.findAll('button').find(button => button.text() === '以当前版本重新确认')!.trigger('click')
  expect(update).toHaveBeenCalledOnce()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(update.mock.calls[1]?.[3]).toBe(8)
  expect(update.mock.calls[1]?.[4]).not.toBe(update.mock.calls[0]?.[4])
  wrapper.unmount()
})

it('requires explicit confirmation before discarding dirty input', async () => {
  const wrapper = mount(WorkItemContentEditor, { props: { item: item(), canParticipate: true } })
  await wrapper.get('button').trigger('click')
  await wrapper.get('textarea').setValue('Unsaved')
  await wrapper.findAll('button').find(button => button.text() === '取消编辑')!.trigger('click')
  expect(useConfirm().request.value?.title).toBe('放弃未保存的修改？')
  useConfirm().settle(false)
  await flushPromises()
  expect(wrapper.get('textarea').element.value).toBe('Unsaved')
  wrapper.unmount()
})

it.each(['ARCHIVED', 'permission', 'external'])('does not expose an edit action for %s', state => {
  const value = item()
  if (state === 'ARCHIVED') value.status = 'ARCHIVED'
  if (state === 'external') value.source = 'GITHUB'
  const wrapper = mount(WorkItemContentEditor, { props: { item: value, canParticipate: state !== 'permission' } })
  expect(wrapper.find('button').exists()).toBe(false)
  wrapper.unmount()
})
