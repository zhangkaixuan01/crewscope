import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { AgentTemplateSummary } from '../../domains/agent/types'
import type { PreferenceForm } from '../../domains/agent/configurationTypes'
import {
  generateOptionFields,
  limitBound,
  limitFloor,
  limitRangeText,
  seedBound,
} from '../../domains/agent/limits'
import AgentConfigurationPreferencesSection from './AgentConfigurationPreferencesSection.vue'

/**
 * The consistency test between the form and the server's validation.
 *
 * Every `min`/`max`/`step` asserted below is read out of the table generated from the domain's
 * `AgentGenerateOptionsLimits`, never typed into this file, so the assertion is "the form renders
 * what the domain declares" rather than "the form renders what someone copied here once".
 */
describe('AgentConfigurationPreferencesSection', () => {
  it('renders each numeric field with the bounds the server validates', () => {
    const wrapper = mount(AgentConfigurationPreferencesSection, { props: props() })

    for (const field of generateOptionFields()) {
      const input = wrapper.get(`#preference-${field}`)
      expect(input.attributes('min'), `${field} min`).toBe(String(limitFloor(field)))
      expect(input.attributes('max'), `${field} max`).toBe(String(limitBound(field, 'maximum')))
      expect(input.attributes('step'), `${field} step`).toBe(String(limitBound(field, 'step')))
      // The range is on screen too, next to the label the member reads.
      expect(wrapper.text()).toContain(limitRangeText(field))
    }

    expect(wrapper.get('#preference-maximumOutputTokens').attributes('min')).toBe('1')
    expect(wrapper.get('#preference-maximumOutputTokens').attributes('max')).toBe('10000000')
    expect(wrapper.get('#preference-maximumAttempts').attributes('max')).toBe('10')
    expect(wrapper.get('#preference-temperature').attributes('step')).toBe('0.01')
    // topP's published minimum is 0 and the field does not take 0: the input starts at one step up, so
    // the browser refuses exactly what the field-level error refuses.
    expect(limitBound('topP', 'minimum')).toBe(0)
    expect(wrapper.get('#preference-topP').attributes('min')).toBe('0.01')
    expect(wrapper.get('#preference-topP').attributes('inputmode')).toBe('decimal')
    expect(wrapper.get('#preference-seed').attributes('max')).toBe(String(seedBound.maximum))
  })

  it('shows a visible field-level error while a value is out of range, without waiting for submit', async () => {
    const wrapper = mount(AgentConfigurationPreferencesSection, { props: props() })

    // Only the typing happens here: there is no submit flag on this component any more, so an error
    // that appears at all has appeared while the member was still editing the field.
    await wrapper.get('#preference-temperature').setValue('3')

    const input = wrapper.get('#preference-temperature')
    expect(input.attributes('aria-invalid')).toBe('true')
    const describedBy = input.attributes('aria-describedby')
    expect(describedBy).toBe('preference-temperature-error')
    const error = wrapper.get(`#${describedBy}`)
    expect(error.attributes('role')).toBe('alert')
    expect(error.text()).toContain(limitRangeText('temperature'))
    expect(error.text()).toContain('2')

    await wrapper.get('#preference-temperature').setValue('1.5')
    expect(wrapper.get('#preference-temperature').attributes('aria-invalid')).toBe('false')
    expect(wrapper.find('#preference-temperature-error').exists()).toBe(false)
    expect(wrapper.get('#preference-temperature').attributes('aria-describedby')).toBe('preference-temperature-hint')
  })

  it('describes the range while the value is still acceptable', () => {
    const wrapper = mount(AgentConfigurationPreferencesSection, { props: props() })

    const input = wrapper.get('#preference-maximumAttempts')
    expect(input.attributes('aria-invalid')).toBe('false')
    expect(input.attributes('aria-describedby')).toBe('preference-maximumAttempts-hint')
    expect(wrapper.get('#preference-maximumAttempts-hint').text()).toBe(limitRangeText('maximumAttempts'))
  })

  it('refuses a fraction where the server takes a whole number, and explains the range it wants', async () => {
    const wrapper = mount(AgentConfigurationPreferencesSection, { props: props() })

    await wrapper.get('#preference-maximumAttempts').setValue('2.5')
    expect(wrapper.get('#preference-maximumAttempts').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#preference-maximumAttempts-error').text()).toContain('1–10')

    // topP's floor is open: zero is refused while the field is filled, not rounded up silently.
    await wrapper.get('#preference-topP').setValue('0')
    expect(wrapper.get('#preference-topP').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#preference-topP-error').text()).toContain('大于 0')
  })

  it('reports what the member typed through the preference event, emptied fields included', async () => {
    // Starts from a filled field so that clearing it is a real change: the section stays quiet while
    // the form still matches the configuration it was handed.
    const wrapper = mount(AgentConfigurationPreferencesSection, {
      props: { ...props(), preferences: { ...preferences(), maximumOutputTokens: '4096' } },
    })

    await wrapper.get('#preference-maximumOutputTokens').setValue('2048')
    // A number input hands back the number it parsed, not the text that was typed: the form model has
    // to carry both spellings, which is what the section's preference type now says out loud.
    expect(wrapper.emitted('updatePreferences')!.at(-1)?.[0]).toMatchObject({ maximumOutputTokens: 2048 })
    await wrapper.get('#preference-maximumOutputTokens').setValue('')
    expect(wrapper.emitted('updatePreferences')!.at(-1)?.[0]).toMatchObject({ maximumOutputTokens: '' })
    expect(wrapper.get('#preference-maximumOutputTokens').attributes('aria-invalid')).toBe('false')
  })

  it('refuses an emptied field the server needs, instead of sending zero for it', async () => {
    const wrapper = mount(AgentConfigurationPreferencesSection, { props: props() })

    await wrapper.get('#preference-maximumAttempts').setValue('')
    expect(wrapper.get('#preference-maximumAttempts').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#preference-maximumAttempts-error').text()).toContain('1–10')

    // The optional fields keep meaning "use the model default" when they are emptied.
    await wrapper.get('#preference-maximumOutputTokens').setValue('')
    expect(wrapper.get('#preference-maximumOutputTokens').attributes('aria-invalid')).toBe('false')
  })
})

function props() {
  return {
    template: template(),
    current: null,
    preferences: preferences(),
    slotAvailable: () => true,
    memberSlot: () => true,
  }
}

function preferences(): PreferenceForm {
  return {
    supplementalInstructions: '',
    approvedSkillKeys: [],
    temperature: '',
    topP: '',
    maximumOutputTokens: '',
    reasoningMode: 'DEFAULT',
    cacheEnabled: true,
    parallelToolCalls: false,
    seed: '',
    maximumAttempts: '1',
  }
}

function template(): AgentTemplateSummary {
  return {
    publisherType: 'ORGANIZATION', publisherId: 'organization-1', key: 'coding-specialist', version: 3,
    runtimeRole: 'CODING', allowedOwnershipTypes: ['USER'], allowedExecutionScopes: ['PERSONAL'],
    declaredCapabilities: ['coding'], requiredModelCapabilities: ['TOOLS'], approvedSkillKeys: ['coding-baseline'],
    memberConfigurableSlots: ['SUPPLEMENTAL_INSTRUCTIONS', 'APPROVED_SKILLS', 'OUTPUT_PREFERENCE'],
    administratorConfigurableSlots: [], creatable: true, platformManaged: false,
    contentHash: 'a'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  }
}
