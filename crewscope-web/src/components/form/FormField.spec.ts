import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { h } from 'vue'
import FormField from './FormField.vue'

describe('FormField', () => {
  it('exposes field-level error semantics and visible copy', () => {
    const wrapper = mount(FormField, { props: { label: 'Temperature', id: 'temperature', error: '请输入 0–2 之间的值。' }, slots: { default: props => h('input', { id: 'temperature', 'aria-invalid': props.ariaInvalid, 'aria-describedby': props.ariaDescribedby }) } })
    expect(wrapper.get('input').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toContain('0–2')
  })
})
