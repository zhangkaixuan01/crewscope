import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import BaseMenu from './BaseMenu.vue'

/**
 * The one dropdown contract（M12b-F03）：此前 6 套手写下拉各自为政的行为，在这里只有一份。
 * 每条断言都对应迁移前至少一套实现做不到的事：方向键、Home/End、Esc 止冒泡、Tab 不拦截、
 * 外点关闭、程序化关闭的焦点归还与模态守卫、组合框的输入框键位让渡。
 */
const mounted: ReturnType<typeof mount>[] = []

const menuBody = `
  <button type="button" role="menuitem" data-testid="first">第一个</button>
  <button type="button" role="menuitem" aria-disabled="true" data-testid="blocked">被服务端挡住</button>
  <button type="button" role="menuitem" data-testid="last">最后一个</button>
`

/**
 * v-model 必须闭环（开合状态归消费者持有），所以每个用例都从真实宿主组件挂载，
 * 而不是把 open 当静态 prop 灌给 BaseMenu。
 */
function mountHost(options: {
  body?: string
  props?: Record<string, unknown>
  trigger?: string
} = {}) {
  const host = defineComponent({
    setup() {
      const open = ref(false)
      return { open }
    },
    render() {
      return h(BaseMenu, {
        open: this.open,
        'onUpdate:open': (value: boolean) => { this.open = value },
        ...(options.props ?? {}),
        triggerClass: 'host-trigger',
      }, {
        trigger: () => options.trigger ?? '更多',
        default: () => h('div', { innerHTML: options.body ?? menuBody }),
      })
    },
  })
  const wrapper = mount(host, { attachTo: document.body })
  mounted.push(wrapper)
  return wrapper
}

async function openMenu(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('.base-menu__trigger').trigger('click')
  await nextTick()
}

beforeEach(() => { document.body.innerHTML = '' })
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  ;(document.activeElement as HTMLElement | null)?.blur()
})

