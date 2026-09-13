import { mount } from '@vue/test-utils'
import BaseButton from './BaseButton.vue'
import StatusBadge from './StatusBadge.vue'
import BaseInput from './BaseInput.vue'
import BaseSwitch from './BaseSwitch.vue'
import BaseTabs from './BaseTabs.vue'
import StatePanel from '../feedback/StatePanel.vue'
import ToastHost from '../feedback/ToastHost.vue'
import ConfirmHost from '../feedback/ConfirmHost.vue'
import { useToast } from '../../composables/useToast'
import { useConfirm } from '../../composables/useConfirm'

describe('foundation components', () => {
  it('exposes loading state and prevents duplicate button commands', () => {
    const wrapper = mount(BaseButton, { props: { loading: true }, slots: { default: '保存' } })

    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('button').attributes()).toHaveProperty('disabled')
    expect(wrapper.text()).toContain('保存')
  })

  it('renders semantic status with text rather than color alone', () => {
    const wrapper = mount(StatusBadge, { props: { tone: 'warning', dot: true }, slots: { default: '待 Review' } })

    expect(wrapper.text()).toBe('待 Review')
    expect(wrapper.classes()).toContain('status-badge--warning')
  })

  it('exposes optional status actions while keeping the default badge unchanged', async () => {
    const wrapper = mount(StatusBadge, {
      props: { interactive: true, availableActions: [{ id: 'review', label: '提交评审' }] },
      slots: { default: '待处理' },
    })
    await wrapper.get('[role="menuitem"]').trigger('click')
    expect(wrapper.emitted('action')).toEqual([['review']])
  })

  it('keeps form controls keyboard and model-value friendly', async () => {
    const input = mount(BaseInput, { props: { modelValue: '' } })
    await input.get('input').setValue('CrewScope')
    expect(input.emitted('update:modelValue')?.at(-1)).toEqual(['CrewScope'])

    const toggle = mount(BaseSwitch, { props: { modelValue: false } })
    await toggle.get('button').trigger('click')
    expect(toggle.emitted('update:modelValue')).toEqual([[true]])

    const tabs = mount(BaseTabs, { props: { modelValue: 'one', tabs: [{ label: '一', value: 'one' }, { label: '二', value: 'two' }] } })
    await tabs.findAll('[role="tab"]')[1].trigger('click')
    expect(tabs.emitted('update:modelValue')).toEqual([['two']])
  })

  it('announces errors and offers a retry action', async () => {
    const wrapper = mount(StatePanel, { props: { state: 'error' } })

    expect(wrapper.attributes('role')).toBe('alert')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('exposes offline, reconnecting and cancelled states without color-only meaning', () => {
    const offline = mount(StatePanel, { props: { state: 'offline' } })
    const reconnecting = mount(StatePanel, { props: { state: 'reconnecting' } })
    const cancelled = mount(StatePanel, { props: { state: 'cancelled' } })

    expect(offline.text()).toContain('当前离线')
    expect(reconnecting.attributes('aria-busy')).toBe('true')
    expect(cancelled.text()).toContain('已取消')
  })

  it('renders shared toast and confirmation hosts', async () => {
    const toast = useToast()
    toast.show('已保存', { tone: 'success', duration: 0 })
    const toastWrapper = mount(ToastHost)
    expect(toastWrapper.text()).toContain('已保存')
    toast.dismiss(toast.toasts.value[0].id)

    const confirmation = useConfirm()
    const pending = confirmation.confirm({ title: '确认操作', description: '请确认继续。', confirmLabel: '继续', cancelLabel: '返回', danger: false })
    const confirmWrapper = mount(ConfirmHost)
    expect(document.body.textContent).toContain('确认操作')
    confirmWrapper.unmount()
    confirmation.settle(true)
    await expect(pending).resolves.toBe(true)
  })
})
