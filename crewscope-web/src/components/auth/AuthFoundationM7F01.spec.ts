import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import M7AuthStatePreview, { authPreviewStates } from '../../stories/M7AuthStatePreview.vue'
import AuthCard from './AuthCard.vue'
import AuthCheckbox from './AuthCheckbox.vue'
import AuthErrorSummary from './AuthErrorSummary.vue'
import AuthField from './AuthField.vue'
import AuthLayout from './AuthLayout.vue'
import AuthPasswordField from './AuthPasswordField.vue'

describe('M7-F01 authentication foundation', () => {
  it('keeps public-page tokens and landmarks inside the AuthLayout boundary', () => {
    const wrapper = mount(AuthLayout, {
      props: { brandTone: 'dark', stageTone: 'neutral', motion: 'reduced' },
      slots: { default: '<section><h2>登录</h2></section>' },
    })

    expect(wrapper.classes()).toContain('cs-auth-theme')
    expect(wrapper.attributes('data-auth-brand-tone')).toBe('dark')
    expect(wrapper.attributes('data-auth-stage-tone')).toBe('neutral')
    expect(wrapper.attributes('data-auth-motion')).toBe('reduced')
    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(wrapper.get('.auth-layout__skip').attributes('href')).toBe('#identity-primary')
    expect(wrapper.get('aside').text()).toContain('成员')
    expect(wrapper.get('aside').text()).toContain('Personal Agent')
    expect(wrapper.get('aside').text()).toContain('团队')
    expect(wrapper.get('img').attributes('src')).toContain('crewscope-mark.svg')
    expect(wrapper.find('.app-shell').exists()).toBe(false)
  })

  it('forwards form semantics, model updates and field errors to the native input', async () => {
    const wrapper = mount(AuthField, {
      props: {
        modelValue: '',
        'onUpdate:modelValue': (value: string) => wrapper.setProps({ modelValue: value }),
        id: 'work-email',
        label: '工作邮箱',
        name: 'email',
        type: 'email' as const,
        autocomplete: 'email',
        inputmode: 'email' as const,
        hint: '使用团队可识别的邮箱',
        error: '邮箱格式无效',
        required: true,
      },
    })

    const input = wrapper.get<HTMLInputElement>('input')
    expect(wrapper.get('label').attributes('for')).toBe('work-email')
    expect(input.attributes('autocomplete')).toBe('email')
    expect(input.attributes('inputmode')).toBe('email')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.attributes('aria-describedby')).toContain('work-email-hint')
    expect(input.attributes('aria-describedby')).toContain('work-email-error')

    await input.setValue('member@example.com')
    expect(wrapper.props('modelValue')).toBe('member@example.com')
  })

  it('keeps password reveal ephemeral and reports only the frozen length budget', async () => {
    const wrapper = mount(AuthPasswordField, {
      props: {
        modelValue: '',
        'onUpdate:modelValue': (value: string) => wrapper.setProps({ modelValue: value }),
        name: 'newPassword',
        autocomplete: 'new-password' as const,
        showGuidance: true,
      },
    })

    const input = wrapper.get<HTMLInputElement>('input')
    expect(input.attributes('type')).toBe('password')
    expect(input.attributes('minlength')).toBe('12')
    expect(input.attributes('maxlength')).toBe('128')
    expect(wrapper.get('[aria-label="密码要求"]').text()).toContain('至少 12 个字符')
    expect(wrapper.text()).not.toMatch(/弱密码|强密码|熵/)

    await input.setValue('😀'.repeat(12))
    expect(wrapper.get('[role="status"]').text()).toBe('密码长度符合要求')
    await wrapper.get('button[aria-label="显示密码"]').trigger('click')
    expect(input.attributes('type')).toBe('text')
    expect(wrapper.get('button[aria-label="隐藏密码"]').attributes('aria-pressed')).toBe('true')
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)

    wrapper.unmount()
    const remounted = mount(AuthPasswordField, { props: { name: 'password' } })
    expect(remounted.get('input').attributes('type')).toBe('password')
  })

  it('moves focus to a changed error summary without positive tabindex ordering', async () => {
    const wrapper = mount(AuthErrorSummary, {
      attachTo: document.body,
      props: { title: '无法登录', messages: ['登录信息无效，请检查后重试。'], focusKey: 1 },
    })
    await nextTick()

    const alert = wrapper.get('[role="alert"]')
    expect(document.activeElement).toBe(alert.element)
    expect(alert.attributes('tabindex')).toBe('-1')
    expect(alert.text()).not.toMatch(/账号不存在|密码错误|账号已锁定/)

    await wrapper.setProps({ focusKey: 2, title: '仍然无法登录' })
    await nextTick()
    expect(document.activeElement).toBe(alert.element)
    wrapper.unmount()
  })

  it('supports explicit initial focus for ordinary forms and terminal status cards', async () => {
    const card = mount(AuthCard, {
      attachTo: document.body,
      props: { title: '当前未开放注册', focusOnMount: true },
    })
    await nextTick()
    expect(document.activeElement).toBe(card.get('h2').element)
    expect(card.get('h2').attributes('tabindex')).toBe('-1')
    card.unmount()

    const field = mount(AuthField, {
      attachTo: document.body,
      props: { label: '用户名', name: 'username', focusOnMount: true },
    })
    await nextTick()
    expect(document.activeElement).toBe(field.get('input').element)
    field.unmount()
  })

  it('provides a native checkbox target large enough for keyboard and touch interaction', async () => {
    const wrapper = mount(AuthCheckbox, {
      props: {
        modelValue: false,
        'onUpdate:modelValue': (value: boolean) => wrapper.setProps({ modelValue: value }),
        id: 'remember-session',
        label: '保持登录',
        description: '仅用于受信设备',
      },
    })

    expect(wrapper.get('label').attributes('for')).toBe('remember-session')
    expect(wrapper.text()).toContain('仅用于受信设备')
    await wrapper.get('input').setValue(true)
    expect(wrapper.props('modelValue')).toBe(true)
  })

  it('renders every public authentication state in the desktop and narrow story contract', () => {
    expect(authPreviewStates).toHaveLength(11)
    for (const state of authPreviewStates) {
      const wrapper = mount(M7AuthStatePreview, { props: { state } })
      expect(wrapper.findAll('main')).toHaveLength(1)
      expect(wrapper.findAll('aside')).toHaveLength(1)
      expect(wrapper.text()).not.toContain('M7 交互原型')
      expect(wrapper.text()).not.toContain('不提交数据')
      wrapper.unmount()
    }
  })
})