describe('BaseMenu', () => {
  it('wires the trigger semantics and renders no popup while closed', () => {
    const wrapper = mountHost()
    const trigger = wrapper.get('.base-menu__trigger')

    expect(trigger.attributes('aria-haspopup')).toBe('menu')
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(trigger.attributes('id')).toMatch(/^base-menu-/)
    expect(wrapper.find('.base-menu__popup').exists()).toBe(false)
  })

  it('names the menu after the trigger, or by an explicit label', async () => {
    const labelledByTrigger = mountHost()
    await openMenu(labelledByTrigger)
    const popup = labelledByTrigger.get('.base-menu__popup')
    expect(popup.attributes('role')).toBe('menu')
    expect(popup.attributes('aria-labelledby')).toBe(labelledByTrigger.get('.base-menu__trigger').attributes('id'))

    const labelled = mountHost({ props: { label: '成员操作' } })
    await openMenu(labelled)
    expect(labelled.get('.base-menu__popup').attributes('aria-label')).toBe('成员操作')
    expect(labelled.get('.base-menu__popup').attributes('aria-labelledby')).toBeUndefined()
  })

  it('focuses the first usable entry on open and skips aria-disabled ones', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)

    expect(document.activeElement?.textContent).toBe('第一个')
  })

  it('cycles with the arrow keys, wraps, and jumps with Home/End', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)
    const popup = wrapper.get('.base-menu__popup')

    await popup.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toBe('最后一个')
    await popup.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toBe('第一个')
    await popup.trigger('keydown', { key: 'ArrowUp' })
    expect(document.activeElement?.textContent).toBe('最后一个')
    await popup.trigger('keydown', { key: 'Home' })
    expect(document.activeElement?.textContent).toBe('第一个')
    await popup.trigger('keydown', { key: 'End' })
    expect(document.activeElement?.textContent).toBe('最后一个')
  })

  it('opens from the trigger arrow keys, ArrowDown first and ArrowUp last', async () => {
    const down = mountHost()
    await down.get('.base-menu__trigger').trigger('keydown', { key: 'ArrowDown' })
    expect(down.get('.base-menu__trigger').attributes('aria-expanded')).toBe('true')
    await nextTick()
    expect(document.activeElement?.textContent).toBe('第一个')

    const up = mountHost()
    await up.get('.base-menu__trigger').trigger('keydown', { key: 'ArrowUp' })
    await nextTick()
    expect(document.activeElement?.textContent).toBe('最后一个')
  })

  it('closes on Escape, consumes the key, and returns focus to the trigger', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)
    const trigger = wrapper.get<HTMLButtonElement>('.base-menu__trigger')
    const documentEscape = vi.fn()
    document.addEventListener('keydown', documentEscape)

    await wrapper.get('.base-menu__popup').trigger('keydown', { key: 'Escape' })
    await nextTick()

    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
    // Escape 既不漏给背后的层，也不触发浏览器默认行为。
    expect(documentEscape).not.toHaveBeenCalled()
    document.removeEventListener('keydown', documentEscape)
  })

  it('closes on Tab without trapping the focus move', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)

    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('.base-menu__popup').element.dispatchEvent(event)
    await nextTick()

    expect(wrapper.get('.base-menu__trigger').attributes('aria-expanded')).toBe('false')
    expect(event.defaultPrevented).toBe(false)
  })

  it('closes on an outside pointerdown but leaves focus alone', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)

    document.dispatchEvent(new Event('pointerdown'))
    await nextTick()

    expect(wrapper.get('.base-menu__trigger').attributes('aria-expanded')).toBe('false')
  })

  it('keeps the menu open for a pointerdown inside its own root', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)

    wrapper.get('.base-menu__popup').element.dispatchEvent(new Event('pointerdown', { bubbles: true }))

    expect(wrapper.get('.base-menu__trigger').attributes('aria-expanded')).toBe('true')
  })

  it('lets the text-editing keys stay with an input inside the popup unless the consumer opts in', async () => {
    const body = `
      <input type="text" aria-label="搜索" />
      <button type="button" role="menuitem" data-testid="opt">选项</button>
    `
    const passive = mountHost({ props: { initialFocus: 'none' }, body })
    await openMenu(passive)
    const passiveInput = passive.get<HTMLInputElement>('input')
    passiveInput.element.focus()

    await passiveInput.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(passiveInput.element)
    expect(passive.find('.base-menu__popup').exists()).toBe(true)

    const roving = mountHost({ props: { initialFocus: 'none', rovingFromEditable: true }, body })
    await openMenu(roving)
    const rovingInput = roving.get<HTMLInputElement>('input')
    rovingInput.element.focus()

    await rovingInput.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toBe('选项')
  })

  it('restores focus to the trigger on a programmatic close, but not when a modal took over', async () => {
    const wrapper = mountHost()
    await openMenu(wrapper)
    wrapper.get<HTMLButtonElement>('[data-testid="last"]').element.focus()

    ;(wrapper.vm as unknown as { open: boolean }).open = false
    // 关闭→弹层卸载→焦点归还，两次 tick 覆盖 sync watch 的 nextTick 链。
    await nextTick()
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get<HTMLButtonElement>('.base-menu__trigger').element)

    // 选完打开确认对话框的路径（成员生命周期一类）：焦点归模态陷阱，触发钮不抢回。
    const modal = document.createElement('div')
    modal.setAttribute('role', 'dialog')
    modal.setAttribute('aria-modal', 'true')
    modal.tabIndex = -1
    document.body.append(modal)
    ;(wrapper.vm as unknown as { open: boolean }).open = true
    await nextTick()
    wrapper.get<HTMLButtonElement>('[data-testid="first"]').element.focus()
    modal.focus()

    ;(wrapper.vm as unknown as { open: boolean }).open = false
    await nextTick()
    await nextTick()
    // 归还被跳过（焦点随弹层卸载回落）——生产环境此刻模态陷阱会接走焦点，触发钮不抢。
    expect(document.activeElement).not.toBe(wrapper.get('.base-menu__trigger').element)
    modal.remove()
  })

  it('queries role=option entries for the listbox kind and leaves the popup role to the consumer', async () => {
    const wrapper = mountHost({
      props: { kind: 'listbox' },
      body: '<div role="listbox"><button type="button" role="option">模型 A</button></div>',
      trigger: '选择模型',
    })
    expect(wrapper.get('.base-menu__trigger').attributes('aria-haspopup')).toBe('listbox')

    await openMenu(wrapper)
    expect(wrapper.get('.base-menu__popup').attributes('role')).toBeUndefined()
    expect(document.activeElement?.textContent).toBe('模型 A')
  })

  it('cannot be opened while disabled', async () => {
    const wrapper = mountHost({ props: { disabled: true } })
    await wrapper.get('.base-menu__trigger').trigger('click')
    expect(wrapper.get('.base-menu__trigger').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.base-menu__popup').exists()).toBe(false)
  })
})
